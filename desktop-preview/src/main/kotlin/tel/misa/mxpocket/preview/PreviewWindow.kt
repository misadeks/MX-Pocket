package tel.misa.mxpocket.preview

import tel.misa.mxpocket.i18n.WorkspaceCopy as W
import tel.misa.mxpocket.i18n.displayName
import tel.misa.mxpocket.i18n.displayPageName
import tel.misa.mxpocket.i18n.displaySelectionReason

import androidx.compose.runtime.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import java.awt.FileDialog
import androidx.compose.ui.awt.ComposeWindow
import java.io.File
import java.nio.file.Files
import java.util.prefs.Preferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.ImageBitmap
import tel.misa.mxpocket.plugins.IntegrationApp
import tel.misa.mxpocket.actions.ActionCall
import tel.misa.mxpocket.deck.*
import tel.misa.mxpocket.decks.DeckCommand
import tel.misa.mxpocket.i18n.LayoutCopy
import tel.misa.mxpocket.i18n.builtInDeckName
import tel.misa.mxpocket.shared.ui.*

private fun now() = System.nanoTime() / 1_000_000
private val DEVICE_LIMIT get() = W.Simulator.text()

@Composable
private fun PreviewTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val icons by produceState<Map<IntegrationApp, ImageBitmap>>(emptyMap()) {
        value = withContext(Dispatchers.IO) { windowsIntegrationIcons() }
    }
    CompositionLocalProvider(LocalIntegrationIcons provides icons) { PocketTheme(mode, content) }
}

private fun chooseBackup(owner: ComposeWindow, repo: DeckRepository, save: Boolean): String? {
    val picker = FileDialog(owner, if (save) W.Export.text() else W.Import.text(),
        if (save) FileDialog.SAVE else FileDialog.LOAD)
    try {
        if (save) picker.file = "mx-pocket-layouts.json"
        picker.isVisible = true
        val file = picker.files.firstOrNull() ?: return null
        if (save) file.writeText(repo.exportJson()) else {
            val bytes = file.inputStream().use { it.readNBytes(DeckLimits.MAX_BYTES + 1) }
            require(bytes.size <= DeckLimits.MAX_BYTES) { W.FileTooLarge.text() }
            repo.importJson(bytes.decodeToString(throwOnInvalidSequence = true)).getOrThrow()
        }
        return if (save) W.Exported.text() else W.Imported.text()
    } finally { picker.dispose() }
}

