package com.mochiwall.app.data

import android.app.WallpaperColors
import com.mochiwall.app.core.model.PhysicsConfig
import com.mochiwall.app.core.model.ProjectContent
import com.mochiwall.app.core.model.ProjectState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Application-wide singleton repository coordinating MochiWall's reactive [ProjectState],
 * O(1) volatile [WallpaperColors] cache for `MochiWallpaperService.Engine.onComputeColors()`,
 * 300 ms debounced physics config persistence, and atomic content commits.
 */
class ProjectRepository(
    private val storage: ProjectStorage
) {

    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initialized = AtomicBoolean(false)

    private val publishLock = Any()
    private val debounceLock = Any()
    private var debounceJob: Job? = null

    private val _state = MutableStateFlow(ProjectState())
    val state: StateFlow<ProjectState> = _state.asStateFlow()

    private val _wallpaperColors = MutableStateFlow<WallpaperColors?>(null)
    val wallpaperColors: StateFlow<WallpaperColors?> = _wallpaperColors.asStateFlow()

    /**
     * Non-blocking O(1) cache read by `MochiWallpaperService.Engine.onComputeColors()`.
     * Always updated strictly before [_state] under [publishLock].
     */
    @Volatile
    var cachedWallpaperColors: WallpaperColors? = null
        private set

    /**
     * Idempotently triggers cold-boot loading from [ProjectStorage] on [Dispatchers.IO],
     * populates [cachedWallpaperColors] and [wallpaperColors] strictly before flipping
     * [ProjectState.isLoaded].
     */
    fun initialize() {
        if (!initialized.compareAndSet(false, true)) return

        repoScope.launch {
            val loaded = storage.coldBootLoad()
            if (loaded != null) {
                val wallpaperFile = storage.resolveWallpaperFile(loaded.content)
                val colors = storage.computeWallpaperColors(wallpaperFile)

                synchronized(publishLock) {
                    if (_state.value.content == null) {
                        cachedWallpaperColors = colors
                        _wallpaperColors.value = colors
                        _state.update { current ->
                            current.copy(
                                isLoaded = true,
                                content = loaded.content,
                                config = loaded.metadata.config
                            )
                        }
                    } else {
                        _state.update { current -> current.copy(isLoaded = true) }
                    }
                }
            } else {
                synchronized(publishLock) {
                    if (_state.value.content == null) {
                        cachedWallpaperColors = null
                        _wallpaperColors.value = null
                    }
                    _state.update { current -> current.copy(isLoaded = true) }
                }
            }
        }
    }

    /**
     * Immediately updates [state] with 0 ms UI lag and schedules a 300 ms debounced
     * `project.json` config-only write.
     */
    fun updateConfig(transform: (PhysicsConfig) -> PhysicsConfig) {
        _state.update { current ->
            current.copy(config = transform(current.config).sanitized())
        }
        scheduleDebouncedConfigSave()
    }

    /**
     * Replaces the active [PhysicsConfig] immediately in [state] and schedules a 300 ms
     * debounced `project.json` config-only write.
     */
    fun setConfig(config: PhysicsConfig) {
        updateConfig { config }
    }

    /**
     * Cancels any pending 300 ms debounce timer and immediately persists the latest
     * [ProjectState.config] on [Dispatchers.IO]. Intended for `MainActivity.onStop()` and `Apply`.
     */
    fun flushConfigSave() {
        cancelPendingDebounce()
        repoScope.launch {
            storage.saveConfigOnly { _state.value.config }
        }
    }

    /**
     * Atomically commits a new framed photo + mask, recomputes [WallpaperColors], and publishes
     * colors strictly before the new [ProjectContent] in [state].
     */
    suspend fun commitNewPhoto(
        stagingFile: File,
        maskBytes: ByteArray,
        width: Int,
        height: Int
    ): ProjectContent {
        cancelPendingDebounce()
        val configAtCommit = _state.value.config
        val metadata = storage.commitNewPhoto(
            stagingWallpaper = stagingFile,
            maskBytes = maskBytes,
            imageWidth = width,
            imageHeight = height,
            currentConfig = configAtCommit
        )
        val content = ProjectContent.fromMetadata(metadata)
        val colors = storage.computeWallpaperColors(storage.resolveWallpaperFile(content))

        var configChangedDuringCommit = false
        synchronized(publishLock) {
            cachedWallpaperColors = colors
            _wallpaperColors.value = colors
            _state.update { current ->
                configChangedDuringCommit = current.config != metadata.config
                current.copy(
                    isLoaded = true,
                    content = content
                )
            }
        }
        if (configChangedDuringCommit) {
            scheduleDebouncedConfigSave()
        }
        return content
    }

    /**
     * Atomically commits an edited mask against the active wallpaper, recomputes
     * [WallpaperColors], and publishes colors strictly before the new [ProjectContent] in [state].
     */
    suspend fun commitEditedMask(maskBytes: ByteArray): ProjectContent {
        cancelPendingDebounce()
        val configAtCommit = _state.value.config
        val metadata = storage.commitEditedMask(
            maskBytes = maskBytes,
            currentConfig = configAtCommit
        )
        val content = ProjectContent.fromMetadata(metadata)
        val colors = storage.computeWallpaperColors(storage.resolveWallpaperFile(content))

        var configChangedDuringCommit = false
        synchronized(publishLock) {
            cachedWallpaperColors = colors
            _wallpaperColors.value = colors
            _state.update { current ->
                configChangedDuringCommit = current.config != metadata.config
                current.copy(
                    isLoaded = true,
                    content = content
                )
            }
        }
        if (configChangedDuringCommit) {
            scheduleDebouncedConfigSave()
        }
        return content
    }

    /**
     * Loads the committed 144x320 mask bytes for the active project, or `null` if none exists.
     */
    suspend fun loadCurrentMaskBytes(): ByteArray? {
        val currentContent = _state.value.content ?: return null
        return storage.readMaskBytes(currentContent)
    }

    /**
     * Resolves the on-disk wallpaper file for [content].
     */
    fun resolveWallpaperFile(content: ProjectContent): File =
        storage.resolveWallpaperFile(content)

    /**
     * Returns the path for Stage 1's `staging_wallpaper.webp`.
     */
    fun stagingWallpaperFile(): File = storage.stagingWallpaperFile()

    private fun scheduleDebouncedConfigSave() {
        synchronized(debounceLock) {
            debounceJob?.cancel()
            debounceJob = repoScope.launch {
                delay(CONFIG_SAVE_DEBOUNCE_MS)
                storage.saveConfigOnly { _state.value.config }
            }
        }
    }

    private fun cancelPendingDebounce() {
        synchronized(debounceLock) {
            debounceJob?.cancel()
            debounceJob = null
        }
    }

    companion object {
        const val CONFIG_SAVE_DEBOUNCE_MS: Long = 300L
    }
}
