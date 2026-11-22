@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package tel.misa.mxpocket.shared.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.launch
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import tel.misa.mxpocket.deck.DeckPage
import tel.misa.mxpocket.deck.DeckState
import tel.misa.mxpocket.plugins.KeyTile
import tel.misa.mxpocket.plugins.PluginFrame

enum class WorkspaceTab(val label: String) {
    HOME("Home"), PROFILES("Profiles"), PLUGINS("Plugins"), WORKFLOWS("Flows"), SETTINGS("Settings"),
}

data class ProfileChoice(val id: String, val name: String)

/** Identifies the live input targets, not their paint (e.g. timer text or album art). */
data class VirtualGrid(
    val generation: Long, val profileId: String, val page: DeckPage, val pluginPage: Int,
    val taps: List<Target>, val holds: List<Target>?,
) {
    data class Target(val token: String?, val actionable: Boolean)
}

fun virtualGrid(generation: Long, deck: DeckState, frame: PluginFrame, holds: PluginFrame?): VirtualGrid {
    fun KeyTile.target() = VirtualGrid.Target(inputToken, enabled && !readOnly)
    return VirtualGrid(generation, deck.activeProfileId, deck.activePage, frame.page,
        frame.keys.map { it.target() }, holds?.keys?.map { it.target() })
}

data class PocketScreenState(
    val status: String,
    val pluginTitle: String,
    val tiles: List<KeypadTile>,
    val profiles: List<ProfileChoice>,
    val activeId: String,
    val reason: String = "",
    val connectionLabel: String = "",
    val connectionActive: Boolean = false,
    val diagnostics: String = "",
    val log: String = "",
    val automatic: Boolean = false,
    val directShortcuts: Boolean = false,
    val simulating: Boolean = false,
    val panelArt: ImageBitmap? = null,
    /** Null for the physical Android host; otherwise displayed in place of unsupported device controls. */
    val deviceLimit: String? = null,
    val usbReady: Boolean = false,
    val activePageId: String = "root",
    val hardwareSummary: Boolean = false,
    val virtualGrid: VirtualGrid? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
)

data class PluginItem(
    val id: String, val title: String, val enabled: Boolean, val capabilities: String,
    val details: String, val profileAvailable: Boolean, val isDefault: Boolean,
    val settingsLabel: String?, val canToggle: Boolean = true,
)

sealed interface PocketScreenEvent {
    data object ToggleConnection : PocketScreenEvent
    data class SetAutomatic(val enabled: Boolean) : PocketScreenEvent
    data class SetDirectShortcuts(val enabled: Boolean) : PocketScreenEvent
    data object EnterPreview : PocketScreenEvent
    data object StopPreview : PocketScreenEvent
    data class SelectPlugin(val id: String) : PocketScreenEvent
    data class VirtualKey(val index: Int, val grid: VirtualGrid? = null, val hold: Boolean = false) : PocketScreenEvent
    data class ChangePage(val delta: Int) : PocketScreenEvent
    data object Repaint : PocketScreenEvent
    data object ChooseImage : PocketScreenEvent
    data object RefreshDiagnostics : PocketScreenEvent
    data object SaveLog : PocketScreenEvent
    data object FeatureSetup : PocketScreenEvent
    data class EnablePlugin(val id: String, val enabled: Boolean) : PocketScreenEvent
    data class DefaultProfile(val id: String) : PocketScreenEvent
    data class PluginSettings(val id: String) : PocketScreenEvent
    data class SetTheme(val mode: ThemeMode) : PocketScreenEvent
}

