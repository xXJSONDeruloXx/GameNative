package app.gamenative.ui.component

import android.app.ActivityManager
import android.content.Context
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.ui.data.PerformanceHudConfig
import app.gamenative.ui.data.PerformanceHudSize
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.util.adaptivePanelWidth
import app.gamenative.utils.MathUtils.normalizedProgress
import com.winlator.renderer.GLRenderer
import com.winlator.winhandler.ProcessInfo
import com.winlator.winhandler.WinHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

object QuickMenuAction {
    const val KEYBOARD = 1
    const val INPUT_CONTROLS = 2
    const val EXIT_GAME = 3
    const val EDIT_CONTROLS = 4
    const val EDIT_PHYSICAL_CONTROLLER = 5
    const val PERFORMANCE_HUD = 6
}

private object QuickMenuTab {
    const val HUD = 0
    const val EFFECTS = 1
    const val CONTROLLER = 2
    const val PROCESSES = 3
}

data class QuickMenuItem(
    val id: Int,
    val icon: ImageVector,
    val labelResId: Int,
    val accentColor: Color = Color.Unspecified,
    val enabled: Boolean = true,
)

private enum class PerformanceHudPreset(val labelResId: Int) {
    FPS_ONLY(R.string.performance_hud_preset_fps_only),
    ESSENTIAL(R.string.performance_hud_preset_essential),
    BATTERY(R.string.performance_hud_preset_battery),
    FULL(R.string.performance_hud_preset_full),
}

private fun applyPerformanceHudPreset(
    currentConfig: PerformanceHudConfig,
    preset: PerformanceHudPreset,
): PerformanceHudConfig {
    return when (preset) {
        PerformanceHudPreset.FPS_ONLY -> currentConfig.copy(
            showFrameRate = true,
            showCpuUsage = false,
            showGpuUsage = false,
            showRamUsage = false,
            showBatteryLevel = false,
            showPowerDraw = false,
            showBatteryRuntime = false,
            showBatteryTemperature = false,
            showClockTime = false,
            showCpuTemperature = false,
            showGpuTemperature = false,
            showFrameRateGraph = false,
            showCpuUsageGraph = false,
            showGpuUsageGraph = false,
        )

        PerformanceHudPreset.ESSENTIAL -> currentConfig.copy(
            showFrameRate = true,
            showCpuUsage = true,
            showGpuUsage = true,
            showRamUsage = true,
            showBatteryLevel = false,
            showPowerDraw = false,
            showBatteryRuntime = false,
            showBatteryTemperature = false,
            showClockTime = false,
            showCpuTemperature = false,
            showGpuTemperature = false,
            showFrameRateGraph = false,
            showCpuUsageGraph = false,
            showGpuUsageGraph = false,
        )

        PerformanceHudPreset.BATTERY -> currentConfig.copy(
            showFrameRate = true,
            showCpuUsage = true,
            showGpuUsage = true,
            showRamUsage = true,
            showBatteryLevel = true,
            showPowerDraw = false,
            showBatteryRuntime = true,
            showBatteryTemperature = true,
            showClockTime = false,
            showCpuTemperature = false,
            showGpuTemperature = false,
            showFrameRateGraph = true,
            showCpuUsageGraph = false,
            showGpuUsageGraph = false,
        )

        PerformanceHudPreset.FULL -> currentConfig.copy(
            showFrameRate = true,
            showCpuUsage = true,
            showGpuUsage = true,
            showRamUsage = true,
            showBatteryLevel = true,
            showPowerDraw = true,
            showBatteryRuntime = true,
            showBatteryTemperature = true,
            showClockTime = true,
            showCpuTemperature = true,
            showGpuTemperature = true,
            showFrameRateGraph = true,
            showCpuUsageGraph = true,
            showGpuUsageGraph = true,
        )
    }
}

private fun matchesPerformanceHudPreset(
    currentConfig: PerformanceHudConfig,
    preset: PerformanceHudPreset,
): Boolean {
    val presetConfig = applyPerformanceHudPreset(currentConfig, preset)
    return currentConfig.showFrameRate == presetConfig.showFrameRate &&
        currentConfig.showCpuUsage == presetConfig.showCpuUsage &&
        currentConfig.showGpuUsage == presetConfig.showGpuUsage &&
        currentConfig.showRamUsage == presetConfig.showRamUsage &&
        currentConfig.showBatteryLevel == presetConfig.showBatteryLevel &&
        currentConfig.showPowerDraw == presetConfig.showPowerDraw &&
        currentConfig.showBatteryRuntime == presetConfig.showBatteryRuntime &&
        currentConfig.showBatteryTemperature == presetConfig.showBatteryTemperature &&
        currentConfig.showClockTime == presetConfig.showClockTime &&
        currentConfig.showCpuTemperature == presetConfig.showCpuTemperature &&
        currentConfig.showGpuTemperature == presetConfig.showGpuTemperature &&
        currentConfig.showFrameRateGraph == presetConfig.showFrameRateGraph &&
        currentConfig.showCpuUsageGraph == presetConfig.showCpuUsageGraph &&
        currentConfig.showGpuUsageGraph == presetConfig.showGpuUsageGraph
}

