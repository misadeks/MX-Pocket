package tel.misa.mxpocket.preview

import tel.misa.mxpocket.i18n.WorkspaceCopy as W
import tel.misa.mxpocket.i18n.displayName
import tel.misa.mxpocket.i18n.displayPageName
import tel.misa.mxpocket.i18n.displaySelectionReason

import androidx.compose.runtime.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import java.awt.FileDialog
import androidx.compose.ui.awt.ComposeWindow
import java.io.File
import java.nio.file.Files
import java.util.prefs.Preferences
import tel.misa.mxpocket.i18n.DesktopCopy as H
import kotlinx.coroutines.launch
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

private val DEVICE_LIMIT get() = H.Separate.text()

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
    val simulating = host.simulator
    val scope = rememberCoroutineScope()
    var repaint by remember { mutableIntStateOf(0) }
    var message by remember(tel.misa.mxpocket.i18n.AppLocale.current) {
        mutableStateOf(if (simulating) W.VirtualReady.text() else H.Start.text())
    }
    var time by remember { mutableLongStateOf(monotonicMs()) }
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
    val usb = remember { DesktopUsb(scope, host, ::status) }
    LaunchedEffect(Unit) {
        while (true) {
            if (host.timerRunning) {
                time = monotonicMs()
                if (host.tick(time)) status(W.TimerFinished.text())
            }
            delay(1_000)
        }
    }
    val profile = deck.activeProfile.displayName()
    val page = deck.activeProfile.displayPageName(deck.activePage)
    var keyboard by remember { mutableStateOf<((Int, Boolean) -> Unit)?>(null) }
    Window({ scope.launch { usb.close(); exitApplication() }; Unit }, title = "$profile - MX Pocket",
        state = rememberWindowState(width = 1120.dp, height = 760.dp),
        onPreviewKeyEvent = {
            val input = keyboard
            if (input != null && it.key in listOf(Key.PageUp, Key.PageDown)) {
                input(if (it.key == Key.PageUp) -1 else 1, it.type == KeyEventType.KeyDown)
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
        PreviewTheme(themeMode) {
        val longPressMs = LocalViewConfiguration.current.longPressTimeoutMillis
        val windowInfo = LocalWindowInfo.current
        val acceptsPageKeys = windowInfo.isWindowFocused && !setup && simulating && tab == WorkspaceTab.HOME
        if (acceptsPageKeys) DisposableEffect(longPressMs) {
            val buttons = host.pageButtons(scope, longPressMs, ::status)
            keyboard = { delta, down ->
                val surface = repo.operatingState.value
                buttons.input(delta, down) {
                    windowInfo.isWindowFocused && host.simulator && !setup && tab == WorkspaceTab.HOME &&
                        surface === repo.operatingState.value
                }
            }
            onDispose { buttons.reset(); keyboard = null }
        }
        val profiles = deck.visibleProfiles.map { ProfileChoice(it.id, it.displayName(), profileReason(it.id)) }
        val board = remember(deck, time, revision, simulating, usb.ready, tel.misa.mxpocket.i18n.AppLocale.current) { host.board(time, deck) }
        val pixels = board.faces.mapIndexed { i, face -> rememberKeyPixels(i, face) }
        LaunchedEffect(usb.ready, board.grid, pixels, repaint) {
            if (usb.ready) usb.submit(board.grid, pixels, repaint)
        }
        val tiles = board.faces.mapIndexed { i, face -> face.toGridTile(simulating, pixels[i]) }

        val state = PocketScreenState(status = message, pluginTitle = if (page == profile) profile else "$profile / $page",
            tiles = tiles, profiles = profiles, activeId = deck.activeProfileId, reason = displaySelectionReason(deck.selectionReason),
            connectionLabel = when { simulating -> W.VirtualKeypad.text(); usb.ready -> W.UsbConnected.text(); usb.active -> W.Connecting.text(); else -> W.Disconnected.text() },
            usbReady = usb.ready, connectionActive = usb.active, diagnostics = usb.diagnostics,
            log = history.joinToString("\n"), simulator = DesktopSimulator(DEVICE_LIMIT, board.grid.takeIf { simulating }, supportsUsb = true),
            activePageId = deck.activePage.id, themeMode = themeMode, language = language.choice)
        val plugins = registry.map {
            val reason = pluginReason(it.id)
            val available = reason == null
            PluginItem(it.id, it.displayName(), available && it.id !in deck.disabledPluginIds, if (!available) W.AndroidOnly.text() else if (simulating) W.Simulation.text() else if (it.id == "audio") H.WindowsMedia.text() else H.Local.text(), when (it.id) {
                "focus" -> W.TimerSimulation.text()
                "audio" -> if (simulating) W.AudioSimulation.text() else H.WiredAudio.text()
                "game.xo" -> if (simulating) H.PreviousOrNext.text() else tel.misa.mxpocket.i18n.KeypadCopy.GameDetails.text()
                else -> W.AndroidOnly.text()
            }, available && profiles.any { profile -> profile.id == it.id }, it.id == deck.defaultProfileId, null, canToggle = available, unavailableReason = reason)
        }
        val options = buildList {
            add(BindingOption(LayoutCopy.Back.text(), ActionCall(DeckActions.BACK)))
            add(BindingOption(LayoutCopy.Root.text(), ActionCall(DeckActions.ROOT)))
            DeckCommand.entries.filter { it.deck in setOf("audio", "navigation", "teams") && it.deck !in deck.disabledPluginIds }.forEach {
                add(BindingOption(LayoutCopy.CategoryAction.text(builtInDeckName(it.deck), it.displayLabel), ActionCall(it.id)))
            }
            deck.visibleProfiles.forEach { add(BindingOption(LayoutCopy.BindProfile.text(it.displayName()), ActionCall(DeckActions.PROFILE, mapOf("profileId" to it.id)))) }
            workflowState.definitions.forEach {
                add(BindingOption(LayoutCopy.AndroidWorkflow.text(it.name), ActionCall("workflow.run", mapOf("workflowId" to it.id))))
            }
            addAll(availableFactoryKeyBindings(deck.disabledPluginIds))
        }.map { it.copy(unavailableReason = bindingReason(it.call, simulating)) }
        val backup = { save: Boolean ->
            try { chooseBackup(window, repo, save)?.let(::status) }
            catch (e: Exception) { status(W.BackupFailed.text(e.message)) }
            Unit
        }
        if (diagnosticsRequest != 0) DiagnosticsWindow(state, diagnosticsRequest) { diagnosticsRequest = 0 }
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
            } else {
                val dispatch: (PocketScreenEvent) -> Unit = { event ->
                try {
                    when (event) {
                        PocketScreenEvent.ToggleConnection -> usb.toggle(longPressMs)
                        PocketScreenEvent.Repaint -> if (usb.ready) { repaint++; status(H.Redrawing.text()) }
                        PocketScreenEvent.EnterPreview -> if (!usb.active) { host.simulator = true; status(W.VirtualConnected.text()) }
                        PocketScreenEvent.StopPreview -> { host.simulator = false; status(W.VirtualDisconnected.text()) }
                        is PocketScreenEvent.SelectPlugin -> { host.select(event.id); status(W.Selected.text(host.profileName)) }
                        is PocketScreenEvent.ChangePage -> { host.cycle(event.delta); status(W.Selected.text(host.profileName)) }
                        is PocketScreenEvent.VirtualKey -> if (simulating && !usb.active && tab == WorkspaceTab.HOME && !setup)
                            host.press(event, monotonicMs())?.let(::status)
                        is PocketScreenEvent.DefaultProfile -> if (profileReason(event.id) == null) repo.setDefault(event.id).getOrThrow()
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
                }
                PocketWorkspace(tab, { tab = it }, state, plugins, dispatch, profiles = {
                    SimulatorProfiles(state, { dispatch(PocketScreenEvent.SelectPlugin(it)) }) {
                LayoutEditor(remember(repo) { repo.asEditorStore() }, options, emptyMap(), { backup(true) }, { backup(false) },
                    live = state, backHandler = registerBack)
                    }
                }, workflows = { DesktopWorkflows(workflows, registerBack) }, openDiagnosticsWindow = { diagnosticsRequest++ })
            }
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
