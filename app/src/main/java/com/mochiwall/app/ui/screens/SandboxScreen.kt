package com.mochiwall.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mochiwall.app.data.ImageFrameProcessor
import com.mochiwall.app.data.ProjectRepository
import com.mochiwall.app.ui.components.MochiPreviewController
import com.mochiwall.app.ui.components.MochiPreviewView
import com.mochiwall.app.ui.components.PhysicsBottomSheet
import com.mochiwall.app.ui.theme.MochiColors
import com.mochiwall.app.ui.viewmodel.SandboxViewModel
import com.mochiwall.app.wallpaper.WallpaperApplyHelper
import kotlinx.coroutines.launch

/**
 * Stage 3 (Sandbox) screen hosting the live full-screen OpenGL ES 3.0 preview, floating top
 * action bar (`New Photo`, `Edit Mask`, `Reset Pose`), compact bottom dock, and collapsible
 * [PhysicsBottomSheet].
 */
@Composable
fun SandboxScreen(
    viewModel: SandboxViewModel,
    repository: ProjectRepository,
    imageFrameProcessor: ImageFrameProcessor,
    onNewPhoto: () -> Unit,
    onEditMask: () -> Unit
) {
    val projectState by viewModel.projectState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val previewController = remember { MochiPreviewController() }

    var isPhysicsSheetExpanded by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = isPhysicsSheetExpanded) {
        isPhysicsSheetExpanded = false
    }

    val handleApplyWallpaper: () -> Unit = {
        viewModel.flushConfigBeforeApply()
        val launched = WallpaperApplyHelper.launchLiveWallpaperPicker(context)
        if (!launched) {
            coroutineScope.launch {
                snackbarHostState.showSnackbar(
                    message = "Live wallpaper picker is not supported on this device."
                )
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MochiColors.ObsidianBg)
    ) {
        // Full-screen live interactive OpenGL ES 3.0 preview
        MochiPreviewView(
            content = projectState.content,
            config = projectState.config,
            repository = repository,
            imageFrameProcessor = imageFrameProcessor,
            modifier = Modifier.fillMaxSize(),
            controller = previewController
        )

        // Top Floating Action Bar
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SandboxTopPill(
                label = "New Photo",
                icon = Icons.Rounded.PhotoLibrary,
                onClick = onNewPhoto
            )
            SandboxTopPill(
                label = "Edit Mask",
                icon = Icons.Rounded.Brush,
                onClick = onEditMask
            )
            SandboxTopPill(
                label = "Reset Pose",
                icon = Icons.Rounded.RestartAlt,
                onClick = { previewController.resetPose() }
            )
        }

        // Collapsed Floating Bottom Dock
        AnimatedVisibility(
            visible = !isPhysicsSheetExpanded,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    onClick = { isPhysicsSheetExpanded = true },
                    shape = RoundedCornerShape(14.dp),
                    color = MochiColors.PanelSurface.copy(alpha = 0.92f),
                    border = BorderStroke(1.dp, MochiColors.PanelBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Tune,
                            contentDescription = null,
                            tint = MochiColors.MochiCream,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Physics",
                            color = MochiColors.MochiCream,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Button(
                    onClick = handleApplyWallpaper,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MochiColors.SoftCoral,
                        contentColor = MochiColors.ObsidianBg
                    ),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 13.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Wallpaper,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Apply Live Wallpaper",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Expanded Collapsible Physics Bottom Sheet
        AnimatedVisibility(
            visible = isPhysicsSheetExpanded,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            PhysicsBottomSheet(
                config = projectState.config,
                onStyleChange = viewModel::setStyle,
                onSelectPreset = viewModel::selectPreset,
                onSoftnessChange = viewModel::setSoftness,
                onWobbleChange = viewModel::setWobble,
                onPullRadiusChange = viewModel::setPullRadius,
                onShakeForceChange = viewModel::setShakeForce,
                onTouchEnabledChange = viewModel::setTouchEnabled,
                onMotionEnabledChange = viewModel::setMotionEnabled,
                onWakeWobbleEnabledChange = viewModel::setWakeWobbleEnabled,
                onSheenEnabledChange = viewModel::setSheenEnabled,
                onTestWakeWobble = { previewController.triggerWakeWobble() },
                onResetPhysics = viewModel::resetPhysics,
                onApplyWallpaper = handleApplyWallpaper,
                onCollapse = { isPhysicsSheetExpanded = false }
            )
        }

        // Snackbar Host positioned above bottom controls
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 84.dp, start = 16.dp, end = 16.dp)
        ) { snackbarData ->
            Snackbar(
                snackbarData = snackbarData,
                containerColor = MochiColors.PanelSurface,
                contentColor = MochiColors.MochiCream,
                actionColor = MochiColors.SoftCoral,
                shape = RoundedCornerShape(14.dp)
            )
        }
    }
}

@Composable
private fun SandboxTopPill(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        color = MochiColors.PanelSurface.copy(alpha = 0.85f),
        border = BorderStroke(1.dp, MochiColors.PanelBorder)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MochiColors.MochiCream,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                color = MochiColors.MochiCream,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
