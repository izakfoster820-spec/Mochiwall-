package com.mochiwall.app.wallpaper

import android.app.Activity
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Helper for Stage 3's `Apply Live Wallpaper` action, launching the direct MochiWall preview
 * picker with a safe fallback to the system Live Wallpaper chooser.
 */
object WallpaperApplyHelper {

    fun launchLiveWallpaperPicker(context: Context): Boolean {
        val primaryIntent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(
                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                ComponentName(context, MochiWallpaperService::class.java)
            )
            if (context !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        try {
            context.startActivity(primaryIntent)
            return true
        } catch (_: ActivityNotFoundException) {
            // Fall through to generic live wallpaper chooser
        }

        val fallbackIntent = Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER).apply {
            if (context !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        return try {
            context.startActivity(fallbackIntent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}
