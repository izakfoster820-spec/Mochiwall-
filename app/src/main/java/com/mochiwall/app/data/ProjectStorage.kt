package com.mochiwall.app.data

import android.app.WallpaperColors
import android.content.Context
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import com.mochiwall.app.core.mask.MaskBrushRasterizer
import com.mochiwall.app.core.model.PhysicsConfig
import com.mochiwall.app.core.model.ProjectContent
import com.mochiwall.app.core.model.ProjectJsonCodec
import com.mochiwall.app.core.model.ProjectMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Verified project snapshot returned by [ProjectStorage.coldBootLoad].
 */
data class LoadedProject(
    val metadata: ProjectMetadata,
    val content: ProjectContent
)

/**
 * Versioned, mutex-serialized atomic storage for MochiWall's single active project.
 *
 * Disk layout under `context.filesDir/mochiwall/`:
 * - `staging_wallpaper.webp`
 * - `wallpaper_v{N}.webp`
 * - `mask_v{N}.bin` (exact 46,080 bytes)
 * - `project.json`
 *
 * All disk reads and writes execute on [Dispatchers.IO] behind a single [Mutex], and content
 * commits defer GC to `v < nextVersion - 1` so any in-flight Live Wallpaper reader referencing
 * `nextVersion - 1` completes safely without `FileNotFoundException`.
 */
class ProjectStorage(context: Context) {

    private val storageDir: File = File(context.applicationContext.filesDir, STORAGE_DIR_NAME)
    private val storageMutex = Mutex()

    /**
     * Loads and validates the active committed project on cold boot, cleaning up stale temp,
     * staging, and non-active versioned files. Returns `null` if no valid project exists.
     */
    suspend fun coldBootLoad(): LoadedProject? = withContext(Dispatchers.IO) {
        storageMutex.withLock {
            ensureStorageDir()
            val metadata = readMetadataLocked()
            if (metadata == null) {
                cleanupStaleTempAndStagingLocked()
                return@withLock null
            }

            val content = ProjectContent.fromMetadata(metadata)
            val wallpaperFile = File(storageDir, content.wallpaperFileName)
            val maskFile = File(storageDir, content.maskFileName)

            val wallpaperValid = wallpaperFile.isFile && wallpaperFile.length() > 0L
            val maskValid = maskFile.isFile &&
                maskFile.length() == MaskBrushRasterizer.MASK_BYTE_COUNT.toLong()

            if (!wallpaperValid || !maskValid) {
                cleanupStaleTempAndStagingLocked()
                return@withLock null
            }

            cleanupOnColdBootLocked(activeVersion = metadata.contentVersion)
            LoadedProject(metadata = metadata, content = content)
        }
    }

    /**
     * Atomically commits a new framed wallpaper and its 144x320 mask under `nextVersion`,
     * updates `project.json`, deletes the staging file, and garbage-collects only versions
     * strictly older than `nextVersion - 1`.
     */
    suspend fun commitNewPhoto(
        stagingWallpaper: File,
        maskBytes: ByteArray,
        imageWidth: Int,
        imageHeight: Int,
        currentConfig: PhysicsConfig
    ): ProjectMetadata {
        require(maskBytes.size == MaskBrushRasterizer.MASK_BYTE_COUNT) {
            "Mask must be exactly ${MaskBrushRasterizer.MASK_BYTE_COUNT} bytes (got ${maskBytes.size})."
        }
        require(imageWidth > 0 && imageHeight > 0) {
            "Image dimensions must be positive (got ${imageWidth}x${imageHeight})."
        }

        return withContext(Dispatchers.IO) {
            storageMutex.withLock {
                ensureStorageDir()
                if (!stagingWallpaper.isFile || stagingWallpaper.length() <= 0L) {
                    throw IOException("Staging wallpaper file is missing or empty: ${stagingWallpaper.name}")
                }

                val currentVersion = readMetadataLocked()?.contentVersion ?: 0
                val nextVersion = currentVersion + 1

                val targetWallpaper = File(storageDir, ProjectContent.wallpaperFileNameFor(nextVersion))
                val targetMask = File(storageDir, ProjectContent.maskFileNameFor(nextVersion))

                copyFileAtomicallyLocked(stagingWallpaper, targetWallpaper)
                writeBytesAtomicallyLocked(targetMask, maskBytes)

                val nextMetadata = ProjectMetadata(
                    contentVersion = nextVersion,
                    imageWidth = imageWidth,
                    imageHeight = imageHeight,
                    config = currentConfig.sanitized()
                )
                writeMetadataAtomicallyLocked(nextMetadata)

                if (stagingWallpaper.exists()) {
                    runCatching { stagingWallpaper.delete() }
                }

                cleanupDeferredVersionsLocked(minRetainedVersion = nextVersion - 1)
                nextMetadata
            }
        }
    }

