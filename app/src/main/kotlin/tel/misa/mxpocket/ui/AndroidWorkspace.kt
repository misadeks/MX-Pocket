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
import tel.misa.mxpocket.i18n.LayoutCopy
import tel.misa.mxpocket.i18n.displayName
import tel.misa.mxpocket.platformBindings
import tel.misa.mxpocket.pluginDetails
import tel.misa.mxpocket.plugins.displayName
import tel.misa.mxpocket.plugins.displayTitle
import tel.misa.mxpocket.shared.ui.*
import tel.misa.mxpocket.workflow.WorkflowScreen

@Composable
internal fun AndroidWorkspace(
    tab: WorkspaceTab, select: (WorkspaceTab) -> Unit, session: PocketSession, state: PocketScreenState,
    event: (PocketScreenEvent) -> Unit, onExport: () -> Unit, onImport: () -> Unit,
    usageGranted: Boolean, onUsageSettings: () -> String?,
) {
    val deck by session.deck.state.collectAsStateWithLifecycle()
    val signals by session.signals.collectAsStateWithLifecycle()
    val flows = session.workflows?.definitions?.collectAsStateWithLifecycle()?.value.orEmpty()
    val disabled by session.disabledPlugins.collectAsStateWithLifecycle()
    val detection by session.detectForegroundApps.collectAsStateWithLifecycle()
    val plugins = session.plugins.descriptors.map { plugin ->
        PluginItem(plugin.id, plugin.displayTitle(), plugin.id !in disabled,
            plugin.capabilities.joinToString { it.displayName() }, session.pluginDetails(plugin.id),
            deck.visibleProfiles.any { it.id == plugin.id }, deck.defaultProfileId == plugin.id, session.pluginSettings(plugin.id)?.first)
    }
    PocketWorkspace(tab, select, state, plugins, event, profiles = {
        val options = buildList {
            add(BindingOption(LayoutCopy.Back.text(), ActionCall(DeckActions.BACK)))
            add(BindingOption(LayoutCopy.Root.text(), ActionCall(DeckActions.ROOT)))
            deck.visibleProfiles.forEach { add(BindingOption(LayoutCopy.BindProfile.text(it.displayName()), ActionCall(DeckActions.PROFILE, mapOf("profileId" to it.id)))) }
            flows.forEach { add(BindingOption(LayoutCopy.BindWorkflow.text(it.name), ActionCall("workflow.run", mapOf("workflowId" to it.id)))) }
            add(BindingOption(LayoutCopy.PlaySound.text(), ActionCall("sound.play")))
            addAll(availableFactoryKeyBindings(disabled))
            session.platformBindings().forEach { (label, call) -> add(BindingOption(label, call)) }
        }
        SavedProfilesEditor(remember(session.deck) { session.deck.asEditorStore() }, disabled, options, signals, onExport, onImport,
            foreground = ForegroundControls(detection, usageGranted, session::setDetectForegroundApps, onUsageSettings) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        session.favoriteApps.discover().mapNotNull { app ->
                            android.content.ComponentName.unflattenFromString(app.component)?.let { MappingApp(it.packageName, app.label) }
                        }.distinctBy { it.packageId }
                    } catch (_: SecurityException) { null }
                }
            },
            backHandler = { enabled, back -> BackHandler(enabled, back) })
    }, workflows = { session.workflows?.let { WorkflowScreen(it) } },
        backHandler = { enabled, back -> BackHandler(enabled, back) })
}