/** Both hosts supply only state and intent handlers. The active content retains its composition on resize. */
@Composable
fun PocketWorkspace(
    tab: WorkspaceTab, select: (WorkspaceTab) -> Unit, state: PocketScreenState,
    plugins: List<PluginItem>, event: (PocketScreenEvent) -> Unit,
    editor: @Composable () -> Unit, workflows: @Composable () -> Unit,
) {
    val holder = rememberSaveableStateHolder()
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        val rail = maxWidth >= 840.dp
        Column {
            Row(Modifier.weight(1f)) {
                if (rail) NavigationRail(containerColor = PocketColors.nav) {
                    WorkspaceTab.entries.forEach { item ->
                        NavigationRailItem(item == tab, { select(item) }, icon = { TabIcon(item) },
                            label = { Text(item.label) }, colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = PocketColors.accent, selectedTextColor = PocketColors.accent,
                                indicatorColor = PocketColors.navIndicator))
                    }
                }
                Box(Modifier.fillMaxSize()) {
                    holder.SaveableStateProvider(tab.name) {
                        when (tab) {
                            WorkspaceTab.HOME -> HomeScreen(state, event)
                            WorkspaceTab.PROFILES -> ProfilesScreen(state, event, editor)
                            WorkspaceTab.PLUGINS -> PluginsScreen(plugins, event)
                            WorkspaceTab.WORKFLOWS -> workflows()
                            WorkspaceTab.SETTINGS -> SettingsScreen(state, event)
                        }
                    }
                }
            }
            if (!rail) NavigationBar(windowInsets = WindowInsets(0), containerColor = PocketColors.nav, tonalElevation = 0.dp) {
                WorkspaceTab.entries.forEach { item ->
                    NavigationBarItem(item == tab, { select(item) }, icon = { TabIcon(item) },
                        label = { Text(item.label, maxLines = 1) }, colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = PocketColors.accent, selectedTextColor = PocketColors.accent,
                            indicatorColor = PocketColors.navIndicator))
                }
            }
        }
    }
}

/**
 * Home mirrors the physical keypad: the nine tiles are the same LCD images, updated as they change.
 * Profile switching lives on the Profiles tab; display maintenance lives in Settings.
 */
@Composable
fun HomeScreen(state: PocketScreenState, event: (PocketScreenEvent) -> Unit) {
    WorkspaceColumn(wide = true) {
        HomeHeader(state, event)
        BoxWithConstraints {
            val split = maxWidth >= 720.dp
            val summary: @Composable () -> Unit = {
                NowOnKeypad(state, event)
                ConnectionCard(state, event)
            }
            // With hardware the mirror is display-only: keys are pressed on the device, never twice.
            val operable = state.simulating || !state.hardwareSummary
            val tiles = if (operable) state.tiles else state.tiles.map { it.copy(readOnly = true) }
            val keypad: @Composable () -> Unit = {
                PocketCard {
                    PocketLabel(if (operable) "Keypad" else "Keypad · live")
                    if (!operable) KeypadGrid(tiles, { _, _ -> }, Modifier.widthIn(max = 480.dp).align(Alignment.CenterHorizontally), state.panelArt)
                    else key(state.activeId, state.activePageId, state.simulating, state.virtualGrid) {
                        // Replacing the clickable nodes cancels a pointer/key still down on the old board.
                        val grid = state.virtualGrid
                        val holds = buildMap<Int, () -> Unit> {
                            grid?.page?.keys?.forEachIndexed { index, binding ->
                                if (binding.holdAction != null) put(index) {
                                    if (grid.holds?.get(index)?.actionable == true)
                                        event(PocketScreenEvent.VirtualKey(index, grid, hold = true))
                                }
                            }
                        }
                        KeypadGrid(tiles, { index, pressed ->
                            if (!pressed && (grid == null || grid.page.keys[index].action != null))
                                event(PocketScreenEvent.VirtualKey(index, grid))
                        }, Modifier.widthIn(max = 480.dp).align(Alignment.CenterHorizontally), state.panelArt, holds)
                    }
                }
            }
            if (split) Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Column(Modifier.weight(.85f), verticalArrangement = Arrangement.spacedBy(14.dp)) { summary() }
                Column(Modifier.weight(1.15f)) { keypad() }
            } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { summary(); keypad() }
        }
    }
}

private data class ConnectionLook(val title: String, val short: String, val icon: ShellIcon, val color: Color)

@Composable
private fun connectionLook(state: PocketScreenState) = when {
    state.simulating -> ConnectionLook("Virtual keypad", "Virtual", ShellIcon.EYE, PocketColors.accent)
    state.usbReady -> ConnectionLook("Connected: USB session active", "Connected", ShellIcon.CHECK, PocketColors.success)
    state.connectionActive -> ConnectionLook("Connecting…", "Connecting", ShellIcon.BOLT, PocketColors.warning)
    else -> ConnectionLook("Not connected", "Not connected", ShellIcon.LINK, PocketColors.muted)
}

