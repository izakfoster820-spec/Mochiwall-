package com.mochiwall.app.wallpaper

import android.graphics.Canvas
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder

class MochiWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = MochiEngine()

    private inner class MochiEngine : Engine() {

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            drawPlaceholderFrame(holder)
        }

        override fun onSurfaceChanged(
            holder: SurfaceHolder,
            format: Int,
            width: Int,
            height: Int,
        ) {
            super.onSurfaceChanged(holder, format, width, height)
            drawPlaceholderFrame(holder)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            if (visible) {
                drawPlaceholderFrame(surfaceHolder)
            }
        }

        private fun drawPlaceholderFrame(holder: SurfaceHolder) {
            val surface = holder.surface ?: return
            if (!surface.isValid) return

            var canvas: Canvas? = null
            try {
                runCatching {
                    canvas = holder.lockCanvas()
                    canvas?.drawColor(BACKGROUND_COLOR)
                }
            } finally {
                canvas?.let { lockedCanvas ->
                    runCatching { holder.unlockCanvasAndPost(lockedCanvas) }
                }
            }
        }
    }

    companion object {
        private const val BACKGROUND_COLOR = 0xFF111215.toInt()
    }
}
