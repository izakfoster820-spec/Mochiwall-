package com.mochiwall.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.mochiwall.app.core.geometry.CoordinateMapper
import com.mochiwall.app.core.mask.DirtyRect
import com.mochiwall.app.core.mask.MaskBrushRasterizer
import com.mochiwall.app.ui.viewmodel.SculptTool
import kotlin.math.hypot
import kotlin.math.max

@Composable
fun MaskCanvasView(
    previewBitmap: Bitmap?,
    imageWidth: Int,
    imageHeight: Int,
    workingMask: ByteArray,
    maskRevision: Int,
    tool: SculptTool,
    brushSizeFraction: Float,
    feather: Float,
    isPeeking: Boolean,
    resetZoomSignal: Int,
    onStrokeCommitted: () -> Unit,
    onZoomChanged: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val latestOnStrokeCommitted by rememberUpdatedState(onStrokeCommitted)
    val latestOnZoomChanged by rememberUpdatedState(onZoomChanged)

    AndroidView(
        modifier = modifier,
        factory = { context ->
            MaskSculptAndroidView(context).apply {
                onStrokeCommittedListener = { latestOnStrokeCommitted() }
                onZoomChangedListener = { newZoom -> latestOnZoomChanged(newZoom) }
                syncState(
                    bitmap = previewBitmap,
                    imgW = imageWidth,
                    imgH = imageHeight,
                    mask = workingMask,
                    revision = maskRevision,
                    activeTool = tool,
                    brushFraction = brushSizeFraction,
                    brushFeather = feather,
                    peeking = isPeeking,
                    resetSignal = resetZoomSignal
                )
            }
        },
        update = { view ->
            view.onStrokeCommittedListener = { latestOnStrokeCommitted() }
            view.onZoomChangedListener = { newZoom -> latestOnZoomChanged(newZoom) }
            view.syncState(
                bitmap = previewBitmap,
                imgW = imageWidth,
                imgH = imageHeight,
                mask = workingMask,
                revision = maskRevision,
                activeTool = tool,
                brushFraction = brushSizeFraction,
                brushFeather = feather,
                peeking = isPeeking,
                resetSignal = resetZoomSignal
            )
        }
    )
}

internal class MaskSculptAndroidView(context: Context) : View(context) {

    private enum class GestureMode {
        IDLE,
        PAINTING,
        PINCH_ZOOM,
        WAITING_ALL_UP
    }

    var onStrokeCommittedListener: (() -> Unit)? = null
    var onZoomChangedListener: ((Float) -> Unit)? = null

    private var previewBitmap: Bitmap? = null
    private var imageWidth: Int = 1
    private var imageHeight: Int = 1
    private var workingMask: ByteArray? = null
    private var tool: SculptTool = SculptTool.BRUSH
    private var brushSizeFraction: Float = 0.12f
    private var feather: Float = 0.50f
    private var isPeeking: Boolean = false

    private var lastAppliedMaskRevision: Int = Int.MIN_VALUE
    private var lastAppliedResetZoomSignal: Int = Int.MIN_VALUE

    // Pre-allocated zero-GC buffers for painting and overlay rendering
    private val strokeStartSnapshot = ByteArray(MaskBrushRasterizer.MASK_BYTE_COUNT)
    private val overlayPixels = IntArray(MaskBrushRasterizer.MASK_BYTE_COUNT)
    private var overlayBitmap: Bitmap? = null

    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val overlayPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val imageDstRect = RectF()
    private var coordinateMapper: CoordinateMapper = CoordinateMapper(1, 1, 1, 1)

    // Zoom & pan state around view center:
    // viewX = (surfaceX - cx) * zoom + cx + panX
    // viewY = (surfaceY - cy) * zoom + cy + panY
    private var zoom: Float = MIN_ZOOM
    private var panX: Float = 0f
    private var panY: Float = 0f

