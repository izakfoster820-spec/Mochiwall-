package com.mochiwall.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mochiwall.app.core.geometry.CoordinateMapper
import com.mochiwall.app.core.mask.DownsampledMask
import com.mochiwall.app.core.mask.MaskBrushRasterizer
import com.mochiwall.app.core.mask.MaskDownsampler
import com.mochiwall.app.core.model.PhysicsConfig
import com.mochiwall.app.core.model.ProjectContent
import com.mochiwall.app.core.physics.DisplayRotation
import com.mochiwall.app.core.physics.MochiPhysicsSolver
import com.mochiwall.app.core.physics.SensorFilterPipeline
import com.mochiwall.app.core.physics.TouchAction
import com.mochiwall.app.core.physics.TouchStateMachine
import com.mochiwall.app.data.ImageFrameProcessor
import com.mochiwall.app.data.MochiSensorController
import com.mochiwall.app.data.ProjectRepository
import com.mochiwall.app.gl.MochiGLRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Imperative controller for Stage 3's [MochiPreviewView] allowing `SandboxScreen` to trigger
 * `Reset Pose` and `Wake Wobble` without hoisting GL thread state into Compose.
 */
class MochiPreviewController {

    @Volatile
    private var attachedView: MochiSandboxGLView? = null

    internal fun attach(view: MochiSandboxGLView) {
        attachedView = view
    }

    internal fun detach(view: MochiSandboxGLView) {
        if (attachedView === view) {
            attachedView = null
        }
    }

    fun resetPose() {
        attachedView?.resetPose()
    }

    fun triggerWakeWobble() {
        attachedView?.triggerWakeWobble()
    }
}

/**
 * Stage 3 Sandbox live interactive OpenGL ES 3.0 preview backed by Android's [GLSurfaceView].
 *
 * Shares the exact same [MochiGLRenderer], [MochiPhysicsSolver], [TouchStateMachine], and
 * [SensorFilterPipeline] as `MochiWallpaperService`, with strict GLThread ownership, race-free
 * IDLE sleep/wake transitions, and leak-free bitmap handoff.
 */
