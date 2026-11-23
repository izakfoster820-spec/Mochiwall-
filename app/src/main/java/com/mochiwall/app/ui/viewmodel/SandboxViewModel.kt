package com.mochiwall.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.mochiwall.app.core.model.MochiPreset
import com.mochiwall.app.core.model.PhysicsStyle
import com.mochiwall.app.core.model.ProjectState
import com.mochiwall.app.data.ProjectRepository
import kotlinx.coroutines.flow.StateFlow

/**
 * ViewModel for Stage 3 (Sandbox), routing live physics style, preset, slider, and toggle changes
 * directly to [ProjectRepository] with 0 ms UI latency and 300 ms debounced disk persistence.
 */
class SandboxViewModel(
    private val repository: ProjectRepository
) : ViewModel() {

    val projectState: StateFlow<ProjectState> = repository.state

    fun setStyle(style: PhysicsStyle) {
        repository.updateConfig { it.copy(style = style) }
    }

    /**
     * Applies [preset]'s exact slider values while preserving the user's current [PhysicsStyle]
     * and 4 toggle states.
     */
    fun selectPreset(preset: MochiPreset) {
        repository.updateConfig { current ->
            current.copy(
                preset = preset,
                softness = preset.softness,
                wobble = preset.wobble,
                pullRadius = preset.pullRadius,
                shakeForce = preset.shakeForce
            )
        }
    }

    fun setSoftness(value: Float) {
        if (!value.isFinite()) return
        val clamped = value.coerceIn(0f, 1f)
        repository.updateConfig { it.copy(softness = clamped) }
    }

    fun setWobble(value: Float) {
        if (!value.isFinite()) return
        val clamped = value.coerceIn(0f, 1f)
        repository.updateConfig { it.copy(wobble = clamped) }
    }

    fun setPullRadius(value: Float) {
        if (!value.isFinite()) return
        val clamped = value.coerceIn(0f, 1f)
        repository.updateConfig { it.copy(pullRadius = clamped) }
    }

    fun setShakeForce(value: Float) {
        if (!value.isFinite()) return
        val clamped = value.coerceIn(0f, 1f)
        repository.updateConfig { it.copy(shakeForce = clamped) }
    }

    fun setTouchEnabled(enabled: Boolean) {
        repository.updateConfig { it.copy(touchEnabled = enabled) }
    }

    fun setMotionEnabled(enabled: Boolean) {
        repository.updateConfig { it.copy(motionEnabled = enabled) }
    }

    fun setWakeWobbleEnabled(enabled: Boolean) {
        repository.updateConfig { it.copy(wakeWobbleEnabled = enabled) }
    }

    fun setSheenEnabled(enabled: Boolean) {
        repository.updateConfig { it.copy(sheenEnabled = enabled) }
    }

    /**
     * Restores the default Mochi preset (`style = PhysicsStyle.MOCHI`, calibrated sliders,
     * and all 4 toggles back ON).
     */
    fun resetPhysics() {
        repository.setConfig(MochiPreset.MOCHI.toDefaultConfig())
    }

    /**
     * Immediately flushes any pending debounced config write before opening the system
     * Live Wallpaper picker.
     */
    fun flushConfigBeforeApply() {
        repository.flushConfigSave()
    }

    companion object {
        fun provideFactory(
            repository: ProjectRepository
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(SandboxViewModel::class.java)) {
                    return SandboxViewModel(repository) as T
                }
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }
        }
    }
}
