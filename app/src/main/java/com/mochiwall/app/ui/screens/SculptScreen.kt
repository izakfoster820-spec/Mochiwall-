package com.mochiwall.app.ui.screens

import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.CenterFocusWeak
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mochiwall.app.ui.components.MaskCanvasView
import com.mochiwall.app.ui.theme.MochiColors
import com.mochiwall.app.ui.viewmodel.SculptTool
import com.mochiwall.app.ui.viewmodel.SculptViewModel
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun SculptScreen(
    viewModel: SculptViewModel,
    onBack: () -> Unit,
    onCommitted: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    BackHandler(enabled = uiState.isCommitting) {}

    val maxPreviewLongEdge = remember(context) {
        val bounds = context.getSystemService(WindowManager::class.java)?.maximumWindowMetrics?.bounds
        if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
            max(bounds.width(), bounds.height())
        } else {
            2400
        }
    }

    LaunchedEffect(Unit) {
        viewModel.loadInitialData(maxPreviewLongEdge)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MochiColors.ObsidianBg)
    ) {
        // Edge-to-edge interactive Sculpt Canvas
        MaskCanvasView(
            previewBitmap = uiState.previewBitmap,
            imageWidth = uiState.imageWidth,
            imageHeight = uiState.imageHeight,
            workingMask = viewModel.workingMask,
            maskRevision = uiState.maskRevision,
            tool = uiState.tool,
            brushSizeFraction = uiState.brushSizeFraction,
            feather = uiState.feather,
            isPeeking = uiState.isPeeking,
            resetZoomSignal = uiState.resetZoomSignal,
            onStrokeCommitted = viewModel::onStrokeCommitted,
            onZoomChanged = viewModel::onZoomChanged,
            modifier = Modifier.fillMaxSize()
        )

        // Top Floating Controls
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // Left: Non-destructive Back
            Surface(
                modifier = Modifier.align(Alignment.CenterStart),
                shape = CircleShape,
                color = MochiColors.PanelSurface.copy(alpha = 0.92f),
                border = BorderStroke(1.dp, MochiColors.PanelBorder)
            ) {
                IconButton(
                    onClick = onBack,
                    enabled = !uiState.isCommitting
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back",
                        tint = MochiColors.MochiCream
                    )
                }
            }

            // Center: Reset Zoom Pill Chip (appears only when zoom > 1.05x)
            AnimatedVisibility(
                visible = uiState.zoom > 1.05f,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
                modifier = Modifier.align(Alignment.Center)
            ) {
                Surface(
                    onClick = viewModel::requestResetZoom,
                    shape = RoundedCornerShape(999.dp),
                    color = MochiColors.PanelSurface.copy(alpha = 0.94f),
                    border = BorderStroke(1.dp, MochiColors.SoftCoral.copy(alpha = 0.75f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CenterFocusWeak,
                            contentDescription = null,
                            tint = MochiColors.SoftCoral,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Reset Zoom",
                            color = MochiColors.MochiCream,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // Right: Hold-to-Peek + Confirm
            Row(
                modifier = Modifier.align(Alignment.CenterEnd),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (uiState.isPeeking) {
                        MochiColors.SoftCoral.copy(alpha = 0.25f)
                    } else {
                        MochiColors.PanelSurface.copy(alpha = 0.92f)
                    },
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (uiState.isPeeking) {
                            MochiColors.SoftCoral
                        } else {
                            MochiColors.PanelBorder
                        }
                    ),
                    modifier = Modifier
                        .size(44.dp)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    viewModel.setPeeking(true)
                                    try {
                                        tryAwaitRelease()
                                    } finally {
                                        viewModel.setPeeking(false)
                                    }
                                }
                            )
                        }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (uiState.isPeeking) {
                                Icons.Rounded.VisibilityOff
                            } else {
                                Icons.Rounded.Visibility
                            },
                            contentDescription = "Hold to peek",
                            tint = if (uiState.isPeeking) {
                                MochiColors.SoftCoral
                            } else {
                                MochiColors.MochiCream
                            },
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Button(
                    onClick = { viewModel.confirmAndCommit(onCommitted) },
                    enabled = !uiState.isCommitting && !uiState.isLoading,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MochiColors.SoftCoral,
                        contentColor = MochiColors.ObsidianBg,
                        disabledContainerColor = MochiColors.SoftCoral.copy(alpha = 0.45f),
                        disabledContentColor = MochiColors.ObsidianBg.copy(alpha = 0.7f)
                    ),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    if (uiState.isCommitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MochiColors.ObsidianBg
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Saving",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                    } else {
                        Text(
                            text = "Confirm",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }

        // Floating Bottom Sculpt Dock
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            shape = RoundedCornerShape(20.dp),
            color = MochiColors.PanelSurface.copy(alpha = 0.95f),
            border = BorderStroke(1.dp, MochiColors.PanelBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                // Row 1: Brush / Eraser segmented pills + Undo / Redo / Clear
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MochiColors.ObsidianBg,
                        border = BorderStroke(1.dp, MochiColors.PanelBorder)
                    ) {
                        Row(
                            modifier = Modifier.padding(3.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            SculptToolPill(
                                label = "Brush",
                                icon = Icons.Rounded.Brush,
                                selected = uiState.tool == SculptTool.BRUSH,
                                onClick = { viewModel.setTool(SculptTool.BRUSH) }
                            )
                            SculptToolPill(
                                label = "Eraser",
                                icon = Icons.Rounded.CleaningServices,
                                selected = uiState.tool == SculptTool.ERASER,
                                onClick = { viewModel.setTool(SculptTool.ERASER) }
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        IconButton(
                            onClick = viewModel::undo,
                            enabled = uiState.canUndo && !uiState.isCommitting,
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.Undo,
                                contentDescription = "Undo",
                                tint = if (uiState.canUndo) {
                                    MochiColors.MochiCream
                                } else {
                                    MochiColors.MochiCream.copy(alpha = 0.30f)
                                },
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        IconButton(
                            onClick = viewModel::redo,
                            enabled = uiState.canRedo && !uiState.isCommitting,
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.Redo,
                                contentDescription = "Redo",
                                tint = if (uiState.canRedo) {
                                    MochiColors.MochiCream
                                } else {
                                    MochiColors.MochiCream.copy(alpha = 0.30f)
                                },
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        IconButton(
                            onClick = viewModel::clearMask,
                            enabled = !uiState.isCommitting && !uiState.isLoading,
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteOutline,
                                contentDescription = "Clear mask",
                                tint = MochiColors.MochiCream.copy(alpha = 0.85f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Row 2: Compact Brush Size & Feather Sliders
                CompactSculptSliderRow(
                    label = "Brush Size",
                    valueText = "${(uiState.brushSizeFraction * 100f).roundToInt()}%",
                    value = uiState.brushSizeFraction,
                    valueRange = SculptViewModel.MIN_BRUSH_SIZE_FRACTION..SculptViewModel.MAX_BRUSH_SIZE_FRACTION,
                    onValueChange = viewModel::setBrushSizeFraction
                )

                CompactSculptSliderRow(
                    label = "Feather",
                    valueText = "${(uiState.feather * 100f).roundToInt()}%",
                    value = uiState.feather,
                    valueRange = 0f..1f,
                    onValueChange = viewModel::setFeather
                )
            }
        }

        // Loading Overlay
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MochiColors.ObsidianBg.copy(alpha = 0.72f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = MochiColors.SoftCoral)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Loading Sculpt Canvas...",
                        color = MochiColors.MochiCream,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        // Error Banner
        val errorMessage = uiState.errorMessage
        if (errorMessage != null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 72.dp, start = 16.dp, end = 16.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MochiColors.PanelSurface,
                border = BorderStroke(1.dp, MochiColors.SoftCoral.copy(alpha = 0.7f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = errorMessage,
                        color = MochiColors.MochiCream,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = viewModel::clearError,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Dismiss error",
                            tint = MochiColors.MochiCream,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SculptToolPill(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(9.dp),
        color = if (selected) MochiColors.SoftCoral else Color.Transparent
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) MochiColors.ObsidianBg else MochiColors.MochiCream.copy(alpha = 0.8f),
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                color = if (selected) MochiColors.ObsidianBg else MochiColors.MochiCream.copy(alpha = 0.85f),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun CompactSculptSliderRow(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = MochiColors.MochiCream.copy(alpha = 0.82f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(74.dp)
        )

        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = MochiColors.SoftCoral,
                activeTrackColor = MochiColors.SoftCoral,
                inactiveTrackColor = MochiColors.PanelBorder
            )
        )

        Text(
            text = valueText,
            color = MochiColors.MochiCream,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(38.dp)
        )
    }
}
