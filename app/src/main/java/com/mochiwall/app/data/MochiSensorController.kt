package com.mochiwall.app.data

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Surface
import com.mochiwall.app.core.physics.DisplayRotation

/**
 * Hardware sensor and display-rotation controller wiring Android's `TYPE_LINEAR_ACCELERATION`
 * and optional `TYPE_GYROSCOPE` sensors into [com.mochiwall.app.core.physics.SensorFilterPipeline].
 *
 * Caches the default display's [DisplayRotation] in a `@Volatile` field updated via
 * [DisplayManager.DisplayListener] so [SensorEventListener.onSensorChanged] never queries
 * `Display.rotation` per event. Passing an optional [sensorHandler] routes sensor callbacks
 * directly to a dedicated render thread (e.g. `SimpleEglRenderThread`) without bouncing through
 * the Main Thread.
 */
class MochiSensorController(
    context: Context,
    private val onLinearAcceleration: (
        sx: Float,
        sy: Float,
        timestampNs: Long,
        rotation: DisplayRotation
    ) -> Unit,
    private val onGyroscope: (
        wx: Float,
        wy: Float,
        timestampNs: Long,
        rotation: DisplayRotation
    ) -> Unit,
    private val sensorHandler: Handler? = null
) {

    private val appContext: Context = context.applicationContext
    private val sensorManager: SensorManager? =
        appContext.getSystemService(SensorManager::class.java)
    private val displayManager: DisplayManager? =
        appContext.getSystemService(DisplayManager::class.java)

    private val linearAccelSensor: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val gyroSensor: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val stateLock = Any()
    private var isStarted: Boolean = false

    @Volatile
    private var currentRotation: DisplayRotation = DisplayRotation.ROTATION_0

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                refreshDisplayRotation()
            }
        }

        override fun onDisplayRemoved(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                currentRotation = DisplayRotation.ROTATION_0
            }
        }

        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                refreshDisplayRotation()
            }
        }
    }

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val values = event.values
            if (values.size < 2) return
            val rotation = currentRotation
            when (event.sensor.type) {
                Sensor.TYPE_LINEAR_ACCELERATION -> {
                    onLinearAcceleration(values[0], values[1], event.timestamp, rotation)
                }
                Sensor.TYPE_GYROSCOPE -> {
                    onGyroscope(values[0], values[1], event.timestamp, rotation)
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            // No-op
        }
    }

    /**
     * Idempotently starts listening to display rotation changes and motion sensors at
     * [SensorManager.SENSOR_DELAY_GAME]. Missing hardware sensors are safely skipped.
     */
    fun start() {
        synchronized(stateLock) {
            if (isStarted) return
            isStarted = true

            refreshDisplayRotation()
            displayManager?.registerDisplayListener(displayListener, mainHandler)

            linearAccelSensor?.let { accel ->
                registerSensorLocked(accel)
            }
            gyroSensor?.let { gyro ->
                registerSensorLocked(gyro)
            }
        }
    }

    /**
     * Idempotently unregisters all sensor and display listeners.
     */
    fun stop() {
        synchronized(stateLock) {
            if (!isStarted) return
            isStarted = false

            runCatching { sensorManager?.unregisterListener(sensorListener) }
            runCatching { displayManager?.unregisterDisplayListener(displayListener) }
        }
    }

    private fun registerSensorLocked(sensor: Sensor) {
        val manager = sensorManager ?: return
        if (sensorHandler != null) {
            manager.registerListener(
                sensorListener,
                sensor,
                SensorManager.SENSOR_DELAY_GAME,
                sensorHandler
            )
        } else {
            manager.registerListener(
                sensorListener,
                sensor,
                SensorManager.SENSOR_DELAY_GAME
            )
        }
    }

    private fun refreshDisplayRotation() {
        val rawRotation = runCatching {
            displayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation
        }.getOrNull() ?: Surface.ROTATION_0

        currentRotation = when (rawRotation) {
            Surface.ROTATION_90 -> DisplayRotation.ROTATION_90
            Surface.ROTATION_180 -> DisplayRotation.ROTATION_180
            Surface.ROTATION_270 -> DisplayRotation.ROTATION_270
            else -> DisplayRotation.ROTATION_0
        }
    }
}