@Composable
fun QuickMenu(
    isVisible: Boolean,
    onDismiss: () -> Unit,
    onItemSelected: (Int) -> Boolean,
    renderer: GLRenderer? = null,
    winHandler: WinHandler? = null,
    isPerformanceHudEnabled: Boolean = false,
    performanceHudConfig: PerformanceHudConfig = PerformanceHudConfig(),
    onPerformanceHudConfigChanged: (PerformanceHudConfig) -> Unit = {},
    hasPhysicalController: Boolean = false,
    activeToggleIds: Set<Int> = emptySet(),
    modifier: Modifier = Modifier,
) {
    val exitGameItem = QuickMenuItem(
        id = QuickMenuAction.EXIT_GAME,
        icon = Icons.AutoMirrored.Filled.ExitToApp,
        labelResId = R.string.exit_game,
        accentColor = PluviaTheme.colors.accentDanger,
    )

    val controllerItems = buildList {
        add(
            QuickMenuItem(
                id = QuickMenuAction.KEYBOARD,
                icon = Icons.Default.Keyboard,
                labelResId = R.string.keyboard,
                accentColor = PluviaTheme.colors.accentCyan,
            )
        )
        add(
            QuickMenuItem(
                id = QuickMenuAction.INPUT_CONTROLS,
                icon = Icons.Default.TouchApp,
                labelResId = R.string.input_controls,
                accentColor = PluviaTheme.colors.accentPurple,
            )
        )
        if (hasPhysicalController) {
            add(
                QuickMenuItem(
                    id = QuickMenuAction.EDIT_PHYSICAL_CONTROLLER,
                    icon = Icons.Default.Gamepad,
                    labelResId = R.string.edit_physical_controller,
                    accentColor = PluviaTheme.colors.accentWarning,
                )
            )
        }
        add(
            QuickMenuItem(
                id = QuickMenuAction.EDIT_CONTROLS,
                icon = Icons.Default.Edit,
                labelResId = R.string.edit_controls,
                accentColor = PluviaTheme.colors.accentSuccess,
            )
        )
    }

    var selectedTab by rememberSaveable(isVisible) { mutableIntStateOf(QuickMenuTab.HUD) }
    val selectedTabLabelResId = when (selectedTab) {
        QuickMenuTab.HUD -> R.string.performance_hud
        QuickMenuTab.EFFECTS -> R.string.screen_effects
        QuickMenuTab.PROCESSES -> R.string.quick_menu_tab_processes
        else -> R.string.quick_menu_tab_controller
    }

    val hudScrollState = rememberScrollState()
    val effectsScrollState = rememberScrollState()
    val effectsTabFocusRequester = remember { FocusRequester() }
    val controllerScrollState = rememberScrollState()
    val hudTabFocusRequester = remember { FocusRequester() }
    val controllerTabFocusRequester = remember { FocusRequester() }
    val processesTabFocusRequester = remember { FocusRequester() }
    val hudItemFocusRequester = remember { FocusRequester() }
    val effectsItemFocusRequester = remember { FocusRequester() }
    val controllerItemFocusRequester = remember { FocusRequester() }
    val processesItemFocusRequester = remember { FocusRequester() }

    BackHandler(enabled = isVisible) {
        onDismiss()
    }

    Box(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(150)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
        }

        AnimatedVisibility(
            visible = isVisible,
            enter = slideInHorizontally(
                initialOffsetX = { fullWidth -> -fullWidth },
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            ),
            exit = slideOutHorizontally(
                targetOffsetX = { fullWidth -> -fullWidth },
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
            ),
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            Surface(
                modifier = Modifier
                    .width(adaptivePanelWidth(400.dp))
                    .fillMaxHeight(),
                shape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                shadowElevation = 24.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = stringResource(R.string.quick_menu_title),
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.SemiBold,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        QuickMenuCloseButton(onClick = onDismiss)
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .width(64.dp)
                                .fillMaxHeight()
                                .focusGroup(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                QuickMenuTabButton(
                                    icon = Icons.Default.QueryStats,
                                    contentDescriptionResId = R.string.performance_hud,
                                    selected = selectedTab == QuickMenuTab.HUD,
                                    accentColor = PluviaTheme.colors.accentPurple,
                                    onSelected = { selectedTab = QuickMenuTab.HUD },
                                    modifier = Modifier.width(56.dp),
                                    focusRequester = hudTabFocusRequester,
                                )
                                QuickMenuTabButton(
                                    icon = Icons.Default.AutoFixHigh,
                                    contentDescriptionResId = R.string.screen_effects,
                                    selected = selectedTab == QuickMenuTab.EFFECTS,
                                    accentColor = PluviaTheme.colors.accentPurple,
                                    onSelected = { selectedTab = QuickMenuTab.EFFECTS },
                                    modifier = Modifier.width(56.dp),
                                    focusRequester = effectsTabFocusRequester,
                                )
                                QuickMenuTabButton(
                                    icon = Icons.Default.Gamepad,
                                    contentDescriptionResId = R.string.quick_menu_tab_controller,
                                    selected = selectedTab == QuickMenuTab.CONTROLLER,
                                    accentColor = PluviaTheme.colors.accentPurple,
                                    onSelected = { selectedTab = QuickMenuTab.CONTROLLER },
                                    modifier = Modifier.width(56.dp),
                                    focusRequester = controllerTabFocusRequester,
                                )
                                QuickMenuTabButton(
                                    icon = Icons.Default.Memory,
                                    contentDescriptionResId = R.string.quick_menu_tab_processes,
                                    selected = selectedTab == QuickMenuTab.PROCESSES,
                                    accentColor = PluviaTheme.colors.accentPurple,
                                    onSelected = { selectedTab = QuickMenuTab.PROCESSES },
                                    modifier = Modifier.width(56.dp),
                                    focusRequester = processesTabFocusRequester,
                                )
                            }

                            Spacer(modifier = Modifier.weight(1f))

                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 4.dp, vertical = 12.dp)
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)),
                            )

                            QuickMenuRailActionButton(
                                item = exitGameItem,
                                onClick = {
                                    if (onItemSelected(QuickMenuAction.EXIT_GAME)) {
                                        onDismiss()
                                    }
                                },
                                modifier = Modifier.width(56.dp),
                            )
                        }

                        Box(
                            modifier = Modifier
                                .padding(horizontal = 12.dp)
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)),
                        )

                        Column(
                            modifier = Modifier
                                .fillMaxSize(),
                        ) {
                            Text(
                                text = stringResource(selectedTabLabelResId),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )

                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                            )

                            Box(
                                modifier = Modifier.weight(1f),
                            ) {
                                when (selectedTab) {
                                    QuickMenuTab.HUD -> {
                                        PerformanceHudQuickMenuTab(
                                            isPerformanceHudEnabled = isPerformanceHudEnabled,
                                            performanceHudConfig = performanceHudConfig,
                                            onTogglePerformanceHud = {
                                                onItemSelected(QuickMenuAction.PERFORMANCE_HUD)
                                            },
                                            onPerformanceHudConfigChanged = onPerformanceHudConfigChanged,
                                            scrollState = hudScrollState,
                                            focusRequester = hudItemFocusRequester,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }

                                    QuickMenuTab.EFFECTS -> {
                                        if (renderer != null) {
                                            ScreenEffectsTabContent(
                                                renderer = renderer,
                                                modifier = Modifier.fillMaxSize(),
                                                firstItemFocusRequester = effectsItemFocusRequester,
                                                scrollState = effectsScrollState,
                                            )
                                        } else {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .padding(horizontal = 8.dp, vertical = 16.dp),
                                                contentAlignment = Alignment.TopStart,
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.main_loading),
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    }

                                    QuickMenuTab.PROCESSES -> {
                                        ProcessesQuickMenuTab(
                                            winHandler = winHandler,
                                            modifier = Modifier.fillMaxSize(),
                                            firstItemFocusRequester = processesItemFocusRequester,
                                        )
                                    }

                                    else -> {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .verticalScroll(controllerScrollState)
                                                .focusGroup(),
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            controllerItems.forEachIndexed { index, item ->
                                                QuickMenuItemRow(
                                                    item = item,
                                                    isActive = item.id in activeToggleIds,
                                                    onClick = {
                                                        if (onItemSelected(item.id)) {
                                                            onDismiss()
                                                        }
                                                    },
                                                    focusRequester = if (index == 0) controllerItemFocusRequester else null,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(isVisible) {
        if (isVisible) {
            repeat(3) {
                try {
                    hudItemFocusRequester.requestFocus()
                    return@LaunchedEffect
                } catch (_: Exception) {
                    delay(80)
                }
            }
        }
    }
}

@Composable
private fun ProcessesQuickMenuTab(
    winHandler: WinHandler?,
    modifier: Modifier = Modifier,
    firstItemFocusRequester: FocusRequester? = null,
) {
    val context = LocalContext.current
    val accentColor = PluviaTheme.colors.accentCyan
    val scrollState = rememberScrollState()

    var processes by remember { mutableStateOf<List<ProcessInfo>>(emptyList()) }
    var hasReceivedData by remember { mutableStateOf(false) }
    var memoryInfo by remember { mutableStateOf<ActivityManager.MemoryInfo?>(null) }
    var killConfirmProcess by remember { mutableStateOf<ProcessInfo?>(null) }

    // Read processes directly from /proc — GameNative's winhandler.exe does not respond
    // to the LIST_PROCESSES UDP request, so we bypass WinHandler entirely here.
    LaunchedEffect(Unit) {
        while (isActive) {
            val procs = withContext(Dispatchers.IO) { readWineProcesses() }
            processes = procs
            hasReceivedData = true

            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            memoryInfo = mi

            delay(1_500)
        }
    }

    // Kill-confirm dialog
    killConfirmProcess?.let { target ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { killConfirmProcess = null },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        android.os.Process.sendSignal(target.pid, 15) // SIGTERM
                        killConfirmProcess = null
                    },
                ) {
                    Text(
                        text = stringResource(R.string.processes_kill),
                        color = PluviaTheme.colors.accentDanger,
                    )
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { killConfirmProcess = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
            title = {
                Text(
                    text = stringResource(R.string.processes_kill),
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.processes_kill_confirm, target.name),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }

    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .focusGroup()
            .padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // System memory card
        memoryInfo?.let { mi ->
            val usedMem = mi.totalMem - mi.availMem
            val usedGb = usedMem / (1024f * 1024 * 1024)
            val totalGb = mi.totalMem / (1024f * 1024 * 1024)
            val memPercent = ((usedMem.toFloat() / mi.totalMem) * 100).roundToInt()
            val memProgress = (usedMem.toFloat() / mi.totalMem).coerceIn(0f, 1f)

            Column(
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                accentColor.copy(alpha = 0.10f),
                                accentColor.copy(alpha = 0.05f),
                            ),
                        ),
                    )
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.processes_system_memory),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "%.1f / %.1f GB".format(usedGb, totalGb),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = "%d%%".format(memPercent),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = accentColor,
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { memProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(999.dp)),
                    color = accentColor,
                    trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        QuickMenuSectionHeader(
            title = if (!hasReceivedData) {
                stringResource(R.string.quick_menu_tab_processes)
            } else {
                stringResource(R.string.processes_count, processes.size)
            },
        )

        if (!hasReceivedData) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 16.dp),
                contentAlignment = Alignment.TopStart,
            ) {
                Text(
                    text = if (winHandler == null) {
                        stringResource(R.string.processes_empty)
                    } else {
                        stringResource(R.string.main_loading)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            processes.forEachIndexed { index, process ->
                QuickMenuProcessRow(
                    process = process,
                    accentColor = accentColor,
                    onBringToFront = { winHandler?.bringToFront(process.name) },
                    onKill = { killConfirmProcess = process },
                    focusRequester = if (index == 0) firstItemFocusRequester else null,
                )
            }
        }
    }
}

private fun readWineProcesses(): List<ProcessInfo> {
    val procDir = File("/proc")
    val pidDirs = try {
        procDir.listFiles { _, name -> name.all { it.isDigit() } } ?: return emptyList()
    } catch (e: Exception) {
        return emptyList()
    }

    val result = mutableListOf<ProcessInfo>()
    for (pidDir in pidDirs) {
        try {
            val pid = pidDir.name.toIntOrNull() ?: continue

            // Quick filter: skip non-wine/exe processes
            val comm = try { File(pidDir, "comm").readText().trim() } catch (e: Exception) { continue }
            if (!comm.contains("wine", ignoreCase = true) && !comm.endsWith("exe", ignoreCase = true)) continue

            // Try to get the full process name from cmdline (wine64-preloader wraps the real .exe)
            val name = try {
                val bytes = File(pidDir, "cmdline").readBytes()
                val args = mutableListOf<String>()
                var start = 0
                for (i in bytes.indices) {
                    if (bytes[i] == 0.toByte()) {
                        val s = String(bytes, start, i - start).trim()
                        if (s.isNotBlank()) args.add(s)
                        start = i + 1
                    }
                }
                val exeArg = args.firstOrNull { it.endsWith(".exe", ignoreCase = true) }
                    ?: args.firstOrNull { it.contains("wine", ignoreCase = true) }
                    ?: args.firstOrNull()
                exeArg
                    ?.substringAfterLast('/')
                    ?.substringAfterLast('\\')
                    ?.ifBlank { comm }
                    ?: comm
            } catch (e: Exception) {
                comm
            }

            // Read resident memory (VmRSS) from /proc/<pid>/status
            val memBytes = try {
                var vmRss = 0L
                File(pidDir, "status").forEachLine { line ->
                    if (line.startsWith("VmRSS:")) {
                        vmRss = line.trim().split("\\s+".toRegex()).getOrNull(1)?.toLongOrNull() ?: 0L
                    }
                }
                vmRss * 1024L
            } catch (e: Exception) {
                0L
            }

            result.add(ProcessInfo(pid, name, memBytes, 0, false))
        } catch (e: Exception) {
            // Process exited during enumeration
        }
    }
    return result.sortedByDescending { it.memoryUsage }
}

@Composable
private fun QuickMenuProcessRow(
    process: ProcessInfo,
    accentColor: Color,
    onBringToFront: () -> Unit,
    onKill: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val dangerColor = PluviaTheme.colors.accentDanger
    val shape = RoundedCornerShape(12.dp)
    val processName = process.name + if (process.wow64Process) " *32" else ""

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .then(
                if (isFocused) {
                    Modifier.border(
                        BorderStroke(
                            2.dp,
                            Brush.verticalGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.tertiary,
                                ),
                            ),
                        ),
                        shape,
                    )
                } else {
                    Modifier
                },
            )
            .clip(shape)
            .background(
                if (isFocused) {
                    Brush.horizontalGradient(
                        colors = listOf(
                            accentColor.copy(alpha = 0.12f),
                            accentColor.copy(alpha = 0.04f),
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
                if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier,
            )
            .focusable(interactionSource = interactionSource)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Name + PID
        Column(modifier = Modifier.weight(1f).padding(end = 4.dp)) {
            Text(
                text = processName,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isFocused) accentColor else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Clip,
                modifier = Modifier.basicMarquee(),
            )
            Text(
                text = stringResource(R.string.processes_pid, process.pid),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Memory usage
        Text(
            text = process.getFormattedMemoryUsage(),
            style = MaterialTheme.typography.labelMedium,
            color = if (isFocused) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Actions
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            QuickMenuProcessActionButton(
                icon = Icons.Default.OpenInFull,
                contentDescription = stringResource(R.string.processes_bring_to_front),
                tint = accentColor,
                onClick = onBringToFront,
            )
            QuickMenuProcessActionButton(
                icon = Icons.Default.Close,
                contentDescription = stringResource(R.string.processes_kill),
                tint = dangerColor,
                onClick = onKill,
            )
        }
    }
}

@Composable
private fun QuickMenuProcessActionButton(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isFocused) tint.copy(alpha = 0.18f)
                else tint.copy(alpha = 0.08f),
            )
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = if (isFocused) tint.copy(alpha = 0.7f) else tint.copy(alpha = 0.25f),
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .focusable(interactionSource = interactionSource),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (isFocused) tint else tint.copy(alpha = 0.8f),
            modifier = Modifier.size(16.dp),
        )
    }
}


