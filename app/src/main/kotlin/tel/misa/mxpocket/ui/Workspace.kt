package tel.misa.mxpocket.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tel.misa.mxpocket.PocketSession
import tel.misa.mxpocket.platformBindings
import tel.misa.mxpocket.pluginDetails
import tel.misa.mxpocket.actions.ActionCall
import tel.misa.mxpocket.deck.DeckActions
import tel.misa.mxpocket.workflow.WorkflowScreen

internal enum class WorkspaceTab(val label: String,val glyph: String) {
    HOME("Home","\u2302"), KEYPAD("Keypad","\u25a6"), PLUGINS("Plugins","\u229e"),
    WORKFLOWS("Flows","\u21c4"), SETTINGS("Settings","\u2699")
}

@Composable internal fun WorkspaceTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Color(0xffaec6ff))
        else -> lightColorScheme(primary = Color(0xff245cb5))
    }
    MaterialTheme(colorScheme = colors) { Surface(content = content) }
}

@Composable internal fun WorkspaceNav(tab: WorkspaceTab, select: (WorkspaceTab) -> Unit) {
    NavigationBar {
        WorkspaceTab.entries.forEach {
            NavigationBarItem(selected = it == tab, onClick = { select(it) },
                icon = { Text(it.glyph,style = MaterialTheme.typography.titleLarge) },
                label = { Text(it.label,maxLines = 1) })
        }
    }
}

@Composable internal fun WorkspacePanel(tab: WorkspaceTab, session: PocketSession, state: PocketScreenState?,
    event: (PocketScreenEvent) -> Unit, onExport: () -> Unit, onImport: () -> Unit) {
    if (tab == WorkspaceTab.HOME || state == null) return
    val deck by session.deck.state.collectAsStateWithLifecycle()
    val signals by session.signals.collectAsStateWithLifecycle()
    val flows by session.workflows.definitions.collectAsStateWithLifecycle()
    val disabled by session.disabledPlugins.collectAsStateWithLifecycle()
    val options = buildList {
        add(BindingOption("Back", ActionCall(DeckActions.BACK)))
        add(BindingOption("Return to root", ActionCall(DeckActions.ROOT)))
        deck.profiles.flatMap { it.pages }.forEach { add(BindingOption("Page: ${it.name}",ActionCall(DeckActions.PAGE,mapOf("pageId" to it.id)))) }
        deck.profiles.forEach { add(BindingOption("Profile: ${it.name}",ActionCall(DeckActions.PROFILE,mapOf("profileId" to it.id)))) }
        flows.forEach { add(BindingOption("Workflow: ${it.name}",ActionCall("workflow.run",mapOf("workflowId" to it.id)))) }
        add(BindingOption("Play a sound",ActionCall("sound.play")))
        session.platformBindings().forEach { (label,call) -> add(BindingOption(label,call)) }
    }
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
        when (tab) {
            WorkspaceTab.KEYPAD -> LayoutEditor(session.deck, options, signals, onExport, onImport)
            WorkspaceTab.WORKFLOWS -> WorkflowScreen(session.workflows)
            WorkspaceTab.PLUGINS -> Section("Plugins") {
                Text("Enable the controls you use. Android access is configured separately.")
                for (plugin in session.plugins.descriptors) {
                    HorizontalDivider()
                    Toggle(plugin.title,plugin.id !in disabled) { session.setPluginEnabled(plugin.id,it) }
                    Text("Capabilities: " + plugin.capabilities.joinToString { it.name.lowercase().replace('_',' ') },
                        style = MaterialTheme.typography.bodyMedium)
                    Text(session.pluginDetails(plugin.id), style = MaterialTheme.typography.bodyMedium)
                    if (deck.profiles.any { it.id == plugin.id }) TextButton(onClick = {
                        session.deck.setDefault(plugin.id).onFailure { session.fail("Cannot change default",it) }
                    }) { Text(if (deck.defaultProfileId == plugin.id) "Default profile" else "Make default profile") }
                    session.pluginSettings(plugin.id)?.let { (label,open) ->
                        OutlinedButton(onClick = open, enabled = plugin.id !in disabled) { Text(label) }
                    }
                }
            }
            WorkspaceTab.SETTINGS -> Section("Settings") {
                Text(state.status)
                Button(onClick = { event(PocketScreenEvent.ToggleConnection) }) {
                    Text(if (state.connectionActive) "Disconnect keypad" else "Connect keypad")
                }
                Toggle("Connect automatically on USB attach",state.automatic) { event(PocketScreenEvent.SetAutomatic(it)) }
                Toggle("Open apps directly from keypad",state.directShortcuts) { event(PocketScreenEvent.SetDirectShortcuts(it)) }
                HorizontalDivider()
                Text("Display",style = MaterialTheme.typography.titleLarge)
                OutlinedButton(onClick = { event(PocketScreenEvent.ChooseImage) }) { Text("Choose keypad background") }
                OutlinedButton(onClick = { event(PocketScreenEvent.Repaint) }) { Text("Repaint all keys") }
                HorizontalDivider()
                Text("Diagnostics",style = MaterialTheme.typography.titleLarge)
                Text("${deck.activeProfile.name} / ${deck.activePage.name}  |  ${session.connectionLabel}")
                Text(state.reason)
                Text("480 x 480 panel; nine 118 x 118 visible regions. An upload acknowledgement does not verify LCD visibility.")
                OutlinedButton(onClick = { event(PocketScreenEvent.RefreshDiagnostics) }) { Text("Refresh USB diagnostics") }
                SelectionContainer { Text(state.diagnostics, fontFamily = FontFamily.Monospace) }
                OutlinedButton(onClick = { event(PocketScreenEvent.SaveLog) }) { Text("Export session log") }
                SelectionContainer { Text(state.log.ifEmpty { "No events yet." },fontFamily = FontFamily.Monospace) }
            }
            WorkspaceTab.HOME -> Unit
        }
    }
}

@Composable private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).toggleable(value,role = Role.Switch,onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(value,null)
    }
}

@Composable private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title,style = MaterialTheme.typography.headlineMedium)
        content()
    }
}
