package app.gamenative.ui.component

import android.view.KeyEvent
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.omfg.OmfgConfig
import app.gamenative.ui.theme.PluviaTheme
import kotlin.math.roundToInt

/**
 * Full OMFG configuration tab for the quick-access panel.
 * Surfaces all hot-reloadable knobs from the OMFG Vulkan layer config,
 * organized into sections matching the omfg-deck plugin layout.
 */
@Composable
fun OmfgConfigTabContent(
    config: OmfgConfig,
    onConfigChanged: (OmfgConfig) -> Unit,
    modifier: Modifier = Modifier,
    firstItemFocusRequester: FocusRequester? = null,
    scrollState: ScrollState = rememberScrollState(),
) {
    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .focusGroup()
            .padding(vertical = 12.dp),
    ) {
        // ── Mode ──────────────────────────────────
        OptionSectionHeader(text = stringResource(R.string.omfg_section_mode))

        OmfgDropdownRow(
            title = stringResource(R.string.omfg_layer_mode),
            selectedValue = config.OMFG_LAYER_MODE,
            options = OmfgConfig.ALL_LAYER_MODES,
            onOptionSelected = { onConfigChanged(config.copy(OMFG_LAYER_MODE = it)) },
            focusRequester = firstItemFocusRequester,
        )

        OmfgDropdownRow(
            title = stringResource(R.string.omfg_debug_view),
            selectedValue = config.OMFG_DEBUG_VIEW,
            options = OmfgConfig.DEBUG_VIEWS,
            onOptionSelected = { onConfigChanged(config.copy(OMFG_DEBUG_VIEW = it)) },
        )

        Spacer(modifier = Modifier.height(16.dp))

        // ── Multi / Adaptive ──────────────────────
        if (config.isMultiMode() || config.isAdaptiveMode()) {
            OptionSectionHeader(text = stringResource(R.string.omfg_section_multi_adaptive))

            if (config.isMultiMode()) {
                OmfgAdjustRow(
                    title = stringResource(R.string.omfg_multi_blend_count),
                    valueText = config.OMFG_MULTI_BLEND_COUNT.toString(),
                    value = config.OMFG_MULTI_BLEND_COUNT.toFloat(),
                    min = 1f, max = 4f, step = 1f,
                    onChange = { v -> onConfigChanged(config.copy(OMFG_MULTI_BLEND_COUNT = v.toInt())) },
                )
            }

            if (config.isAdaptiveMode()) {
                OmfgAdjustRow(
                    title = stringResource(R.string.omfg_adaptive_target_fps),
                    valueText = config.OMFG_ADAPTIVE_MULTI_TARGET_FPS.toString(),
                    value = config.OMFG_ADAPTIVE_MULTI_TARGET_FPS.toFloat(),
                    min = 30f, max = 240f, step = 5f,
                    onChange = { v -> onConfigChanged(config.copy(OMFG_ADAPTIVE_MULTI_TARGET_FPS = v.toInt())) },
                )
                OmfgAdjustRow(
                    title = stringResource(R.string.omfg_adaptive_min_frames),
                    valueText = config.OMFG_ADAPTIVE_MULTI_MIN_GENERATED_FRAMES.toString(),
                    value = config.OMFG_ADAPTIVE_MULTI_MIN_GENERATED_FRAMES.toFloat(),
                    min = 0f, max = 3f, step = 1f,
                    onChange = { v -> onConfigChanged(config.copy(OMFG_ADAPTIVE_MULTI_MIN_GENERATED_FRAMES = v.toInt())) },
                )
                OmfgAdjustRow(
                    title = stringResource(R.string.omfg_adaptive_max_frames),
                    valueText = config.OMFG_ADAPTIVE_MULTI_MAX_GENERATED_FRAMES.toString(),
                    value = config.OMFG_ADAPTIVE_MULTI_MAX_GENERATED_FRAMES.toFloat(),
                    min = 1f, max = 4f, step = 1f,
                    onChange = { v -> onConfigChanged(config.copy(OMFG_ADAPTIVE_MULTI_MAX_GENERATED_FRAMES = v.toInt())) },
                )
                OmfgAdjustRow(
                    title = stringResource(R.string.omfg_adaptive_interval_ms),
                    valueText = String.format("%.1f", config.OMFG_ADAPTIVE_MULTI_INTERVAL_THRESHOLD_MS),
                    value = config.OMFG_ADAPTIVE_MULTI_INTERVAL_THRESHOLD_MS,
                    min = 0.1f, max = 10f, step = 0.1f,
                    onChange = { v -> onConfigChanged(config.copy(OMFG_ADAPTIVE_MULTI_INTERVAL_THRESHOLD_MS = (v * 10).roundToInt() / 10f)) },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // ── Reprojection ──────────────────────────
        if (config.isReprojectMode()) {
            OptionSectionHeader(text = stringResource(R.string.omfg_section_reprojection))

            OmfgAdjustRow(
                title = stringResource(R.string.omfg_reproject_search_radius),
                valueText = config.OMFG_REPROJECT_SEARCH_RADIUS.toString(),
                value = config.OMFG_REPROJECT_SEARCH_RADIUS.toFloat(),
                min = 1f, max = 6f, step = 1f,
                onChange = { v -> onConfigChanged(config.copy(OMFG_REPROJECT_SEARCH_RADIUS = v.toInt())) },
            )
            OmfgAdjustRow(
                title = stringResource(R.string.omfg_reproject_patch_radius),
                valueText = config.OMFG_REPROJECT_PATCH_RADIUS.toString(),
                value = config.OMFG_REPROJECT_PATCH_RADIUS.toFloat(),
                min = 1f, max = 4f, step = 1f,
                onChange = { v -> onConfigChanged(config.copy(OMFG_REPROJECT_PATCH_RADIUS = v.toInt())) },
            )
            OmfgAdjustRow(
                title = stringResource(R.string.omfg_reproject_confidence_scale),
                valueText = String.format("%.1f", config.OMFG_REPROJECT_CONFIDENCE_SCALE),
                value = config.OMFG_REPROJECT_CONFIDENCE_SCALE,
                min = 1f, max = 10f, step = 0.5f,
                onChange = { v -> onConfigChanged(config.copy(OMFG_REPROJECT_CONFIDENCE_SCALE = v)) },
            )
            OmfgAdjustRow(
                title = stringResource(R.string.omfg_reproject_disocclusion_current_bias),
                valueText = String.format("%.2f", config.OMFG_REPROJECT_DISOCCLUSION_CURRENT_BIAS),
                value = config.OMFG_REPROJECT_DISOCCLUSION_CURRENT_BIAS,
                min = 0f, max = 1f, step = 0.05f,
                onChange = { v -> onConfigChanged(config.copy(OMFG_REPROJECT_DISOCCLUSION_CURRENT_BIAS = v)) },
            )
            OmfgAdjustRow(
                title = stringResource(R.string.omfg_reproject_disocclusion_scale),
                valueText = String.format("%.2f", config.OMFG_REPROJECT_DISOCCLUSION_SCALE),
                value = config.OMFG_REPROJECT_DISOCCLUSION_SCALE,
                min = 0.5f, max = 6f, step = 0.25f,
                onChange = { v -> onConfigChanged(config.copy(OMFG_REPROJECT_DISOCCLUSION_SCALE = v)) },
            )
            OmfgAdjustRow(
                title = stringResource(R.string.omfg_reproject_hole_fill_strength),
                valueText = String.format("%.2f", config.OMFG_REPROJECT_HOLE_FILL_STRENGTH),
                value = config.OMFG_REPROJECT_HOLE_FILL_STRENGTH,
                min = 0f, max = 1f, step = 0.05f,
                onChange = { v -> onConfigChanged(config.copy(OMFG_REPROJECT_HOLE_FILL_STRENGTH = v)) },
            )
            OmfgAdjustRow(
                title = stringResource(R.string.omfg_reproject_hole_fill_radius),
                valueText = config.OMFG_REPROJECT_HOLE_FILL_RADIUS.toString(),
                value = config.OMFG_REPROJECT_HOLE_FILL_RADIUS.toFloat(),
                min = 1f, max = 6f, step = 1f,
                onChange = { v -> onConfigChanged(config.copy(OMFG_REPROJECT_HOLE_FILL_RADIUS = v.toInt())) },
            )
            OmfgAdjustRow(
                title = stringResource(R.string.omfg_reproject_gradient_confidence_weight),
                valueText = String.format("%.1f", config.OMFG_REPROJECT_GRADIENT_CONFIDENCE_WEIGHT),
                value = config.OMFG_REPROJECT_GRADIENT_CONFIDENCE_WEIGHT,
                min = 0f, max = 16f, step = 0.5f,
                onChange = { v -> onConfigChanged(config.copy(OMFG_REPROJECT_GRADIENT_CONFIDENCE_WEIGHT = v)) },
            )
            OmfgAdjustRow(
                title = stringResource(R.string.omfg_reproject_chroma_weight),
                valueText = String.format("%.2f", config.OMFG_REPROJECT_CHROMA_WEIGHT),
                value = config.OMFG_REPROJECT_CHROMA_WEIGHT,
                min = 0f, max = 1f, step = 0.05f,
                onChange = { v -> onConfigChanged(config.copy(OMFG_REPROJECT_CHROMA_WEIGHT = v)) },
            )
            OmfgAdjustRow(
                title = stringResource(R.string.omfg_reproject_ambiguity_scale),
                valueText = String.format("%.1f", config.OMFG_REPROJECT_AMBIGUITY_SCALE),
                value = config.OMFG_REPROJECT_AMBIGUITY_SCALE,
                min = 1f, max = 12f, step = 0.5f,
                onChange = { v -> onConfigChanged(config.copy(OMFG_REPROJECT_AMBIGUITY_SCALE = v)) },
            )

            Spacer(modifier = Modifier.height(16.dp))
        }

        // ── Optical Flow ──────────────────────────
        if (config.isOptflowMode()) {
            OptionSectionHeader(text = stringResource(R.string.omfg_section_optflow))

            OmfgAdjustRow(
                title = stringResource(R.string.omfg_optflow_search_radius),
                valueText = config.OMFG_OPTICAL_FLOW_SEARCH_RADIUS.toString(),
                value = config.OMFG_OPTICAL_FLOW_SEARCH_RADIUS.toFloat(),
                min = 1f, max = 6f, step = 1f,
                onChange = { v -> onConfigChanged(config.copy(OMFG_OPTICAL_FLOW_SEARCH_RADIUS = v.toInt())) },
            )
            OmfgAdjustRow(
                title = stringResource(R.string.omfg_optflow_patch_radius),
                valueText = config.OMFG_OPTICAL_FLOW_PATCH_RADIUS.toString(),
                value = config.OMFG_OPTICAL_FLOW_PATCH_RADIUS.toFloat(),
                min = 1f, max = 4f, step = 1f,
                onChange = { v -> onConfigChanged(config.copy(OMFG_OPTICAL_FLOW_PATCH_RADIUS = v.toInt())) },
            )
            OmfgAdjustRow(
                title = stringResource(R.string.omfg_optflow_levels),
                valueText = config.OMFG_OPTICAL_FLOW_LEVELS.toString(),
                value = config.OMFG_OPTICAL_FLOW_LEVELS.toFloat(),
                min = 1f, max = 5f, step = 1f,
                onChange = { v -> onConfigChanged(config.copy(OMFG_OPTICAL_FLOW_LEVELS = v.toInt())) },
            )
            OmfgAdjustRow(
                title = stringResource(R.string.omfg_optflow_confidence_scale),
                valueText = String.format("%.1f", config.OMFG_OPTICAL_FLOW_CONFIDENCE_SCALE),
                value = config.OMFG_OPTICAL_FLOW_CONFIDENCE_SCALE,
                min = 1f, max = 10f, step = 0.5f,
                onChange = { v -> onConfigChanged(config.copy(OMFG_OPTICAL_FLOW_CONFIDENCE_SCALE = v)) },
            )
            OmfgAdjustRow(
                title = stringResource(R.string.omfg_optflow_motion_penalty),
                valueText = String.format("%.3f", config.OMFG_OPTICAL_FLOW_MOTION_PENALTY),
                value = config.OMFG_OPTICAL_FLOW_MOTION_PENALTY,
                min = 0f, max = 0.1f, step = 0.001f,
                onChange = { v -> onConfigChanged(config.copy(OMFG_OPTICAL_FLOW_MOTION_PENALTY = v)) },
            )

            Spacer(modifier = Modifier.height(16.dp))
        }

        // ── BFI ───────────────────────────────────
        if (config.isBfiMode()) {
            OptionSectionHeader(text = stringResource(R.string.omfg_section_bfi))

            OmfgAdjustRow(
                title = stringResource(R.string.omfg_bfi_period),
                valueText = config.OMFG_BFI_PERIOD.toString(),
                value = config.OMFG_BFI_PERIOD.toFloat(),
                min = 1f, max = 4f, step = 1f,
                onChange = { v -> onConfigChanged(config.copy(OMFG_BFI_PERIOD = v.toInt())) },
            )
            OmfgAdjustRow(
                title = stringResource(R.string.omfg_bfi_hold_ms),
                valueText = "${config.OMFG_BFI_HOLD_MS}ms",
                value = config.OMFG_BFI_HOLD_MS.toFloat(),
                min = 1f, max = 33f, step = 1f,
                onChange = { v -> onConfigChanged(config.copy(OMFG_BFI_HOLD_MS = v.toInt())) },
            )
        }

        // ── Visual hold (bfi / copy / history-copy) ──
        if (config.isVisualMode()) {
            OmfgAdjustRow(
                title = stringResource(R.string.omfg_visual_hold_ms),
                valueText = "${config.OMFG_VISUAL_HOLD_MS}ms",
                value = config.OMFG_VISUAL_HOLD_MS.toFloat(),
                min = 1f, max = 50f, step = 1f,
                onChange = { v -> onConfigChanged(config.copy(OMFG_VISUAL_HOLD_MS = v.toInt())) },
            )
        }

        // ── Mode-specific flags ───────────────────
        if (config.isBlendMode() || config.isCopyMode() || config.isHistoryCopyMode()) {
            Spacer(modifier = Modifier.height(16.dp))
            OptionSectionHeader(text = stringResource(R.string.omfg_section_flags))

            if (config.isBlendMode()) {
                ScreenEffectToggleRow(
                    title = stringResource(R.string.omfg_blend_original_present_first),
                    subtitle = stringResource(R.string.omfg_blend_original_present_first_desc),
                    enabled = config.OMFG_BLEND_ORIGINAL_PRESENT_FIRST,
                    onToggle = { onConfigChanged(config.copy(OMFG_BLEND_ORIGINAL_PRESENT_FIRST = !config.OMFG_BLEND_ORIGINAL_PRESENT_FIRST)) },
                )
            }
            if (config.isCopyMode()) {
                ScreenEffectToggleRow(
                    title = stringResource(R.string.omfg_copy_original_present_first),
                    subtitle = stringResource(R.string.omfg_copy_original_present_first_desc),
                    enabled = config.OMFG_COPY_ORIGINAL_PRESENT_FIRST,
                    onToggle = { onConfigChanged(config.copy(OMFG_COPY_ORIGINAL_PRESENT_FIRST = !config.OMFG_COPY_ORIGINAL_PRESENT_FIRST)) },
                )
            }
            if (config.isHistoryCopyMode()) {
                ScreenEffectToggleRow(
                    title = stringResource(R.string.omfg_history_copy_freeze),
                    subtitle = stringResource(R.string.omfg_history_copy_freeze_desc),
                    enabled = config.OMFG_HISTORY_COPY_FREEZE_HISTORY,
                    onToggle = { onConfigChanged(config.copy(OMFG_HISTORY_COPY_FREEZE_HISTORY = !config.OMFG_HISTORY_COPY_FREEZE_HISTORY)) },
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ── Diagnostics ───────────────────────────
        OptionSectionHeader(text = stringResource(R.string.omfg_section_diagnostics))

        ScreenEffectToggleRow(
            title = stringResource(R.string.omfg_present_timing),
            subtitle = stringResource(R.string.omfg_present_timing_desc),
            enabled = config.OMFG_PRESENT_TIMING,
            onToggle = { onConfigChanged(config.copy(OMFG_PRESENT_TIMING = !config.OMFG_PRESENT_TIMING)) },
        )
        ScreenEffectToggleRow(
            title = stringResource(R.string.omfg_present_wait),
            subtitle = stringResource(R.string.omfg_present_wait_desc),
            enabled = config.OMFG_PRESENT_WAIT,
            onToggle = { onConfigChanged(config.copy(OMFG_PRESENT_WAIT = !config.OMFG_PRESENT_WAIT)) },
        )
        if (config.OMFG_PRESENT_WAIT) {
            OmfgAdjustRow(
                title = stringResource(R.string.omfg_present_wait_timeout),
                valueText = "${config.OMFG_PRESENT_WAIT_TIMEOUT_S}s",
                value = config.OMFG_PRESENT_WAIT_TIMEOUT_S.toFloat(),
                min = 1f, max = 15f, step = 1f,
                onChange = { v -> onConfigChanged(config.copy(OMFG_PRESENT_WAIT_TIMEOUT_S = v.toInt())) },
            )
        }
        ScreenEffectToggleRow(
            title = stringResource(R.string.omfg_benchmark),
            subtitle = stringResource(R.string.omfg_benchmark_desc),
            enabled = config.OMFG_BENCHMARK,
            onToggle = { onConfigChanged(config.copy(OMFG_BENCHMARK = !config.OMFG_BENCHMARK)) },
        )

        Spacer(modifier = Modifier.height(16.dp))

        // ── Startup-scoped (requires restart) ────
        OptionSectionHeader(text = stringResource(R.string.omfg_section_startup))

        OmfgAdjustRow(
            title = stringResource(R.string.omfg_swapchain_bump),
            valueText = if (config.OMFG_SWAPCHAIN_IMAGE_BUMP_OVERRIDE == 0) "Auto" else config.OMFG_SWAPCHAIN_IMAGE_BUMP_OVERRIDE.toString(),
            value = config.OMFG_SWAPCHAIN_IMAGE_BUMP_OVERRIDE.toFloat(),
            min = 0f, max = 4f, step = 1f,
            onChange = { v -> onConfigChanged(config.copy(OMFG_SWAPCHAIN_IMAGE_BUMP_OVERRIDE = v.toInt())) },
        )
        ScreenEffectToggleRow(
            title = stringResource(R.string.omfg_device_debug),
            subtitle = stringResource(R.string.omfg_device_debug_desc),
            enabled = config.OMFG_CREATE_DEVICE_DEBUG,
            onToggle = { onConfigChanged(config.copy(OMFG_CREATE_DEVICE_DEBUG = !config.OMFG_CREATE_DEVICE_DEBUG)) },
        )
        ScreenEffectToggleRow(
            title = stringResource(R.string.omfg_append_timing_ext),
            subtitle = stringResource(R.string.omfg_append_timing_ext_desc),
            enabled = config.OMFG_CREATE_DEVICE_APPEND_TIMING_EXTENSIONS,
            onToggle = { onConfigChanged(config.copy(OMFG_CREATE_DEVICE_APPEND_TIMING_EXTENSIONS = !config.OMFG_CREATE_DEVICE_APPEND_TIMING_EXTENSIONS)) },
        )
        ScreenEffectToggleRow(
            title = stringResource(R.string.omfg_append_timing_feat),
            subtitle = stringResource(R.string.omfg_append_timing_feat_desc),
            enabled = config.OMFG_CREATE_DEVICE_APPEND_TIMING_FEATURES,
            onToggle = { onConfigChanged(config.copy(OMFG_CREATE_DEVICE_APPEND_TIMING_FEATURES = !config.OMFG_CREATE_DEVICE_APPEND_TIMING_FEATURES)) },
        )

        Spacer(modifier = Modifier.height(20.dp))

        // ── Reset ─────────────────────────────────
        ScreenEffectActionRow(
            title = stringResource(R.string.omfg_reset_defaults),
            icon = Icons.Default.RestartAlt,
            accentColor = PluviaTheme.colors.accentPurple,
            onClick = { onConfigChanged(OmfgConfig.DEFAULTS) },
        )

        Spacer(modifier = Modifier.height(24.dp))
    }
}

// ---------------------------------------------------------------------------
// Internal composables reusing the same visual style as ScreenEffectsPanel
// ---------------------------------------------------------------------------

/**
 * A slider-style adjustment row for numeric OMFG values.
 * Uses the same visual pattern as [ScreenEffectAdjustmentRow].
 */
@Composable
private fun OmfgAdjustRow(
    title: String,
    valueText: String,
    value: Float,
    min: Float,
    max: Float,
    step: Float,
    onChange: (Float) -> Unit,
    focusRequester: FocusRequester? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val accentColor = PluviaTheme.colors.accentPurple
    val shape = RoundedCornerShape(14.dp)
    var isAdjustmentLocked by remember { mutableStateOf(false) }
    val progress = ((value - min) / (max - min)).coerceIn(0f, 1f)

    Column(
        modifier = Modifier
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(shape)
            .background(
                if (isFocused) {
                    Brush.horizontalGradient(
                        colors = listOf(
                            accentColor.copy(alpha = 0.16f),
                            accentColor.copy(alpha = 0.08f),
                        ),
                    )
                } else {
                    Brush.horizontalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f),
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.10f),
                        ),
                    )
                },
            )
            .then(
                if (isFocused && !isAdjustmentLocked) {
                    Modifier.border(
                        width = 2.dp,
                        color = accentColor.copy(alpha = 0.7f),
                        shape = shape,
                    )
                } else {
                    Modifier
                },
            )
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                },
            )
            .onFocusChanged {
                if (!it.isFocused) {
                    isAdjustmentLocked = false
                }
            }
            .focusable(interactionSource = interactionSource)
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN && isFocused) {
                    when {
                        keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BUTTON_A -> {
                            isAdjustmentLocked = !isAdjustmentLocked
                            true
                        }
                        isAdjustmentLocked && keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BUTTON_B -> {
                            isAdjustmentLocked = false
                            true
                        }
                        isAdjustmentLocked && keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> {
                            onChange((value - step).coerceIn(min, max))
                            true
                        }
                        isAdjustmentLocked && keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            onChange((value + step).coerceIn(min, max))
                            true
                        }
                        else -> false
                    }
                } else {
                    false
                }
            }
            .selectable(
                selected = isFocused,
                interactionSource = interactionSource,
                indication = null,
                onClick = {},
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Medium,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = valueText,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isFocused) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (isAdjustmentLocked) {
                    Text(
                        text = "●",
                        style = MaterialTheme.typography.labelSmall,
                        color = accentColor,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OmfgAdjustButton(
                text = "−",
                rowIsFocused = isFocused,
                isAdjustmentLocked = isAdjustmentLocked,
                accentColor = accentColor,
                onClick = { onChange((value - step).coerceIn(min, max)) },
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(999.dp)),
                    color = accentColor,
                    trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                )

                Row(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { onChange((value - step).coerceIn(min, max)) },
                            ),
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { onChange((value + step).coerceIn(min, max)) },
                            ),
                    )
                }
            }

            OmfgAdjustButton(
                text = "+",
                rowIsFocused = isFocused,
                isAdjustmentLocked = isAdjustmentLocked,
                accentColor = accentColor,
                onClick = { onChange((value + step).coerceIn(min, max)) },
            )
        }
    }
}

