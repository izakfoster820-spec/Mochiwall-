package com.mochiwall.app.core.model

data class ProjectMetadata(
    val contentVersion: Int,
    val imageWidth: Int,
    val imageHeight: Int,
    val config: PhysicsConfig
)

data class ProjectContent(
    val contentVersion: Int,
    val imageWidth: Int,
    val imageHeight: Int,
    val wallpaperFileName: String,
    val maskFileName: String
) {
    companion object {
        fun wallpaperFileNameFor(version: Int): String = "wallpaper_v${version}.webp"

        fun maskFileNameFor(version: Int): String = "mask_v${version}.bin"

        fun fromMetadata(metadata: ProjectMetadata): ProjectContent {
            return ProjectContent(
                contentVersion = metadata.contentVersion,
                imageWidth = metadata.imageWidth,
                imageHeight = metadata.imageHeight,
                wallpaperFileName = wallpaperFileNameFor(metadata.contentVersion),
                maskFileName = maskFileNameFor(metadata.contentVersion)
            )
        }
    }
}

data class ProjectState(
    val isLoaded: Boolean = false,
    val content: ProjectContent? = null,
    val config: PhysicsConfig = PhysicsConfig()
)
