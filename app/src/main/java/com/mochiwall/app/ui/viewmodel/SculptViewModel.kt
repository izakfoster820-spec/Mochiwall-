package com.mochiwall.app.ui.viewmodel

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mochiwall.app.core.mask.MaskBrushRasterizer
import com.mochiwall.app.core.mask.MaskHistoryBuffer
import com.mochiwall.app.data.ImageFrameProcessor
import com.mochiwall.app.data.ProjectRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SculptTool {
    BRUSH,
    ERASER
}

data class SculptUiState(
    val previewBitmap: Bitmap? = null,
    val imageWidth: Int = 1,
    val imageHeight: Int = 1,
    val isLoading: Boolean = true,
    val isCommitting: Boolean = false,
    val tool: SculptTool = SculptTool.BRUSH,
    val brushSizeFraction: Float = 0.12f,
    val feather: Float = 0.20f,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val zoom: Float = 1.0f,
    val isPeeking: Boolean = false,
    val maskRevision: Int = 0,
    val resetZoomSignal: Int = 0,
    val errorMessage: String? = null
)

class SculptViewModel(
    private val repository: ProjectRepository,
    private val imageFrameProcessor: ImageFrameProcessor,
    private val isEditMaskMode: Boolean,
    private val exportWidth: Int,
    private val exportHeight: Int
) : ViewModel() {

    private val _uiState = MutableStateFlow(SculptUiState())
    val uiState: StateFlow<SculptUiState> = _uiState.asStateFlow()

    /**
     * Shared directly with [com.mochiwall.app.ui.components.MaskCanvasView] on the Main Thread
     * for zero-latency stroke rasterization.
     */
    val workingMask: ByteArray = ByteArray(MaskBrushRasterizer.MASK_BYTE_COUNT)

    private val historyBuffer = MaskHistoryBuffer()
    private var hasInitialized = false

    fun loadInitialData(maxPreviewLongEdge: Int) {
        if (hasInitialized) return
        hasInitialized = true

        _uiState.update { it.copy(isLoading = true, errorMessage = null) }

        viewModelScope.launch {
            try {
                val safeMaxEdge = maxPreviewLongEdge.takeIf { it > 0 }
                val decodedBitmap: Bitmap
                val resolvedWidth: Int
                val resolvedHeight: Int

                if (isEditMaskMode) {
                    val activeContent = repository.state.value.content
                        ?: throw IllegalStateException("No active wallpaper found to edit.")
                    val wallpaperFile = repository.resolveWallpaperFile(activeContent)
                    decodedBitmap = imageFrameProcessor.decodeWallpaperBitmap(
                        file = wallpaperFile,
                        maxLongEdge = safeMaxEdge
                    )
                    val existingMask = repository.loadCurrentMaskBytes()
                    if (existingMask != null && existingMask.size == MaskBrushRasterizer.MASK_BYTE_COUNT) {
                        System.arraycopy(
                            existingMask,
                            0,
                            workingMask,
                            0,
                            MaskBrushRasterizer.MASK_BYTE_COUNT
                        )
                    } else {
                        workingMask.fill(0)
                    }
                    resolvedWidth = activeContent.imageWidth.coerceAtLeast(1)
                    resolvedHeight = activeContent.imageHeight.coerceAtLeast(1)
                } else {
                    val stagingFile = repository.stagingWallpaperFile()
                    decodedBitmap = imageFrameProcessor.decodeWallpaperBitmap(
                        file = stagingFile,
                        maxLongEdge = safeMaxEdge
                    )
                    workingMask.fill(0)
                    resolvedWidth = if (exportWidth > 0) exportWidth else decodedBitmap.width.coerceAtLeast(1)
                    resolvedHeight = if (exportHeight > 0) exportHeight else decodedBitmap.height.coerceAtLeast(1)
                }

                historyBuffer.initialize(workingMask)

                _uiState.update { current ->
                    current.copy(
                        previewBitmap = decodedBitmap,
                        imageWidth = resolvedWidth,
                        imageHeight = resolvedHeight,
                        isLoading = false,
                        canUndo = historyBuffer.canUndo,
                        canRedo = historyBuffer.canRedo,
                        maskRevision = current.maskRevision + 1,
                        errorMessage = null
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                hasInitialized = false
                _uiState.update { current ->
                    current.copy(
                        isLoading = false,
                        errorMessage = t.message?.takeIf { it.isNotBlank() }
                            ?: "Could not load wallpaper for sculpting."
                    )
                }
            }
        }
    }

    fun setTool(tool: SculptTool) {
        _uiState.update { it.copy(tool = tool) }
    }

    fun setBrushSizeFraction(fraction: Float) {
        if (!fraction.isFinite()) return
        val clamped = fraction.coerceIn(MIN_BRUSH_SIZE_FRACTION, MAX_BRUSH_SIZE_FRACTION)
        _uiState.update { it.copy(brushSizeFraction = clamped) }
    }

    fun setFeather(value: Float) {
        if (!value.isFinite()) return
        val clamped = value.coerceIn(0f, 1f)
        _uiState.update { it.copy(feather = clamped) }
    }

    fun setPeeking(peeking: Boolean) {
        _uiState.update { it.copy(isPeeking = peeking) }
    }

    /**
     * Called strictly on a clean single-finger `ACTION_UP` (or primary pointer lift after 120 ms)
     * from `MaskCanvasView` to commit the finished stroke into [MaskHistoryBuffer].
     */
    fun onStrokeCommitted() {
        historyBuffer.commit(workingMask)
        _uiState.update { current ->
            current.copy(
                canUndo = historyBuffer.canUndo,
                canRedo = historyBuffer.canRedo
            )
        }
    }

    fun undo() {
        if (historyBuffer.undo(workingMask)) {
            _uiState.update { current ->
                current.copy(
                    canUndo = historyBuffer.canUndo,
                    canRedo = historyBuffer.canRedo,
                    maskRevision = current.maskRevision + 1
                )
            }
        }
    }

    fun redo() {
        if (historyBuffer.redo(workingMask)) {
            _uiState.update { current ->
                current.copy(
                    canUndo = historyBuffer.canUndo,
                    canRedo = historyBuffer.canRedo,
                    maskRevision = current.maskRevision + 1
                )
            }
        }
    }

    fun clearMask() {
        val hasAnyNonZero = workingMask.any { it != 0.toByte() }
        if (!hasAnyNonZero) return

        workingMask.fill(0)
        historyBuffer.commit(workingMask)
        _uiState.update { current ->
            current.copy(
                canUndo = historyBuffer.canUndo,
                canRedo = historyBuffer.canRedo,
                maskRevision = current.maskRevision + 1
            )
        }
    }

    fun onZoomChanged(newZoom: Float) {
        if (!newZoom.isFinite()) return
        val clamped = newZoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        _uiState.update { current ->
            if (current.zoom == clamped) current else current.copy(zoom = clamped)
        }
    }

    fun requestResetZoom() {
        _uiState.update { current ->
            current.copy(
                zoom = MIN_ZOOM,
                resetZoomSignal = current.resetZoomSignal + 1
            )
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun confirmAndCommit(onCommitted: () -> Unit) {
        val current = _uiState.value
        if (current.isCommitting || current.isLoading) return

        // Snapshot on Main Thread before handing off to IO commit.
        val maskSnapshot = workingMask.copyOf()
        val imgW = current.imageWidth
        val imgH = current.imageHeight

        _uiState.update { it.copy(isCommitting = true, errorMessage = null) }

        viewModelScope.launch {
            try {
                if (isEditMaskMode) {
                    repository.commitEditedMask(maskSnapshot)
                } else {
                    repository.commitNewPhoto(
                        stagingFile = repository.stagingWallpaperFile(),
                        maskBytes = maskSnapshot,
                        width = imgW,
                        height = imgH
                    )
                }
                _uiState.update { it.copy(isCommitting = false) }
                onCommitted()
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _uiState.update { state ->
                    state.copy(
                        isCommitting = false,
                        errorMessage = t.message?.takeIf { it.isNotBlank() }
                            ?: "Could not save sculpted mask. Please try again."
                    )
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        historyBuffer.clear()

        val bitmap = _uiState.value.previewBitmap
        _uiState.update { it.copy(previewBitmap = null) }
        if (bitmap != null && !bitmap.isRecycled) {
            bitmap.recycle()
        }
    }

    companion object {
        const val MIN_BRUSH_SIZE_FRACTION = 0.04f
        const val MAX_BRUSH_SIZE_FRACTION = 0.25f
        const val MIN_ZOOM = 1.0f
        const val MAX_ZOOM = 3.0f

        fun provideFactory(
            repository: ProjectRepository,
            imageFrameProcessor: ImageFrameProcessor,
            isEditMaskMode: Boolean,
            exportWidth: Int,
            exportHeight: Int
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(SculptViewModel::class.java)) {
                    return SculptViewModel(
                        repository = repository,
                        imageFrameProcessor = imageFrameProcessor,
                        isEditMaskMode = isEditMaskMode,
                        exportWidth = exportWidth,
                        exportHeight = exportHeight
                    ) as T
                }
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }
        }
    }
}
