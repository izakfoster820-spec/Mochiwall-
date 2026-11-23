package com.mochiwall.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mochiwall.app.core.model.MochiPreset
import com.mochiwall.app.core.model.PhysicsConfig
import com.mochiwall.app.core.model.PhysicsStyle
import com.mochiwall.app.ui.theme.MochiColors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Collapsible floating bottom panel for Stage 3 live physics tuning.
 *
 * Intentionally avoids a full-screen dimmed `ModalBottomSheet` scrim so the upper ~46% of the
 * live wallpaper remains directly interactive while adjusting style, presets, sliders, and toggles.
 */
@Composable
fun PhysicsBottomSheet(
    config: PhysicsConfig,
    onStyleChange: (PhysicsStyle) -> Unit,
    onSelectPreset: (MochiPreset) -> Unit,
    onSoftnessChange: (Float) -> Unit,
    onWobbleChange: (Float) -> Unit,
    onPullRadiusChange: (Float) -> Unit,
    onShakeForceChange: (Float) -> Unit,
    onTouchEnabledChange: (Boolean) -> Unit,
    onMotionEnabledChange: (Boolean) -> Unit,
    onWakeWobbleEnabledChange: (Boolean) -> Unit,
    onSheenEnabledChange: (Boolean) -> Unit,
    onTestWakeWobble: () -> Unit,
    onResetPhysics: () -> Unit,
    onApplyWallpaper: () -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val maxSheetHeight = (configuration.screenHeightDp * 0.54f).dp.coerceAtLeast(320.dp)
    val sheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    val blockTouchInteraction = remember { MutableInteractionSource() }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = maxSheetHeight)
            .clickable(
                interactionSource = blockTouchInteraction,
                indication = null,
                onClick = {}
            ),
        shape = sheetShape,
        color = MochiColors.PanelSurface.copy(alpha = 0.97f),
        border = BorderStroke(1.dp, MochiColors.PanelBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
        ) {
            // Drag/collapse pill handle + Header row
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(onCollapse) {
                        var totalDragY = 0f
                        detectVerticalDragGestures(
                            onDragStart = { totalDragY = 0f },
                            onVerticalDrag = { _, dragAmount ->
                                totalDragY += dragAmount
                                if (totalDragY > 48f) {
                                    totalDragY = 0f
                                    onCollapse()
                                }
                            }
                        )
                    },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .padding(top = 10.dp, bottom = 4.dp)
                        .width(38.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(MochiColors.MochiCream.copy(alpha = 0.28f))
                        .clickable(onClick = onCollapse)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Physics & Motion",
                        color = MochiColors.MochiCream,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )

                    TextButton(
                        onClick = onResetPhysics,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "Reset Physics",
                            color = MochiColors.SoftCoral,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    IconButton(
                        onClick = onCollapse,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.KeyboardArrowDown,
                            contentDescription = "Collapse physics panel",
                            tint = MochiColors.MochiCream
                        )
                    }
                }
            }

            HorizontalDivider(color = MochiColors.PanelBorder, thickness = 1.dp)

            // Scrollable Controls (Style Switch + Presets + 4 Sliders + 4 Toggles)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 12.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 2-Pill Physics Style Segmented Selector
                PhysicsStyleSelector(
                    selectedStyle = config.style,
                    onStyleChange = onStyleChange
                )

                // 3 Preset Chips Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PresetChip(
                        label = "Mochi",
                        selected = config.matchesPreset(MochiPreset.MOCHI),
                        onClick = { onSelectPreset(MochiPreset.MOCHI) },
                        modifier = Modifier.weight(1f)
                    )
                    PresetChip(
                        label = "Soft Jelly",
                        selected = config.matchesPreset(MochiPreset.SOFT_JELLY),
                        onClick = { onSelectPreset(MochiPreset.SOFT_JELLY) },
                        modifier = Modifier.weight(1f)
                    )
                    PresetChip(
                        label = "Firm Rubber",
                        selected = config.matchesPreset(MochiPreset.FIRM_RUBBER),
                        onClick = { onSelectPreset(MochiPreset.FIRM_RUBBER) },
                        modifier = Modifier.weight(1f)
                    )
                }

                // 4 Physics Sliders
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    PhysicsSliderRow(
                        label = "Softness",
                        value = config.softness,
                        onValueChange = onSoftnessChange
                    )
                    PhysicsSliderRow(
                        label = "Wobble",
                        value = config.wobble,
                        onValueChange = onWobbleChange
                    )
                    PhysicsSliderRow(
                        label = "Pull Radius",
                        value = config.pullRadius,
                        onValueChange = onPullRadiusChange
                    )
                    PhysicsSliderRow(
                        label = "Shake Force",
                        value = config.shakeForce,
                        onValueChange = onShakeForceChange
                    )
                }

                HorizontalDivider(color = MochiColors.PanelBorder.copy(alpha = 0.7f), thickness = 1.dp)

                // 4 Toggle Switches
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PhysicsToggleRow(
                        label = "Touch Interaction",
                        checked = config.touchEnabled,
                        onCheckedChange = onTouchEnabledChange
                    )
                    PhysicsToggleRow(
                        label = "Motion & Tilt",
                        checked = config.motionEnabled,
                        onCheckedChange = onMotionEnabledChange
                    )
                    PhysicsToggleRow(
                        label = "Wake Wobble",
                        checked = config.wakeWobbleEnabled,
                        onCheckedChange = onWakeWobbleEnabledChange,
                        trailingAction = if (config.wakeWobbleEnabled) {
                            {
                                Surface(
                                    onClick = onTestWakeWobble,
                                    shape = RoundedCornerShape(999.dp),
                                    color = MochiColors.ObsidianBg,
                                    border = BorderStroke(
                                        width = 1.dp,
                                        color = MochiColors.SoftCoral.copy(alpha = 0.7f)
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.PlayArrow,
                                            contentDescription = null,
                                            tint = MochiColors.SoftCoral,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(2.dp))
                                        Text(
                                            text = "Test",
                                            color = MochiColors.MochiCream,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }
                        } else {
                            null
                        }
                    )
                    PhysicsToggleRow(
                        label = "Surface Sheen",
                        checked = config.sheenEnabled,
                        onCheckedChange = onSheenEnabledChange
                    )
                }
            }

            // Primary CTA Button pinned cleanly at bottom of sheet
            Button(
                onClick = onApplyWallpaper,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, bottom = 14.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MochiColors.SoftCoral,
                    contentColor = MochiColors.ObsidianBg
                ),
                contentPadding = PaddingValues(vertical = 13.dp)
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
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun PhysicsStyleSelector(
    selectedStyle: PhysicsStyle,
    onStyleChange: (PhysicsStyle) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MochiColors.ObsidianBg,
        border = BorderStroke(1.dp, MochiColors.PanelBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            StyleSegmentPill(
                label = "Mochi (Smooth)",
                selected = selectedStyle == PhysicsStyle.MOCHI,
                onClick = { onStyleChange(PhysicsStyle.MOCHI) },
                modifier = Modifier.weight(1f)
            )
            StyleSegmentPill(
                label = "Jelly (Waves)",
                selected = selectedStyle == PhysicsStyle.JELLY,
                onClick = { onStyleChange(PhysicsStyle.JELLY) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun StyleSegmentPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MochiColors.SoftCoral else Color.Transparent
    ) {
        Text(
            text = label,
            color = if (selected) MochiColors.ObsidianBg else MochiColors.MochiCream.copy(alpha = 0.85f),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp)
        )
    }
}

private fun PhysicsConfig.matchesPreset(preset: MochiPreset): Boolean {
    return abs(softness - preset.softness) <= PRESET_MATCH_EPS &&
        abs(wobble - preset.wobble) <= PRESET_MATCH_EPS &&
        abs(pullRadius - preset.pullRadius) <= PRESET_MATCH_EPS &&
        abs(shakeForce - preset.shakeForce) <= PRESET_MATCH_EPS
}

@Composable
private fun PresetChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MochiColors.SoftCoral else MochiColors.ObsidianBg,
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) MochiColors.SoftCoral else MochiColors.PanelBorder
        )
    ) {
        Text(
            text = label,
            color = if (selected) MochiColors.ObsidianBg else MochiColors.MochiCream,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 9.dp)
        )
    }
}