@Composable
private fun PerformanceHudQuickMenuTab(
    isPerformanceHudEnabled: Boolean,
    performanceHudConfig: PerformanceHudConfig,
    onTogglePerformanceHud: () -> Unit,
    onPerformanceHudConfigChanged: (PerformanceHudConfig) -> Unit,
    scrollState: ScrollState,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    val accentColor = PluviaTheme.colors.accentPurple

    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud),
            subtitle = stringResource(R.string.performance_hud_description),
            enabled = isPerformanceHudEnabled,
            onToggle = onTogglePerformanceHud,
            accentColor = accentColor,
            focusRequester = focusRequester,
        )

        Spacer(modifier = Modifier.height(8.dp))

        QuickMenuSectionHeader(
            title = stringResource(R.string.performance_hud_presets),
        )

        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PerformanceHudPreset.values().forEach { preset ->
                QuickMenuChoiceChip(
                    text = stringResource(preset.labelResId),
                    selected = matchesPerformanceHudPreset(performanceHudConfig, preset),
                    accentColor = accentColor,
                    onClick = {
                        onPerformanceHudConfigChanged(applyPerformanceHudPreset(performanceHudConfig, preset))
                        if (!isPerformanceHudEnabled) {
                            onTogglePerformanceHud()
                        }
                    },
                    modifier = Modifier.width(56.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        QuickMenuSectionHeader(
            title = stringResource(R.string.performance_hud_appearance),
        )

        Text(
            text = stringResource(R.string.performance_hud_size),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )

        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                PerformanceHudSize.SMALL to R.string.performance_hud_size_small,
                PerformanceHudSize.MEDIUM to R.string.performance_hud_size_medium,
                PerformanceHudSize.LARGE to R.string.performance_hud_size_large,
            ).forEach { (size, labelResId) ->
                QuickMenuChoiceChip(
                    text = stringResource(labelResId),
                    selected = performanceHudConfig.size == size,
                    accentColor = accentColor,
                    onClick = {
                        onPerformanceHudConfigChanged(performanceHudConfig.copy(size = size))
                    },
                    modifier = Modifier.width(56.dp),
                )
            }
        }

        QuickMenuAdjustmentRow(
            title = stringResource(R.string.performance_hud_background_opacity),
            valueText = stringResource(
                R.string.performance_hud_percentage_value,
                (performanceHudConfig.backgroundOpacity * 100f).roundToInt(),
            ),
            progress = normalizedProgress(performanceHudConfig.backgroundOpacity, 0f, 1f),
            onDecrease = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(
                        backgroundOpacity = (performanceHudConfig.backgroundOpacity - 0.05f).coerceIn(0f, 1f),
                    ),
                )
            },
            onIncrease = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(
                        backgroundOpacity = (performanceHudConfig.backgroundOpacity + 0.05f).coerceIn(0f, 1f),
                    ),
                )
            },
            accentColor = accentColor,
        )

        QuickMenuAdjustmentRow(
            title = stringResource(R.string.performance_hud_color_intensity),
            valueText = stringResource(
                R.string.performance_hud_percentage_value,
                (performanceHudConfig.colorIntensity * 100f).roundToInt(),
            ),
            progress = normalizedProgress(performanceHudConfig.colorIntensity, 0f, 1f),
            onDecrease = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(
                        colorIntensity = (performanceHudConfig.colorIntensity - 0.05f).coerceIn(0f, 1f),
                    ),
                )
            },
            onIncrease = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(
                        colorIntensity = (performanceHudConfig.colorIntensity + 0.05f).coerceIn(0f, 1f),
                    ),
                )
            },
            accentColor = accentColor,
        )

        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud_text_outline),
            enabled = performanceHudConfig.showTextOutline,
            onToggle = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(showTextOutline = !performanceHudConfig.showTextOutline),
                )
            },
            accentColor = accentColor,
        )

        Spacer(modifier = Modifier.height(8.dp))

        QuickMenuSectionHeader(
            title = stringResource(R.string.performance_hud_metrics),
        )

        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud_frame_rate),
            enabled = performanceHudConfig.showFrameRate,
            onToggle = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(showFrameRate = !performanceHudConfig.showFrameRate),
                )
            },
            accentColor = accentColor,
        )
        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud_frame_rate_graph),
            enabled = performanceHudConfig.showFrameRateGraph,
            onToggle = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(showFrameRateGraph = !performanceHudConfig.showFrameRateGraph),
                )
            },
            accentColor = accentColor,
        )
        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud_cpu_usage),
            enabled = performanceHudConfig.showCpuUsage,
            onToggle = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(showCpuUsage = !performanceHudConfig.showCpuUsage),
                )
            },
            accentColor = accentColor,
        )
        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud_cpu_usage_graph),
            enabled = performanceHudConfig.showCpuUsageGraph,
            onToggle = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(showCpuUsageGraph = !performanceHudConfig.showCpuUsageGraph),
                )
            },
            accentColor = accentColor,
        )
        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud_gpu_usage),
            enabled = performanceHudConfig.showGpuUsage,
            onToggle = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(showGpuUsage = !performanceHudConfig.showGpuUsage),
                )
            },
            accentColor = accentColor,
        )
        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud_gpu_usage_graph),
            enabled = performanceHudConfig.showGpuUsageGraph,
            onToggle = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(showGpuUsageGraph = !performanceHudConfig.showGpuUsageGraph),
                )
            },
            accentColor = accentColor,
        )
        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud_ram_usage),
            enabled = performanceHudConfig.showRamUsage,
            onToggle = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(showRamUsage = !performanceHudConfig.showRamUsage),
                )
            },
            accentColor = accentColor,
        )
        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud_battery_level),
            enabled = performanceHudConfig.showBatteryLevel,
            onToggle = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(showBatteryLevel = !performanceHudConfig.showBatteryLevel),
                )
            },
            accentColor = accentColor,
        )
        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud_power_draw),
            enabled = performanceHudConfig.showPowerDraw,
            onToggle = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(showPowerDraw = !performanceHudConfig.showPowerDraw),
                )
            },
            accentColor = accentColor,
        )
        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud_runtime_left),
            enabled = performanceHudConfig.showBatteryRuntime,
            onToggle = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(showBatteryRuntime = !performanceHudConfig.showBatteryRuntime),
                )
            },
            accentColor = accentColor,
        )
        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud_battery_temperature),
            enabled = performanceHudConfig.showBatteryTemperature,
            onToggle = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(showBatteryTemperature = !performanceHudConfig.showBatteryTemperature),
                )
            },
            accentColor = accentColor,
        )
        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud_clock_time),
            enabled = performanceHudConfig.showClockTime,
            onToggle = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(showClockTime = !performanceHudConfig.showClockTime),
                )
            },
            accentColor = accentColor,
        )
        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud_cpu_temperature),
            enabled = performanceHudConfig.showCpuTemperature,
            onToggle = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(showCpuTemperature = !performanceHudConfig.showCpuTemperature),
                )
            },
            accentColor = accentColor,
        )
        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud_gpu_temperature),
            enabled = performanceHudConfig.showGpuTemperature,
            onToggle = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(showGpuTemperature = !performanceHudConfig.showGpuTemperature),
                )
            },
            accentColor = accentColor,
        )

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun QuickMenuSectionHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
        if (!subtitle.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun QuickMenuCloseButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(14.dp)

    Box(
        modifier = modifier
            .size(44.dp)
            .then(
                if (isFocused) {
                    Modifier.border(
                        BorderStroke(
                            2.dp,
                            Brush.verticalGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.tertiary,
                                ),
                            ),
                        ),
                        shape,
                    )
                } else {
                    Modifier
                }
            )
            .clip(shape)
            .background(
                if (isFocused) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                } else {
                    Color.Transparent
                },
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .focusable(interactionSource = interactionSource),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Close,
            contentDescription = stringResource(R.string.quick_menu_back),
            tint = if (isFocused) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun QuickMenuTabButton(
    icon: ImageVector,
    contentDescriptionResId: Int,
    selected: Boolean,
    accentColor: Color,
    onSelected: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(14.dp)

    Box(
        modifier = modifier
            .size(56.dp)
            .then(
                if (isFocused) {
                    Modifier.border(
                        BorderStroke(
                            2.dp,
                            Brush.verticalGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.tertiary,
                                ),
                            ),
                        ),
                        shape,
                    )
                } else {
                    Modifier
                }
            )
            .clip(shape)
            .background(
                when {
                    selected -> accentColor.copy(alpha = 0.18f)
                    isFocused -> accentColor.copy(alpha = 0.12f)
                    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                },
            )
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                }
            )
            .onFocusChanged {
                if (it.isFocused && !selected) {
                    onSelected()
                }
            }
            .selectable(
                selected = selected,
                interactionSource = interactionSource,
                indication = null,
                onClick = onSelected,
            )
            .focusable(interactionSource = interactionSource),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = stringResource(contentDescriptionResId),
            tint = when {
                selected || isFocused -> accentColor
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun QuickMenuRailActionButton(
    item: QuickMenuItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val accentColor = if (item.accentColor != Color.Unspecified) {
        item.accentColor
    } else {
        MaterialTheme.colorScheme.error
    }
    val shape = RoundedCornerShape(14.dp)

    Box(
        modifier = modifier
            .size(56.dp)
            .then(
                if (isFocused) {
                    Modifier.border(
                        width = 2.dp,
                        color = accentColor.copy(alpha = 0.7f),
                        shape = shape,
                    )
                } else {
                    Modifier.border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                        shape = shape,
                    )
                }
            )
            .clip(shape)
            .background(
                if (isFocused) {
                    accentColor.copy(alpha = 0.18f)
                } else {
                    accentColor.copy(alpha = 0.08f)
                },
            )
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                }
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .focusable(interactionSource = interactionSource),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = stringResource(item.labelResId),
            tint = if (isFocused) accentColor else accentColor.copy(alpha = 0.9f),
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun QuickMenuChoiceChip(
    text: String,
    selected: Boolean,
    accentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(12.dp)

    Box(
        modifier = modifier
            .height(44.dp)
            .then(
                if (isFocused) {
                    Modifier.border(
                        width = 2.dp,
                        color = accentColor.copy(alpha = 0.7f),
                        shape = shape,
                    )
                } else {
                    Modifier.border(
                        width = 1.dp,
                        color = if (selected) accentColor.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                        shape = shape,
                    )
                }
            )
            .clip(shape)
            .background(
                when {
                    selected -> accentColor.copy(alpha = 0.18f)
                    isFocused -> accentColor.copy(alpha = 0.12f)
                    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)
                },
            )
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                }
            )
            .selectable(
                selected = selected,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .focusable(interactionSource = interactionSource)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected || isFocused) accentColor else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (selected || isFocused) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