    /**
     * Atomically commits an edited mask by cloning the current active wallpaper into a new
     * self-contained `nextVersion`, writing the new mask, updating `project.json`, and
     * garbage-collecting only versions strictly older than `nextVersion - 1`.
     */
    suspend fun commitEditedMask(
        maskBytes: ByteArray,
        currentConfig: PhysicsConfig
    ): ProjectMetadata {
        require(maskBytes.size == MaskBrushRasterizer.MASK_BYTE_COUNT) {
            "Mask must be exactly ${MaskBrushRasterizer.MASK_BYTE_COUNT} bytes (got ${maskBytes.size})."
        }

        return withContext(Dispatchers.IO) {
            storageMutex.withLock {
                ensureStorageDir()
                val current = readMetadataLocked()
                    ?: throw IllegalStateException("Cannot commit edited mask: no committed project exists.")

                val currentWallpaper = File(
                    storageDir,
                    ProjectContent.wallpaperFileNameFor(current.contentVersion)
                )
                if (!currentWallpaper.isFile || currentWallpaper.length() <= 0L) {
                    throw IllegalStateException(
                        "Cannot commit edited mask: active wallpaper ${currentWallpaper.name} is missing."
                    )
                }

                val nextVersion = current.contentVersion + 1
                val targetWallpaper = File(storageDir, ProjectContent.wallpaperFileNameFor(nextVersion))
                val targetMask = File(storageDir, ProjectContent.maskFileNameFor(nextVersion))

                copyFileAtomicallyLocked(currentWallpaper, targetWallpaper)
                writeBytesAtomicallyLocked(targetMask, maskBytes)

                val nextMetadata = ProjectMetadata(
                    contentVersion = nextVersion,
                    imageWidth = current.imageWidth,
                    imageHeight = current.imageHeight,
                    config = currentConfig.sanitized()
                )
                writeMetadataAtomicallyLocked(nextMetadata)

                cleanupDeferredVersionsLocked(minRetainedVersion = nextVersion - 1)
                nextMetadata
            }
        }
    }

    /**
     * Persists only the physics config supplied by [configProvider] without bumping
     * `contentVersion`.
     *
     * CRITICAL: Always reads the latest committed `project.json` from disk AND invokes
     * `configProvider().sanitized()` inside [storageMutex] at the exact moment of writing,
     * eliminating both stale `contentVersion` overwrites and out-of-order debounce vs. flush
     * config races. Safely no-ops if no committed project exists yet.
     */
    suspend fun saveConfigOnly(configProvider: () -> PhysicsConfig) {
        withContext(Dispatchers.IO) {
            storageMutex.withLock {
                ensureStorageDir()
                val latestCommitted = readMetadataLocked() ?: return@withLock
                val latestConfig = configProvider().sanitized()
                val updated = latestCommitted.copy(config = latestConfig)
                writeMetadataAtomicallyLocked(updated)
            }
        }
    }

    /**
     * Convenience overload persisting a fixed [config] snapshot via [saveConfigOnly].
     */
    suspend fun saveConfigOnly(config: PhysicsConfig) = saveConfigOnly { config }

    /**
     * Reads the committed 144x320 mask bytes for [content], returning `null` if missing or invalid.
     */
    suspend fun readMaskBytes(content: ProjectContent): ByteArray? = withContext(Dispatchers.IO) {
        storageMutex.withLock {
            val maskFile = File(storageDir, content.maskFileName)
            if (!maskFile.isFile || maskFile.length() != MaskBrushRasterizer.MASK_BYTE_COUNT.toLong()) {
                return@withLock null
            }
            val bytes = runCatching { maskFile.readBytes() }.getOrNull() ?: return@withLock null
            if (bytes.size == MaskBrushRasterizer.MASK_BYTE_COUNT) bytes else null
        }
    }

    /**
     * Resolves the on-disk wallpaper file for [content].
     */
    fun resolveWallpaperFile(content: ProjectContent): File =
        File(storageDir, content.wallpaperFileName)

    /**
     * Returns the path for Stage 1's temporary `staging_wallpaper.webp` file.
     */
    fun stagingWallpaperFile(): File = File(storageDir, STAGING_WALLPAPER_NAME)

    /**
     * Computes [WallpaperColors] from a subsampled `~128 x 286` software sRGB thumbnail on
     * [Dispatchers.IO] and immediately recycles the thumbnail bitmap in `finally`.
     */
    suspend fun computeWallpaperColors(wallpaperFile: File): WallpaperColors? =
        withContext(Dispatchers.IO) {
            if (!wallpaperFile.isFile || wallpaperFile.length() <= 0L) {
                return@withContext null
            }
            runCatching {
                val source = ImageDecoder.createSource(wallpaperFile)
                val thumb = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))

