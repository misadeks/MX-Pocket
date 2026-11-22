package tel.misa.mxpocket.preview

import androidx.compose.runtime.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import java.awt.FileDialog
import androidx.compose.ui.awt.ComposeWindow
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tel.misa.mxpocket.actions.ActionCall
import tel.misa.mxpocket.deck.*
import tel.misa.mxpocket.decks.DeckCommand
import tel.misa.mxpocket.shared.ui.*

private const val SIM_LIMIT = "The virtual keypad runs locally. It never sends USB or Windows media commands."

private fun chooseBackup(owner: ComposeWindow, repo: DeckRepository, save: Boolean): String? {
    val picker = FileDialog(owner, if (save) "Export layouts" else "Import layouts",
        if (save) FileDialog.SAVE else FileDialog.LOAD)
    try {
        if (save) picker.file = "mx-pocket-layouts.json"
        picker.isVisible = true
        val file = picker.files.firstOrNull() ?: return null
        if (save) file.writeText(repo.exportJson()) else {
            val bytes = file.inputStream().use { it.readNBytes(DeckLimits.MAX_BYTES + 1) }
            require(bytes.size <= DeckLimits.MAX_BYTES) { "Layout file too large." }
            repo.importJson(bytes.decodeToString(throwOnInvalidSequence = true)).getOrThrow()
        }
        return if (save) "Layouts exported." else "Layouts imported."
    } finally { picker.dispose() }
}

