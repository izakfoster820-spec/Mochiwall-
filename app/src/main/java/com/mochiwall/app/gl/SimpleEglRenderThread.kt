package com.mochiwall.app.gl

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.Surface
import com.mochiwall.app.core.geometry.CoordinateMapper
import com.mochiwall.app.core.mask.DownsampledMask
import com.mochiwall.app.core.model.PhysicsConfig
import com.mochiwall.app.core.physics.DisplayRotation
import com.mochiwall.app.core.physics.MochiPhysicsSolver
import com.mochiwall.app.core.physics.SensorFilterPipeline
import com.mochiwall.app.core.physics.TouchAction
import com.mochiwall.app.core.physics.TouchStateMachine
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Minimal 3-state EGL14 OpenGL ES 3.0 render loop dedicated exclusively to `MochiWallpaperService`.
 *
 * Implements Blueprint Section 5.3:
 * - Strict 3-state lifecycle: [State.DETACHED], [State.ACTIVE], [State.DESTROYED]
 * - Never calls `Surface.setFrameRate(...)` (LTPO OLED scales naturally via Choreographer + lerp)
 * - Keeps `EGLContext` alive across surface detaches until [destroy]
 * - Synchronous 500 ms latch on [onSurfaceDestroyed] and [destroy]
 * - Exposes [renderHandler] so `MochiSensorController` delivers IMU callbacks directly on the GL thread
 * - Explicit [wakeRenderer] that never schedules Choreographer while invisible or detached
 */
