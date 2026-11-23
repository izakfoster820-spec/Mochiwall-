package com.mochiwall.app.ui.viewmodel

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mochiwall.app.data.ImageFrameProcessor
import com.mochiwall.app.data.ProjectRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.max

data class FrameUiState(
    val sourceBitmap: Bitmap? = null,
    val isLoadingPhoto: Boolean = false,
    val isExporting: Boolean = false,
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val viewportWidth: Float = 0f,
    val viewportHeight: Float = 0f,
    val errorMessage: String? = null
)

class FrameViewModel(
    private val repository: ProjectRepository,
    private val imageFrameProcessor: ImageFrameProcessor
) : ViewModel() {

    private val _uiState = MutableStateFlow(FrameUiState())
    val uiState: StateFlow<FrameUiState> = _uiState.asStateFlow()

    private var decodeJob: Job? = null
    private val pendingRecycleBitmaps = mutableListOf<Bitmap>()

    fun onPhotoSelected(uri: Uri, screenWidth: Int, screenHeight: Int) {
        decodeJob?.cancel()
        _uiState.update { it.copy(isLoadingPhoto = true, errorMessage = null) }

        decodeJob = viewModelScope.launch {
            try {
                val decoded = imageFrameProcessor.decodeSourceForFraming(
                    uri = uri,
                    screenWidth = screenWidth,
                    screenHeight = screenHeight
                )

                val previousBitmap = _uiState.value.sourceBitmap
                val currentViewportW = _uiState.value.viewportWidth
                val currentViewportH = _uiState.value.viewportHeight
                val initialScale = computeMinScale(
                    bitmap = decoded,
                    viewportWidth = currentViewportW,
                    viewportHeight = currentViewportH
                )

                _uiState.update { current ->
                    current.copy(
                        sourceBitmap = decoded,
                        isLoadingPhoto = false,
                        scale = initialScale,
                        offsetX = 0f,
                        offsetY = 0f,
                        errorMessage = null
                    )
                }

                if (previousBitmap != null && previousBitmap !== decoded) {
                    scheduleSafeRecycle(previousBitmap)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _uiState.update { current ->
                    current.copy(
                        isLoadingPhoto = false,
                        errorMessage = t.message?.takeIf { it.isNotBlank() }
                            ?: "Could not load the selected photo. Please try another image."
                    )
                }
            }
        }
    }

    fun onViewportSizeChanged(width: Float, height: Float) {
        if (!width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f) return

        _uiState.update { current ->
            val bitmap = current.sourceBitmap
            if (bitmap == null || bitmap.isRecycled) {
                current.copy(viewportWidth = width, viewportHeight = height)
            } else {
                val minScale = computeMinScale(bitmap, width, height)
                val maxScale = minScale * MAX_SCALE_MULTIPLIER
                val isFirstViewportLayout = current.viewportWidth <= 0f || current.viewportHeight <= 0f

                val clampedScale = if (isFirstViewportLayout) {
                    minScale
                } else {
                    current.scale.coerceIn(minScale, maxScale)
                }

                val maxOffsetX = max(0f, (bitmap.width * clampedScale - width) * 0.5f)
                val maxOffsetY = max(0f, (bitmap.height * clampedScale - height) * 0.5f)
                val clampedOffsetX = if (isFirstViewportLayout) {
                    0f
                } else {
                    current.offsetX.coerceIn(-maxOffsetX, maxOffsetX)
                }
                val clampedOffsetY = if (isFirstViewportLayout) {
                    0f
                } else {
                    current.offsetY.coerceIn(-maxOffsetY, maxOffsetY)
                }

                current.copy(
                    viewportWidth = width,
                    viewportHeight = height,
                    scale = clampedScale,
                    offsetX = clampedOffsetX,
                    offsetY = clampedOffsetY
                )
            }
        }
    }

    fun onTransformGesture(
        panDeltaX: Float,
        panDeltaY: Float,
        zoomChange: Float,
        centroidX: Float,
        centroidY: Float
    ) {
        if (!panDeltaX.isFinite() || !panDeltaY.isFinite() || !zoomChange.isFinite()) return

        _uiState.update { current ->
            val bitmap = current.sourceBitmap ?: return@update current
            if (bitmap.isRecycled || current.viewportWidth <= 0f || current.viewportHeight <= 0f) {
                return@update current
            }

            val viewportW = current.viewportWidth
            val viewportH = current.viewportHeight
            val minScale = computeMinScale(bitmap, viewportW, viewportH)
            val maxScale = minScale * MAX_SCALE_MULTIPLIER

            val oldScale = if (current.scale.isFinite() && current.scale > 0f) {
                current.scale
            } else {
                minScale
            }
            val safeZoom = if (zoomChange > 0f) zoomChange else 1f
            val newScale = (oldScale * safeZoom).coerceIn(minScale, maxScale)
            val actualZoomFactor = newScale / oldScale

            val cx = viewportW * 0.5f
            val cy = viewportH * 0.5f
            val safeCentroidX = if (centroidX.isFinite()) centroidX else cx
            val safeCentroidY = if (centroidY.isFinite()) centroidY else cy

            val rawOffsetX = (safeCentroidX - cx) * (1f - actualZoomFactor) +
                current.offsetX * actualZoomFactor + panDeltaX
            val rawOffsetY = (safeCentroidY - cy) * (1f - actualZoomFactor) +
                current.offsetY * actualZoomFactor + panDeltaY

            val maxOffsetX = max(0f, (bitmap.width * newScale - viewportW) * 0.5f)
            val maxOffsetY = max(0f, (bitmap.height * newScale - viewportH) * 0.5f)

            current.copy(
                scale = newScale,
                offsetX = rawOffsetX.coerceIn(-maxOffsetX, maxOffsetX),
                offsetY = rawOffsetY.coerceIn(-maxOffsetY, maxOffsetY)
            )
        }
    }

    fun exportForSculpt(
        windowBoundsWidth: Int,
        windowBoundsHeight: Int,
        onExported: (exportWidth: Int, exportHeight: Int) -> Unit
    ) {
        val snapshot = _uiState.value
        val bitmap = snapshot.sourceBitmap ?: return
        if (snapshot.isExporting || snapshot.isLoadingPhoto || bitmap.isRecycled) return

        _uiState.update { it.copy(isExporting = true, errorMessage = null) }

        viewModelScope.launch {
            try {
                val (targetWidth, targetHeight) = imageFrameProcessor.computeExportDimensions(
                    windowBoundsWidth = windowBoundsWidth,
                    windowBoundsHeight = windowBoundsHeight
                )
                val result = imageFrameProcessor.exportFramedWallpaper(
                    source = bitmap,
                    scale = snapshot.scale,
                    offsetX = snapshot.offsetX,
                    offsetY = snapshot.offsetY,
                    viewportWidth = snapshot.viewportWidth,
                    viewportHeight = snapshot.viewportHeight,
                    exportWidth = targetWidth,
                    exportHeight = targetHeight,
                    destinationFile = repository.stagingWallpaperFile()
                )
                _uiState.update { it.copy(isExporting = false) }
                onExported(result.width, result.height)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _uiState.update { current ->
                    current.copy(
                        isExporting = false,
                        errorMessage = t.message?.takeIf { it.isNotBlank() }
                            ?: "Failed to prepare framed wallpaper. Please try again."
                    )
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private fun computeMinScale(
        bitmap: Bitmap,
        viewportWidth: Float,
        viewportHeight: Float
    ): Float {
        if (bitmap.width <= 0 || bitmap.height <= 0 || viewportWidth <= 0f || viewportHeight <= 0f) {
            return 1f
        }
        return max(
            viewportWidth / bitmap.width.toFloat(),
            viewportHeight / bitmap.height.toFloat()
        )
    }

    private fun scheduleSafeRecycle(bitmap: Bitmap) {
        pendingRecycleBitmaps.add(bitmap)
        viewModelScope.launch {
            // Allow any in-flight Compose draw frame referencing the replaced bitmap to finish.
            delay(RECYCLE_DEFER_MS)
            if (pendingRecycleBitmaps.remove(bitmap) && !bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        decodeJob?.cancel()

        val heldBitmap = _uiState.value.sourceBitmap
        _uiState.update { it.copy(sourceBitmap = null) }
        if (heldBitmap != null && !heldBitmap.isRecycled) {
            heldBitmap.recycle()
        }

        for (bitmap in pendingRecycleBitmaps) {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
        pendingRecycleBitmaps.clear()
    }

    companion object {
        private const val MAX_SCALE_MULTIPLIER = 6.0f
        private const val RECYCLE_DEFER_MS = 64L

        fun provideFactory(
            repository: ProjectRepository,
            imageFrameProcessor: ImageFrameProcessor
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(FrameViewModel::class.java)) {
                    return FrameViewModel(repository, imageFrameProcessor) as T
                }
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }
        }
    }
}