fun main() = application {
    val appearance = remember { java.util.prefs.Preferences.userRoot().node("tel/misa/mxpocket") }
    val language = remember { DesktopLanguage(appearance) }
    val directory = remember {
        File(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "MX Pocket").also {
            Files.createDirectories(it.toPath())
        }
    }
    val registry = remember { defaultDeck().profiles }
    val switches = remember { Preferences.userNodeForPackage(PreviewDeck::class.java) }
    val repo = remember { DeckRepository(File(directory, "deck.json"),
        registry.filter { switches.getBoolean("disabled.${it.id}", false) }.map { it.id }.toSet()) }
    val host = remember { PreviewDeck(repo) }
    val workflows = remember(directory) { DesktopWorkflowStore(directory) }
    val workflowState by workflows.state.collectAsState()
    val deck by repo.operatingState.collectAsState()
    var tab by remember { mutableStateOf(WorkspaceTab.HOME) }
    var themeMode by remember {
        mutableStateOf(ThemeMode.entries.find { it.name == appearance.get("theme", null) } ?: ThemeMode.SYSTEM)
    }
    var simulating by remember { mutableStateOf(true) }
    var message by remember(tel.misa.mxpocket.i18n.AppLocale.current) {
        mutableStateOf(if (simulating) W.VirtualReady.text() else W.VirtualDisconnected.text())
    }
    var time by remember { mutableLongStateOf(now()) }
    var revision by remember { mutableIntStateOf(0) }
    val history = remember { mutableStateListOf<String>() }
    var setup by remember { mutableStateOf(false) }
    var setupReview by remember { mutableStateOf(false) }
    var diagnosticsRequest by remember { mutableIntStateOf(0) }
    var nestedBack by remember { mutableStateOf<(() -> Unit)?>(null) }
    val registerBack: @Composable (Boolean, () -> Unit) -> Unit = { enabled, back ->
        val current by rememberUpdatedState(back)
        DisposableEffect(enabled) {
            nestedBack = if (enabled) ({ current() }) else null
            onDispose { nestedBack = null }
        }
    }

    fun status(text: String) {
        message = text
        history.add(text)
        if (history.size > 60) history.removeAt(0)
        revision++
    }
    LaunchedEffect(simulating) {
        if (simulating) while (true) {
            time = now()
            if (host.tick(time)) status(W.TimerFinished.text())
            delay(1_000)
        }
    }
    val profile = deck.activeProfile.displayName()
    val page = deck.activeProfile.displayPageName(deck.activePage)
    Window(::exitApplication, title = "$profile - MX Pocket",
        state = rememberWindowState(width = 1120.dp, height = 760.dp),
        onPreviewKeyEvent = {
            if (!setup && simulating && tab == WorkspaceTab.HOME && it.key in listOf(Key.PageUp, Key.PageDown)) {
                host.pageButtons.input(if (it.key == Key.PageUp) -1 else 1, it.type == KeyEventType.KeyDown, false,
                    { delta -> repo.changePage(delta).onFailure { error -> status(error.message ?: "Cannot change profile.") } },
                    { repo.enterGame().onFailure { error -> status(error.message ?: "Game unavailable.") } })
                true
            } else if (it.type == KeyEventType.KeyUp && it.key == Key.Escape) {
                when {
                    setup -> { setup = false; true }
                    nestedBack != null -> { nestedBack?.invoke(); true }
                    tab != WorkspaceTab.HOME -> { tab = WorkspaceTab.HOME; true }
                    else -> false
                }
            } else false
        }) {
        val acceptsPageKeys = LocalWindowInfo.current.isWindowFocused && !setup && simulating && tab == WorkspaceTab.HOME
        DisposableEffect(acceptsPageKeys) { onDispose { host.pageButtons.reset() } }
        val profiles = deck.visibleProfiles.map { ProfileChoice(it.id, it.displayName()) }
        val board = remember(deck, time, revision, simulating, tel.misa.mxpocket.i18n.AppLocale.current) { host.board(time, deck) }
        val tiles = board.faces.map { it.toGridTile(simulating) }
        val state = PocketScreenState(status = message, pluginTitle = if (page == profile) profile else "$profile / $page",
            tiles = tiles, profiles = profiles, activeId = deck.activeProfileId, reason = displaySelectionReason(deck.selectionReason),
            connectionLabel = if (simulating) W.VirtualKeypad.text() else W.Disconnected.text(), diagnostics = W.NoHid.text(),
            log = history.joinToString("\n"), simulator = DesktopSimulator(DEVICE_LIMIT, board.grid.takeIf { simulating }),
            activePageId = deck.activePage.id, themeMode = themeMode, language = language.choice)
        val plugins = registry.map {
            val available = it.id in setOf("focus", "audio", "game.xo")
            PluginItem(it.id, it.displayName(), available && it.id !in deck.disabledPluginIds, if (available) W.Simulation.text() else W.AndroidOnly.text(), when (it.id) {
                "focus" -> W.TimerSimulation.text()
                "audio" -> W.AudioSimulation.text()
                "game.xo" -> W.GameSimulation.text()
                else -> W.AndroidOnly.text()
            }, available && profiles.any { profile -> profile.id == it.id }, it.id == deck.defaultProfileId, null, canToggle = available)
        }
        val options = buildList {
            add(BindingOption(LayoutCopy.Back.text(), ActionCall(DeckActions.BACK)))
            add(BindingOption(LayoutCopy.Root.text(), ActionCall(DeckActions.ROOT)))
            DeckCommand.entries.filter { it.deck in setOf("audio", "navigation", "teams") && it.deck !in deck.disabledPluginIds }.forEach {
                add(BindingOption(LayoutCopy.CategoryAction.text(builtInDeckName(it.deck), it.displayLabel), ActionCall(it.id)))
            }
            deck.profiles.forEach { add(BindingOption(LayoutCopy.BindProfile.text(it.displayName()), ActionCall(DeckActions.PROFILE, mapOf("profileId" to it.id)))) }
            workflowState.definitions.forEach {
                add(BindingOption(LayoutCopy.AndroidWorkflow.text(it.name), ActionCall("workflow.run", mapOf("workflowId" to it.id))))
            }
            addAll(availableFactoryKeyBindings(deck.disabledPluginIds))
        }
        val backup = { save: Boolean ->
            try { chooseBackup(window, repo, save)?.let(::status) }
            catch (e: Exception) { status(W.BackupFailed.text(e.message)) }
            Unit
        }
        if (diagnosticsRequest != 0) DiagnosticsWindow(state, diagnosticsRequest) { diagnosticsRequest = 0 }
        PreviewTheme(themeMode) {
            if (setup) {
                SetupScreen(SetupScreenState(emptySet(), setupReview, emptyList(), "", false,
                    options = SetupFeature.entries.map { SetupOption(it, unavailableReason = W.HostRequired.text()) },
                    introduction = DEVICE_LIMIT, reviewHint = W.NoSetup.text())) {
                    when (it) {
                        SetupEvent.Review -> setupReview = true
                        SetupEvent.Edit -> setupReview = false
                        else -> setup = false
                    }
                }
            } else PocketWorkspace(tab, { tab = it }, state, plugins, event = { event ->
                try {
                    when (event) {
                        PocketScreenEvent.EnterPreview -> { host.resetInput(); simulating = true; status(W.VirtualConnected.text()) }
                        PocketScreenEvent.StopPreview -> { host.resetInput(); simulating = false; status(W.VirtualDisconnected.text()) }
                        is PocketScreenEvent.SelectPlugin -> { host.select(event.id); status(W.Selected.text(host.profileName)) }
                        is PocketScreenEvent.ChangePage -> { host.cycle(event.delta); status(W.Selected.text(host.profileName)) }
                        is PocketScreenEvent.VirtualKey -> if (simulating && tab == WorkspaceTab.HOME && !setup)
                            host.press(event, now())?.let(::status)
                        is PocketScreenEvent.DefaultProfile -> repo.setDefault(event.id).getOrThrow()
                        is PocketScreenEvent.EnablePlugin -> {
                            switches.putBoolean("disabled.${event.id}", !event.enabled)
                            host.setPluginEnabled(event.id, event.enabled)
                        }
                        PocketScreenEvent.FeatureSetup -> { setupReview = false; setup = true }
                        is PocketScreenEvent.SetTheme -> { themeMode = event.mode; appearance.put("theme", event.mode.name) }
                        is PocketScreenEvent.SetLanguage -> language.set(event.language)
                        else -> status(DEVICE_LIMIT)
                    }
                } catch (e: Exception) { status(e.message ?: W.Failed.text()) }
            }, editor = {
                LayoutEditor(remember(repo) { repo.asEditorStore() }, options, emptyMap(), { backup(true) }, { backup(false) },
                    live = state, backHandler = registerBack)
            }, workflows = { DesktopWorkflows(workflows, registerBack) }, openDiagnosticsWindow = { diagnosticsRequest++ })
        }
    }
}

@Composable
internal fun DiagnosticsWindow(screen: PocketScreenState, request: Int, onClose: () -> Unit) {
    val windowState = rememberWindowState(width = 640.dp, height = 680.dp)
    Window(onCloseRequest = onClose, title = "${W.Diagnostics.text()} - MX Pocket", state = windowState, onPreviewKeyEvent = {
        if (it.type == KeyEventType.KeyUp && it.key == Key.Escape) { onClose(); true } else false
    }) {
        LaunchedEffect(request) {
            windowState.isMinimized = false
            window.toFront()
            window.requestFocus()
        }
        PocketTheme(screen.themeMode) { DiagnosticsScreen(screen, onClose) {} }
    }
}
