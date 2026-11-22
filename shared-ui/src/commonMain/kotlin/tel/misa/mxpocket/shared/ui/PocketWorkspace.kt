@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package tel.misa.mxpocket.shared.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
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
import tel.misa.mxpocket.deck.PROFILE_HOME
import tel.misa.mxpocket.plugins.KeyTile
import tel.misa.mxpocket.plugins.PluginFrame

enum class WorkspaceTab(val label: String) {
    HOME("Home"), KEYPAD("Keypad"), PLUGINS("Plugins"), WORKFLOWS("Flows"), SETTINGS("Settings"),
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
                            WorkspaceTab.KEYPAD -> editor()
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

@Composable
fun HomeScreen(state: PocketScreenState, event: (PocketScreenEvent) -> Unit) {
    Section("MX Pocket", wide = true) {
        BoxWithConstraints {
            val split = maxWidth >= 720.dp
            val controls: @Composable () -> Unit = {
                if (state.activeId == PROFILE_HOME) {
                    PocketCard("Choose a profile") {
                        state.profiles.chunked(2).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { profile ->
                                    PocketButton(profile.name, { event(PocketScreenEvent.SelectPlugin(profile.id)) },
                                        Modifier.weight(1f).heightIn(min = 48.dp), icon = PocketStyle.accent(profile.id).icon)
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
                PocketCard {
                    ConnectionBadge(state)
                    state.deviceLimit?.let { Note(it) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.deviceLimit == null) PocketButton(if (state.connectionActive) "Disconnect" else "Connect keypad",
                            { event(PocketScreenEvent.ToggleConnection) }, tone = if (state.connectionActive) PocketTone.DANGER else PocketTone.PRIMARY)
                        PocketButton(if (state.simulating) "Disconnect virtual keypad" else "Connect virtual keypad", {
                            event(if (state.simulating) PocketScreenEvent.StopPreview else PocketScreenEvent.EnterPreview)
                        }, enabled = !state.connectionActive)
                    }
                }
                if (state.activeId != PROFILE_HOME) Column {
                    PocketLabel("Profiles")
                    if (split) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { ProfileChips(state, event) }
                    else Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ProfileChips(state, event)
                    }
                }
            }
            val keypad: @Composable () -> Unit = {
                PocketCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        PocketIconTile(PocketStyle.accent(state.activeId), 40.dp)
                        Column {
                            Heading(state.pluginTitle)
                            if (state.reason.isNotBlank()) Note(state.reason)
                        }
                    }
                    if (state.hardwareSummary) PocketInner {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            PocketIcon(ShellIcon.KEYBOARD)
                            PocketLabel("Keypad shows:")
                        }
                        Text(state.tiles.joinToString(", ") { it.label.ifBlank { "(blank)" } }, style = PocketMono)
                    }
                    if (state.activeId != PROFILE_HOME) PocketButton("Profile launcher", { event(PocketScreenEvent.SelectPlugin(PROFILE_HOME)) },
                        Modifier.fillMaxWidth(), icon = ShellIcon.HOME)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PocketButton("Previous page", { event(PocketScreenEvent.ChangePage(-1)) }, Modifier.weight(1f), icon = ShellIcon.CHEVRON_LEFT)
                        PocketButton("Next page", { event(PocketScreenEvent.ChangePage(1)) }, Modifier.weight(1f), trailingIcon = ShellIcon.CHEVRON_RIGHT)
                    }
                    if (!state.hardwareSummary) key(state.activeId, state.activePageId, state.simulating, state.virtualGrid) {
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
                        KeypadGrid(state.tiles, { index, pressed ->
                            if (!pressed && (grid == null || grid.page.keys[index].action != null))
                                event(PocketScreenEvent.VirtualKey(index, grid))
                        }, Modifier.widthIn(max = 480.dp).align(Alignment.CenterHorizontally), state.panelArt, holds)
                    }
                    if (state.deviceLimit == null) {
                        PocketDivider()
                        PocketButton("Repaint all nine LCDs", { event(PocketScreenEvent.Repaint) }, Modifier.fillMaxWidth(), ShellIcon.REFRESH)
                        PocketButton("Choose custom image for all nine keys", { event(PocketScreenEvent.ChooseImage) }, Modifier.fillMaxWidth(), ShellIcon.IMAGE)
                    }
                    Text("480 × 480 panel · Nine 118 × 118 visible key regions.", style = PocketMono, color = PocketColors.faint)
                }
            }
            if (split) Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Column(Modifier.weight(.85f), verticalArrangement = Arrangement.spacedBy(14.dp)) { controls() }
                Column(Modifier.weight(1.15f)) { keypad() }
            } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { controls(); keypad() }
        }
    }
}

@Composable
private fun ProfileChips(state: PocketScreenState, event: (PocketScreenEvent) -> Unit) {
    state.profiles.forEach { profile ->
        val accent = PocketStyle.accent(profile.id)
        FilterChip(selected = profile.id == state.activeId, label = { Text(profile.name) },
            shape = CircleShape, leadingIcon = { PocketIcon(accent.icon, if (profile.id == state.activeId) Color(accent.tint) else PocketColors.muted, 16.dp) },
            colors = FilterChipDefaults.filterChipColors(containerColor = PocketColors.chip, selectedContainerColor = Color(accent.fill),
                labelColor = PocketColors.muted, selectedLabelColor = Color.White),
            onClick = { event(PocketScreenEvent.SelectPlugin(profile.id)) })
    }
}

@Composable
private fun ConnectionBadge(state: PocketScreenState) {
    val (label, icon, color) = when {
        state.simulating -> Triple("Virtual keypad", ShellIcon.EYE, Color(0xff93c5fd))
        state.usbReady -> Triple("Connected: USB session active", ShellIcon.CHECK, PocketColors.success)
        state.connectionActive -> Triple("Connecting…", ShellIcon.BOLT, PocketColors.warning)
        else -> Triple("Not connected", ShellIcon.LINK, PocketColors.muted)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = MaterialTheme.shapes.medium, color = color.copy(alpha = .14f)) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { PocketIcon(icon, color, 20.dp) }
        }
        Column {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(state.status, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = PocketColors.muted, style = MaterialTheme.typography.bodySmall)
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
        PocketCard("Connection", ShellIcon.LINK, caps = true) {
            PocketDivider()
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