@Composable
private fun QuickMenuAdjustmentRow(
    title: String,
    valueText: String,
    progress: Float,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    accentColor: Color,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(14.dp)
    var isAdjustmentLocked by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
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
                }
            )
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                }
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
                            onDecrease()
                            true
                        }

                        isAdjustmentLocked && keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            onIncrease()
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
            QuickMenuAdjustmentButton(
                text = "-",
                rowIsFocused = isFocused,
                isAdjustmentLocked = isAdjustmentLocked,
                accentColor = accentColor,
                onClick = onDecrease,
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
                                onClick = onDecrease,
                            ),
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onIncrease,
                            ),
                    )
                }
            }

            QuickMenuAdjustmentButton(
                text = "+",
                rowIsFocused = isFocused,
                isAdjustmentLocked = isAdjustmentLocked,
                accentColor = accentColor,
                onClick = onIncrease,
            )
        }
    }
}

@Composable
private fun QuickMenuAdjustmentButton(
    text: String,
    rowIsFocused: Boolean,
    isAdjustmentLocked: Boolean,
    accentColor: Color,
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

@Composable
private fun QuickMenuToggleRow(
    title: String,
    enabled: Boolean,
    onToggle: () -> Unit,
    accentColor: Color,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    focusRequester: FocusRequester? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(14.dp))
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
                        shape = RoundedCornerShape(14.dp),
                    )
                } else {
                    Modifier
                }
            )
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                }
            )
            .selectable(
                selected = isFocused,
                interactionSource = interactionSource,
                indication = null,
                onClick = onToggle,
            )
            .focusable(interactionSource = interactionSource)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Medium,
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        QuickMenuSwitch(
            enabled = enabled,
            accentColor = accentColor,
        )
    }
}

