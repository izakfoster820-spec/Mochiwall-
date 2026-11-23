package com.mochiwall.app.ui.screens

import android.graphics.Matrix
import android.graphics.Paint
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mochiwall.app.ui.theme.MochiColors
import com.mochiwall.app.ui.viewmodel.FrameViewModel

@Composable
fun FrameScreen(
    viewModel: FrameViewModel,
    canCancel: Boolean,
    onCancel: () -> Unit,
    onExportSuccess: (exportWidth: Int, exportHeight: Int) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    BackHandler(enabled = uiState.isExporting) {}

    val windowBounds = remember(context) {
        context.getSystemService(WindowManager::class.java)?.maximumWindowMetrics?.bounds
    }
    val screenWidth = windowBounds?.width() ?: 0
    val screenHeight = windowBounds?.height() ?: 0

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            viewModel.onPhotoSelected(
                uri = uri,
                screenWidth = screenWidth,
                screenHeight = screenHeight
            )
        }
    }

    val launchPhotoPicker: () -> Unit = {
        photoPickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    val drawMatrix = remember { Matrix() }
    val bitmapPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    }
    val clockGuideDashEffect = remember {
        PathEffect.dashPathEffect(floatArrayOf(14f, 10f), 0f)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MochiColors.ObsidianBg)
    ) {
        val sourceBitmap = uiState.sourceBitmap

        if (sourceBitmap == null || sourceBitmap.isRecycled) {
            FrameEmptyState(
                isLoadingPhoto = uiState.isLoadingPhoto,
                onChoosePhoto = launchPhotoPicker
            )

            if (canCancel) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(start = 16.dp, top = 12.dp),
                    shape = CircleShape,
                    color = MochiColors.PanelSurface.copy(alpha = 0.92f),
                    border = BorderStroke(1.dp, MochiColors.PanelBorder)
                ) {
                    IconButton(
                        onClick = onCancel,
                        enabled = !uiState.isExporting
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Cancel",
                            tint = MochiColors.MochiCream
                        )
                    }
                }
            }
        } else {
            // Interactive edge-to-edge framing canvas (1:1 matrix match with ImageFrameProcessor)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { intSize ->
                        viewModel.onViewportSizeChanged(
                            width = intSize.width.toFloat(),
                            height = intSize.height.toFloat()
                        )
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            viewModel.onTransformGesture(
                                panDeltaX = pan.x,
                                panDeltaY = pan.y,
                                zoomChange = zoom,
                                centroidX = centroid.x,
                                centroidY = centroid.y
                            )
                        }
                    }
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    if (!sourceBitmap.isRecycled) {
                        drawIntoCanvas { canvas ->
                            drawMatrix.reset()
                            drawMatrix.postTranslate(
                                -sourceBitmap.width / 2f,
                                -sourceBitmap.height / 2f
                            )
                            drawMatrix.postScale(uiState.scale, uiState.scale)
                            drawMatrix.postTranslate(
                                size.width / 2f + uiState.offsetX,
                                size.height / 2f + uiState.offsetY
                            )
                            canvas.nativeCanvas.drawBitmap(sourceBitmap, drawMatrix, bitmapPaint)
                        }
                    }

                    // Non-destructive top 22% Lock-Screen Clock Safe-Zone tint & dashed line
                    val clockZoneHeight = size.height * 0.22f
                    drawRect(
                        color = MochiColors.ObsidianBg.copy(alpha = 0.22f),
                        topLeft = Offset.Zero,
                        size = Size(size.width, clockZoneHeight)
                    )
                    drawLine(
                        color = MochiColors.MochiCream.copy(alpha = 0.42f),
                        start = Offset(0f, clockZoneHeight),
                        end = Offset(size.width, clockZoneHeight),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = clockGuideDashEffect
                    )

                    // Subtle Rule-of-Thirds 3x3 grid lines
                    val gridColor = MochiColors.MochiCream.copy(alpha = 0.18f)
                    val gridStroke = 1.dp.toPx()
                    val thirdW = size.width / 3f
                    val thirdH = size.height / 3f

                    drawLine(
                        color = gridColor,
                        start = Offset(thirdW, 0f),
                        end = Offset(thirdW, size.height),
                        strokeWidth = gridStroke
                    )
                    drawLine(
                        color = gridColor,
                        start = Offset(thirdW * 2f, 0f),
                        end = Offset(thirdW * 2f, size.height),
                        strokeWidth = gridStroke
                    )
                    drawLine(
                        color = gridColor,
                        start = Offset(0f, thirdH),
                        end = Offset(size.width, thirdH),
                        strokeWidth = gridStroke
                    )
                    drawLine(
                        color = gridColor,
                        start = Offset(0f, thirdH * 2f),
                        end = Offset(size.width, thirdH * 2f),
                        strokeWidth = gridStroke
                    )
                }

                // Translucent Lock Screen Clock Zone indicator positioned just inside the 22% band
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(fraction = 0.22f)
                        .padding(bottom = 8.dp),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = MochiColors.ObsidianBg.copy(alpha = 0.50f),
                        border = BorderStroke(
                            width = 0.5.dp,
                            color = MochiColors.MochiCream.copy(alpha = 0.22f)
                        )
                    ) {
                        Text(
                            text = "Lock Screen Clock Zone",
                            color = MochiColors.MochiCream.copy(alpha = 0.72f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            // Floating Top Controls
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (canCancel) {
                    Surface(
                        shape = CircleShape,
                        color = MochiColors.PanelSurface.copy(alpha = 0.90f),
                        border = BorderStroke(1.dp, MochiColors.PanelBorder)
                    ) {
                        IconButton(
                            onClick = onCancel,
                            enabled = !uiState.isExporting
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Cancel",
                                tint = MochiColors.MochiCream
                            )
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.size(48.dp))
                }

                Surface(
                    onClick = launchPhotoPicker,
                    enabled = !uiState.isExporting && !uiState.isLoadingPhoto,
                    shape = RoundedCornerShape(999.dp),
                    color = MochiColors.PanelSurface.copy(alpha = 0.92f),
                    border = BorderStroke(1.dp, MochiColors.PanelBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PhotoLibrary,
                            contentDescription = null,
                            tint = MochiColors.MochiCream,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Change Photo",
                            color = MochiColors.MochiCream,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }

            // Floating Bottom Action Bar
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                shape = RoundedCornerShape(20.dp),
                color = MochiColors.PanelSurface.copy(alpha = 0.95f),
                border = BorderStroke(1.dp, MochiColors.PanelBorder)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Frame Wallpaper",
                            color = MochiColors.MochiCream,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Pinch & drag to fill screen",
                            color = MochiColors.MochiCream.copy(alpha = 0.65f),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Button(
                        onClick = {
                            viewModel.exportForSculpt(
                                windowBoundsWidth = screenWidth,
                                windowBoundsHeight = screenHeight,
                                onExported = onExportSuccess
                            )
                        },
                        enabled = !uiState.isExporting && !uiState.isLoadingPhoto,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MochiColors.SoftCoral,
                            contentColor = MochiColors.ObsidianBg,
                            disabledContainerColor = MochiColors.SoftCoral.copy(alpha = 0.45f),
                            disabledContentColor = MochiColors.ObsidianBg.copy(alpha = 0.7f)
                        ),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
                    ) {
                        if (uiState.isExporting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MochiColors.ObsidianBg
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Preparing...",
                                fontWeight = FontWeight.SemiBold
                            )
                        } else {
                            Text(
                                text = "Next: Sculpt",
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }

        // Loading overlay while decoding a replacement photo
        if (uiState.isLoadingPhoto && sourceBitmap != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MochiColors.ObsidianBg.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = MochiColors.SoftCoral)
            }
        }

        // Subtle Error Banner
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
private fun FrameEmptyState(
    isLoadingPhoto: Boolean,
    onChoosePhoto: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = CircleShape,
            color = MochiColors.PanelSurface,
            border = BorderStroke(1.dp, MochiColors.PanelBorder),
            modifier = Modifier.size(84.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (isLoadingPhoto) {
                    CircularProgressIndicator(
                        color = MochiColors.SoftCoral,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(32.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.AddPhotoAlternate,
                        contentDescription = null,
                        tint = MochiColors.SoftCoral,
                        modifier = Modifier.size(38.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "MochiWall Studio",
            color = MochiColors.MochiCream,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Choose a photo to frame for your screen, then sculpt soft-body regions.",
            color = MochiColors.MochiCream.copy(alpha = 0.72f),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(28.dp))

        Button(
            onClick = onChoosePhoto,
            enabled = !isLoadingPhoto,
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MochiColors.SoftCoral,
                contentColor = MochiColors.ObsidianBg
            ),
            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.PhotoLibrary,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = if (isLoadingPhoto) "Loading Photo..." else "Choose Photo",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