                    val srcW = info.size.width
                    val srcH = info.size.height
                    if (srcW > 0 && srcH > 0) {
                        val scale = min(
                            COLOR_THUMB_WIDTH.toDouble() / srcW.toDouble(),
                            COLOR_THUMB_HEIGHT.toDouble() / srcH.toDouble()
                        ).coerceAtMost(1.0)
                        val targetW = (srcW * scale).roundToInt().coerceAtLeast(1)
                        val targetH = (srcH * scale).roundToInt().coerceAtLeast(1)
                        decoder.setTargetSize(targetW, targetH)
                    }
                }
                try {
                    WallpaperColors.fromBitmap(thumb)
                } finally {
                    thumb.recycle()
                }
            }.getOrNull()
        }

    private fun ensureStorageDir() {
        if (!storageDir.exists()) {
            storageDir.mkdirs()
        }
    }

    private fun readMetadataLocked(): ProjectMetadata? {
        val projectFile = File(storageDir, PROJECT_JSON_NAME)
        if (!projectFile.isFile || projectFile.length() <= 0L) return null
        val json = runCatching {
            projectFile.readText(StandardCharsets.UTF_8)
        }.getOrNull() ?: return null
        return ProjectJsonCodec.decode(json)
    }

    private fun writeMetadataAtomicallyLocked(metadata: ProjectMetadata) {
        val encoded = ProjectJsonCodec.encode(metadata).toByteArray(StandardCharsets.UTF_8)
        writeBytesAtomicallyLocked(File(storageDir, PROJECT_JSON_NAME), encoded)
    }

    private fun writeBytesAtomicallyLocked(target: File, bytes: ByteArray) {
        val tempFile = File(target.parentFile, "${target.name}.tmp")
        try {
            FileOutputStream(tempFile).use { fos ->
                fos.write(bytes)
                fos.flush()
                fos.fd.sync()
            }
            replaceFileAtomicallyLocked(tempFile, target)
        } catch (t: Throwable) {
            runCatching { tempFile.delete() }
            throw t
        }
    }

    private fun copyFileAtomicallyLocked(source: File, target: File) {
        val tempFile = File(target.parentFile, "${target.name}.tmp")
        try {
            FileInputStream(source).use { fis ->
                FileOutputStream(tempFile).use { fos ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    while (true) {
                        val read = fis.read(buffer)
                        if (read < 0) break
                        fos.write(buffer, 0, read)
                    }
                    fos.flush()
                    fos.fd.sync()
                }
            }
            replaceFileAtomicallyLocked(tempFile, target)
        } catch (t: Throwable) {
            runCatching { tempFile.delete() }
            throw t
        }
    }

    private fun replaceFileAtomicallyLocked(tempFile: File, targetFile: File) {
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
                        "Atomic replace failed for ${tempFile.name} -> ${targetFile.name}."
                    )
                }
            }
        }
    }

    private fun cleanupStaleTempAndStagingLocked() {
        val files = storageDir.listFiles() ?: return
        for (file in files) {
            val name = file.name
            if (name.endsWith(".tmp") || name == STAGING_WALLPAPER_NAME) {
                runCatching { file.delete() }
            }
        }
    }

    private fun cleanupOnColdBootLocked(activeVersion: Int) {
        val files = storageDir.listFiles() ?: return
        for (file in files) {
            val name = file.name
            if (name.endsWith(".tmp") || name == STAGING_WALLPAPER_NAME) {
                runCatching { file.delete() }
                continue
            }
            val version = parseVersionedFileNumber(name)
            if (version != null && version != activeVersion) {
                runCatching { file.delete() }
            }
        }
    }

    private fun cleanupDeferredVersionsLocked(minRetainedVersion: Int) {
        val files = storageDir.listFiles() ?: return
        for (file in files) {
            val version = parseVersionedFileNumber(file.name)
            if (version != null && version < minRetainedVersion) {
                runCatching { file.delete() }
            }
        }
    }

    private fun parseVersionedFileNumber(fileName: String): Int? {
        val match = VERSIONED_FILE_REGEX.matchEntire(fileName) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    companion object {
        private const val STORAGE_DIR_NAME = "mochiwall"
        private const val STAGING_WALLPAPER_NAME = "staging_wallpaper.webp"
        private const val PROJECT_JSON_NAME = "project.json"
        private const val COPY_BUFFER_SIZE = 16 * 1024
        private const val COLOR_THUMB_WIDTH = 128
        private const val COLOR_THUMB_HEIGHT = 286

        private val VERSIONED_FILE_REGEX = Regex("^(?:wallpaper|mask)_v(\\d+)\\.(?:webp|bin)$")
    }
}
