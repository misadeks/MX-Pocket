package tel.misa.mxpocket

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent as AndroidKeyEvent
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tel.misa.mxpocket.driver.KeyEvent
import tel.misa.mxpocket.driver.MxKeypad
import tel.misa.mxpocket.driver.KeypadSink
import tel.misa.mxpocket.driver.SimulatedKeypad
import tel.misa.mxpocket.plugins.*
import tel.misa.mxpocket.deck.*
import tel.misa.mxpocket.actions.*
import tel.misa.mxpocket.workflow.*
import java.time.LocalDateTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

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
    private val open: OpenKeypad = { manager, device, event, log, error ->
        MxKeypad.connect(manager, device, event, log, error)
    },
) {
    interface Screen {
        fun render()
        fun openApp(app: PhoneApp): Boolean
    }
    data class Request(val name: String, val token: String = UUID.randomUUID().toString())
    data class PhoneAction(val app: PhoneApp, val ticket: Request, val generation: Int,
        val pressedAt: Long, val id: String = UUID.randomUUID().toString())
    companion object { const val PHONE_ACTION_TTL = 60_000L }
    private data class Layout(val profile: String, val page: DeckPage, val pluginEpoch: Int,
        val disabled: Set<String>)
    private data class Stamp(val layout: Layout, val frame: PluginFrame, val revision: Int)

    private val main = Handler(Looper.getMainLooper())
    private val audio = app.getSystemService(AudioManager::class.java)
    private val logs = ArrayDeque<String>()
    private var screen: Screen? = null
    private val sessionScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    val deck = DeckRepository(app)
    private val switches = app.getSharedPreferences("plugins", Context.MODE_PRIVATE)
    private val disabled = MutableStateFlow(switches.getStringSet("disabled", emptySet()).orEmpty().toSet())
    val disabledPlugins = disabled.asStateFlow()
    private val _signals = MutableStateFlow(emptyMap<String,String>())
    val signals = _signals.asStateFlow()
    val plugins = PluginEngine(
        BuiltInPlugins.create(app.getString(R.string.focus), app.getString(R.string.audio), app.getString(R.string.phone)),
        CoroutineScope(Dispatchers.Main.immediate), SystemClock::elapsedRealtime,
        readAudio = { AudioSnapshot(audio.getStreamVolume(AudioManager.STREAM_MUSIC),
            audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)) },
        perform = ::perform, invalidateUi = ::invalidate, reportError = { id, error -> fail("Plugin $id failed", error) },
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
    private var onHandoff: ((PhoneAction) -> Unit)? = null
    private var physicalPressAt: Long? = null
    private var pendingAction: PhoneAction? = null
    private val images = KeyImages()
    private var revision = 0
    private var painted: Stamp? = null
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
    private val lcd = arrayOfNulls<KeyTile>(9)
    @Volatile private var receipt = 0L
    private var lastLayout: Layout? = null
    private val feedback = mutableMapOf<Int, String>()
    private var feedbackPage: DeckPage? = null
    val actions = ActionDispatcher { call -> execute(call, null) }
    val workflows = WorkflowController(app, sessionScope, object : WorkflowHost {
        override fun snapshot() = workflowContext()
        override fun actions() = workflowActions()
        override suspend fun execute(id: String, params: Map<String,String>): Boolean =
            this@PocketSession.actions.dispatch(ActionCall(id, params)).success
    })

    init {
        sessionScope.launch {
            deck.state.collect { syncLayout(); invalidate() }
        }
        sessionScope.launch { workflows.status.collect { invalidate() } }
    }

    private fun syncLayout() {
        val state = deck.state.value
        val id = state.activePage.keys.mapNotNull { key ->
            key.action?.takeIf { it.id == "plugin.key" }?.params?.get("pluginId")
        }.firstOrNull { hasPlugin(it) }
        if (id != null && plugins.selectedId != id) plugins.select(id)
        plugins.setEnabled((output != null || preview && screen != null) && id != null && id !in disabled.value &&
            (!preview || screen != null))
        val layout = layout()
        if (layout != lastLayout) {
            lastLayout = layout
            receipt = 0
            pendingAction = null
        }
        if (feedbackPage != state.activePage) {
            feedbackPage = state.activePage
            feedback.clear()
        }
    }

    private fun hasPlugin(id: String) = plugins.descriptors.any { it.id == id }
    private fun layout() = deck.state.value.let { Layout(it.activeProfileId, it.activePage, plugins.generation, disabled.value) }

    fun setPluginEnabled(id: String, enabled: Boolean) {
        require(hasPlugin(id))
        disabled.value = if (enabled) disabled.value - id else disabled.value + id
        switches.edit().putStringSet("disabled", disabled.value).apply()
        syncLayout()
        invalidate()
    }

    fun frame(): PluginFrame {
        val native = plugins.render()
        return PluginFrame(deck.state.value.activePage.keys.mapIndexed { index, key ->
            val call = key.action
            val plugin = call?.params?.get("pluginId")?.takeIf { call.id == "plugin.key" }
            val tile = if (plugin == plugins.selectedId) call.params["index"]?.toIntOrNull()?.let(native.keys::getOrNull)
                else workflowTile(call) ?: platformTile(call)
            val available = key.enabled && call != null && (plugin == null || plugin == plugins.selectedId) &&
                ownerOf(call) !in disabled.value && (tile?.enabled != false)
            val label = key.label.ifBlank { tile?.label ?: actionLabel(call) }
            val footer = when {
                !key.enabled || ownerOf(call) in disabled.value -> "Disabled"
                !available && call != null -> "Unavailable"
                else -> feedback[index] ?: tile?.footer.orEmpty()
            }
            (tile ?: KeyTile(label, footer, key.color)).copy(label = label, footer = footer,
                background = if (!available) 0xff494c51.toInt() else if (key.color == DeckKey().color)
                    tile?.background ?: key.color else key.color,
                icon = key.icon.ifBlank { tile?.icon.orEmpty() }, enabled = available)
        })
    }

    fun keyImage(index: Int, tile: KeyTile): Bitmap = images.image(index, tile)

    private fun workflowContext() = WorkflowContext(request != null && output != null,
        signals.value["activity"].orEmpty(), signals.value)

    private fun updateObservation() {
        signalClock?.cancel()
        signalClock = null
        sampleContext()
        if (screen != null || request != null) signalClock = sessionScope.launch {
            while (true) { delay(1000); sampleContext() }
        } else deck.clearObservation()
    }

    private fun sampleContext() {
        val time = LocalDateTime.now()
        val connected = output != null && request != null
        val state = deck.state.value
        val snapshot = mapOf("foreground" to (screen != null).toString(),
            "activity" to if (screen != null) "MX Pocket" else "Not observed",
            "mediaActive" to audio.isMusicActive.toString(), "keypadConnected" to connected.toString(),
            "usbDevice" to if (connected) "046D:C354" else "None",
            "simulator" to preview.toString(), "hour" to time.hour.toString(),
            "weekday" to time.dayOfWeek.name, "profile" to state.activeProfileId,
            "plugin" to (state.activePage.keys.firstOrNull()?.action?.params?.get("pluginId") ?: "custom"),
            "workflow" to (workflows.status.value?.phase?.name ?: "IDLE")) + platformSignals()
        _signals.value = snapshot
        deck.observe(snapshot, SystemClock.elapsedRealtime()).onFailure { fail("Cannot select layout", it) }
        workflows.onContextChanged(workflowContext())
    }

    fun selectProfile(id: String) {
        deck.selectProfile(id).onFailure { fail("Cannot select profile", it) }
        syncLayout()
        invalidate()
    }

    fun changePage(delta: Int) {
        val state = deck.state.value
        val pages = state.activeProfile.pages
        val result = when {
            delta < 0 && state.histories.getValue(state.activeProfileId).size > 1 -> deck.back()
            pages.size > 1 -> deck.openPage(pages[Math.floorMod(pages.indexOf(state.activePage) + delta, pages.size)].id)
            else -> deck.cycleProfile(delta)
        }
        result.onFailure { fail("Cannot change page", it) }
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
        return KeyTile(definition.name, status?.let { "${it.phase.name.lowercase()} ${it.step}" } ?: "Run workflow", color)
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
                perform(PluginAction.CompletionTone, sessionScope)
                ActionResult(true, "Tone requested")
            }
            "phone.open" -> {
                val target = PhoneApp.entries.find { it.name == call.params["app"] }
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
        screen.render()
    }

    fun detach(screen: Screen) {
        if (this.screen !== screen) return
        this.screen = null
        syncLayout()
        updateObservation()
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
    fun connect(ticket: Request, manager: UsbManager, device: UsbDevice, end: () -> Unit, handoff: (PhoneAction) -> Unit) {
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
                        val epoch = receipt
                        main.post { if (request == ticket) handleKey(event, arrivedAt, epoch = epoch) }
                    },
                    { line -> main.post { if (request == ticket) log(line) } },
                    { error -> main.post { if (request == ticket) stopWithError("USB connection failed", error) } })
                if (request != ticket) { connected.close(); return@launch }
                output = connected
                syncLayout()
                updateObservation()
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
        request = null
        preview = false
        plugins.setEnabled(false)
        hardwareScope?.cancel()
        hardwareScope = null
        output?.close()
        output = null
        simulator = null
        receipt = 0
        lcd.fill(null)
        painting = null
        painted = null
        onHandoff = null
        pendingAction = null
        val end = onEnd
        onEnd = null
        end?.invoke()
        updateObservation()
        message(reason)
    }

    fun phoneKey(index: Int): Boolean {
        if (screen == null || output == null) return false
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

    private fun handleKey(event: KeyEvent, arrivedAt: Long, virtual: Boolean = false, epoch: Long = receipt) {
        log("${if (event.synthetic) "CANCEL" else "KEY"} 0x${event.controlId.toString(16)} " +
            if (event.pressed) "press" else "release")
        when (event.controlId) {
            0x01a1 -> if (event.pressed && !event.synthetic) changePage(-1)
            0x01a2 -> if (event.pressed && !event.synthetic) changePage(1)
            in 1..9 -> {
                // Old labels can still be visible while a new page is uploading. Never replay a skipped press.
                val index = event.controlId - 1
                val page = deck.state.value.activePage
                val call = page.keys[index].action ?: return
                if (event.pressed && !virtual && (epoch == 0L || epoch != receipt || painted?.layout != layout() ||
                    !sameBinding(lcd[index], frame().keys[index]))) return
                if (!frame().keys[index].enabled) return
                physicalPressAt = arrivedAt.takeIf { !virtual && event.pressed && !event.synthetic && request != null }
                try {
                    if (call.id == "plugin.key" && call.params["pluginId"] == plugins.selectedId) {
                        plugins.key(PluginKeyEvent(call.params.getValue("index").toInt(), event.pressed, event.synthetic))
                        // A built-in plugin may request navigation as part of its input handler.
                        if (plugins.selectedId != call.params["pluginId"] && deck.state.value.profiles.any { it.id == plugins.selectedId })
                            selectProfile(plugins.selectedId)
                    } else if (event.pressed && !event.synthetic) {
                        press(call, virtual, index)
                    }
                } finally {
                    physicalPressAt = null
                }
            }
        }
    }

    fun setImage(image: Bitmap) {
        // Only composed copies leave the main thread. Don't cancel an in-flight HID transaction.
        images.setBackdrop(image)
        log("Custom image loaded; labels will be overlaid on all nine keys")
        repaint()
    }

    fun repaint() { revision++; images.clear(); invalidate() }

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
        val stamp = Stamp(layout(), frame, revision)
        val previous = painted
        if (stamp == previous && receipt != 0L && lcd.indices.all { lcd[it] == frame.keys[it] }) return
        painting = scope.launch {
            try {
                val force = previous?.revision != revision
                for (index in 0..8) {
                    if (stamp.layout != layout() || stamp.revision != revision || output !== connected) break
                    if (!force && lcd[index] == frame.keys[index]) continue
                    connected.setKeyImage(index, images.image(index, frame.keys[index]))
                    if (output === connected) lcd[index] = frame.keys[index]
                }
                if (stamp.layout == layout() && stamp.revision == revision && output === connected) {
                    painted = stamp
                    if (receipt == 0L) receipt = ++receiptSequence
                    if (previous == null && !preview) message("LCD image updates acknowledged. Verify visibility on the keypad.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (output === connected) stopWithError("LCD upload failed", e)
            } finally {
                if (output === connected) invalidate()
            }
        }
    }

    private var receiptSequence = 0L
    private fun sameBinding(a: KeyTile?, b: KeyTile): Boolean =
        a != null && a.enabled == b.enabled && a.inputToken == b.inputToken

    private fun perform(action: PluginAction, scope: CoroutineScope) {
        when (action) {
            is PluginAction.Volume -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, when (action.change) {
                VolumeChange.LOWER -> AudioManager.ADJUST_LOWER
                VolumeChange.RAISE -> AudioManager.ADJUST_RAISE
                VolumeChange.MUTE -> AudioManager.ADJUST_MUTE
                VolumeChange.UNMUTE -> AudioManager.ADJUST_UNMUTE
            }, if (screen == null) 0 else AudioManager.FLAG_SHOW_UI)
            is PluginAction.Media -> {
                val code = when (action.command) {
                    MediaCommand.PLAY_PAUSE -> AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                    MediaCommand.PREVIOUS -> AndroidKeyEvent.KEYCODE_MEDIA_PREVIOUS
                    MediaCommand.NEXT -> AndroidKeyEvent.KEYCODE_MEDIA_NEXT
                }
                audio.dispatchMediaKeyEvent(AndroidKeyEvent(AndroidKeyEvent.ACTION_DOWN, code))
                audio.dispatchMediaKeyEvent(AndroidKeyEvent(AndroidKeyEvent.ACTION_UP, code))
                log("Media command sent; an active media session is required")
            }
            is PluginAction.OpenApp -> if (screen?.openApp(action.app) != true) {
                val time = physicalPressAt ?: return
                val ticket = request ?: return
                val handoff = PhoneAction(action.app, ticket, plugins.generation, time)
                pendingAction = handoff
                onHandoff?.invoke(handoff)
            } else Unit
            PluginAction.CompletionTone -> scope.launch {
                val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 50)
                try { tone.startTone(ToneGenerator.TONE_PROP_ACK, 500); delay(600) } finally { tone.release() }
                log("Focus session complete")
            }
            PluginAction.Repaint -> repaint()
            is PluginAction.SelectPlugin -> error("Navigation is handled by PluginEngine")
        }
    }

    fun logText(limit: Int = 250) = logs.takeLast(limit).joinToString("\n")
    fun takePhoneAction(id: String?): PhoneAction? {
        val pending = pendingAction ?: return null
        if (id != pending.id) return null // An older tap must not consume the replacement.
        pendingAction = null
        return pending.takeIf { it.ticket == request && output != null && it.generation == plugins.generation &&
            SystemClock.elapsedRealtime() - it.pressedAt < PHONE_ACTION_TTL }
    }
    fun log(line: String) {
        logs.addLast("${SystemClock.elapsedRealtime()} $line")
        while (logs.size > 250) logs.removeFirst()
        // USB fragments can produce a burst of logs; coalesce phone updates, never poll while idle.
        if (screen != null) invalidate()
    }
    fun message(text: String) { status = text; screen?.render() }
    fun fail(text: String, error: Throwable) {
        log("ERROR $text: ${error.message}")
        message("$text: ${error.message}. Check diagnostics and try again.")
    }
    private fun stopWithError(text: String, error: Throwable) { disconnect(); fail(text, error) }
}
