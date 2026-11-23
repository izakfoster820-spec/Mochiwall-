package com.mochiwall.app.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mochiwall.app.data.ImageFrameProcessor
import com.mochiwall.app.data.ProjectRepository
import com.mochiwall.app.ui.screens.FrameScreen
import com.mochiwall.app.ui.screens.SandboxScreen
import com.mochiwall.app.ui.screens.SculptScreen
import com.mochiwall.app.ui.theme.MochiColors
import com.mochiwall.app.ui.viewmodel.FrameViewModel
import com.mochiwall.app.ui.viewmodel.SandboxViewModel
import com.mochiwall.app.ui.viewmodel.SculptViewModel

private object StudioRoutes {
    const val FRAME = "frame"
    const val SANDBOX = "sandbox"
    const val ARG_IS_EDIT_MASK = "isEditMask"
    const val ARG_EXPORT_WIDTH = "exportWidth"
    const val ARG_EXPORT_HEIGHT = "exportHeight"
    const val SCULPT_PATTERN = "sculpt/{$ARG_IS_EDIT_MASK}/{$ARG_EXPORT_WIDTH}/{$ARG_EXPORT_HEIGHT}"

    fun sculpt(isEditMask: Boolean, exportWidth: Int, exportHeight: Int): String {
        return "sculpt/$isEditMask/$exportWidth/$exportHeight"
    }
}

/**
 * Root navigation graph connecting Stage 1 (Frame), Stage 2 (Sculpt), and Stage 3 (Sandbox).
 *
 * Implements the cold-boot gate so returning users with a valid committed project enter Stage 3
 * directly with zero Stage-1 flash.
 */
@Composable
fun StudioNavGraph(
    repository: ProjectRepository,
    imageFrameProcessor: ImageFrameProcessor
) {
    val projectState by repository.state.collectAsStateWithLifecycle()

    // Cold-boot gate: hold ObsidianBg while ProjectStorage completes initial load (< 30 ms).
    if (!projectState.isLoaded) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MochiColors.ObsidianBg)
        )
        return
    }

    val navController = rememberNavController()
    val startDestination = remember {
        if (projectState.content != null) StudioRoutes.SANDBOX else StudioRoutes.FRAME
    }

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(route = StudioRoutes.FRAME) {
            val frameViewModel: FrameViewModel = viewModel(
                factory = FrameViewModel.provideFactory(
                    repository = repository,
                    imageFrameProcessor = imageFrameProcessor
                )
            )
            FrameScreen(
                viewModel = frameViewModel,
                canCancel = projectState.content != null,
                onCancel = { navController.popBackStack() },
                onExportSuccess = { exportWidth, exportHeight ->
                    navController.navigate(
                        StudioRoutes.sculpt(
                            isEditMask = false,
                            exportWidth = exportWidth,
                            exportHeight = exportHeight
                        )
                    )
                }
            )
        }

        composable(
            route = StudioRoutes.SCULPT_PATTERN,
            arguments = listOf(
                navArgument(StudioRoutes.ARG_IS_EDIT_MASK) { type = NavType.BoolType },
                navArgument(StudioRoutes.ARG_EXPORT_WIDTH) { type = NavType.IntType },
                navArgument(StudioRoutes.ARG_EXPORT_HEIGHT) { type = NavType.IntType }
            )
        ) { backStackEntry ->
            val args = backStackEntry.arguments
            val isEditMask = args?.getBoolean(StudioRoutes.ARG_IS_EDIT_MASK) ?: false
            val exportWidth = args?.getInt(StudioRoutes.ARG_EXPORT_WIDTH) ?: 0
            val exportHeight = args?.getInt(StudioRoutes.ARG_EXPORT_HEIGHT) ?: 0

            val sculptViewModel: SculptViewModel = viewModel(
                factory = SculptViewModel.provideFactory(
                    repository = repository,
                    imageFrameProcessor = imageFrameProcessor,
                    isEditMaskMode = isEditMask,
                    exportWidth = exportWidth,
                    exportHeight = exportHeight
                )
            )
            SculptScreen(
                viewModel = sculptViewModel,
                onBack = { navController.popBackStack() },
                onCommitted = {
                    navController.navigate(StudioRoutes.SANDBOX) {
                        popUpTo(0) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(route = StudioRoutes.SANDBOX) {
            // Safety guard: redirect cleanly to Stage 1 if Sandbox is ever reached without content.
            LaunchedEffect(projectState.isLoaded, projectState.content) {
                if (projectState.isLoaded && projectState.content == null) {
                    navController.navigate(StudioRoutes.FRAME) {
                        popUpTo(0) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }

            val sandboxViewModel: SandboxViewModel = viewModel(
                factory = SandboxViewModel.provideFactory(repository = repository)
            )
            SandboxScreen(
                viewModel = sandboxViewModel,
                repository = repository,
                imageFrameProcessor = imageFrameProcessor,
                onNewPhoto = {
                    navController.navigate(StudioRoutes.FRAME) {
                        launchSingleTop = true
                    }
                },
                onEditMask = {
                    navController.navigate(
                        StudioRoutes.sculpt(
                            isEditMask = true,
                            exportWidth = 0,
                            exportHeight = 0
                        )
                    ) {
                        launchSingleTop = true
                    }
                }
            )
        }
    }
}
