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
import tel.misa.mxpocket.actions.ActionCall
import tel.misa.mxpocket.deck.*
import tel.misa.mxpocket.decks.DeckCommand
import tel.misa.mxpocket.shared.ui.*

private fun now() = System.nanoTime() / 1_000_000
private const val DEVICE_LIMIT = "Windows simulator: no USB connection, phone apps, camera, media sessions or Android access grants."

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
    var simulating by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf("Virtual keypad connected. No USB messages are sent.") }
    var time by remember { mutableLongStateOf(now()) }
    var revision by remember { mutableIntStateOf(0) }
    val history = remember { mutableStateListOf<String>() }
    var setup by remember { mutableStateOf(false) }
    var setupReview by remember { mutableStateOf(false) }
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
            if (host.tick(time)) status("Focus timer finished. No tone played.")
            delay(1_000)
        }
    }
    Window(::exitApplication, title = "${deck.activeProfile.name} - MX Pocket",
        state = rememberWindowState(width = 1120.dp, height = 760.dp),
        onPreviewKeyEvent = {
            if (it.type == KeyEventType.KeyUp && it.key == Key.Escape) {
                when {
                    setup -> { setup = false; true }
                    nestedBack != null -> { nestedBack?.invoke(); true }
                    tab != WorkspaceTab.HOME -> { tab = WorkspaceTab.HOME; true }
                    else -> false
                }
            } else false
        }) {
        val profiles = deck.profiles.map { ProfileChoice(it.id, it.name) }
        val board = remember(deck, time, revision, simulating) { host.board(time, deck) }
        val tiles = board.faces.map { it.toGridTile(simulating) }
        val state = PocketScreenState(status = message, pluginTitle = if (deck.activeProfile.name == deck.activePage.name)
            deck.activeProfile.name else "${deck.activeProfile.name} / ${deck.activePage.name}",
            tiles = tiles, profiles = profiles, activeId = deck.activeProfileId, reason = deck.selectionReason,
            connectionLabel = if (simulating) "Virtual keypad" else "Disconnected", diagnostics = "No USB diagnostics: Windows has no HID adapter.",
            log = history.joinToString("\n"), simulating = simulating, deviceLimit = DEVICE_LIMIT,
            activePageId = deck.activePage.id, hardwareSummary = !simulating,
            virtualGrid = board.grid.takeIf { simulating })
        val plugins = defaultDeck().profiles.map {
            PluginItem(it.id, it.name, true, "Local simulation", when (it.id) {
                "focus" -> "Timer and presets run locally. No completion sound."
                "audio" -> "Volume and playback are simulated. Windows media is unchanged."
                "game.xo" -> "Play against the computer or a second player on this keypad."
                else -> "Android device actions do not run on Windows."
            }, profiles.any { profile -> profile.id == it.id }, it.id == deck.defaultProfileId, null, canToggle = false)
        }
        val options = buildList {
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
        }
        val backup = { save: Boolean ->
            try { chooseBackup(window, repo, save)?.let(::status) }
            catch (e: Exception) { status("Layout backup failed: ${e.message}") }
            Unit
        }
        PocketTheme {
            if (setup) {
                SetupScreen(SetupScreenState(emptySet(), setupReview, emptyList(), "", false,
                    options = SetupFeature.entries.map { SetupOption(it, unavailableReason = "Requires the Android host.") },
                    introduction = DEVICE_LIMIT, reviewHint = "No Android setup steps on this host.")) {
                    when (it) {
                        SetupEvent.Review -> setupReview = true
                        SetupEvent.Edit -> setupReview = false
                        else -> setup = false
                    }
                }
            } else PocketWorkspace(tab, { tab = it }, state, plugins, event = { event ->
                try {
                    when (event) {
                        PocketScreenEvent.EnterPreview -> { host.resetInput(); simulating = true; status("Virtual keypad connected.") }
                        PocketScreenEvent.StopPreview -> { host.resetInput(); simulating = false; status("Virtual keypad disconnected.") }
                        is PocketScreenEvent.SelectPlugin -> { host.select(event.id); status("Selected ${host.profileName}.") }
                        is PocketScreenEvent.ChangePage -> { host.cycle(event.delta); status("Selected ${host.profileName}.") }
                        is PocketScreenEvent.VirtualKey -> if (simulating && tab == WorkspaceTab.HOME && !setup)
                            host.press(event, now())?.let(::status)
                        is PocketScreenEvent.DefaultProfile -> repo.setDefault(event.id).getOrThrow()
                        PocketScreenEvent.FeatureSetup -> { setupReview = false; setup = true }
                        else -> status(DEVICE_LIMIT)
                    }
                } catch (e: Exception) { status(e.message ?: "Workspace operation failed.") }
            }, editor = {
                LayoutEditor(remember(repo) { repo.asEditorStore() }, options, emptyMap(), { backup(true) }, { backup(false) },
                    live = state, backHandler = registerBack)
            }, workflows = { DesktopWorkflows(workflows, registerBack) })
        }
    }
}