@Composable
private fun PhysicsSliderRow(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit
) {
    val safeValue = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = MochiColors.MochiCream.copy(alpha = 0.85f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(82.dp)
        )

        Slider(
            value = safeValue,
            onValueChange = onValueChange,
            valueRange = 0f..1f,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = MochiColors.SoftCoral,
                activeTrackColor = MochiColors.SoftCoral,
                inactiveTrackColor = MochiColors.PanelBorder
            )
        )

        Text(
            text = "${(safeValue * 100f).roundToInt()}%",
            color = MochiColors.MochiCream,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
            modifier = Modifier.width(38.dp)
        )
    }
}

@Composable
private fun PhysicsToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    trailingAction: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = MochiColors.MochiCream,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )

        if (trailingAction != null) {
            trailingAction()
            Spacer(modifier = Modifier.width(10.dp))
        }

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MochiColors.ObsidianBg,
                checkedTrackColor = MochiColors.SoftCoral,
                checkedBorderColor = MochiColors.SoftCoral,
                uncheckedThumbColor = MochiColors.MochiCream.copy(alpha = 0.72f),
                uncheckedTrackColor = MochiColors.ObsidianBg,
                uncheckedBorderColor = MochiColors.PanelBorder
            )
        )
    }
}

private const val PRESET_MATCH_EPS = 0.01f
