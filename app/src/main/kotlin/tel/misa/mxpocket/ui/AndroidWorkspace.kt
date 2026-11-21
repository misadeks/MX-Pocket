package tel.misa.mxpocket.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.activity.compose.BackHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tel.misa.mxpocket.PocketSession
import tel.misa.mxpocket.actions.ActionCall
import tel.misa.mxpocket.deck.DeckActions
import tel.misa.mxpocket.deck.asEditorStore
import tel.misa.mxpocket.platformBindings
import tel.misa.mxpocket.pluginDetails
import tel.misa.mxpocket.shared.ui.*
import tel.misa.mxpocket.workflow.WorkflowScreen

@Composable
internal fun AndroidWorkspace(
    tab: WorkspaceTab, select: (WorkspaceTab) -> Unit, session: PocketSession, state: PocketScreenState,
    event: (PocketScreenEvent) -> Unit, onExport: () -> Unit, onImport: () -> Unit,
) {
    val deck by session.deck.state.collectAsStateWithLifecycle()
    val signals by session.signals.collectAsStateWithLifecycle()
    val flows by session.workflows.definitions.collectAsStateWithLifecycle()
    val disabled by session.disabledPlugins.collectAsStateWithLifecycle()
    val plugins = session.plugins.descriptors.map { plugin ->
        PluginItem(plugin.id, plugin.title, plugin.id !in disabled,
            plugin.capabilities.joinToString { it.name.lowercase().replace('_', ' ') }, session.pluginDetails(plugin.id),
            deck.profiles.any { it.id == plugin.id }, deck.defaultProfileId == plugin.id, session.pluginSettings(plugin.id)?.first)
    }
    PocketWorkspace(tab, select, state, plugins, event, editor = {
        val options = buildList {
            add(BindingOption("Back", ActionCall(DeckActions.BACK)))
            add(BindingOption("Return to root", ActionCall(DeckActions.ROOT)))
            deck.profiles.forEach { add(BindingOption("Profile: ${it.name}", ActionCall(DeckActions.PROFILE, mapOf("profileId" to it.id)))) }
            flows.forEach { add(BindingOption("Workflow: ${it.name}", ActionCall("workflow.run", mapOf("workflowId" to it.id)))) }
            add(BindingOption("Play a sound", ActionCall("sound.play")))
            session.platformBindings().forEach { (label, call) -> add(BindingOption(label, call)) }
        }
        LayoutEditor(remember(session.deck) { session.deck.asEditorStore() }, options, signals, onExport, onImport,
            backHandler = { enabled, back -> BackHandler(enabled, back) })
    }, workflows = { WorkflowScreen(session.workflows) })
}