    // Touch state machine fields
    private var gestureMode: GestureMode = GestureMode.IDLE
    private var strokeStartTimeMs: Long = 0L
    private var paintPointerId: Int = MotionEvent.INVALID_POINTER_ID
    private var lastMaskX: Float = 0f
    private var lastMaskY: Float = 0f

    private var pinchPointerId1: Int = MotionEvent.INVALID_POINTER_ID
    private var pinchPointerId2: Int = MotionEvent.INVALID_POINTER_ID
    private var prevPinchMidX: Float = 0f
    private var prevPinchMidY: Float = 0f
    private var prevPinchSpan: Float = 1f

    fun syncState(
        bitmap: Bitmap?,
        imgW: Int,
        imgH: Int,
        mask: ByteArray,
        revision: Int,
        activeTool: SculptTool,
        brushFraction: Float,
        brushFeather: Float,
        peeking: Boolean,
        resetSignal: Int
    ) {
        var needsRedraw = false

        if (previewBitmap !== bitmap) {
            previewBitmap = bitmap
            needsRedraw = true
        }

        val safeImgW = imgW.coerceAtLeast(1)
        val safeImgH = imgH.coerceAtLeast(1)
        if (imageWidth != safeImgW || imageHeight != safeImgH) {
            imageWidth = safeImgW
            imageHeight = safeImgH
            updateCoordinateMapper()
            needsRedraw = true
        }

        val maskChanged = workingMask !== mask
        workingMask = mask
        if (maskChanged || revision != lastAppliedMaskRevision) {
            lastAppliedMaskRevision = revision
            rebuildFullOverlay()
            needsRedraw = true
        }

        tool = activeTool
        brushSizeFraction = brushFraction.coerceIn(0.04f, 0.25f)
        feather = brushFeather.coerceIn(0f, 1f)

        if (isPeeking != peeking) {
            isPeeking = peeking
            needsRedraw = true
        }

        if (lastAppliedResetZoomSignal == Int.MIN_VALUE) {
            lastAppliedResetZoomSignal = resetSignal
        } else if (resetSignal != lastAppliedResetZoomSignal) {
            lastAppliedResetZoomSignal = resetSignal
            resetZoom()
            needsRedraw = false // resetZoom already invalidates
        }

        if (needsRedraw) {
            invalidate()
        }
    }