@Composable
private fun OmfgAdjustButton(
    text: String,
    rowIsFocused: Boolean,
    isAdjustmentLocked: Boolean,
    accentColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .width(44.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (isAdjustmentLocked) {
                    accentColor.copy(alpha = 0.25f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (rowIsFocused) 0.32f else 0.45f)
                },
            )
            .border(
                width = if (isAdjustmentLocked) 2.dp else 1.dp,
                color = if (isAdjustmentLocked) {
                    accentColor.copy(alpha = 0.9f)
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                },
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (isAdjustmentLocked) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A dropdown selector row for picking from a list of string options.
 * Tap/click/A to open a dropdown menu with all options.
 * The currently selected value is shown with a chevron indicator.
 */
@Composable
private fun OmfgDropdownRow(
    title: String,
    selectedValue: String,
    options: List<String>,
    onOptionSelected: (String) -> Unit,
    focusRequester: FocusRequester? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val accentColor = PluviaTheme.colors.accentPurple
    val shape = RoundedCornerShape(14.dp)

    Box(
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .clip(shape)
                .background(
                    if (isFocused) {
                        Brush.horizontalGradient(
                            colors = listOf(
                                accentColor.copy(alpha = 0.16f),
                                accentColor.copy(alpha = 0.08f),
                            ),
                        )
                    } else {
                        Brush.horizontalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f),
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.10f),
                            ),
                        )
                    },
                )
                .then(
                    if (isFocused) {
                        Modifier.border(
                            width = 2.dp,
                            color = accentColor.copy(alpha = 0.7f),
                            shape = shape,
                        )
                    } else {
                        Modifier
                    },
                )
                .then(
                    if (focusRequester != null) {
                        Modifier.focusRequester(focusRequester)
                    } else {
                        Modifier
                    },
                )
                .focusable(interactionSource = interactionSource)
                .onPreviewKeyEvent { keyEvent ->
                    if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN && isFocused) {
                        when (keyEvent.nativeKeyEvent.keyCode) {
                            KeyEvent.KEYCODE_BUTTON_A,
                            KeyEvent.KEYCODE_DPAD_RIGHT,
                            KeyEvent.KEYCODE_DPAD_LEFT -> {
                                expanded = true
                                true
                            }
                            else -> false
                        }
                    } else {
                        false
                    }
                }
                .selectable(
                    selected = isFocused,
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = { expanded = true },
                )
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Medium,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = selectedValue,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isFocused) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (isFocused) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .background(
                    MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(12.dp),
                ),
        ) {
            options.forEachIndexed { index, option ->
                val isSelected = option == selectedValue
                DropdownMenuItem(
                    text = {
                        Text(
                            text = option,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) accentColor else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    onClick = {
                        onOptionSelected(option)
                        expanded = false
                    },
                    modifier = Modifier.then(
                        if (isSelected) {
                            Modifier.background(
                                accentColor.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(8.dp),
                            )
                        } else {
                            Modifier
                        },
                    ),
                )
                if (index < options.lastIndex) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f),
                    )
                }
            }
        }
    }
}