fun main() = application {
    val directory = remember {
        File(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "MX Pocket").also {
            Files.createDirectories(it.toPath())
        }
    }
    val repo = remember { DeckRepository(File(directory, "deck.json")) }
    val host = remember { PreviewDeck(repo) }
    val workflows = remember(directory) { DesktopWorkflowStore(directory) }
    val workflowState by workflows.state.collectAsState()
    val deck by repo.state.collectAsState()
    var tab by remember { mutableStateOf(WorkspaceTab.HOME) }
    val appearance = remember { java.util.prefs.Preferences.userRoot().node("tel/misa/mxpocket") }
    var themeMode by remember {
        mutableStateOf(ThemeMode.entries.find { it.name == appearance.get("theme", null) } ?: ThemeMode.SYSTEM)
    }
    var message by remember { mutableStateOf("Connect the wired keypad, or choose the virtual keypad in Settings.") }
    var time by remember { mutableLongStateOf(monotonicMs()) }
    var revision by remember { mutableIntStateOf(0) }
    val history = remember { mutableStateListOf<String>() }
    var repaint by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
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
                if (host.tick(time)) status("Focus timer finished. No tone played.")
            }
            delay(1_000)
        }
    }
    Window({ scope.launch { usb.close(); exitApplication() }; Unit }, title = "${deck.activeProfile.name} - MX Pocket",
        state = rememberWindowState(width = 1120.dp, height = 760.dp),
        onPreviewKeyEvent = {
            if (it.type == KeyEventType.KeyUp && it.key == Key.Escape) {
                when {
                    nestedBack != null -> { nestedBack?.invoke(); true }
                    tab != WorkspaceTab.HOME -> { tab = WorkspaceTab.HOME; true }
                    else -> false
                }
            } else false
        }) {
        val simulating = host.simulator
        val profiles = remember(deck.profiles) { deck.profiles.map { ProfileChoice(it.id, it.name, profileReason(it.id)) } }
        val board = remember(deck, time, revision, simulating, usb.ready) { host.board(monotonicMs(), deck) }
        val pixels = board.faces.mapIndexed { i, face -> rememberKeyPixels(i, face) }
        LaunchedEffect(usb.ready, board.grid, pixels, repaint) {
            if (usb.ready) usb.submit(board.grid, pixels, repaint)
        }
        val tiles = board.faces.mapIndexed { i, face -> face.toGridTile(simulating, pixels[i]) }
        val state = PocketScreenState(status = message, pluginTitle = if (deck.activeProfile.name == deck.activePage.name)
            deck.activeProfile.name else "${deck.activeProfile.name} / ${deck.activePage.name}",
            tiles = tiles, profiles = profiles, activeId = deck.activeProfileId, reason = deck.selectionReason,
            connectionLabel = when { simulating -> "Virtual keypad"; usb.ready -> "USB ready"; usb.active -> "Connecting"; else -> "Disconnected" },
            usbReady = usb.ready, connectionActive = usb.active, diagnostics = usb.diagnostics,
            log = history.joinToString("\n"), simulator = DesktopSimulator(SIM_LIMIT, board.grid.takeIf { simulating }, supportsUsb = true),
            activePageId = deck.activePage.id, themeMode = themeMode)
        val plugins = remember(profiles, simulating, deck.defaultProfileId) { defaultDeck().profiles.map {
            val reason = pluginReason(it.id)
            PluginItem(it.id, it.name, reason == null, when (it.id) {
                "audio" -> if (simulating) "Virtual audio" else "Windows media"
                else -> "On this computer"
            }, when (it.id) {
                "focus" -> "Start, pause or reset a focus session. Choose a preset or adjust by five minutes."
                "audio" -> if (simulating) "Simulated volume and playback. Windows audio is unchanged."
                    else "Use the wired keys for volume, mute and playback in a compatible media app."
                "game.xo" -> "Play locally against the computer or a second player."
                else -> reason.orEmpty()
            }, profiles.any { profile -> profile.id == it.id }, it.id == deck.defaultProfileId, null,
                canToggle = false, unavailableReason = reason)
        } }
        val options = remember(profiles, workflowState.definitions, simulating) { buildList {
            add(BindingOption("Back", ActionCall(DeckActions.BACK)))
            add(BindingOption("Return to root", ActionCall(DeckActions.ROOT)))
            DeckCommand.entries.filter { it.deck in setOf("audio", "navigation", "teams") }.forEach {
                add(BindingOption("${it.deck}: ${it.label}", ActionCall(it.id)))
            }
            profiles.forEach { add(BindingOption("Profile: ${it.name}", ActionCall(DeckActions.PROFILE, mapOf("profileId" to it.id)))) }
            workflowState.definitions.forEach {
                add(BindingOption("Workflow: ${it.name} (Android execution)", ActionCall("workflow.run", mapOf("workflowId" to it.id))))
            }
            addAll(factoryKeyBindings)
        }.map { it.copy(unavailableReason = bindingReason(it.call, simulating)) } }
        val backup = { save: Boolean ->
            try { chooseBackup(window, repo, save)?.let(::status) }
            catch (e: Exception) { status("Layout backup failed: ${e.message}") }
            Unit
        }
        PocketTheme(themeMode) {
            PocketWorkspace(tab, { tab = it }, state, plugins, event = { event ->
                try {
                    when (event) {
                        PocketScreenEvent.ToggleConnection -> usb.toggle()
                        PocketScreenEvent.EnterPreview -> if (!usb.active) { host.simulator = true; status(SIM_LIMIT) }
                        PocketScreenEvent.StopPreview -> { host.simulator = false; status("Virtual keypad disconnected.") }
                        is PocketScreenEvent.SelectPlugin -> { host.select(event.id); status("Selected ${host.profileName}.") }
                        is PocketScreenEvent.ChangePage -> { host.cycle(event.delta); status("Selected ${host.profileName}.") }
                        is PocketScreenEvent.VirtualKey -> if (host.simulator && !usb.active && tab == WorkspaceTab.HOME)
                            host.press(event, monotonicMs())?.let(::status)
                        is PocketScreenEvent.DefaultProfile -> if (profileReason(event.id) == null) repo.setDefault(event.id).getOrThrow()
                        PocketScreenEvent.Repaint -> if (usb.ready) { repaint++; status("Redrawing LCD keys.") }
                        is PocketScreenEvent.SetTheme -> { themeMode = event.mode; appearance.put("theme", event.mode.name) }
                        else -> status("This operation is unavailable on Windows.")
                    }
                } catch (e: Exception) { status(e.message ?: "Workspace operation failed.") }
            }, editor = {
                LayoutEditor(remember(repo) { repo.asEditorStore() }, options, emptyMap(), { backup(true) }, { backup(false) },
                    live = state, backHandler = registerBack)
            }, workflows = { DesktopWorkflows(workflows, registerBack) })
        }
    }
}