    fun resetZoom() {
        zoom = MIN_ZOOM
        panX = 0f
        panY = 0f
        onZoomChangedListener?.invoke(MIN_ZOOM)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateCoordinateMapper()
        clampPanToBounds()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (workingMask != null) {
            rebuildFullOverlay()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        overlayBitmap?.let { bmp ->
            if (!bmp.isRecycled) {
                bmp.recycle()
            }
        }
        overlayBitmap = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(BACKGROUND_OBSIDIAN)

        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        val cx = w * 0.5f
        val cy = h * 0.5f

        canvas.save()
        canvas.translate(cx + panX, cy + panY)
        canvas.scale(zoom, zoom)
        canvas.translate(-cx, -cy)

        val bmp = previewBitmap
        if (bmp != null && !bmp.isRecycled) {
            canvas.drawBitmap(bmp, null, imageDstRect, imagePaint)
        }

        if (!isPeeking && workingMask != null) {
            if (overlayBitmap == null || overlayBitmap?.isRecycled == true) {
                rebuildFullOverlay()
            }
            val overlay = overlayBitmap
            if (overlay != null && !overlay.isRecycled) {
                canvas.drawBitmap(overlay, null, imageDstRect, overlayPaint)
            }
        }

        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val mask = workingMask ?: return false
        if (width <= 0 || height <= 0 || mask.size < MaskBrushRasterizer.MASK_BYTE_COUNT) {
            return false
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                System.arraycopy(
                    mask,
                    0,
                    strokeStartSnapshot,
                    0,
                    MaskBrushRasterizer.MASK_BYTE_COUNT
                )
                strokeStartTimeMs = SystemClock.uptimeMillis()
                paintPointerId = event.getPointerId(0)
                gestureMode = GestureMode.PAINTING

                val (mx, my) = coordinateMapper.zoomedSurfaceToMaskPixel(
                    viewX = event.x,
                    viewY = event.y,
                    zoom = zoom,
                    panX = panX,
                    panY = panY
                )
                val centerX = mx + 0.5f
                val centerY = my + 0.5f
                lastMaskX = centerX
                lastMaskY = centerY

                val dirty = MaskBrushRasterizer.paintStrokeSegment(
                    mask = mask,
                    x0 = centerX,
                    y0 = centerY,
                    x1 = centerX,
                    y1 = centerY,
                    radiusPx = currentBrushRadiusPx(),
                    feather = feather,
                    isEraser = tool == SculptTool.ERASER
                )
                if (dirty != null) {
                    updateOverlayRect(dirty)
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                when (gestureMode) {
                    GestureMode.PAINTING -> {
                        val elapsed = SystemClock.uptimeMillis() - strokeStartTimeMs
                        if (elapsed <= PINCH_ROLLBACK_WINDOW_MS) {
                            // Immediate 0-frame rollback of the initial dab and switch to pinch-zoom
                            System.arraycopy(
                                strokeStartSnapshot,
                                0,
                                mask,
                                0,
                                MaskBrushRasterizer.MASK_BYTE_COUNT
                            )
                            rebuildFullOverlay()
                            invalidate()

                            val secondPointerId = event.getPointerId(event.actionIndex)
                            startPinchZoom(event, paintPointerId, secondPointerId)
                        }
                        // If elapsed > 120 ms: completely ignore the secondary pointer and keep painting.
                    }

                    GestureMode.IDLE -> {
                        if (event.pointerCount >= 2) {
                            startPinchZoom(event, event.getPointerId(0), event.getPointerId(1))
                        }
                    }

                    GestureMode.PINCH_ZOOM,
                    GestureMode.WAITING_ALL_UP -> Unit
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                when (gestureMode) {
                    GestureMode.PAINTING -> {
                        val idx = event.findPointerIndex(paintPointerId)
                        if (idx >= 0) {
                            val (mx, my) = coordinateMapper.zoomedSurfaceToMaskPixel(
                                viewX = event.getX(idx),
                                viewY = event.getY(idx),
                                zoom = zoom,
                                panX = panX,
                                panY = panY
                            )
                            val centerX = mx + 0.5f
                            val centerY = my + 0.5f

                            val dirty = MaskBrushRasterizer.paintStrokeSegment(
                                mask = mask,
                                x0 = lastMaskX,
                                y0 = lastMaskY,
                                x1 = centerX,
                                y1 = centerY,
                                radiusPx = currentBrushRadiusPx(),
                                feather = feather,
                                isEraser = tool == SculptTool.ERASER
                            )
                            lastMaskX = centerX
                            lastMaskY = centerY

                            if (dirty != null) {
                                updateOverlayRect(dirty)
                                invalidate()
                            }
                        }
                    }

                    GestureMode.PINCH_ZOOM -> {
                        val idx1 = event.findPointerIndex(pinchPointerId1)
                        val idx2 = event.findPointerIndex(pinchPointerId2)
                        if (idx1 >= 0 && idx2 >= 0) {
                            val x1 = event.getX(idx1)
                            val y1 = event.getY(idx1)
                            val x2 = event.getX(idx2)
                            val y2 = event.getY(idx2)

                            val currMidX = (x1 + x2) * 0.5f
                            val currMidY = (y1 + y2) * 0.5f
                            val currSpan = hypot(x2 - x1, y2 - y1)

                            val scaleFactor = if (prevPinchSpan > MIN_PINCH_SPAN) {
                                currSpan / prevPinchSpan
                            } else {
                                1f
                            }
                            val oldZoom = zoom
                            val newZoom = (oldZoom * scaleFactor).coerceIn(MIN_ZOOM, MAX_ZOOM)
                            val actualFactor = newZoom / oldZoom

                            val cx = width * 0.5f
                            val cy = height * 0.5f
                            panX = (currMidX - prevPinchMidX) +
                                (prevPinchMidX - cx) * (1f - actualFactor) +
                                panX * actualFactor
                            panY = (currMidY - prevPinchMidY) +
                                (prevPinchMidY - cy) * (1f - actualFactor) +
                                panY * actualFactor
                            zoom = newZoom
                            clampPanToBounds()

                            prevPinchMidX = currMidX
                            prevPinchMidY = currMidY
                            if (currSpan > MIN_PINCH_SPAN) {
                                prevPinchSpan = currSpan
                            }

                            onZoomChangedListener?.invoke(zoom)
                            invalidate()
                        }
                    }

                    GestureMode.IDLE,
                    GestureMode.WAITING_ALL_UP -> Unit
                }
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val liftedId = event.getPointerId(event.actionIndex)
                when (gestureMode) {
                    GestureMode.PAINTING -> {
                        if (liftedId == paintPointerId) {
                            gestureMode = GestureMode.WAITING_ALL_UP
                            paintPointerId = MotionEvent.INVALID_POINTER_ID
                            onStrokeCommittedListener?.invoke()
                        }
                        // If an ignored secondary finger lifted, continue painting uninterrupted.
                    }

                    GestureMode.PINCH_ZOOM -> {
                        if (liftedId == pinchPointerId1 || liftedId == pinchPointerId2) {
                            gestureMode = GestureMode.WAITING_ALL_UP
                            pinchPointerId1 = MotionEvent.INVALID_POINTER_ID
                            pinchPointerId2 = MotionEvent.INVALID_POINTER_ID
                        }
                    }

                    GestureMode.IDLE,
                    GestureMode.WAITING_ALL_UP -> Unit
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                val wasPainting = gestureMode == GestureMode.PAINTING
                gestureMode = GestureMode.IDLE
                paintPointerId = MotionEvent.INVALID_POINTER_ID
                pinchPointerId1 = MotionEvent.INVALID_POINTER_ID
                pinchPointerId2 = MotionEvent.INVALID_POINTER_ID

                if (wasPainting) {
                    performClick()
                    onStrokeCommittedListener?.invoke()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (gestureMode == GestureMode.PAINTING) {
                    System.arraycopy(
                        strokeStartSnapshot,
                        0,
                        mask,
                        0,
                        MaskBrushRasterizer.MASK_BYTE_COUNT
                    )
                    rebuildFullOverlay()
                    invalidate()
                }
                gestureMode = GestureMode.IDLE
                paintPointerId = MotionEvent.INVALID_POINTER_ID
                pinchPointerId1 = MotionEvent.INVALID_POINTER_ID
                pinchPointerId2 = MotionEvent.INVALID_POINTER_ID
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }

    private fun startPinchZoom(event: MotionEvent, id1: Int, id2: Int) {
        val idx1 = event.findPointerIndex(id1)
        val idx2 = event.findPointerIndex(id2)
        if (idx1 < 0 || idx2 < 0 || id1 == id2) {
            gestureMode = GestureMode.WAITING_ALL_UP
            paintPointerId = MotionEvent.INVALID_POINTER_ID
            return
        }

        gestureMode = GestureMode.PINCH_ZOOM
        paintPointerId = MotionEvent.INVALID_POINTER_ID
        pinchPointerId1 = id1
        pinchPointerId2 = id2

        val x1 = event.getX(idx1)
        val y1 = event.getY(idx1)
        val x2 = event.getX(idx2)
        val y2 = event.getY(idx2)
        prevPinchMidX = (x1 + x2) * 0.5f
        prevPinchMidY = (y1 + y2) * 0.5f
        prevPinchSpan = max(MIN_PINCH_SPAN, hypot(x2 - x1, y2 - y1))
    }

    private fun currentBrushRadiusPx(): Float {
        return (brushSizeFraction * MaskBrushRasterizer.MASK_WIDTH * 0.5f)
            .coerceAtLeast(MIN_BRUSH_RADIUS_PX)
    }

    private fun updateCoordinateMapper() {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        coordinateMapper = CoordinateMapper(
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            surfaceWidth = w,
            surfaceHeight = h
        )
        val vp = coordinateMapper.viewport
        val uSpan = (vp.uMax - vp.uMin).coerceAtLeast(1e-6f)
        val vSpan = (vp.vMax - vp.vMin).coerceAtLeast(1e-6f)

        val dstLeft = (-vp.uMin / uSpan) * w
        val dstRight = ((1f - vp.uMin) / uSpan) * w
        val dstTop = (-vp.vMin / vSpan) * h
        val dstBottom = ((1f - vp.vMin) / vSpan) * h
        imageDstRect.set(dstLeft, dstTop, dstRight, dstBottom)
    }

    private fun clampPanToBounds() {
        val cx = width * 0.5f
        val cy = height * 0.5f
        val maxPanX = max(0f, (zoom - 1f) * cx)
        val maxPanY = max(0f, (zoom - 1f) * cy)
        panX = panX.coerceIn(-maxPanX, maxPanX)
        panY = panY.coerceIn(-maxPanY, maxPanY)
    }

    private fun ensureOverlayBitmap(): Bitmap {
        val existing = overlayBitmap
        if (existing != null && !existing.isRecycled) {
            return existing
        }
        val created = Bitmap.createBitmap(
            MaskBrushRasterizer.MASK_WIDTH,
            MaskBrushRasterizer.MASK_HEIGHT,
            Bitmap.Config.ARGB_8888
        )
        overlayBitmap = created
        return created
    }

    private fun updateOverlayRect(dirty: DirtyRect) {
        val mask = workingMask ?: return
        val existing = overlayBitmap
        if (existing == null || existing.isRecycled) {
            rebuildFullOverlay()
            return
        }
        val maskW = MaskBrushRasterizer.MASK_WIDTH

        for (py in dirty.top..dirty.bottom) {
            val rowOffset = py * maskW
            for (px in dirty.left..dirty.right) {
                val idx = rowOffset + px
                val u = mask[idx].toInt() and 0xFF
                val alpha = (u * MAX_OVERLAY_ALPHA + 127) / 255
                overlayPixels[idx] = (alpha shl 24) or SOFT_CORAL_RGB
            }
        }

        val dirtyW = dirty.right - dirty.left + 1
        val dirtyH = dirty.bottom - dirty.top + 1
        existing.setPixels(
            overlayPixels,
            dirty.top * maskW + dirty.left,
            maskW,
            dirty.left,
            dirty.top,
            dirtyW,
            dirtyH
        )
    }

    private fun rebuildFullOverlay() {
        val mask = workingMask ?: return
        val bmp = ensureOverlayBitmap()
        val count = MaskBrushRasterizer.MASK_BYTE_COUNT

        for (idx in 0 until count) {
            val u = mask[idx].toInt() and 0xFF
            val alpha = (u * MAX_OVERLAY_ALPHA + 127) / 255
            overlayPixels[idx] = (alpha shl 24) or SOFT_CORAL_RGB
        }

        bmp.setPixels(
            overlayPixels,
            0,
            MaskBrushRasterizer.MASK_WIDTH,
            0,
            0,
            MaskBrushRasterizer.MASK_WIDTH,
            MaskBrushRasterizer.MASK_HEIGHT
        )
    }

    companion object {
        private const val MIN_ZOOM = 1.0f
        private const val MAX_ZOOM = 3.0f
        private const val PINCH_ROLLBACK_WINDOW_MS = 120L
        private const val MIN_PINCH_SPAN = 4f
        private const val MIN_BRUSH_RADIUS_PX = 1.5f
        private const val MAX_OVERLAY_ALPHA = 115 // ~45% alpha
        private const val SOFT_CORAL_RGB = 0x00FF6B6B
        private val BACKGROUND_OBSIDIAN = 0xFF111215.toInt()
    }
}