@Composable
private fun HomeHeader(state: PocketScreenState, event: (PocketScreenEvent) -> Unit) {
    val look = connectionLook(state)
    val dark = PocketColors.dark
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.weight(1f)) { WorkspaceTitle("MX Pocket") }
        // The state is spelled out next to the dot; color never carries it alone.
        Surface(shape = CircleShape, color = PocketColors.card, border = BorderStroke(1.dp, PocketColors.cardStroke)) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PocketDot(look.color)
                Text(look.short, style = MaterialTheme.typography.labelLarge, color = PocketColors.text)
            }
        }
        Surface(onClick = { event(PocketScreenEvent.SetTheme(if (dark) ThemeMode.LIGHT else ThemeMode.DARK)) },
            shape = CircleShape, color = PocketColors.accent,
            modifier = Modifier.size(48.dp).semantics { contentDescription = if (dark) "Switch to light theme" else "Switch to dark theme" }) {
            Box(contentAlignment = Alignment.Center) { PocketIcon(if (dark) ShellIcon.SUN else ShellIcon.MOON, PocketColors.onAccent, 22.dp) }
        }
    }
}

@Composable
private fun NowOnKeypad(state: PocketScreenState, event: (PocketScreenEvent) -> Unit) {
    val profileName = state.profiles.find { it.id == state.activeId }?.name ?: state.pluginTitle
    val pageName = state.pluginTitle.substringAfter(" / ", "")
    PocketCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RoundPageButton(ShellIcon.CHEVRON_LEFT, "Previous page") { event(PocketScreenEvent.ChangePage(-1)) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PocketDot(PocketColors.accent)
                    PocketLabel("Now on keypad", color = PocketColors.accent)
                }
                Text(profileName, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold, color = PocketColors.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (pageName.isNotBlank()) Text(pageName, color = PocketColors.accent, style = MaterialTheme.typography.titleMedium)
                if (state.reason.isNotBlank()) Note(state.reason)
            }
            RoundPageButton(ShellIcon.CHEVRON_RIGHT, "Next page") { event(PocketScreenEvent.ChangePage(1)) }
        }
    }
}

@Composable
private fun RoundPageButton(icon: ShellIcon, label: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = CircleShape, color = PocketColors.control, border = BorderStroke(1.dp, PocketColors.cardStroke),
        modifier = Modifier.size(52.dp).semantics { contentDescription = label }) {
        Box(contentAlignment = Alignment.Center) { PocketIcon(icon, PocketColors.text, 20.dp) }
    }
}

@Composable
private fun ConnectionCard(state: PocketScreenState, event: (PocketScreenEvent) -> Unit) {
    val look = connectionLook(state)
    PocketCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = MaterialTheme.shapes.medium, color = look.color.copy(alpha = .14f)) {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { PocketIcon(look.icon, look.color, 20.dp) }
            }
            Column(Modifier.weight(1f)) {
                Text(look.title, style = MaterialTheme.typography.titleMedium, color = PocketColors.title)
                Text(state.status, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    color = PocketColors.muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        state.deviceLimit?.let { Note(it) }
        if (state.deviceLimit == null) PocketButton(if (state.connectionActive) "Disconnect" else "Connect keypad",
            { event(PocketScreenEvent.ToggleConnection) }, Modifier.fillMaxWidth(),
            icon = ShellIcon.LINK, tone = if (state.connectionActive) PocketTone.DANGER else PocketTone.PRIMARY)
    }
}

/** Profiles: the infinite carousel switches what is on the keypad; the editor below edits its layout. */
@Composable
private fun ProfilesScreen(state: PocketScreenState, event: (PocketScreenEvent) -> Unit, editor: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 1152.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                WorkspaceTitle("Profiles")
                Row(Modifier.fillMaxWidth()) {
                    PocketLabel("On keypad", Modifier.weight(1f))
                    Text("Swipe to switch", style = MaterialTheme.typography.labelMedium, color = PocketColors.faint)
                }
            }
        }
        ProfileCarousel(state.profiles, state.activeId) { event(PocketScreenEvent.SelectPlugin(it)) }
        Box(Modifier.weight(1f).fillMaxWidth()) { editor() }
    }
}

private const val CAROUSEL_LOOPS = 1000

/**
 * An endless, snapping profile strip. The centered card is sharp; the cards beside it are slightly
 * blurred and dimmed. Settling on a card, or tapping one, selects that profile.
 */
