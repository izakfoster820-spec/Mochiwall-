package com.mochiwall.app.wallpaper

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.ViewConfiguration
import androidx.core.content.ContextCompat
import com.mochiwall.app.MochiApplication
import com.mochiwall.app.core.mask.DownsampledMask
import com.mochiwall.app.core.mask.MaskBrushRasterizer
import com.mochiwall.app.core.mask.MaskDownsampler
import com.mochiwall.app.core.model.PhysicsConfig
import com.mochiwall.app.core.model.ProjectContent
import com.mochiwall.app.core.physics.MochiPhysicsSolver
import com.mochiwall.app.core.physics.TouchAction
import com.mochiwall.app.data.ImageFrameProcessor
import com.mochiwall.app.data.MochiSensorController
import com.mochiwall.app.data.ProjectRepository
import com.mochiwall.app.gl.SimpleEglRenderThread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/**
 * MochiWall Live Wallpaper Service hosting isolated [MochiEngine] instances so simultaneous
 * Wallpaper Picker previews and active Home/Lock Screen instances share zero mutable render state.
 *
 * Implements Blueprint Sections 4.3, 4.5, and 5.3:
 * - Direct sensor delivery onto `SimpleEglRenderThread.renderHandler`
 * - Multi-pointer safe `onTouchEvent` + `WallpaperManager.COMMAND_TAP` fallback deduplication
 * - `targetSdk = 35` `RECEIVER_NOT_EXPORTED` Wake Wobble on `ACTION_SCREEN_ON` & `ACTION_USER_PRESENT`
 *   with 2000 ms pending TTL and 1500 ms cooldown
 * - Main-Thread `notifyColorsChanged()` on cold-boot load and content version changes + O(1) `onComputeColors()`
 */
class MochiWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = MochiEngine()

    private inner class MochiEngine : Engine() {

        private val repository: ProjectRepository =
            (application as MochiApplication).projectRepository
        private val imageFrameProcessor: ImageFrameProcessor =
            (application as MochiApplication).imageFrameProcessor

        private val renderThread = SimpleEglRenderThread(
            onContextRecreated = { reloadActiveContent() }
        )

        private val sensorController = MochiSensorController(
            context = applicationContext,
            onLinearAcceleration = renderThread::onLinearAcceleration,
            onGyroscope = renderThread::onGyroscope,
            sensorHandler = renderThread.renderHandler
        )

        private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val activeLoadJob = AtomicReference<Job?>(null)
        private val touchSlopPx: Float =
            ViewConfiguration.get(applicationContext).scaledTouchSlop.toFloat()

        private var wakeReceiverRegistered: Boolean = false
        private var mainActivePointerId: Int = MotionEvent.INVALID_POINTER_ID
        private var lastLoadedVersion: Int = -1
        private var hasNotifiedBootColors: Boolean = false
        private var lastNotifiedColorsVersion: Int = -1

        @Volatile
        private var latestConfig: PhysicsConfig = PhysicsConfig()

        @Volatile
        private var latestContent: ProjectContent? = null

        @Volatile
        private var engineVisible: Boolean = false

        @Volatile
        private var pendingWakeWobble: Boolean = false

        @Volatile
        private var pendingWakeWobbleTimeMs: Long = 0L

        @Volatile
        private var lastWakeWobbleTimeMs: Long = 0L

        private val wakeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                if (action != Intent.ACTION_SCREEN_ON && action != Intent.ACTION_USER_PRESENT) {
                    return
                }
                if (!latestConfig.wakeWobbleEnabled) return

                val now = SystemClock.elapsedRealtime()
                if (engineVisible) {
                    if (canFireWakeWobble(now)) {
                        lastWakeWobbleTimeMs = now
                        pendingWakeWobble = false
                        renderThread.triggerWakeWobble()
                    }
                } else {
                    pendingWakeWobble = true
                    pendingWakeWobbleTimeMs = now
                }
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(true)
            setOffsetNotificationsEnabled(false)
            engineVisible = isVisible

            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            ContextCompat.registerReceiver(
                applicationContext,
                wakeReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            wakeReceiverRegistered = true

            engineScope.launch {
                repository.state.collect { state ->
                    latestConfig = state.config
                    latestContent = state.content
                    renderThread.updateConfig(state.config)
                    syncSensorState()

                    val content = state.content
                    if (content != null) {
                        if (content.contentVersion != lastLoadedVersion) {
                            lastLoadedVersion = content.contentVersion
                            loadContentAsync(content, textureOnly = false)
                        }
                    } else if (state.isLoaded && lastLoadedVersion != 0) {
                        lastLoadedVersion = 0
                        activeLoadJob.getAndSet(null)?.cancel()
                        renderThread.clearContent()
                    }

                    if (state.isLoaded && repository.cachedWallpaperColors != null) {
                        val contentVersion = content?.contentVersion ?: -1
                        val shouldNotify = !hasNotifiedBootColors ||
                            (content != null && contentVersion != lastNotifiedColorsVersion)
                        if (shouldNotify) {
                            hasNotifiedBootColors = true
                            lastNotifiedColorsVersion = contentVersion
                            notifyColorsChanged()
                        }
                    }
                }
            }
        }

        override fun onSurfaceChanged(
            holder: SurfaceHolder,
            format: Int,
            width: Int,
            height: Int
        ) {
            super.onSurfaceChanged(holder, format, width, height)
            val surface = holder.surface ?: return
            renderThread.onSurfaceChanged(surface, width, height)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            renderThread.onSurfaceDestroyed()
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            engineVisible = visible
            if (!visible) {
                mainActivePointerId = MotionEvent.INVALID_POINTER_ID
            }
            syncSensorState()
            renderThread.setVisible(visible)

            if (visible && pendingWakeWobble) {
                pendingWakeWobble = false
                val now = SystemClock.elapsedRealtime()
                if (now - pendingWakeWobbleTimeMs <= WAKE_WOBBLE_TTL_MS &&
                    latestConfig.wakeWobbleEnabled &&
                    canFireWakeWobble(now)
                ) {
                    lastWakeWobbleTimeMs = now
                    renderThread.triggerWakeWobble()
                }
            }
        }

        override fun onTouchEvent(event: MotionEvent) {
            val actionMasked = event.actionMasked
            val eventTimeMs = event.eventTime

            when (actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (latestConfig.touchEnabled) {
                        val pointerId = event.getPointerId(0)
                        mainActivePointerId = pointerId
                        renderThread.onTouchEvent(
                            action = TouchAction.DOWN,
                            pointerId = pointerId,
                            screenX = event.getX(0),
                            screenY = event.getY(0),
                            eventTimeMs = eventTimeMs,
                            touchSlopPx = touchSlopPx
                        )
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    val activeId = mainActivePointerId
                    if (activeId != MotionEvent.INVALID_POINTER_ID && latestConfig.touchEnabled) {
                        val pointerIndex = event.findPointerIndex(activeId)
                        if (pointerIndex >= 0) {
                            renderThread.onTouchEvent(
                                action = TouchAction.MOVE,
                                pointerId = activeId,
                                screenX = event.getX(pointerIndex),
                                screenY = event.getY(pointerIndex),
                                eventTimeMs = eventTimeMs,
                                touchSlopPx = touchSlopPx
                            )
                        }
                    }
                }

                MotionEvent.ACTION_POINTER_UP -> {
                    val pointerIndex = event.actionIndex
                    val pointerId = event.getPointerId(pointerIndex)
                    if (pointerId == mainActivePointerId) {
                        val x = event.getX(pointerIndex)
                        val y = event.getY(pointerIndex)
                        mainActivePointerId = MotionEvent.INVALID_POINTER_ID
                        renderThread.onTouchEvent(
                            action = TouchAction.UP,
                            pointerId = pointerId,
                            screenX = x,
                            screenY = y,
                            eventTimeMs = eventTimeMs,
                            touchSlopPx = touchSlopPx
                        )
                    }
                }

                MotionEvent.ACTION_UP -> {
                    val activeId = mainActivePointerId
                    if (activeId != MotionEvent.INVALID_POINTER_ID) {
                        val pointerIndex = event.findPointerIndex(activeId).let { idx ->
                            if (idx >= 0) idx else event.actionIndex
                        }
                        val x = event.getX(pointerIndex)
                        val y = event.getY(pointerIndex)
                        mainActivePointerId = MotionEvent.INVALID_POINTER_ID
                        renderThread.onTouchEvent(
                            action = TouchAction.UP,
                            pointerId = activeId,
                            screenX = x,
                            screenY = y,
                            eventTimeMs = eventTimeMs,
                            touchSlopPx = touchSlopPx
                        )
                    }
                }

                MotionEvent.ACTION_CANCEL -> {
                    mainActivePointerId = MotionEvent.INVALID_POINTER_ID
                    renderThread.onTouchEvent(
                        action = TouchAction.CANCEL,
                        pointerId = -1,
                        screenX = 0f,
                        screenY = 0f,
                        eventTimeMs = eventTimeMs,
                        touchSlopPx = touchSlopPx
                    )
                }
            }

            super.onTouchEvent(event)
        }

        override fun onCommand(
            action: String?,
            x: Int,
            y: Int,
            z: Int,
            extras: Bundle?,
            resultRequested: Boolean
        ): Bundle? {
            if (action == WallpaperManager.COMMAND_TAP && latestConfig.touchEnabled) {
                renderThread.onCommandTap(
                    screenX = x.toFloat(),
                    screenY = y.toFloat(),
                    uptimeMs = SystemClock.uptimeMillis()
                )
            }
            return super.onCommand(action, x, y, z, extras, resultRequested)
        }

        override fun onComputeColors(): WallpaperColors? {
            return repository.cachedWallpaperColors
        }

        override fun onDestroy() {
            if (wakeReceiverRegistered) {
                runCatching { applicationContext.unregisterReceiver(wakeReceiver) }
                wakeReceiverRegistered = false
            }

            // Strictly stop sensor delivery BEFORE tearing down renderThread's Looper
            sensorController.stop()

            activeLoadJob.getAndSet(null)?.cancel()
            engineScope.cancel()
            ioScope.cancel()

            renderThread.destroy()
            super.onDestroy()
        }

        private fun syncSensorState() {
            if (engineVisible && latestConfig.motionEnabled) {
                sensorController.start()
            } else {
                sensorController.stop()
            }
        }

        private fun canFireWakeWobble(nowElapsedMs: Long): Boolean {
            val last = lastWakeWobbleTimeMs
            return last == 0L || (nowElapsedMs - last) >= WAKE_WOBBLE_COOLDOWN_MS
        }

        private fun reloadActiveContent() {
            val content = latestContent ?: return
            loadContentAsync(content, textureOnly = true)
        }

        private fun loadContentAsync(content: ProjectContent, textureOnly: Boolean) {
            activeLoadJob.getAndSet(null)?.cancel()

            val job = ioScope.launch {
                var decodedBitmap: Bitmap? = null
                try {
                    val wallpaperFile = repository.resolveWallpaperFile(content)
                    decodedBitmap = imageFrameProcessor.decodeWallpaperBitmap(
                        file = wallpaperFile,
                        maxLongEdge = null
                    )

                    if (!isActive) {
                        if (!decodedBitmap.isRecycled) decodedBitmap.recycle()
                        return@launch
                    }

                    if (textureOnly) {
                        val handedOff = decodedBitmap
                        decodedBitmap = null
                        renderThread.submitContentUpdate(
                            bitmap = handedOff,
                            imageWidth = content.imageWidth,
                            imageHeight = content.imageHeight,
                            downsampledMask = null
                        )
                    } else {
                        val maskBytes = repository.loadCurrentMaskBytes()
                        val downsampledMask = if (
                            maskBytes != null &&
                            maskBytes.size == MaskBrushRasterizer.MASK_BYTE_COUNT
                        ) {
                            withContext(Dispatchers.Default) {
                                MaskDownsampler.downsample(maskBytes)
                            }
                        } else {
                            DownsampledMask(
                                weights = FloatArray(MochiPhysicsSolver.VERTEX_COUNT),
                                hasActiveVertices = false
                            )
                        }

                        if (!isActive) {
                            if (!decodedBitmap.isRecycled) decodedBitmap.recycle()
                            return@launch
                        }

                        val handedOff = decodedBitmap
                        decodedBitmap = null
                        renderThread.submitContentUpdate(
                            bitmap = handedOff,
                            imageWidth = content.imageWidth,
                            imageHeight = content.imageHeight,
                            downsampledMask = downsampledMask
                        )
                    }
                } catch (_: CancellationException) {
                    val leftover = decodedBitmap
                    if (leftover != null && !leftover.isRecycled) {
                        leftover.recycle()
                    }
                } catch (_: Throwable) {
                    val leftover = decodedBitmap
                    if (leftover != null && !leftover.isRecycled) {
                        leftover.recycle()
                    }
                }
            }

            activeLoadJob.set(job)
        }
    }

    companion object {
        private const val WAKE_WOBBLE_TTL_MS: Long = 2_000L
        private const val WAKE_WOBBLE_COOLDOWN_MS: Long = 1_500L
    }
}