@Composable
private fun QuickMenuSwitch(
    enabled: Boolean,
    accentColor: Color,
) {
    Box(
        modifier = Modifier
            .width(56.dp)
            .height(32.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(
                if (enabled) accentColor else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            )
            .border(
                width = 1.dp,
                color = if (enabled) accentColor.copy(alpha = 0.8f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                shape = RoundedCornerShape(999.dp),
            )
            .padding(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .align(if (enabled) Alignment.CenterEnd else Alignment.CenterStart)
                .background(Color.White, CircleShape),
        )
    }
}

@Composable
private fun QuickMenuItemRow(
    item: QuickMenuItem,
    isActive: Boolean = false,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val isEnabled = item.enabled

    val accentColor = if (item.accentColor != Color.Unspecified) {
        item.accentColor
    } else {
        MaterialTheme.colorScheme.primary
    }

    val disabledAlpha = 0.4f
    val shape = RoundedCornerShape(12.dp)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (isFocused && isEnabled) {
                    Modifier.border(
                        BorderStroke(
                            2.dp,
                            Brush.verticalGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.tertiary,
                                ),
                            ),
                        ),
                        shape,
                    )
                } else {
                    Modifier
                }
            )
            .clip(shape)
            .then(
                if (isFocused && isEnabled) {
                    Modifier.background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                accentColor.copy(alpha = 0.15f),
                                accentColor.copy(alpha = 0.05f),
                            ),
                        ),
                    )
                } else {
                    Modifier
                }
            )
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                }
            )
            .selectable(
                selected = isFocused,
                enabled = isEnabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .focusable(
                enabled = isEnabled,
                interactionSource = interactionSource,
            )
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .then(
                    if (isActive) {
                        Modifier.border(BorderStroke(2.dp, accentColor), CircleShape)
                    } else Modifier
                )
                .clip(CircleShape)
                .background(
                    when {
                        !isEnabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        isFocused || isActive -> accentColor.copy(alpha = 0.2f)
                        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                tint = when {
                    !isEnabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = disabledAlpha)
                    isFocused || isActive -> accentColor
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(22.dp),
            )
        }

        Text(
            text = stringResource(item.labelResId),
            style = MaterialTheme.typography.bodyLarge,
            color = when {
                !isEnabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = disabledAlpha)
                isFocused -> accentColor
                else -> MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(1f),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun Preview_QuickMenu() {
    PluviaTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            QuickMenu(
                isVisible = true,
                onDismiss = {},
                onItemSelected = { false },
                hasPhysicalController = false,
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun Preview_QuickMenu_WithController() {
    PluviaTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            QuickMenu(
                isVisible = true,
                onDismiss = {},
                onItemSelected = { false },
                hasPhysicalController = true,
            )
        }
    }
}