@Composable
internal fun ProfileCarousel(profiles: List<ProfileChoice>, activeId: String, select: (String) -> Unit) {
    if (profiles.isEmpty()) return
    val count = profiles.size
    val activeIndex = profiles.indexOfFirst { it.id == activeId }.coerceAtLeast(0)
    val list = key(count) { rememberLazyListState(CAROUSEL_LOOPS / 2 * count + activeIndex) }
    val scope = rememberCoroutineScope()
    val currentActive by rememberUpdatedState(activeId)
    val currentSelect by rememberUpdatedState(select)
    val currentProfiles by rememberUpdatedState(profiles)
    val centered by remember(list) {
        derivedStateOf {
            val info = list.layoutInfo
            val middle = (info.viewportStartOffset + info.viewportEndOffset) / 2
            info.visibleItemsInfo.minByOrNull { kotlin.math.abs(it.offset + it.size / 2 - middle) }?.index ?: list.firstVisibleItemIndex
        }
    }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) currentProfiles.getOrNull(centered % currentProfiles.size)?.let {
                if (it.id != currentActive) currentSelect(it.id)
            }
        }
    }
    // A switch made elsewhere (keypad page buttons, rules) brings that profile to the middle.
    LaunchedEffect(activeId, count) {
        val target = profiles.indexOfFirst { it.id == activeId }
        val current = centered
        if (target >= 0 && current % count != target && !list.isScrollInProgress) {
            var index = current - current % count + target
            if (index - current > count / 2) index -= count
            if (current - index > count / 2) index += count
            list.animateScrollToItem(index)
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cardWidth = (maxWidth * .42f).coerceIn(132.dp, 200.dp)
        val side = ((maxWidth - cardWidth) / 2).coerceAtLeast(0.dp)
        LazyRow(state = list, flingBehavior = rememberSnapFlingBehavior(list),
            contentPadding = PaddingValues(horizontal = side, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            items(count * CAROUSEL_LOOPS) { index ->
                val profile = profiles[index % count]
                val focused = index == centered
                val selected = profile.id == activeId
                val accent = PocketStyle.accent(profile.id)
                val fade by animateFloatAsState(if (focused) 1f else .55f)
                val scale by animateFloatAsState(if (focused) 1f else .9f)
                Surface(onClick = {
                    scope.launch { list.animateScrollToItem(index) }
                    if (!selected) select(profile.id)
                }, shape = RoundedCornerShape(16.dp),
                    color = if (selected) PocketColors.accentSoft else PocketColors.card,
                    border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) PocketColors.accent else PocketColors.cardStroke),
                    modifier = Modifier.width(cardWidth)
                        .graphicsLayer { alpha = fade; scaleX = scale; scaleY = scale }
                        .blur(if (focused) 0.dp else 2.dp, BlurredEdgeTreatment.Unbounded)
                        .semantics { this.selected = selected }) {
                    Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PocketIconTile(accent, 40.dp)
                        Text(profile.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (selected) PocketColors.accent else PocketColors.title)
                    }
                }
            }
        }
    }
}