class SimpleEglRenderThread(
    private val onContextRecreated: (() -> Unit)? = null
) {

    enum class State {
        DETACHED,
        ACTIVE,
        DESTROYED
    }

    private val handlerThread = HandlerThread("MochiWallpaperGL").apply { start() }
    val renderHandler: Handler = Handler(handlerThread.looper)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val destroyed = AtomicBoolean(false)
    private val pendingBitmap = AtomicReference<Bitmap?>(null)

    // Render-thread-exclusively owned EGL, GL, physics, and input state
    private var state: State = State.DETACHED
    private var isVisible: Boolean = false
    private var choreographerScheduled: Boolean = false
    private var hasInitializedGlOnce: Boolean = false
    private var contextNeedsGlInit: Boolean = false
    private var pendingClearTexture: Boolean = false

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglConfig: EGLConfig? = null
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private val renderer = MochiGLRenderer()
    private val solver = MochiPhysicsSolver()
    private val touchStateMachine = TouchStateMachine()
    private val sensorPipeline = SensorFilterPipeline()

    private var currentConfig: PhysicsConfig = PhysicsConfig()
    private var mapper: CoordinateMapper = CoordinateMapper(1, 1, 1, 1)
    private var imageWidth: Int = 1
    private var imageHeight: Int = 1
    private var surfaceWidth: Int = 1
    private var surfaceHeight: Int = 1

    private val frameCallback = Choreographer.FrameCallback { frameTimeNanos ->
        choreographerScheduled = false
        if (state != State.ACTIVE || !isVisible || eglSurface == EGL14.EGL_NO_SURFACE) {
            return@FrameCallback
        }
        renderSingleFrameOnRenderThread(frameTimeNanos)
    }

    fun onSurfaceChanged(surface: Surface, width: Int, height: Int) {
        if (destroyed.get() || width <= 0 || height <= 0 || !surface.isValid) return

        renderHandler.post {
            if (state == State.DESTROYED || !surface.isValid) return@post

            stopChoreographerOnRenderThread()

            if (!ensureEglContextOnRenderThread()) {
                state = State.DETACHED
                return@post
            }

            destroyWindowSurfaceOnRenderThread()

            val config = eglConfig ?: run {
                state = State.DETACHED
                return@post
            }

            val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
            val createdSurface = EGL14.eglCreateWindowSurface(
                eglDisplay,
                config,
                surface,
                surfaceAttribs,
                0
            )
            if (createdSurface == null || createdSurface == EGL14.EGL_NO_SURFACE) {
                state = State.DETACHED
                return@post
            }
            eglSurface = createdSurface

            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                val err = EGL14.eglGetError()
                if (err == EGL14.EGL_CONTEXT_LOST) {
                    handleContextLostOnRenderThread()
                } else {
                    destroyWindowSurfaceOnRenderThread()
                    state = State.DETACHED
                }
                return@post
            }

            if (contextNeedsGlInit || !renderer.isInitialized) {
                val isRecreation = hasInitializedGlOnce
                hasInitializedGlOnce = true
                contextNeedsGlInit = false
                renderer.onGlContextCreated()
                if (isRecreation && pendingBitmap.get() == null) {
                    notifyContextRecreated()
                }
            }

            surfaceWidth = width
            surfaceHeight = height
            rebuildMapperOnRenderThread()
            renderer.onViewportChanged(width, height)
            state = State.ACTIVE

            if (isVisible) {
                renderSingleFrameOnRenderThread(System.nanoTime())
            }
        }
    }

    /**
     * Synchronously detaches and destroys the window [EGLSurface] (keeping [EGLContext] alive)
     * with a 500 ms safety latch so SurfaceFlinger never races a live GL draw.
     */
    fun onSurfaceDestroyed() {
        if (destroyed.get() || !handlerThread.isAlive) return

        if (Looper.myLooper() === renderHandler.looper) {
            detachSurfaceOnRenderThread()
            return
        }

        val latch = CountDownLatch(1)
        val posted = renderHandler.post {
            try {
                detachSurfaceOnRenderThread()
            } finally {
                latch.countDown()
            }
        }
        if (posted) {
            try {
                latch.await(LATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    fun setVisible(visible: Boolean) {
        if (destroyed.get()) return

        renderHandler.post {
            if (state == State.DESTROYED) return@post

            if (!visible) {
                isVisible = false
                stopChoreographerOnRenderThread()
                touchStateMachine.cancel(SystemClock.uptimeMillis())
                sensorPipeline.reset()
            } else {
                isVisible = true
                if (state == State.ACTIVE && eglSurface != EGL14.EGL_NO_SURFACE) {
                    renderSingleFrameOnRenderThread(System.nanoTime())
                }
            }
        }
    }

    /**
     * Schedules the next Choreographer vsync frame if and only if the engine is both
     * [State.ACTIVE] and visible. Safe to invoke from any thread.
     */
    fun wakeRenderer() {
        if (destroyed.get()) return

        if (Looper.myLooper() === renderHandler.looper) {
            wakeRendererOnRenderThread()
        } else {
            renderHandler.post { wakeRendererOnRenderThread() }
        }
    }

    fun onTouchEvent(
        action: TouchAction,
        pointerId: Int,
        screenX: Float,
        screenY: Float,
        eventTimeMs: Long,
        touchSlopPx: Float
    ) {
        if (destroyed.get()) return

        renderHandler.post {
            if (state == State.DESTROYED) return@post
            if (!currentConfig.touchEnabled &&
                action != TouchAction.CANCEL &&
                action != TouchAction.UP
            ) {
                return@post
            }

            if (action == TouchAction.CANCEL) {
                touchStateMachine.onTouchEvent(
                    action = TouchAction.CANCEL,
                    pointerId = -1,
                    screenX = 0f,
                    screenY = 0f,
                    physX = 0f,
                    physY = 0f,
                    eventTimeMs = eventTimeMs,
                    touchSlopPx = touchSlopPx
                )
            } else {
                val phys = mapper.surfaceToPhysics(screenX, screenY)
                touchStateMachine.onTouchEvent(
                    action = action,
                    pointerId = pointerId,
                    screenX = screenX,
                    screenY = screenY,
                    physX = phys.x,
                    physY = phys.y,
                    eventTimeMs = eventTimeMs,
                    touchSlopPx = touchSlopPx
                )
            }
            wakeRendererOnRenderThread()
        }
    }

    fun onCommandTap(screenX: Float, screenY: Float, uptimeMs: Long) {
        if (destroyed.get()) return

        renderHandler.post {
            if (state == State.DESTROYED || !currentConfig.touchEnabled) return@post

            val phys = mapper.surfaceToPhysics(screenX, screenY)
            val accepted = touchStateMachine.enqueueCommandTap(phys.x, phys.y, uptimeMs)
            if (accepted) {
                wakeRendererOnRenderThread()
            }
        }
    }

    fun onLinearAcceleration(
        sx: Float,
        sy: Float,
        timestampNs: Long,
        rotation: DisplayRotation
    ) {
        if (destroyed.get()) return

        if (Looper.myLooper() !== renderHandler.looper) {
            renderHandler.post {
                onLinearAccelerationOnRenderThread(sx, sy, timestampNs, rotation)
            }
            return
        }
        onLinearAccelerationOnRenderThread(sx, sy, timestampNs, rotation)
    }

    fun onGyroscope(
        wx: Float,
        wy: Float,
        timestampNs: Long,
        rotation: DisplayRotation
    ) {
        if (destroyed.get()) return

        if (Looper.myLooper() !== renderHandler.looper) {
            renderHandler.post {
                onGyroscopeOnRenderThread(wx, wy, timestampNs, rotation)
            }
            return
        }
        onGyroscopeOnRenderThread(wx, wy, timestampNs, rotation)
    }

    fun triggerWakeWobble() {
        if (destroyed.get()) return

        renderHandler.post {
            if (state == State.DESTROYED || !isVisible || !currentConfig.wakeWobbleEnabled) {
                return@post
            }
            solver.applyWakeWobble(currentConfig)
            wakeRendererOnRenderThread()
        }
    }

    fun updateConfig(config: PhysicsConfig) {
        if (destroyed.get()) return
        val clean = config.sanitized()

        renderHandler.post {
            if (state == State.DESTROYED) return@post

            val previous = currentConfig
            currentConfig = clean
            if (!clean.motionEnabled) {
                sensorPipeline.reset()
            }
            if (!clean.touchEnabled && previous.touchEnabled) {
                touchStateMachine.cancel(SystemClock.uptimeMillis())
            }
            if (isVisible && state == State.ACTIVE) {
                wakeRendererOnRenderThread()
            }
        }
    }

    fun submitContentUpdate(
        bitmap: Bitmap?,
        imageWidth: Int,
        imageHeight: Int,
        downsampledMask: DownsampledMask?
    ) {
        if (destroyed.get()) {
            if (bitmap != null && !bitmap.isRecycled) {
                bitmap.recycle()
            }
            return
        }

        if (bitmap != null) {
            val displaced = pendingBitmap.getAndSet(bitmap)
            if (displaced != null && displaced !== bitmap && !displaced.isRecycled) {
                displaced.recycle()
            }
        }

        renderHandler.post {
            if (state == State.DESTROYED) {
                recyclePendingBitmap()
                return@post
            }

            pendingClearTexture = false
            this.imageWidth = if (imageWidth > 0) imageWidth else 1
            this.imageHeight = if (imageHeight > 0) imageHeight else 1
            rebuildMapperOnRenderThread()

            if (downsampledMask != null) {
                solver.setMask(downsampledMask.weights, downsampledMask.hasActiveVertices)
            }
            touchStateMachine.reset()
            solver.resetPose()

            if (isVisible && state == State.ACTIVE) {
                wakeRendererOnRenderThread()
            }
        }
    }

    fun clearContent() {
        recyclePendingBitmap()
        if (destroyed.get()) return

        renderHandler.post {
            if (state == State.DESTROYED) return@post

            touchStateMachine.reset()
            solver.resetPose()
            if (state == State.ACTIVE && eglSurface != EGL14.EGL_NO_SURFACE) {
                pendingClearTexture = false
                renderer.clearTexture()
                if (isVisible) {
                    renderSingleFrameOnRenderThread(System.nanoTime())
                }
            } else {
                pendingClearTexture = true
            }
        }
    }

    /**
     * Idempotently tears down all OpenGL/EGL resources on the render thread (500 ms latch)
     * and quits the internal [HandlerThread].
     */
    fun destroy() {
        if (!destroyed.compareAndSet(false, true)) return

        recyclePendingBitmap()

        if (handlerThread.isAlive) {
            if (Looper.myLooper() === renderHandler.looper) {
                destroyOnRenderThread()
            } else {
                val latch = CountDownLatch(1)
                val posted = renderHandler.post {
                    try {
                        destroyOnRenderThread()
                    } finally {
                        latch.countDown()
                    }
                }
                if (posted) {
                    try {
                        latch.await(LATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
            }
        }

        handlerThread.quitSafely()
    }

    private fun onLinearAccelerationOnRenderThread(
        sx: Float,
        sy: Float,
        timestampNs: Long,
        rotation: DisplayRotation
    ) {
        if (!isVisible || state != State.ACTIVE || !currentConfig.motionEnabled) return
        sensorPipeline.onLinearAcceleration(sx, sy, timestampNs, rotation)
        if (sensorPipeline.hasActiveForce(currentConfig)) {
            wakeRendererOnRenderThread()
        }
    }

    private fun onGyroscopeOnRenderThread(
        wx: Float,
        wy: Float,
        timestampNs: Long,
        rotation: DisplayRotation
    ) {
        if (!isVisible || state != State.ACTIVE || !currentConfig.motionEnabled) return
        sensorPipeline.onGyroscope(wx, wy, timestampNs, rotation)
        if (sensorPipeline.hasActiveForce(currentConfig)) {
            wakeRendererOnRenderThread()
        }
    }

    private fun wakeRendererOnRenderThread() {
        if (state != State.ACTIVE || !isVisible || eglSurface == EGL14.EGL_NO_SURFACE) {
            return
        }
        if (!choreographerScheduled) {
            choreographerScheduled = true
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    private fun stopChoreographerOnRenderThread() {
        if (choreographerScheduled) {
            choreographerScheduled = false
            Choreographer.getInstance().removeFrameCallback(frameCallback)
        }
    }

    private fun renderSingleFrameOnRenderThread(frameTimeNanos: Long) {
        if (state != State.ACTIVE || !isVisible || eglSurface == EGL14.EGL_NO_SURFACE) {
            return
        }

        if (pendingClearTexture) {
            pendingClearTexture = false
            renderer.clearTexture()
        }

        pendingBitmap.getAndSet(null)?.let { bitmap ->
            renderer.uploadTexture(bitmap)
        }

        val sensorFx = sensorPipeline.computeForceX(currentConfig)
        val sensorFy = sensorPipeline.computeForceY(currentConfig)

        val stillActive = solver.stepFrame(
            frameTimeNs = frameTimeNanos,
            config = currentConfig,
            touch = touchStateMachine,
            sensorFx = sensorFx,
            sensorFy = sensorFy
        )

        renderer.renderFrame(solver, mapper, currentConfig)

        val swapped = EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        if (!swapped) {
            val err = EGL14.eglGetError()
            if (err == EGL14.EGL_CONTEXT_LOST) {
                handleContextLostOnRenderThread()
            }
            return
        }

        if (stillActive || pendingBitmap.get() != null) {
            wakeRendererOnRenderThread()
        }
    }

    private fun ensureEglContextOnRenderThread(): Boolean {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) {
                return false
            }
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                return false
            }
            eglDisplay = display
        }

        if (eglConfig == null) {
            eglConfig = chooseEglConfigOnRenderThread(eglDisplay) ?: return false
        }

        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            val config = eglConfig ?: return false
            val contextAttribs = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
                EGL14.EGL_NONE
            )
            val createdContext = EGL14.eglCreateContext(
                eglDisplay,
                config,
                EGL14.EGL_NO_CONTEXT,
                contextAttribs,
                0
            )
            if (createdContext == null || createdContext == EGL14.EGL_NO_CONTEXT) {
                return false
            }
            eglContext = createdContext
            contextNeedsGlInit = true
        }

        return true
    }

    private fun chooseEglConfigOnRenderThread(display: EGLDisplay): EGLConfig? {
        val es3Renderable = EGL14.EGL_OPENGL_ES2_BIT or EGLExt.EGL_OPENGL_ES3_BIT_KHR
        val es2Renderable = EGL14.EGL_OPENGL_ES2_BIT

        val candidates = arrayOf(
            buildConfigAttribs(alphaSize = 8, renderableType = es3Renderable),
            buildConfigAttribs(alphaSize = 8, renderableType = es2Renderable),
            buildConfigAttribs(alphaSize = 0, renderableType = es3Renderable),
            buildConfigAttribs(alphaSize = 0, renderableType = es2Renderable)
        )

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)

        for (attribs in candidates) {
            val ok = EGL14.eglChooseConfig(
                display,
                attribs,
                0,
                configs,
                0,
                configs.size,
                numConfigs,
                0
            )
            if (ok && numConfigs[0] > 0 && configs[0] != null) {
                return configs[0]
            }
        }
        return null
    }

    private fun buildConfigAttribs(alphaSize: Int, renderableType: Int): IntArray {
        return intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, alphaSize,
            EGL14.EGL_RENDERABLE_TYPE, renderableType,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE
        )
    }

    private fun detachSurfaceOnRenderThread() {
        stopChoreographerOnRenderThread()
        destroyWindowSurfaceOnRenderThread()
        if (state != State.DESTROYED) {
            state = State.DETACHED
        }
    }

    private fun destroyWindowSurfaceOnRenderThread() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT
            )
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
                eglSurface = EGL14.EGL_NO_SURFACE
            }
        }
    }

    private fun handleContextLostOnRenderThread() {
        stopChoreographerOnRenderThread()
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT
            )
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
                eglSurface = EGL14.EGL_NO_SURFACE
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                eglContext = EGL14.EGL_NO_CONTEXT
            }
        }
        contextNeedsGlInit = true
        if (state != State.DESTROYED) {
            state = State.DETACHED
        }
    }

    private fun destroyOnRenderThread() {
        state = State.DESTROYED
        isVisible = false
        stopChoreographerOnRenderThread()
        recyclePendingBitmap()
        sensorPipeline.reset()
        touchStateMachine.reset()
        solver.resetPose()

        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            if (eglContext != EGL14.EGL_NO_CONTEXT && eglSurface != EGL14.EGL_NO_SURFACE) {
                if (EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                    renderer.release()
                }
            }

            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT
            )

            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
                eglSurface = EGL14.EGL_NO_SURFACE
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                eglContext = EGL14.EGL_NO_CONTEXT
            }
            EGL14.eglTerminate(eglDisplay)
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }

        eglConfig = null
    }

    private fun rebuildMapperOnRenderThread() {
        mapper = CoordinateMapper(
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            surfaceWidth = surfaceWidth,
            surfaceHeight = surfaceHeight
        )
        solver.setAspectRatio(mapper.viewport.aspectRatio)
    }

    private fun recyclePendingBitmap() {
        val pending = pendingBitmap.getAndSet(null)
        if (pending != null && !pending.isRecycled) {
            pending.recycle()
        }
    }

    private fun notifyContextRecreated() {
        val callback = onContextRecreated ?: return
        mainHandler.post {
            if (!destroyed.get()) {
                callback()
            }
        }
    }

    companion object {
        private const val LATCH_TIMEOUT_MS: Long = 500L
    }
}
