package tel.misa.mxpocket.shared.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

enum class WorkspaceTab(val label: String, val symbol: String) {
    HOME("Home", "\u2302"), KEYPAD("Keypad", "\u25A6"), PLUGINS("Plugins", "\u229E"),
    WORKFLOWS("Flows", "\u21C4"), SETTINGS("Settings", "\u2699"),
}

data class ProfileChoice(val id: String, val name: String)

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
    data class TapKey(val index: Int) : PocketScreenEvent
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
                if (rail) NavigationRail {
                    WorkspaceTab.entries.forEach { item ->
                        NavigationRailItem(item == tab, { select(item) }, icon = { Text(item.symbol) },
                            label = { Text(item.label) })
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
            if (!rail) NavigationBar(windowInsets = WindowInsets(0)) {
                WorkspaceTab.entries.forEach { item ->
                    NavigationBarItem(item == tab, { select(item) }, icon = { Text(item.symbol) },
                        label = { Text(item.label, maxLines = 1) })
                }
            }
        }
    }
}

@Composable
fun HomeScreen(state: PocketScreenState, event: (PocketScreenEvent) -> Unit) {
    Section("MX Pocket") {
        Text(state.status, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (state.deviceLimit == null) {
            Button(onClick = { event(PocketScreenEvent.ToggleConnection) }) {
                Text(if (state.connectionActive) "Disconnect" else "Connect keypad")
            }
        } else Note(state.deviceLimit)
        OutlinedButton(onClick = {
            event(if (state.simulating) PocketScreenEvent.StopPreview else PocketScreenEvent.EnterPreview)
        }, enabled = !state.connectionActive) {
            Text(if (state.simulating) "Disconnect virtual keypad" else "Connect virtual keypad")
        }
        HorizontalDivider()
        Heading(state.pluginTitle)
        if (state.reason.isNotBlank()) Note(state.reason)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.profiles.forEach { profile ->
                FilterChip(selected = profile.id == state.activeId, label = { Text(profile.name) },
                    onClick = { event(PocketScreenEvent.SelectPlugin(profile.id)) })
            }
        }
        KeypadGrid(state.tiles, { index, pressed ->
            // A completed grid click emits a down/up pair. phoneKey is already one complete click.
            if (!pressed) event(PocketScreenEvent.TapKey(index))
        }, Modifier.widthIn(max = 480.dp).fillMaxWidth().align(Alignment.CenterHorizontally), state.panelArt)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { event(PocketScreenEvent.ChangePage(-1)) }) { Text("Previous page") }
            TextButton(onClick = { event(PocketScreenEvent.ChangePage(1)) }) { Text("Next page") }
        }
    }
}

@Composable
private fun PluginsScreen(plugins: List<PluginItem>, event: (PocketScreenEvent) -> Unit) {
    Section("Plugins") {
        for (plugin in plugins) {
            HorizontalDivider()
            SettingSwitch(plugin.title, plugin.enabled, plugin.canToggle) { event(PocketScreenEvent.EnablePlugin(plugin.id, it)) }
            if (plugin.capabilities.isNotBlank()) Note("Capabilities: ${plugin.capabilities}")
            Text(plugin.details)
            if (plugin.profileAvailable) TextButton(onClick = { event(PocketScreenEvent.DefaultProfile(plugin.id)) },
                enabled = !plugin.isDefault) { Text(if (plugin.isDefault) "Default profile" else "Make default profile") }
            plugin.settingsLabel?.let { label ->
                OutlinedButton(onClick = { event(PocketScreenEvent.PluginSettings(plugin.id)) }, enabled = plugin.enabled) { Text(label) }
            }
        }
    }
}

@Composable
private fun SettingsScreen(state: PocketScreenState, event: (PocketScreenEvent) -> Unit) {
    Section("Settings") {
        OutlinedButton(onClick = { event(PocketScreenEvent.FeatureSetup) }) { Text("Feature setup") }
        if (state.deviceLimit == null) {
            Text(state.status)
            Button(onClick = { event(PocketScreenEvent.ToggleConnection) }) {
                Text(if (state.connectionActive) "Disconnect keypad" else "Connect keypad")
            }
            SettingSwitch("Connect automatically on USB attach", state.automatic) { event(PocketScreenEvent.SetAutomatic(it)) }
            SettingSwitch("Open apps directly from keypad", state.directShortcuts) { event(PocketScreenEvent.SetDirectShortcuts(it)) }
            HorizontalDivider()
            Heading("Display")
            OutlinedButton(onClick = { event(PocketScreenEvent.ChooseImage) }) { Text("Choose keypad background") }
            OutlinedButton(onClick = { event(PocketScreenEvent.Repaint) }) { Text("Repaint all keys") }
        } else Text(state.deviceLimit)
        HorizontalDivider()
        Heading("Diagnostics")
        Text(listOf(state.pluginTitle, state.connectionLabel).filter { it.isNotBlank() }.joinToString("  |  "))
        Note(state.reason)
        Text("480 x 480 panel; nine 118 x 118 visible regions. An upload acknowledgement does not verify LCD visibility.")
        if (state.deviceLimit == null) OutlinedButton(onClick = { event(PocketScreenEvent.RefreshDiagnostics) }) { Text("Refresh USB diagnostics") }
        SelectableReadout(state.diagnostics)
        Heading("Session log")
        if (state.deviceLimit == null) OutlinedButton(onClick = { event(PocketScreenEvent.SaveLog) }) { Text("Export session log") }
        SelectableReadout(state.log.ifEmpty { "No events yet." })
    }
}

@Composable
private fun SelectableReadout(text: String) {
    BasicTextField(text, onValueChange = {}, readOnly = true, modifier = Modifier.fillMaxWidth(),
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, color = LocalContentColor.current))
}

@Composable
private fun SettingSwitch(label: String, value: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).toggleable(value, enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(value, null, enabled = enabled)
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
            content()
        }
    }
}

@Composable private fun Heading(text: String) = Text(text, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
@Composable private fun Note(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
