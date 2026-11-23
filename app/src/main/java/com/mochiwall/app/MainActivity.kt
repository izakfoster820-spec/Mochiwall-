package com.mochiwall.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.mochiwall.app.ui.navigation.StudioNavGraph
import com.mochiwall.app.ui.theme.StudioTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        setContent {
            val app = application as MochiApplication
            StudioTheme {
                StudioNavGraph(
                    repository = app.projectRepository,
                    imageFrameProcessor = app.imageFrameProcessor
                )
            }
        }
    }

    override fun onStop() {
        (application as MochiApplication).projectRepository.flushConfigSave()
        super.onStop()
    }
}
