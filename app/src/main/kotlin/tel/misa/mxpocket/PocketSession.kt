package tel.misa.mxpocket

import android.app.Application
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import androidx.core.content.ContextCompat
import tel.misa.mxpocket.decks.*
import tel.misa.mxpocket.driver.KeyEvent
import tel.misa.mxpocket.driver.MxKeypad
import tel.misa.mxpocket.driver.KeypadSink
import tel.misa.mxpocket.driver.SimulatedKeypad
import tel.misa.mxpocket.plugins.*
import tel.misa.mxpocket.deck.*
import tel.misa.mxpocket.actions.*
import tel.misa.mxpocket.workflow.*
import java.time.LocalDateTime

open class PocketApplication : Application(), WorkflowProvider {
    internal val session by lazy { createSession() }
    override val workflows get() = session.workflows
    internal open fun createSession() = PocketSession(this)
}

internal typealias KeypadOutput = KeypadSink

internal typealias OpenKeypad = suspend (UsbManager, UsbDevice, (KeyEvent) -> Unit,
    (String) -> Unit, (Throwable) -> Unit) -> KeypadOutput

/** One main-thread owner across phone screens. Only preview or a promoted service enables plugins. */
internal class PocketSession(
    private val app: Context,
    registeredPlugins: List<PocketPlugin> = BuiltInPlugins.create(app.getString(R.string.focus),
        app.getString(R.string.audio), app.getString(R.string.phone), app.getString(R.string.xo), app.getString(R.string.onenote)),
    private val usageDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val usageTimeMillis: () -> Long = System::currentTimeMillis,
    private val open: OpenKeypad = { manager, device, event, log, error ->
        RenderedKeypad(MxKeypad.connect(manager, device, event, log, error))
    },
) {
    interface Screen {
        fun render()
        fun openApp(app: PhoneApp): Boolean
        fun openUri(uri: String): Boolean = false
        fun shareText(text: String): Boolean = false
        fun deckAction(command: DeckCommand, component: String?): DeckResult = unavailable("Open MX Pocket to use this control.")
    }
    data class Request(val name: String, val token: String = UUID.randomUUID().toString())
    sealed interface Target {
        data class Phone(val app: PhoneApp) : Target
        data class FavoriteApp(val component: String) : Target
        data class Studio(val mode: CameraMode) : Target
        data class Uri(val uri: String) : Target
        data class Share(val text: String) : Target
    }
    data class PhoneAction(val target: Target, val ticket: Request, val index: Int, val receipt: Long,
        val pressedAt: Long, val id: String = UUID.randomUUID().toString())
    companion object { const val PHONE_ACTION_TTL = 60_000L }
    private data class Layout(val profile: String, val page: DeckPage, val pluginEpoch: Int,
        val disabled: Set<String>)
    private data class GridTarget(val screen: Long, val pluginPage: Int, val repaint: Long, val art: Long)
    private data class LcdKey(val tile: KeyTile, val action: ActionCall?, val image: Bitmap,
        val repaint: Long, val hasTrackDetails: Boolean) {
        fun matches(other: LcdKey) = repaint == other.repaint && action == other.action &&
            tile.enabled == other.tile.enabled && tile.readOnly == other.tile.readOnly && tile.inputToken == other.tile.inputToken &&
            (image === other.image || image.sameAs(other.image))
    }

    private val main = Handler(Looper.getMainLooper())
    private val physicalKeys = PhysicalChordInput(main)
    private data class PluginSlot(val index: Int, val generation: Int)
    private val physicalOwners = arrayOfNulls<PluginSlot>(9)
    private val audio = app.getSystemService(AudioManager::class.java)
    private val logs = ArrayDeque<String>()
    private var screen: Screen? = null
    private val sessionScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    val deck = DeckRepository(app)
    private val switches = app.getSharedPreferences("plugins", Context.MODE_PRIVATE)
    private val detectApps = MutableStateFlow(switches.getBoolean("detectForegroundApps", false))
    val detectForegroundApps = detectApps.asStateFlow()
    fun setDetectForegroundApps(enabled: Boolean) {
        switches.edit().putBoolean("detectForegroundApps", enabled).apply()
        detectApps.value = enabled
    }
    private val disabled = MutableStateFlow(switches.getStringSet("disabled", emptySet()).orEmpty().toSet())
    val disabledPlugins = disabled.asStateFlow()
    private val _signals = MutableStateFlow(emptyMap<String,String>())
    val signals = _signals.asStateFlow()
    private var visibleKey = false
    private val cameraAccess = CameraControlsAccess(app)
    internal val favoriteApps = FavoriteApps(app)
    private val media = MediaDeck(app, ::canShowTrack)
    private val decks = MutableStateFlow(tel.misa.mxpocket.decks.DeckState())
    val deckState = decks.asStateFlow()
    private var deckWatch: Job? = null
    val plugins = PluginEngine(
        registeredPlugins,
        CoroutineScope(Dispatchers.Main.immediate), SystemClock::elapsedRealtime,
        readAudio = { AudioSnapshot(audio.getStreamVolume(AudioManager.STREAM_MUSIC),
            audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)) },
        perform = ::perform, invalidateUi = ::invalidate, reportError = { id, error -> fail("Plugin $id failed", error) },
        observeDecks = deckState,
        readActiveTool = { OneNoteAccessService.connected?.activeTool() },
        readToolBar = { OneNoteAccessService.connected?.toolBar() ?: emptyList() },
    )
    var request: Request? = null
        private set
    var status = app.getString(R.string.not_connected)
        private set
    var preview = false
        private set
    private var hardwareScope: CoroutineScope? = null
    private var output: KeypadOutput? = null
    private var onEnd: (() -> Unit)? = null
    private var onHandoff: ((PhoneAction) -> DeckResult)? = null
    private var physicalPressAt: Long? = null
    private var physicalIndex: Int? = null
    private var pendingAction: PhoneAction? = null
    private val images = KeyImages(favoriteApps::icon)
    private var repaintEpoch = 0L
    private var paintedLayout: Layout? = null
    private var scanLayout: Layout? = null
    private var nextKey = 0
    private var screenGeneration = 0L
    private var paintedTarget: GridTarget? = null
    private var splitTarget: GridTarget? = null
    private var gridHasArt = false
    private var painting: Job? = null
    private var scheduled = false
    private var simulator: SimulatedKeypad? = null
    val ready get() = output != null
    val connectionLabel get() = when {
        preview -> "Virtual keypad"
        output != null -> "USB session active"
        request != null -> "Connecting"
        else -> "Disconnected"
    }
    val isPhoneShortcutPage get() = deck.state.value.let {
        it.activeProfileId == "phone" && it.activePage.id == it.activeProfile.rootPageId && plugins.selectedId == "phone"
    }
    private var signalClock: Job? = null
    private val lcd = arrayOfNulls<LcdKey>(9)
    // The input reader captures the receipt at arrival, before posting the event to main.
    @Volatile private var receipts = LongArray(9)
    private var lastLayout: Layout? = null
    private val feedback = mutableMapOf<Int, String>()
    private var feedbackPage: DeckPage? = null
    private var feedbackProfile: String? = null
    private var attachedUsb = emptySet<String>()
    val actions = ActionDispatcher { call -> execute(call, null) }
    val workflows = WorkflowController(app, sessionScope, object : WorkflowHost {
        override fun snapshot() = workflowContext()
        override fun actions() = workflowActions()
        override suspend fun execute(id: String, params: Map<String,String>): Boolean {
            // These host actions are offered only behind a visible confirmation in the workflow runner.
            if (id == DeckCommand.HOME.id || id == DeckCommand.OPEN.id) {
                val command = DeckCommand.fromId(id) ?: return false
                if (command.deck in disabled.value || !app.isUnlockedAndInteractive()) return false
                return screen?.deckAction(command, params["component"])?.success == true
            }
            return this@PocketSession.actions.dispatch(ActionCall(id,params)).success
        }
    })

    init {
        sessionScope.launch {
            deck.state.collect { physicalKeys.cancel(); syncLayout(); invalidate() }
        }
        sessionScope.launch { workflows.status.collect { invalidate() } }
        sessionScope.launch { workflows.definitions.collect { invalidate() } }
        sessionScope.launch { deckState.collect { invalidate() } }
    }

    private fun syncLayout() {
        val state = deck.state.value
        if (lastLayout?.let { it.profile != state.activeProfileId || it.page != state.activePage } == true)
            plugins.setEnabled(false) // Release held keys before rebinding even if the plugin ID is unchanged.
        val id = selectedPlugin(state.activePage)
        if (id != null && plugins.selectedId != id) plugins.select(id)
        plugins.setEnabled((output != null || preview && screen != null) && id != null && id !in disabled.value &&
            (!preview || screen != null))
        val layout = layout()
        if (layout != lastLayout) {
            physicalKeys.cancel()
            if (lastLayout?.profile != layout.profile || lastLayout?.page?.id != layout.page.id)
                screenGeneration++
            lastLayout = layout
            receipts = LongArray(9)
            pendingAction = null
            media.refreshDisplay()
        }
        if (feedbackPage != state.activePage || feedbackProfile != state.activeProfileId) {
            feedbackProfile = state.activeProfileId
            feedbackPage = state.activePage
            feedback.clear()
        }
    }

    private fun hasPlugin(id: String) = plugins.descriptors.any { it.id == id }
    private fun selectedPlugin(page: DeckPage) = page.keys.firstNotNullOfOrNull { key ->
        key.action?.slotOwner()?.takeIf(::hasPlugin)
    }
    private fun layout() = deck.state.value.let { Layout(it.activeProfileId, it.activePage, plugins.generation, disabled.value) }

    fun setPluginEnabled(id: String, enabled: Boolean) {
        require(hasPlugin(id))
        disabled.value = if (enabled) disabled.value - id else disabled.value + id
        switches.edit().putStringSet("disabled", disabled.value).apply()
        syncLayout()
        invalidate()
    }

    fun pluginSettings(id: String): Pair<String, () -> Unit>? {
        val command = when (id) {
            "launcher" -> DeckCommand.EDIT
            "studio" -> DeckCommand.CAMERA_ACCESS
            "audio" -> DeckCommand.MEDIA_ACCESS
            "navigation" -> DeckCommand.NAV_ACCESS
            else -> return null
        }
        return command.label to {
            if (id !in disabled.value) screen?.deckAction(command,null)?.let { message(it.message) }
        }
    }

    private fun canShowTrack(): Boolean {
        if ("audio" in disabled.value || deck.state.value.activeProfileId != "audio") return false
        val ticket = request
        if (ticket != null) return output != null && !preview && app.hasKeypadAccess(ticket)
        return screen != null && app.isUnlockedAndInteractive()
    }

    private fun visibleArtwork(state: MediaDeckState) = state.artwork?.takeIf { canShowTrack() }

    fun frame(): PluginFrame {
        val mediaState = deckState.value.media
        if ((mediaState.title.isNotEmpty() || mediaState.artwork != null) && !canShowTrack()) media.refreshDisplay()
        images.useArtwork(visibleArtwork(deckState.value.media))
        val native = plugins.render()
        val page = deck.state.value.activePage
        val keys = page.keys.mapIndexed { index, key ->
            val call = key.action
            val plugin = call?.slotOwner()
            val tile = if (call?.id == "plugin.key" && plugin == plugins.selectedId)
                call.params["index"]?.toIntOrNull()?.let(native.keys::getOrNull)
                else workflowTile(call) ?: platformTile(call)
            val registered = tile != null || call?.id in setOf(DeckActions.PAGE, DeckActions.PROFILE,
                DeckActions.BACK, DeckActions.ROOT, "sound.play","phone.open","keypad.status")
            val cameraControl = tile?.inputToken?.let(DeckCommand::fromId)?.takeIf { it.nativeControl }
            val available = registered && key.enabled && call != null && (plugin == null || plugin == plugins.selectedId) &&
                (cameraControl == null || isStudioKey(index, cameraControl)) &&
                ownerOf(call) !in disabled.value && (tile?.enabled != false)
            val footer = when {
                !key.enabled || ownerOf(call) in disabled.value -> "Disabled"
                // Keep the access state and its recovery hint even after an earlier failed press.
                tile?.glyph == NavigationGlyph.ACCESS_INFO ||
                    tile?.glyph == NavigationGlyph.RECENTS && !tile.enabled -> tile.footer
                !available && call != null -> "Unavailable"
                call?.id == "workflow.run" && workflows.status.value?.workflowId == call.params["workflowId"] -> tile?.footer.orEmpty()
                else -> feedback[index] ?: tile?.footer.orEmpty()
            }
            key.present(tile, actionLabel(call), footer, available)
        }
        val hasPluginKeys = page.keys.any { key ->
            val action = key.action
            action?.id == "plugin.key" && action.params["pluginId"] == plugins.selectedId
        }
        return PluginFrame(keys, if (hasPluginKeys) native.page else 0)
    }

    fun keyImage(index: Int, tile: KeyTile): Bitmap = images.image(index, tile)
    val panelArt get() = images.artwork

    private fun workflowContext() = WorkflowContext(request != null && output != null,
        signals.value["activity"].orEmpty(), signals.value - FOREGROUND_APP)

    private fun configuredApps(state: tel.misa.mxpocket.deck.DeckState, enabled: Boolean): Set<String> =
        if (!enabled || !state.automatic || !state.activeProfile.automatic || state.pinnedProfileId != null) emptySet()
        else state.profiles.asSequence().filter { it.automatic }.flatMap { it.rules }
            .filter { it.signal == FOREGROUND_APP }.map { it.value }.toSet()

    private fun updateObservation() {
        if (screen == null && (request == null || output == null)) {
            signalClock?.cancel()
            signalClock = null
            sampleContext(null)
            deck.clearObservation()
            return
        }
        if (signalClock?.isActive == true) {
            sampleContext()
            return
        }
        signalClock = sessionScope.launch {
            combine(deck.state, detectApps, ::configuredApps).distinctUntilChanged().collectLatest { configured ->
                sampleWhileEligible(configured)
            }
        }
    }

    private suspend fun sampleWhileEligible(configured: Set<String>) {
        var reader: ForegroundApps? = null
        var lastLockedPoll: Long? = null
        var unlockedSince: Long? = null
        var generation = 0L
        val watcher = if (configured.isEmpty()) null else app.watchForegroundBoundaries { boundary ->
            generation++
            reader = null
            val now = usageTimeMillis()
            val granted = app.hasUsageAccess()
            unlockedSince = now.takeIf { granted && boundary == ForegroundBoundary.UNLOCK }
            if (boundary == ForegroundBoundary.ACCESS || !granted) lastLockedPoll = null
            else if (boundary == ForegroundBoundary.SCREEN) lastLockedPoll = now
            sampleContext(null)
        }
        try {
            while (true) {
                var match: String? = null
                val granted = configured.isNotEmpty() && app.hasUsageAccess()
                if (granted && app.isUnlockedAndInteractive()) {
                    val source = reader ?: ForegroundApps(app.getSystemService(UsageStatsManager::class.java),
                        unlockedSince ?: usageTimeMillis(), lastLockedPoll).also { reader = it }
                    val started = generation
                    val result = withContext(usageDispatcher) { source.match(configured, usageTimeMillis()) }
                    if (started == generation) {
                        if (app.isUnlockedAndInteractive() && app.hasUsageAccess()) match = result
                        else { reader = null; lastLockedPoll = null; unlockedSince = null }
                    }
                } else {
                    reader = null
                    unlockedSince = null
                    lastLockedPoll = if (granted) usageTimeMillis() else null
                }
                sampleContext(match)
                delay(1000)
            }
        } finally { watcher?.close() }
    }

    private fun sampleContext(foregroundApp: String? = _signals.value[FOREGROUND_APP]) {
        val time = LocalDateTime.now()
        val connected = output != null && request != null
        val state = deck.state.value
        val snapshot = mapOf("foreground" to (screen != null).toString(),
            "activity" to if (screen != null) "MX Pocket" else "Not observed",
            "mediaActive" to (audio.isMusicActive && "audio" !in disabled.value).toString(), "keypadConnected" to connected.toString(),
            "usbDevice" to if (connected) "046D:C354" else "None",
            "simulator" to preview.toString(), "hour" to time.hour.toString(),
            "weekday" to time.dayOfWeek.name, "profile" to state.activeProfileId,
            "plugin" to (selectedPlugin(state.activePage) ?: "custom"),
            "workflow" to (workflows.status.value?.phase?.name ?: "IDLE")) +
            attachedUsb.associate { "usb.$it" to "true" } + platformSignals() +
            if (foregroundApp != null && foregroundApp in configuredApps(state, detectApps.value) &&
                app.isUnlockedAndInteractive() && app.hasUsageAccess())
                mapOf(FOREGROUND_APP to foregroundApp) else emptyMap()
        _signals.value = snapshot
        deck.observe(snapshot, SystemClock.elapsedRealtime()).onFailure { fail("Cannot select layout", it) }
        workflows.onContextChanged(workflowContext())
    }

    fun observeUsb(devices: Set<String>) { attachedUsb = devices; sampleContext() }

    fun selectProfile(id: String) {
        deck.selectProfile(id).onFailure { fail("Cannot select profile", it) }
        syncLayout()
        invalidate()
    }

    fun changePage(delta: Int) {
        deck.changePage(delta).onFailure { fail("Cannot change page", it) }
        syncLayout()
        invalidate()
    }

    private fun ownerOf(call: ActionCall?): String? = when {
        call?.id == "plugin.key" -> call.params["pluginId"]
        call?.id?.startsWith("media.") == true -> "audio"
        else -> call?.id?.substringBefore('.')?.takeIf(::hasPlugin)
    }

    private fun actionLabel(call: ActionCall?): String = when (call?.id) {
        null -> "Empty"
        DeckActions.BACK -> "Back"
        DeckActions.ROOT -> "Root"
        DeckActions.PROFILE -> deck.state.value.profiles.find { it.id == call.params["profileId"] }?.name ?: "Profile"
        DeckActions.PAGE -> deck.state.value.activeProfile.pages.find { it.id == call.params["pageId"] }?.name ?: "Page"
        "workflow.run" -> workflows.definitions.value.find { it.id == call.params["workflowId"] }?.name ?: "Missing workflow"
        "sound.play" -> "Play sound"
        else -> call.id
    }

    private fun workflowTile(call: ActionCall?): KeyTile? {
        if (call?.id != "workflow.run") return null
        val definition = workflows.definitions.value.find { it.id == call.params["workflowId"] }
            ?: return KeyTile("Missing workflow", "Edit key", 0xff494c51.toInt(), enabled = false)
        val status = workflows.status.value?.takeIf { it.workflowId == definition.id }
        val color = when (status?.phase) {
            WorkflowPhase.FAILED -> 0xff8c303a
            WorkflowPhase.SUCCESS -> 0xff286046
            WorkflowPhase.RUNNING, WorkflowPhase.CONFIRMATION -> 0xff66488c
            else -> 0xff234f72
        }.toInt()
        val footer = when (status?.phase) {
            null -> "Run workflow"
            WorkflowPhase.CONFIRMATION -> "Confirm in Flows"
            else -> "${status.phase.name.lowercase()} ${status.step}"
        }
        return KeyTile(definition.name, footer, color)
    }

    private fun workflowActions() = listOf(
        WorkflowActionSpec("sound.play","Play a sound"),
        WorkflowActionSpec("keypad.status","Set key status",listOf(WorkflowField("key","Key index (0-8)",(0..8).map(Int::toString)), WorkflowField("text","Status text"))),
        WorkflowActionSpec(DeckActions.PROFILE,"Switch profile",listOf(WorkflowField("profileId","Profile ID",deck.state.value.profiles.map { it.id }))),
        WorkflowActionSpec(DeckActions.PAGE,"Open page in active profile",listOf(WorkflowField("pageId","Page ID",deck.state.value.profiles.flatMap { p -> p.pages.map { it.id } }.distinct()))),
        WorkflowActionSpec(DeckActions.BACK,"Previous page"), WorkflowActionSpec(DeckActions.ROOT,"Profile root"),
        WorkflowActionSpec("phone.open","Open a phone app",listOf(WorkflowField("app","Phone app",PhoneApp.entries.map { it.name })), requiresConfirmation = true),
    ) + platformWorkflowActions()

    private fun press(call: ActionCall, virtual: Boolean, index: Int) {
        val result = execute(call, if (virtual) WorkflowOrigin.VIRTUAL else WorkflowOrigin.PHYSICAL)
        if (!result.success) { feedback[index] = "Failed"; message(result.message) }
        else if (call.id != "workflow.run") message(result.message)
        invalidate()
    }

    private fun execute(call: ActionCall, origin: WorkflowOrigin?): ActionResult {
        if (ownerOf(call) in disabled.value) return ActionResult(false,"Enable this plugin first.")
        fun result(change: Result<Unit>) = change.fold({ ActionResult(true,"Layout updated") },
            { ActionResult(false,it.message ?: "Could not update the layout") })
        return when (call.id) {
            DeckActions.BACK -> result(deck.back())
            DeckActions.ROOT -> result(deck.root())
            DeckActions.PROFILE -> result(deck.selectProfile(call.params["profileId"].orEmpty()))
            DeckActions.PAGE -> result(deck.openPage(call.params["pageId"].orEmpty()))
            "sound.play" -> {
                try {
                    val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 50)
                    if (!tone.startTone(ToneGenerator.TONE_PROP_ACK,500)) {
                        tone.release()
                        ActionResult(false,"Tone output unavailable")
                    } else {
                        sessionScope.launch { try { delay(600) } finally { tone.release() } }
                        ActionResult(true,"Tone started")
                    }
                } catch (_: RuntimeException) { ActionResult(false,"Tone output unavailable") }
            }
            "phone.open" -> {
                val target = PhoneApp.entries.find { it.name == call.params["app"] }
                if (origin == WorkflowOrigin.PHYSICAL && target == PhoneApp.CAMERA) {
                    if (screen == null) {
                        val offered = offerPhysicalShortcut(Target.Phone(target))
                        return ActionResult(offered.success, offered.message)
                    }
                    if (!freshAuthorizedPress()) return ActionResult(false,"Press Camera again on the authorized keypad.")
                }
                val ok = screen != null && target != null && app.isUnlockedAndInteractive() && app.launchPhoneApp(target) == null
                ActionResult(ok, if (ok) "App requested" else "Open MX Pocket on an unlocked phone to launch an app.")
            }
            "keypad.status" -> {
                val index = call.params["key"]?.toIntOrNull()
                val text = call.params["text"]
                if (index == null || index !in 0..8 || text.isNullOrBlank() || text.length > 128) ActionResult(false,"Invalid key or status text")
                else { feedback[index] = text; invalidate(); ActionResult(true, "Status updated") }
            }
            "workflow.run" -> {
                val ok = origin != null && workflows.run(call.params["workflowId"].orEmpty(),origin)
                ActionResult(ok, if (ok) "Workflow started" else "Workflow unavailable or busy")
            }
            else -> platformAction(call, origin)
        }
    }

    fun attach(screen: Screen) {
        this.screen = screen
        syncLayout()
        updateObservation()
        watchDecks()
        media.refreshDisplay()
        screen.render()
    }

    fun detach(screen: Screen) {
        if (this.screen !== screen) return
        this.screen = null
        syncLayout()
        updateObservation()
        watchDecks()
    }

    fun startPreview() {
        disconnect()
        preview = true
        val sim = SimulatedKeypad { event -> if (preview && screen != null) handleKey(event, SystemClock.elapsedRealtime(), virtual = true) }
        simulator = sim
        output = sim
        hardwareScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
        syncLayout()
        updateObservation()
        invalidate()
        watchDecks()
        message(app.getString(R.string.preview_note))
    }

    fun reserve(device: UsbDevice): Request? {
        if (request != null) return null
        if (preview) disconnect()
        preview = false
        plugins.setEnabled(false)
        return Request(device.deviceName).also {
            request = it
            message("Starting keypad session...")
        }
    }

    /** Called only after revalidation and successful foreground promotion. */
    fun connect(ticket: Request, manager: UsbManager, device: UsbDevice, end: () -> Unit, handoff: (PhoneAction) -> DeckResult) {
        if (request != ticket || hardwareScope != null) return
        val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
        hardwareScope = scope
        onEnd = end
        onHandoff = handoff
        message("Claiming descriptor-backed HID interfaces and initializing keypad...")
        scope.launch {
            try {
                val connected = open(manager, device,
                    { event ->
                        val arrivedAt = SystemClock.elapsedRealtime()
                        val epoch = receipts.getOrElse(event.controlId - 1) { 0L }
                        main.post { if (request == ticket) handleKey(event, arrivedAt, epoch = epoch) }
                    },
                    { line -> main.post { if (request == ticket) log(line) } },
                    { error -> main.post { if (request == ticket) stopWithError("USB connection failed", error) } })
                if (request != ticket) { connected.close(); return@launch }
                output = connected
                media.refreshDisplay()
                syncLayout()
                updateObservation()
                watchDecks()
                message("Protocol initialized. Uploading nine LCD images; check the physical keys.")
                invalidate()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (request == ticket) stopWithError("Cannot initialize keypad", e)
            }
        }
    }

    fun disconnect(reason: String = app.getString(R.string.not_connected)) {
        physicalKeys.disconnect()
        physicalOwners.fill(null)
        request = null
        preview = false
        plugins.setEnabled(false)
        hardwareScope?.cancel()
        hardwareScope = null
        output?.close()
        output = null
        simulator = null
        receipts = LongArray(9)
        lcd.fill(null)
        paintedTarget = null
        splitTarget = null
        gridHasArt = false
        watchDecks()
        media.refreshDisplay()
        painting = null
        paintedLayout = null
        scanLayout = null
        onHandoff = null
        pendingAction = null
        val end = onEnd
        onEnd = null
        end?.invoke()
        updateObservation()
        message(reason)
    }

    fun phoneKey(index: Int): Boolean {
        if (screen == null || output == null || visibleKey) return false
        val sim = simulator
        if (sim != null) {
            sim.button(index + 1, true)
            sim.button(index + 1, false)
        } else {
            handleKey(KeyEvent(index + 1, true), SystemClock.elapsedRealtime(), virtual = true)
            handleKey(KeyEvent(index + 1, false), SystemClock.elapsedRealtime(), virtual = true)
        }
        log("${if (preview) "PREVIEW" else "ACTION"} plugin=${plugins.selectedId} key=${index + 1}")
        invalidate()
        return true
    }

    private fun handleKey(event: KeyEvent, arrivedAt: Long, virtual: Boolean = false, epoch: Long = 0L) {
        log("${if (event.synthetic) "CANCEL" else "KEY"} 0x${event.controlId.toString(16)} " +
            if (event.pressed) "press" else "release")
        when (event.controlId) {
            0x01a1 -> if (event.pressed && !event.synthetic) changePage(-1)
            0x01a2 -> if (event.pressed && !event.synthetic) changePage(1)
            in 1..9 -> {
                val index = event.controlId - 1
                if (virtual) {
                    val call = deck.state.value.activePage.keys[index].action ?: return
                    val tile = frame().keys[index]
                    if (!event.pressed || tile.enabled && !tile.readOnly)
                        dispatchKey(index, call, event, virtual = true, pressAt = null)
                } else if (!event.pressed || event.synthetic) {
                    if (!physicalKeys.release(index, event.synthetic)) return
                    val owner = physicalOwners[index]
                    physicalOwners[index] = null
                    if (owner != null && owner.generation == plugins.generation)
                        plugins.key(PluginKeyEvent(owner.index, false, event.synthetic))
                } else if (physicalKeys.begin(index)) {
                    physicalDown(index, arrivedAt, epoch)
                }
            }
        }
    }

    private fun physicalDown(index: Int, arrivedAt: Long, epoch: Long) {
        val binding = layout()
        val ticket = request ?: return
        val call = binding.page.keys[index].action ?: return
        val tiles = frame()
        fun actionable(latest: PluginFrame) = latest.keys[index].let { it.enabled && !it.readOnly } &&
            acknowledged(index, epoch, latest, binding)
        if (!actionable(tiles)) return
        val partners = chordPartners(binding, tiles, index)
        val event = KeyEvent(index + 1, true)
        if (partners.isEmpty() && !physicalKeys.hasPending) {
            dispatchKey(index, call, event, virtual = false, pressAt = arrivedAt)
            return
        }
        val profile = deck.state.value.activeProfile
        physicalKeys.press(index, arrivedAt, epoch, partners, current = {
            request == ticket && binding == layout() && actionable(frame()) &&
                (partners.isEmpty() || (deck.state.value.activeProfile == profile &&
                    SystemClock.elapsedRealtime() - arrivedAt in 0 until 2_000L &&
                    app.isUnlockedAndInteractive() && app.hasKeypadAccess(ticket)))
        }, single = { deferred ->
            dispatchKey(index, call, event, virtual = false, pressAt = arrivedAt.takeUnless { deferred })
        }, chord = { chord ->
            val id = plugins.selectedId
            plugins.chord(chord) // Neither member lends a synchronous user-press authority to the callback.
            followPluginSelection(id)
        })
    }

    private fun chordPartners(layout: Layout, frame: PluginFrame, index: Int): Map<Int, PhysicalChordInput.Partner> {
        if (plugins.chords.isEmpty()) return emptyMap()
        val page = layout.page
        fun slot(i: Int) = page.keys[i].action?.takeIf { it.id == "plugin.key" && it.params["pluginId"] == plugins.selectedId }
            ?.params?.get("index")?.toIntOrNull()
        val own = slot(index) ?: return emptyMap()
        if (PluginSlot(own, plugins.generation) in physicalOwners) return emptyMap()
        return buildMap {
            for (pair in plugins.chords) {
                val partner = pair.partner(own) ?: continue
                if (PluginSlot(partner, plugins.generation) in physicalOwners) continue
                for (i in page.keys.indices) {
                    if (physicalKeys.available(i) && slot(i) == partner && frame.keys[i].enabled && !frame.keys[i].readOnly &&
                        acknowledged(i, receipts[i], frame, layout))
                        put(i, PhysicalChordInput.Partner(pair, receipts[i]))
                }
            }
        }
    }

    private fun dispatchKey(index: Int, call: ActionCall, event: KeyEvent, virtual: Boolean, pressAt: Long?) {
        physicalPressAt = pressAt
        physicalIndex = index.takeIf { pressAt != null }
        visibleKey = virtual && event.pressed && !event.synthetic
        try {
            if (call.id == "plugin.key" && call.params["pluginId"] == plugins.selectedId) {
                val slot = PluginSlot(call.params.getValue("index").toInt(), plugins.generation)
                // Two physical aliases (or a phone tap) must not release another input's plugin slot.
                if (slot in physicalOwners) return
                if (!virtual) physicalOwners[index] = slot
                plugins.key(PluginKeyEvent(slot.index, event.pressed, event.synthetic))
                followPluginSelection(call.params.getValue("pluginId"))
            } else if (event.pressed && !event.synthetic) {
                press(call.commandBinding(), virtual, index)
            }
        } finally {
            physicalPressAt = null
            physicalIndex = null
            visibleKey = false
        }
    }

    private fun followPluginSelection(previous: String) {
        if (plugins.selectedId != previous && deck.state.value.profiles.any { it.id == plugins.selectedId })
            selectProfile(plugins.selectedId)
    }

    fun cancelPhysicalGesture() {
        if (plugins.chords.isEmpty()) return
        physicalKeys.cancel()
        receipts = LongArray(9) // Also reject input posted from the USB thread before this boundary.
        invalidate()
    }

    fun setImage(image: Bitmap) {
        // Only composed copies leave the main thread. Don't cancel an in-flight HID transaction.
        images.setBackdrop(image)
        log("Custom image loaded; labels will be overlaid on all nine keys")
        invalidate()
    }

    fun repaint() { repaintEpoch++; images.clear(); invalidate() }

    private fun invalidate() {
        if (scheduled) return
        scheduled = true
        main.post {
            scheduled = false
            screen?.render()
            paint()
        }
    }

    private fun paint() {
        val connected = output ?: return
        val scope = hardwareScope ?: return
        if (painting?.isActive == true) return
        val frame = frame()
        val layout = layout()
        if (scanLayout != layout) { scanLayout = layout; nextKey = 0 }
        // Cold Audio entry: keep the old, non-private layout until cover bytes or the bounded fallback arrive.
        if (!preview && decks.value.media.awaitingCover && layout.profile == "audio" &&
            lcd.none { it?.hasTrackDetails == true }) return
        val target = target(frame)
        val art = images.artwork
        val keys = lcd.indices.map { desiredKey(layout, frame, it) }
        val dirty = lcd.indices.filter { lcd[it]?.matches(keys[it]) != true }
        if (splitTarget != target) splitTarget = null

        if (connected is GridOutput && splitTarget == null && (paintedTarget != target || dirty.size > 1)) {
            receipts = LongArray(9)
            painting = scope.launch {
                try {
                    val result = connected.writeGrid(art, keys.map { it.image }, gridHasArt) {
                        val latest = frame()
                        output === connected && this@PocketSession.layout() == layout &&
                            target(latest) == target && keys.indices.all { keys[it].matches(desiredKey(layout, latest, it)) }
                    }
                    // Record what actually reached the panel, even if superseded in flight.
                    // Only the next paint against live state may issue input receipts.
                    if (output === connected) when (result) {
                        GridWrite.ACKNOWLEDGED -> {
                            paintedTarget = target
                            gridHasArt = art != null
                            for (i in keys.indices) lcd[i] = keys[i]
                        }
                        GridWrite.CLEARED -> { gridHasArt = false; lcd.fill(null); splitTarget = target }
                        GridWrite.TOO_LARGE -> splitTarget = target
                        GridWrite.SUPERSEDED -> Unit
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (output === connected) stopWithError("LCD grid upload failed", e)
                } finally {
                    if (output === connected) main.post(::paint)
                }
            }
            return
        }
        // One transaction at a time. Re-read desired state after its ACK, continuing around the panel
        // rather than repeatedly starting at a rapidly changing first key.
        if (dirty.isNotEmpty()) {
            receipts = receipts.copyOf().also { values -> dirty.forEach { values[it] = 0 } }
            val index = dirty.minBy { (it - nextKey + lcd.size) % lcd.size }
            nextKey = (index + 1) % lcd.size
            val key = keys[index]
            painting = scope.launch {
                try {
                    val sent = if (connected is RenderedKeypad) {
                        connected.setKeyImage(index, key.image, coverArt = art != null)
                    } else {
                        connected.setKeyImage(index, key.image)
                        true
                    }
                    if (sent && output === connected) lcd[index] = key
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (output === connected) stopWithError("LCD upload failed", e)
                } finally {
                    if (output === connected) main.post(::paint)
                }
            }
            return
        }
        val first = paintedLayout == null
        paintedLayout = layout
        paintedTarget = target
        splitTarget = null
        keys.forEachIndexed { index, key -> lcd[index] = key }
        if (receipts.any { it == 0L }) {
            val previous = receipts
            val fresh = ++receiptSequence
            receipts = LongArray(previous.size) { if (previous[it] == 0L) fresh else previous[it] }
        }
        if (first && !preview) message("LCD image updates acknowledged. Verify visibility on the keypad.")
    }

    private var receiptSequence = 0L
    private fun target(frame: PluginFrame) = GridTarget(screenGeneration, frame.page, repaintEpoch, images.artworkRevision)

    private fun desiredKey(layout: Layout, frame: PluginFrame, index: Int): LcdKey {
        val tile = frame.keys[index]
        return LcdKey(tile, layout.page.keys[index].action, images.image(index, tile), repaintEpoch,
            images.artwork != null || decks.value.media.title.isNotEmpty())
    }

    private fun acknowledged(index: Int, epoch: Long, latest: PluginFrame = frame(), layout: Layout = layout()): Boolean {
        if (epoch == 0L || epoch != receipts[index]) return false
        if (!latest.keys[index].enabled || latest.keys[index].readOnly) return false
        return paintedLayout == layout && paintedTarget == target(latest) &&
            lcd[index]?.matches(desiredKey(layout, latest, index)) == true
    }

    private fun perform(action: PluginAction, scope: CoroutineScope) {
        when (action) {
            is PluginAction.Deck -> {
                val result = runDeckAction(action.command.id, action.component?.let { mapOf("component" to it) }.orEmpty(),
                    fromPhysicalKey = physicalPressAt != null)
                if (action.command.nativeControl) physicalIndex?.let {
                    feedback[it] = if (result.success) "Requested" else "Not sent"
                }
                message(result.message)
            }
            is PluginAction.Volume -> message(runDeckAction(when (action.change) {
                VolumeChange.LOWER -> DeckCommand.QUIETER
                VolumeChange.RAISE -> DeckCommand.LOUDER
                VolumeChange.MUTE -> DeckCommand.MUTE
                VolumeChange.UNMUTE -> DeckCommand.UNMUTE
            }.id, fromPhysicalKey = physicalPressAt != null).message)
            is PluginAction.Media -> message(runDeckAction(when (action.command) {
                MediaCommand.PLAY_PAUSE -> DeckCommand.PLAY
                MediaCommand.PREVIOUS -> DeckCommand.PREVIOUS
                MediaCommand.NEXT -> DeckCommand.NEXT
            }.id, fromPhysicalKey = physicalPressAt != null).message)
            is PluginAction.OpenApp, is PluginAction.OpenUri, is PluginAction.ShareText -> {
                if (!visibleKey && physicalPressAt == null) return
                if (screen != null && physicalPressAt != null && !freshAuthorizedPress()) {
                    message("Press the shortcut again on the authorized keypad.")
                    return
                }
                val target = when (action) {
                    is PluginAction.OpenApp -> Target.Phone(action.app)
                    is PluginAction.OpenUri -> Target.Uri(action.uri)
                    is PluginAction.ShareText -> Target.Share(action.text)
                    else -> return
                }
                val opened = when (action) {
                    is PluginAction.OpenApp -> screen?.openApp(action.app) == true
                    is PluginAction.OpenUri -> screen?.openUri(action.uri) == true
                    is PluginAction.ShareText -> screen?.shareText(action.text) == true
                    else -> false
                }
                if (!opened && physicalPressAt != null) {
                    message(offerPhysicalShortcut(target).message)
                }
            }
            PluginAction.CompletionTone -> scope.launch {
                val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 50)
                try { tone.startTone(ToneGenerator.TONE_PROP_ACK, 500); delay(600) } finally { tone.release() }
                log("Focus session complete")
            }
            is PluginAction.AccessibilityClick -> {
                val service = OneNoteAccessService.connected
                message(when {
                    service == null -> "Enable MX Pocket OneNote tools in Android Accessibility settings."
                    service.selectToolById(action.text) -> "Requested ${action.text} in OneNote."
                    else -> "Open OneNote and show the ${action.text} control first."
                })
            }
            PluginAction.Repaint -> repaint()
            is PluginAction.SelectPlugin -> error("Navigation is handled by PluginEngine")
        }
    }

    fun publishFavorites(apps: List<Favorite>) {
        val selected = favoriteApps.selected()
        decks.value = decks.value.copy(favorites = apps.filter { it.component in selected })
    }
    /** Observe until both the physical session and visible screen release their ownership. */
    private fun watchDecks() {
        if (screen == null && request == null) {
            deckWatch?.cancel(); deckWatch = null
            images.useArtwork(null)
            images.clear()
            decks.value = decks.value.copy(media = MediaDeckState(), navigationEnabled = false)
            return
        }
        if (deckWatch?.isActive == true) return
        deckWatch = CoroutineScope(Dispatchers.Main.immediate).launch {
            coroutineScope {
                var reload: Job? = null
                fun publishMedia(next: MediaDeckState) {
                    val old = decks.value.media
                    if (old == next) return
                    if (old.title != next.title || old.artwork !== next.artwork) {
                        images.clear()
                        (output as? RenderedKeypad)?.clearImages()
                    }
                    decks.value = decks.value.copy(media = next)
                    images.useArtwork(visibleArtwork(next))
                }
                fun favoritesChanged() {
                    reload?.cancel()
                    reload = launch {
                        val installed = withContext(Dispatchers.IO) { favoriteApps.discover() }
                        val hadIcons = decks.value.favorites.isNotEmpty()
                        favoriteApps.invalidateIcons()
                        publishFavorites(installed)
                        if (hadIcons) { images.clear(); invalidate() }
                    }
                }
                val packages = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) { favoritesChanged() }
                }
                val filter = IntentFilter().apply {
                    addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
                    addAction(Intent.ACTION_PACKAGE_CHANGED); addDataScheme("package")
                }
                ContextCompat.registerReceiver(app, packages, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
                try {
                    launch {
                        disabled.map { "audio" !in it }.distinctUntilChanged().collectLatest { enabled ->
                            if (enabled) media.observe(::publishMedia) else publishMedia(MediaDeckState())
                        }
                    }
                    favoritesChanged()
                    while (true) {
                        decks.value = decks.value.copy(navigationEnabled = NavigationService.connected != null,
                            cameraControls = cameraAccess.enabled)
                        delay(1000)
                    }
                } finally { app.unregisterReceiver(packages) }
            }
        }
    }
    /** Main thread. User authority is synchronous input, never a parameter a saved workflow can forge. */
    fun runDeckAction(id: String, params: Map<String, String> = emptyMap(), fromPhysicalKey: Boolean = false): DeckResult {
        val c = DeckCommand.fromId(id) ?: return unavailable("Unknown deck action.")
        if (c.deck in disabled.value) return unavailable("Enable this plugin first.")
        val component = params["component"]
        if ((c == DeckCommand.OPEN && (component.isNullOrBlank() || params.size != 1)) ||
            (c != DeckCommand.OPEN && params.isNotEmpty())) return unavailable("Invalid action parameters.")
        if (c == DeckCommand.VOLUME_LEVEL) return unavailable("Volume level is a display, not a control.")
        if (!app.isUnlockedAndInteractive() && !(c.lockedMediaKey && fromPhysicalKey && freshAuthorizedPress()))
            return unavailable("Unlock the phone or use a connected keypad media key.")
        if (c.workflowSafe) return media.execute(c)
        if (!(visibleKey && screen != null) && !freshAuthorizedPress())
            return unavailable("Press a key on the unlocked phone or connected keypad.")
        if (c.legacyCapture) return unavailable("This control belonged to the old in-app camera.")
        if (c.nativeControl) {
            val index = physicalIndex ?: return unavailable("Use the physical Studio keypad in the phone camera.")
            if (!cameraAccess.enabled) return unavailable("On the phone, enable keypad camera controls in Plugins > Studio.")
            val service = NavigationService.connected ?: return unavailable("Enable MX Pocket navigation in Accessibility for camera controls.")
            fun authorized() = freshAuthorizedPress() && app.isUnlockedAndInteractive() && output != null &&
                cameraAccess.enabled && NavigationService.connected === service && isStudioKey(index, c) &&
                acknowledged(index, receipts[index]) && lcd[index]?.tile?.inputToken == c.id
            if (!authorized()) return unavailable("Enable Camera controls and press the matching Studio key on the unlocked phone.")
            return service.cameraPress(c, ::authorized)
        }
        if (c.deck == "navigation" && c != DeckCommand.NAV_ACCESS &&
            (screen == null || c in setOf(DeckCommand.HOME, DeckCommand.BACK, DeckCommand.RECENTS)))
            NavigationService.connected?.let { return it.press(c) }
        screen?.let { return it.deckAction(c, component) }
        val target = when (c) {
            DeckCommand.OPEN -> {
                if (favoriteApps.intentFor(component) == null) return unavailable("Favorite is no longer available.")
                Target.FavoriteApp(component!!)
            }
            DeckCommand.STUDIO -> Target.Studio(CameraMode.PHOTO)
            DeckCommand.VIDEO -> Target.Studio(CameraMode.VIDEO)
            else -> return unavailable("Open MX Pocket to use this control.")
        }
        return offerPhysicalShortcut(target)
    }

    private fun isStudioKey(index: Int, command: DeckCommand): Boolean {
        val state = deck.state.value
        val key = state.activePage.keys[index]
        val call = key.action ?: return false
        return state.activeProfileId == "studio" && state.activePage.id == state.activeProfile.rootPageId &&
            key.enabled && (key.label.isBlank() || key.label == command.label) &&
            call.id == "plugin.key" && call.params["pluginId"] == "studio" && plugins.selectedId == "studio"
    }

    private fun freshAuthorizedPress() = physicalPressAt?.let { SystemClock.elapsedRealtime() - it in 0 until 2_000L } == true &&
        request?.let(app::hasKeypadAccess) == true

    private fun offerPhysicalShortcut(target: Target): DeckResult {
        val ticket = request ?: return unavailable("Keypad disconnected.")
        val time = physicalPressAt ?: return unavailable("Press a keypad key.")
        val index = physicalIndex ?: return unavailable("Press a keypad key.")
        val offer = onHandoff ?: return unavailable("Keypad session unavailable.")
        val action = PhoneAction(target, ticket, index, receipts[index], time)
        pendingAction = action
        return offer(action)
    }

    fun isCurrentShortcut(action: PhoneAction): Boolean =
        pendingAction == action && action.ticket == request && output != null && acknowledged(action.index, action.receipt)

    fun logText(limit: Int = 250) = logs.takeLast(limit).joinToString("\n")
    fun takePhoneAction(id: String?): PhoneAction? {
        val pending = pendingAction ?: return null
        if (id != pending.id) return null // An older tap must not consume the replacement.
        val valid = isCurrentShortcut(pending) && SystemClock.elapsedRealtime() - pending.pressedAt in 0 until PHONE_ACTION_TTL
        pendingAction = null
        return pending.takeIf { valid }
    }
    fun log(line: String) {
        logs.addLast("${SystemClock.elapsedRealtime()} $line")
        while (logs.size > 250) logs.removeFirst()
        // USB fragments can produce a burst of logs; coalesce phone updates, never poll while idle.
        if (screen != null) invalidate()
    }
    fun message(text: String) { status = text; screen?.render() }
    fun fail(text: String, error: Throwable) {
        android.util.Log.e("PocketSession", "ERROR $text", error)
        log("ERROR $text: ${error.message}")
        message("$text: ${error.message}. Check diagnostics and try again.")
    }
    private fun stopWithError(text: String, error: Throwable) { disconnect(); fail(text, error) }
}