@Composable
private fun PluginsScreen(plugins: List<PluginItem>, event: (PocketScreenEvent) -> Unit) {
    Section("Plugins") {
        Note(if (plugins.any { it.canToggle }) "Enable the controls you use. Android access is configured separately."
            else "Available in this simulator. Android access is configured on the phone.")
        for (plugin in plugins) {
            PocketCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PocketIconTile(PocketStyle.accent(plugin.id))
                    Column(Modifier.weight(1f)) {
                        Heading(plugin.title)
                        Text("Capabilities: ${plugin.capabilities.ifBlank { "none" }}", color = PocketColors.faint, style = PocketMono)
                    }
                    Switch(plugin.enabled, { event(PocketScreenEvent.EnablePlugin(plugin.id, it)) }, enabled = plugin.canToggle,
                        modifier = Modifier.semantics { contentDescription = plugin.title })
                }
                Text(plugin.details, style = MaterialTheme.typography.bodyLarge)
                if (plugin.profileAvailable || plugin.settingsLabel != null) {
                    PocketDivider()
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        plugin.settingsLabel?.let { PocketButton(it, { event(PocketScreenEvent.PluginSettings(plugin.id)) }, enabled = plugin.enabled) }
                        if (plugin.profileAvailable) {
                            if (plugin.isDefault) PocketTag("Default profile", PocketColors.accent)
                            else PocketTextLink("Make default profile", { event(PocketScreenEvent.DefaultProfile(plugin.id)) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(state: PocketScreenState, event: (PocketScreenEvent) -> Unit) {
    Section("Settings") {
        PocketButton("Feature setup", { event(PocketScreenEvent.FeatureSetup) }, Modifier.fillMaxWidth(), ShellIcon.SETTINGS, PocketTone.ACCENT)
        PocketCard("Appearance", if (PocketColors.dark) ShellIcon.MOON else ShellIcon.SUN, caps = true) {
            PocketDivider()
            Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    val chosen = mode == state.themeMode
                    PocketButton(mode.label, { event(PocketScreenEvent.SetTheme(mode)) },
                        Modifier.weight(1f).semantics { selected = chosen; role = Role.RadioButton },
                        icon = if (chosen) ShellIcon.CHECK else null, tone = if (chosen) PocketTone.ACCENT else PocketTone.NEUTRAL)
                }
            }
            Note("System follows your device's dark theme setting.")
        }
        PocketCard("Connection", ShellIcon.LINK, caps = true) {
            PocketDivider()
            PocketButton(if (state.simulating) "Disconnect virtual keypad" else "Connect virtual keypad", {
                event(if (state.simulating) PocketScreenEvent.StopPreview else PocketScreenEvent.EnterPreview)
            }, Modifier.fillMaxWidth(), ShellIcon.EYE, enabled = !state.connectionActive)
            if (state.deviceLimit == null) {
                PocketLabel("Status")
                Text(state.status)
                PocketButton(if (state.connectionActive) "Disconnect keypad" else "Connect keypad",
                    { event(PocketScreenEvent.ToggleConnection) }, Modifier.fillMaxWidth(), ShellIcon.SWAP,
                    if (state.connectionActive) PocketTone.DANGER else PocketTone.NEUTRAL)
                PocketDivider()
                PocketSwitchRow("Connect automatically on USB attach", "Start a session when Android reports this keypad attached",
                    state.automatic) { event(PocketScreenEvent.SetAutomatic(it)) }
                PocketSwitchRow("Open apps directly from keypad", "Needs Display over other apps; otherwise a notification is shown",
                    state.directShortcuts) { event(PocketScreenEvent.SetDirectShortcuts(it)) }
            } else Note(state.deviceLimit)
        }
        if (state.deviceLimit == null) PocketCard("Display", ShellIcon.MONITOR, caps = true) {
            PocketDivider()
            PocketListRow(ShellIcon.IMAGE, "Choose keypad background", "Image behind all nine keys for this session") { event(PocketScreenEvent.ChooseImage) }
            PocketListRow(ShellIcon.REFRESH, "Repaint all keys", "Redraw all nine 118 × 118 LCD regions") { event(PocketScreenEvent.Repaint) }
        }
        PocketCard("Diagnostics", ShellIcon.DOCUMENT, caps = true) {
            PocketDivider()
            PocketInner {
                Text(listOf(state.pluginTitle, state.connectionLabel).filter { it.isNotBlank() }.joinToString("  |  "))
                if (state.reason.isNotBlank()) Note(state.reason)
            }
            PocketInner { Text("Display profile: 480 x 480 panel; nine 118 x 118 visible regions. An upload acknowledgement does not verify LCD visibility.") }
            if (state.deviceLimit == null) PocketButton("Refresh USB diagnostics", { event(PocketScreenEvent.RefreshDiagnostics) },
                Modifier.fillMaxWidth(), ShellIcon.REFRESH, PocketTone.ACCENT)
            PocketLabel("Device descriptor details")
            PocketInner { SelectableReadout(state.diagnostics) }
            if (state.deviceLimit == null) PocketButton("Export session log", { event(PocketScreenEvent.SaveLog) },
                Modifier.fillMaxWidth(), ShellIcon.DOWNLOAD)
            PocketLabel("Session log")
            PocketInner { SelectableReadout(state.log.ifEmpty { "No events yet." }) }
        }
    }
}

@Composable
private fun SelectableReadout(text: String) {
    BasicTextField(text, onValueChange = {}, readOnly = true, modifier = Modifier.fillMaxWidth(),
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, color = LocalContentColor.current))
}

@Composable
private fun Section(title: String, wide: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    WorkspaceColumn(wide) {
        WorkspaceTitle(title)
        content()
    }
}

@Composable private fun Heading(text: String) = Text(text, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
@Composable private fun Note(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
