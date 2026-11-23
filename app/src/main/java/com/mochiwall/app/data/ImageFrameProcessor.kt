package com.mochiwall.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Result of exporting a framed Stage 1 wallpaper to disk.
 */
data class ExportResult(
    val file: File,
    val width: Int,
    val height: Int
)

/**
 * Handles Stage 1 sRGB source decoding, portrait-guarded framing export (opaque #FF111215
 * flatten + WEBP_LOSSY quality 94), and GL/preview bitmap decoding with strict memory hygiene.
 */
class ImageFrameProcessor(context: Context) {

    private val appContext: Context = context.applicationContext

    /**
     * Computes portrait export dimensions from window bounds, falling back to 1080x2400 on
     * non-positive inputs and proportionally capping the long edge at [MAX_EXPORT_LONG_EDGE].
     */
    fun computeExportDimensions(
        windowBoundsWidth: Int,
        windowBoundsHeight: Int
    ): Pair<Int, Int> = Companion.computeExportDimensions(windowBoundsWidth, windowBoundsHeight)

    /**
     * Decodes a user-picked image URI into a software sRGB [Bitmap] for Stage 1 framing,
     * proportionally capping its long edge at `2 * max(screenWidth, screenHeight)`.
     */
    suspend fun decodeSourceForFraming(
        uri: Uri,
        screenWidth: Int,
        screenHeight: Int
    ): Bitmap = withContext(Dispatchers.IO) {
        val maxScreenEdge = max(screenWidth, screenHeight).let { edge ->
            if (edge > 0) edge else DEFAULT_EXPORT_HEIGHT
        }
        val maxAllowedEdge = (maxScreenEdge.toLong() * 2L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()

        val source = ImageDecoder.createSource(appContext.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))

            val srcW = info.size.width
            val srcH = info.size.height
            val srcLongEdge = max(srcW, srcH)
            if (srcW > 0 && srcH > 0 && srcLongEdge > maxAllowedEdge) {
                val ratio = maxAllowedEdge.toDouble() / srcLongEdge.toDouble()
                val targetW = (srcW * ratio).roundToInt().coerceAtLeast(1)
                val targetH = (srcH * ratio).roundToInt().coerceAtLeast(1)
                decoder.setTargetSize(targetW, targetH)
            }
        }
    }

    /**
     * Renders the framed Stage 1 viewport crop onto an opaque `#FF111215` ARGB_8888 sRGB canvas,
     * atomically writes it as `WEBP_LOSSY` (quality 94) to [destinationFile], and immediately
     * recycles the temporary export bitmap in `finally`.
     *
     * Coordinate convention for framing parameters:
     * - [scale] maps source bitmap pixels to Stage 1 viewport pixels (centered on bitmap center).
     * - [offsetX] and [offsetY] are pan offsets in Stage 1 viewport pixels relative to the
     *   viewport center.
     * - [viewportWidth] and [viewportHeight] define the Stage 1 viewport rectangle mapped 1:1 onto
     *   `[0, exportWidth] x [0, exportHeight]`.
     */
    suspend fun exportFramedWallpaper(
        source: Bitmap,
        scale: Float,
        offsetX: Float,
        offsetY: Float,
        viewportWidth: Float,
        viewportHeight: Float,
        exportWidth: Int,
        exportHeight: Int,
        destinationFile: File
    ): ExportResult = withContext(Dispatchers.IO) {
        require(!source.isRecycled) { "Source bitmap must not be recycled." }
        require(source.width > 0 && source.height > 0) {
            "Source bitmap dimensions must be positive (got ${source.width}x${source.height})."
        }
        require(exportWidth > 0 && exportHeight > 0) {
            "Export dimensions must be positive (got ${exportWidth}x${exportHeight})."
        }

        val safeViewportW = if (viewportWidth.isFinite() && viewportWidth > 0f) {
            viewportWidth
        } else {
            exportWidth.toFloat()
        }
        val safeViewportH = if (viewportHeight.isFinite() && viewportHeight > 0f) {
            viewportHeight
        } else {
            exportHeight.toFloat()
        }
        val minAspectFillScale = max(
            safeViewportW / source.width.toFloat(),
            safeViewportH / source.height.toFloat()
        )
        val safeScale = if (scale.isFinite() && scale > 0f) scale else minAspectFillScale
        val safeOffsetX = if (offsetX.isFinite()) offsetX else 0f
        val safeOffsetY = if (offsetY.isFinite()) offsetY else 0f

        destinationFile.parentFile?.let { parent ->
            if (!parent.exists()) {
                parent.mkdirs()
            }
        }

        val exportBitmap = Bitmap.createBitmap(
            exportWidth,
            exportHeight,
            Bitmap.Config.ARGB_8888,
            true,
            ColorSpace.get(ColorSpace.Named.SRGB)
        )

        val tempFile = File(destinationFile.parentFile, "${destinationFile.name}.tmp")
        try {
            val canvas = Canvas(exportBitmap)
            canvas.drawColor(BACKGROUND_OBSIDIAN, PorterDuff.Mode.SRC)

            val drawMatrix = Matrix().apply {
                postTranslate(-source.width / 2f, -source.height / 2f)
                postScale(safeScale, safeScale)
                postTranslate(
                    safeViewportW / 2f + safeOffsetX,
                    safeViewportH / 2f + safeOffsetY
                )
                postScale(
                    exportWidth.toFloat() / safeViewportW,
                    exportHeight.toFloat() / safeViewportH
                )
            }

            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(source, drawMatrix, paint)

            FileOutputStream(tempFile).use { fos ->
                val compressed = exportBitmap.compress(
                    Bitmap.CompressFormat.WEBP_LOSSY,
                    WEBP_QUALITY,
                    fos
                )
                if (!compressed) {
                    throw IOException("Failed to compress framed wallpaper to WEBP_LOSSY.")
                }
                fos.flush()
                fos.fd.sync()
            }

            replaceFileAtomically(tempFile, destinationFile)
            ExportResult(file = destinationFile, width = exportWidth, height = exportHeight)
        } catch (t: Throwable) {
            runCatching { tempFile.delete() }
            throw t
        } finally {
            exportBitmap.recycle()
        }
    }

    /**
     * Decodes a wallpaper [File] into a software sRGB [Bitmap] for OpenGL upload, Stage 2 preview,
     * or thumbnail extraction. Optionally caps the long edge at [maxLongEdge].
     */
    suspend fun decodeWallpaperBitmap(
        file: File,
        maxLongEdge: Int? = null
    ): Bitmap = withContext(Dispatchers.IO) {
        val source = ImageDecoder.createSource(file)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))

            val cap = maxLongEdge
            if (cap != null && cap > 0) {
                val srcW = info.size.width
                val srcH = info.size.height
                val srcLongEdge = max(srcW, srcH)
                if (srcW > 0 && srcH > 0 && srcLongEdge > cap) {
                    val ratio = cap.toDouble() / srcLongEdge.toDouble()
                    val targetW = (srcW * ratio).roundToInt().coerceAtLeast(1)
                    val targetH = (srcH * ratio).roundToInt().coerceAtLeast(1)
                    decoder.setTargetSize(targetW, targetH)
                }
            }
        }
    }

    private fun replaceFileAtomically(tempFile: File, targetFile: File) {
        try {
            Files.move(
                tempFile.toPath(),
                targetFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: Exception) {
            try {
                Files.move(
                    tempFile.toPath(),
                    targetFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: Exception) {
                if (targetFile.exists()) {
                    targetFile.delete()
                }
                if (!tempFile.renameTo(targetFile)) {
                    throw IOException(
                        "Failed to move ${tempFile.name} to ${targetFile.name}."
                    )
                }
            }
        }
    }

    companion object {
        const val MAX_EXPORT_LONG_EDGE: Int = 4096
        const val DEFAULT_EXPORT_WIDTH: Int = 1080
        const val DEFAULT_EXPORT_HEIGHT: Int = 2400
        const val WEBP_QUALITY: Int = 94
        val BACKGROUND_OBSIDIAN: Int = 0xFF111215.toInt()

        /**
         * Always enforces portrait orientation (`min(w, h) x max(w, h)`), guarding against
         * non-positive dimensions with a `1080 x 2400` fallback, and proportionally capping the
         * long edge at [MAX_EXPORT_LONG_EDGE] (4096 px).
         */
        fun computeExportDimensions(
            windowBoundsWidth: Int,
            windowBoundsHeight: Int
        ): Pair<Int, Int> {
            val rawW: Int
            val rawH: Int
            if (windowBoundsWidth <= 0 || windowBoundsHeight <= 0) {
                rawW = DEFAULT_EXPORT_WIDTH
                rawH = DEFAULT_EXPORT_HEIGHT
            } else {
                rawW = min(windowBoundsWidth, windowBoundsHeight)
                rawH = max(windowBoundsWidth, windowBoundsHeight)
            }

            if (rawH <= MAX_EXPORT_LONG_EDGE) {
                return rawW to rawH
            }

            val ratio = MAX_EXPORT_LONG_EDGE.toDouble() / rawH.toDouble()
            val cappedW = (rawW * ratio).roundToInt().coerceAtLeast(1)
            return cappedW to MAX_EXPORT_LONG_EDGE
        }
    }
}