@Composable
fun MochiPreviewView(
    content: ProjectContent?,
    config: PhysicsConfig,
    repository: ProjectRepository,
    imageFrameProcessor: ImageFrameProcessor,
    modifier: Modifier = Modifier,
    controller: MochiPreviewController? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val glView = remember { MochiSandboxGLView(context) }
    val ioScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    val activeLoadJob = remember { AtomicReference<Job?>(null) }
    val isResumed = remember { AtomicBoolean(false) }

    val latestContent by rememberUpdatedState(content)
    val latestConfig by rememberUpdatedState(config)
    val latestRepository by rememberUpdatedState(repository)
    val latestProcessor by rememberUpdatedState(imageFrameProcessor)

    val sensorController = remember(glView) {
        MochiSensorController(
            context = context,
            onLinearAcceleration = { sx, sy, timestampNs, rotation ->
                glView.dispatchLinearAcceleration(sx, sy, timestampNs, rotation)
            },
            onGyroscope = { wx, wy, timestampNs, rotation ->
                glView.dispatchGyroscope(wx, wy, timestampNs, rotation)
            },
            sensorHandler = null
        )
    }

    val launchContentLoad = remember(glView, ioScope) {
        { target: ProjectContent?, textureOnly: Boolean ->
            activeLoadJob.getAndSet(null)?.cancel()

            if (target == null) {
                glView.clearContent()
            } else {
                val repo = latestRepository
                val processor = latestProcessor
                val job = ioScope.launch {
                    var decodedBitmap: Bitmap? = null
                    try {
                        val wallpaperFile = repo.resolveWallpaperFile(target)
                        decodedBitmap = processor.decodeWallpaperBitmap(
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
                            glView.submitTextureReload(handedOff)
                        } else {
                            val maskBytes = repo.loadCurrentMaskBytes()
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
                            glView.submitContentUpdate(
                                bitmap = handedOff,
                                mask = downsampledMask,
                                imageWidth = target.imageWidth,
                                imageHeight = target.imageHeight
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
    }

    DisposableEffect(controller, glView) {
        controller?.attach(glView)
        onDispose {
            controller?.detach(glView)
        }
    }

    DisposableEffect(glView, launchContentLoad) {
        glView.setOnGlContextRecreatedListener {
            latestContent?.let { activeContent ->
                launchContentLoad(activeContent, true)
            }
        }
        onDispose {
            glView.setOnGlContextRecreatedListener(null)
        }
    }

    LaunchedEffect(content?.contentVersion) {
        launchContentLoad(content, false)
    }

    LaunchedEffect(config) {
        glView.updateConfig(config)
        if (isResumed.get()) {
            if (config.motionEnabled) {
                sensorController.start()
            } else {
                sensorController.stop()
                glView.resetSensors()
            }
        }
    }

    DisposableEffect(lifecycleOwner, glView, sensorController) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    isResumed.set(true)
                    glView.onResume()
                    if (latestConfig.motionEnabled) {
                        sensorController.start()
                    }
                }

                Lifecycle.Event.ON_PAUSE -> {
                    isResumed.set(false)
                    sensorController.stop()
                    glView.onHostPause()
                }

                else -> Unit
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            isResumed.set(false)
            sensorController.stop()
            activeLoadJob.getAndSet(null)?.cancel()
            ioScope.cancel()
            glView.releaseResources()
        }
    }

    AndroidView(
        factory = { glView },
        modifier = modifier
    )
}

internal class MochiSandboxGLView(context: Context) : GLSurfaceView(context) {

    // GLThread-exclusively owned rendering & simulation state
    private val glRenderer = MochiGLRenderer()
    private val solver = MochiPhysicsSolver()
    private val touchStateMachine = TouchStateMachine()
    private val sensorPipeline = SensorFilterPipeline()

    private var glConfig: PhysicsConfig = PhysicsConfig()
    private var mapper: CoordinateMapper = CoordinateMapper(1, 1, 1, 1)
    private var glImageWidth: Int = 1
    private var glImageHeight: Int = 1
    private var glSurfaceWidth: Int = 1
    private var glSurfaceHeight: Int = 1
    private var hasCreatedSurfaceOnce: Boolean = false

    // Thread-safe cross-thread handoff state
    private val wakeRequested = AtomicBoolean(false)
    private val pendingBitmap = AtomicReference<Bitmap?>(null)

    @Volatile
    private var touchEnabledFlag: Boolean = true

    @Volatile
    private var onGlContextRecreated: (() -> Unit)? = null

    // Main-thread-only active touch tracking
    private var mainActivePointerId: Int = MotionEvent.INVALID_POINTER_ID
    private val touchSlopPx: Float = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private val internalRenderer = object : Renderer {
        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            val isRecreation = hasCreatedSurfaceOnce
            hasCreatedSurfaceOnce = true

            glRenderer.onGlContextCreated()

            if (isRecreation && pendingBitmap.get() == null) {
                post { onGlContextRecreated?.invoke() }
            }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            if (width <= 0 || height <= 0) return
            glSurfaceWidth = width
            glSurfaceHeight = height
            glRenderer.onViewportChanged(width, height)
            rebuildMapperOnGlThread()
            wakeRenderer()
        }

        override fun onDrawFrame(gl: GL10?) {
            wakeRequested.set(false)

            pendingBitmap.getAndSet(null)?.let { bitmap ->
                glRenderer.uploadTexture(bitmap)
            }

            val sensorFx = sensorPipeline.computeForceX(glConfig)
            val sensorFy = sensorPipeline.computeForceY(glConfig)

            val stillActive = solver.stepFrame(
                frameTimeNs = System.nanoTime(),
                config = glConfig,
                touch = touchStateMachine,
                sensorFx = sensorFx,
                sensorFy = sensorFy
            )

            glRenderer.renderFrame(solver, mapper, glConfig)

            // Race-free IDLE sleep transition
            if (!stillActive && !wakeRequested.get()) {
                renderMode = RENDERMODE_WHEN_DIRTY
                if (wakeRequested.get()) {
                    renderMode = RENDERMODE_CONTINUOUSLY
                    requestRender()
                }
            }
        }
    }

    init {
        setEGLContextClientVersion(3)
        preserveEGLContextOnPause = true
        setRenderer(internalRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun setOnGlContextRecreatedListener(listener: (() -> Unit)?) {
        onGlContextRecreated = listener
    }

    /**
     * Race-free wake from IDLE sleep. Safe to invoke from any thread.
     */
    fun wakeRenderer() {
        wakeRequested.set(true)
        renderMode = RENDERMODE_CONTINUOUSLY
        requestRender()
    }

    fun updateConfig(config: PhysicsConfig) {
        val clean = config.sanitized()
        touchEnabledFlag = clean.touchEnabled
        queueEvent {
            val previous = glConfig
            glConfig = clean
            if (!clean.motionEnabled && previous.motionEnabled) {
                sensorPipeline.reset()
            }
            if (!clean.touchEnabled && previous.touchEnabled) {
                touchStateMachine.cancel(SystemClock.uptimeMillis())
            }
            wakeRenderer()
        }
    }

    fun dispatchLinearAcceleration(
        sx: Float,
        sy: Float,
        timestampNs: Long,
        rotation: DisplayRotation
    ) {
        queueEvent {
            if (!glConfig.motionEnabled) return@queueEvent
            sensorPipeline.onLinearAcceleration(sx, sy, timestampNs, rotation)
            if (sensorPipeline.hasActiveForce(glConfig)) {
                wakeRenderer()
            }
        }
    }

    fun dispatchGyroscope(
        wx: Float,
        wy: Float,
        timestampNs: Long,
        rotation: DisplayRotation
    ) {
        queueEvent {
            if (!glConfig.motionEnabled) return@queueEvent
            sensorPipeline.onGyroscope(wx, wy, timestampNs, rotation)
            if (sensorPipeline.hasActiveForce(glConfig)) {
                wakeRenderer()
            }
        }
    }

    fun resetSensors() {
        queueEvent {
            sensorPipeline.reset()
        }
    }

    fun submitContentUpdate(
        bitmap: Bitmap,
        mask: DownsampledMask,
        imageWidth: Int,
        imageHeight: Int
    ) {
        val displaced = pendingBitmap.getAndSet(bitmap)
        if (displaced != null && displaced !== bitmap && !displaced.isRecycled) {
            displaced.recycle()
        }

        queueEvent {
            glImageWidth = if (imageWidth > 0) imageWidth else 1
            glImageHeight = if (imageHeight > 0) imageHeight else 1
            rebuildMapperOnGlThread()
            solver.setMask(mask.weights, mask.hasActiveVertices)
            wakeRenderer()
        }
    }

    fun submitTextureReload(bitmap: Bitmap) {
        val displaced = pendingBitmap.getAndSet(bitmap)
        if (displaced != null && displaced !== bitmap && !displaced.isRecycled) {
            displaced.recycle()
        }
        wakeRenderer()
    }

    fun clearContent() {
        recyclePendingBitmap()
        queueEvent {
            glRenderer.clearTexture()
            touchStateMachine.reset()
            solver.resetPose()
            requestRender()
        }
    }

    fun resetPose() {
        mainActivePointerId = MotionEvent.INVALID_POINTER_ID
        queueEvent {
            touchStateMachine.reset()
            solver.resetPose()
            wakeRenderer()
        }
    }

    fun triggerWakeWobble() {
        queueEvent {
            solver.applyWakeWobble(glConfig)
            wakeRenderer()
        }
    }

    fun onHostPause() {
        mainActivePointerId = MotionEvent.INVALID_POINTER_ID
        queueEvent {
            sensorPipeline.reset()
            touchStateMachine.cancel(SystemClock.uptimeMillis())
        }
        onPause()
    }

    fun releaseResources() {
        onGlContextRecreated = null
        mainActivePointerId = MotionEvent.INVALID_POINTER_ID
        renderMode = RENDERMODE_WHEN_DIRTY
        recyclePendingBitmap()
        queueEvent {
            sensorPipeline.reset()
            touchStateMachine.reset()
            glRenderer.release()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val actionMasked = event.actionMasked
        val eventTimeMs = event.eventTime

        return when (actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!touchEnabledFlag) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                val pointerId = event.getPointerId(0)
                mainActivePointerId = pointerId
                queueTouchEvent(
                    action = TouchAction.DOWN,
                    pointerId = pointerId,
                    screenX = event.getX(0),
                    screenY = event.getY(0),
                    eventTimeMs = eventTimeMs
                )
                true
            }

            MotionEvent.ACTION_MOVE -> {
                val activeId = mainActivePointerId
                if (activeId == MotionEvent.INVALID_POINTER_ID || !touchEnabledFlag) {
                    return false
                }
                val pointerIndex = event.findPointerIndex(activeId)
                if (pointerIndex < 0) return false
                queueTouchEvent(
                    action = TouchAction.MOVE,
                    pointerId = activeId,
                    screenX = event.getX(pointerIndex),
                    screenY = event.getY(pointerIndex),
                    eventTimeMs = eventTimeMs
                )
                true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val pointerIndex = event.actionIndex
                val pointerId = event.getPointerId(pointerIndex)
                if (pointerId == mainActivePointerId) {
                    val x = event.getX(pointerIndex)
                    val y = event.getY(pointerIndex)
                    mainActivePointerId = MotionEvent.INVALID_POINTER_ID
                    queueTouchEvent(
                        action = TouchAction.UP,
                        pointerId = pointerId,
                        screenX = x,
                        screenY = y,
                        eventTimeMs = eventTimeMs
                    )
                    true
                } else {
                    mainActivePointerId != MotionEvent.INVALID_POINTER_ID
                }
            }

            MotionEvent.ACTION_UP -> {
                val activeId = mainActivePointerId
                if (activeId == MotionEvent.INVALID_POINTER_ID) return false
                val pointerIndex = event.findPointerIndex(activeId).let { idx ->
                    if (idx >= 0) idx else event.actionIndex
                }
                val x = event.getX(pointerIndex)
                val y = event.getY(pointerIndex)
                mainActivePointerId = MotionEvent.INVALID_POINTER_ID
                queueTouchEvent(
                    action = TouchAction.UP,
                    pointerId = activeId,
                    screenX = x,
                    screenY = y,
                    eventTimeMs = eventTimeMs
                )
                true
            }

            MotionEvent.ACTION_CANCEL -> {
                mainActivePointerId = MotionEvent.INVALID_POINTER_ID
                queueTouchEvent(
                    action = TouchAction.CANCEL,
                    pointerId = -1,
                    screenX = 0f,
                    screenY = 0f,
                    eventTimeMs = eventTimeMs
                )
                true
            }

            else -> super.onTouchEvent(event)
        }
    }

    private fun queueTouchEvent(
        action: TouchAction,
        pointerId: Int,
        screenX: Float,
        screenY: Float,
        eventTimeMs: Long
    ) {
        queueEvent {
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
            wakeRenderer()
        }
    }

    private fun rebuildMapperOnGlThread() {
        mapper = CoordinateMapper(
            imageWidth = glImageWidth,
            imageHeight = glImageHeight,
            surfaceWidth = glSurfaceWidth,
            surfaceHeight = glSurfaceHeight
        )
        solver.setAspectRatio(mapper.viewport.aspectRatio)
    }

    private fun recyclePendingBitmap() {
        val pending = pendingBitmap.getAndSet(null)
        if (pending != null && !pending.isRecycled) {
            pending.recycle()
        }
    }
}
