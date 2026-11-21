package tel.misa.mxpocket

import android.app.Application
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent as AndroidKeyEvent
import java.io.Closeable
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import androidx.core.content.ContextCompat
import tel.misa.mxpocket.decks.*
import tel.misa.mxpocket.driver.KeyEvent
import tel.misa.mxpocket.driver.MxKeypad
import tel.misa.mxpocket.plugins.*

open class PocketApplication : Application() {
    internal val session by lazy { createSession() }
    internal open fun createSession() = PocketSession(this)
}

internal interface KeypadOutput : Closeable {
    suspend fun setKeyImage(index: Int, bitmap: Bitmap)
}

internal typealias OpenKeypad = suspend (UsbManager, UsbDevice, (KeyEvent) -> Unit,
    (String) -> Unit, (Throwable) -> Unit) -> KeypadOutput

/** One main-thread owner across phone screens. Only preview or a promoted service enables plugins. */
internal class PocketSession(
    private val app: Context,
    private val open: OpenKeypad = { manager, device, event, log, error ->
        val keypad = MxKeypad.connect(manager, device, event, log, error)
        object : KeypadOutput {
            override suspend fun setKeyImage(index: Int, bitmap: Bitmap) = keypad.setKeyImage(index, bitmap)
            override fun close() = keypad.close()
        }
    },
) {
    interface Screen {
        fun render()
        fun openApp(app: PhoneApp): Boolean
        fun deckAction(command: DeckCommand, component: String?): DeckResult = unavailable("Open MX Pocket to use this control.")
    }
    data class Request(val name: String, val token: String = UUID.randomUUID().toString())
    sealed interface Target {
        data class Phone(val app: PhoneApp) : Target
        data class FavoriteApp(val component: String) : Target
        data object Studio : Target
    }
    data class PhoneAction(val target: Target, val ticket: Request, val generation: Int,
        val pressedAt: Long, val id: String = UUID.randomUUID().toString())
    companion object { const val PHONE_ACTION_TTL = 60_000L }
    private data class Stamp(val generation: Int, val frame: PluginFrame, val revision: Int)

    private val main = Handler(Looper.getMainLooper())
    private val audio = app.getSystemService(AudioManager::class.java)
    private val logs = ArrayDeque<String>()
    private var screen: Screen? = null
    private var visibleKey = false
    private var studio: StudioActivity? = null
    internal val favoriteApps = FavoriteApps(app)
    private val media = MediaDeck(app)
    private val decks = MutableStateFlow(DeckState())
    val deckState = decks.asStateFlow()
    private var deckWatch: Job? = null
    val plugins = PluginEngine(
        BuiltInPlugins.create(app.getString(R.string.focus), app.getString(R.string.audio), app.getString(R.string.phone)),
        CoroutineScope(Dispatchers.Main.immediate), SystemClock::elapsedRealtime,
        readAudio = { AudioSnapshot(audio.getStreamVolume(AudioManager.STREAM_MUSIC),
            audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)) },
        perform = ::perform, invalidateUi = ::invalidate, reportError = { id, error -> fail("Plugin $id failed", error) },
        observeDecks = deckState,
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
    private var custom: Bitmap? = null
    private var revision = 0
    private var painted: Stamp? = null
    private var painting: Job? = null
    private var scheduled = false

    fun attach(screen: Screen) {
        this.screen = screen
        if (preview) plugins.setEnabled(true)
        watchDecks()
        screen.render()
    }

    fun detach(screen: Screen) {
        if (this.screen !== screen) return
        this.screen = null
        if (preview) plugins.setEnabled(false)
        watchDecks()
    }

    fun startPreview() {
        disconnect()
        preview = true
        plugins.setEnabled(screen != null)
        watchDecks()
        message(app.getString(R.string.preview_note))
    }

    fun reserve(device: UsbDevice): Request? {
        if (request != null) return null
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
                        main.post { if (request == ticket) handleKey(event, arrivedAt) }
                    },
                    { line -> main.post { if (request == ticket) log(line) } },
                    { error -> main.post { if (request == ticket) stopWithError("USB connection failed", error) } })
                if (request != ticket) { connected.close(); return@launch }
                output = connected
                plugins.setEnabled(true)
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
        request = null
        preview = false
        plugins.setEnabled(false)
        hardwareScope?.cancel()
        hardwareScope = null
        output?.close()
        output = null
        watchDecks()
        painting = null
        painted = null
        onHandoff = null
        pendingAction = null
        val end = onEnd
        onEnd = null
        end?.invoke()
        message(reason)
    }

    fun phoneKey(index: Int): Boolean {
        if (screen == null || visibleKey || (!preview && output == null)) return false
        visibleKey = true
        try { plugins.key(PluginKeyEvent(index, true)) } finally { visibleKey = false }
        plugins.key(PluginKeyEvent(index, false))
        log("${if (preview) "PREVIEW" else "ACTION"} plugin=${plugins.selectedId} key=${index + 1}")
        invalidate()
        return true
    }

    private fun handleKey(event: KeyEvent, arrivedAt: Long) {
        log("${if (event.synthetic) "CANCEL" else "KEY"} 0x${event.controlId.toString(16)} " +
            if (event.pressed) "press" else "release")
        when (event.controlId) {
            0x01a1 -> if (event.pressed && !event.synthetic) plugins.next(-1)
            0x01a2 -> if (event.pressed && !event.synthetic) plugins.next(1)
            in 1..9 -> {
                // Old labels can still be visible while a new page is uploading. Never replay a skipped press.
                if (event.pressed && painted?.generation != plugins.generation) return
                if (event.pressed && painted?.frame?.keys?.get(event.controlId - 1)?.inputToken !=
                    plugins.render().keys[event.controlId - 1].inputToken) return
                physicalPressAt = arrivedAt.takeIf { event.pressed && !event.synthetic && output != null }
                try {
                    plugins.key(PluginKeyEvent(event.controlId - 1, event.pressed, event.synthetic))
                } finally {
                    physicalPressAt = null
                }
            }
        }
    }

    fun setImage(image: Bitmap) {
        // Only composed copies leave the main thread. Don't cancel an in-flight HID transaction.
        custom?.recycle()
        custom = image
        log("Custom image loaded; labels will be overlaid on all nine keys")
        repaint()
    }

    fun repaint() { revision++; invalidate() }

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
        val frame = plugins.render()
        val stamp = Stamp(plugins.generation, frame, revision)
        val previous = painted
        if (stamp == previous) return
        painting = scope.launch {
            try {
                val same = previous?.generation == stamp.generation && previous.revision == stamp.revision
                for (index in 0..8) {
                    if (stamp.generation != plugins.generation || stamp.revision != revision || output !== connected) break
                    if (same && previous?.frame?.keys?.get(index) == frame.keys[index]) continue
                    val bitmap = renderKey(index, frame.keys[index])
                    try { connected.setKeyImage(index, bitmap) } finally { bitmap.recycle() }
                }
                if (stamp.generation == plugins.generation && stamp.revision == revision && output === connected) {
                    painted = stamp
                    if (previous == null) message("LCD image updates acknowledged. Verify visibility on the keypad.")
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

    private fun renderKey(index: Int, tile: KeyTile): Bitmap {
        val bitmap = Bitmap.createBitmap(118, 118, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(tile.background)
        custom?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER }
        if (custom != null) {
            paint.color = 0xc0000000.toInt()
            canvas.drawRect(0f, 36f, 118f, 91f, paint)
            paint.color = Color.WHITE
        }
        paint.textSize = 14f
        canvas.drawText("${index + 1}", 59f, 23f, paint)
        tile.iconComponent?.let { id -> favoriteApps.icon(id)?.let { icon ->
            canvas.drawBitmap(icon, null, android.graphics.Rect(36, 5, 82, 51), Paint(Paint.FILTER_BITMAP_FLAG))
        } }
        paint.textSize = 18f
        while (paint.measureText(tile.label) > 106 && paint.textSize > 10) paint.textSize -= 1
        canvas.drawText(tile.label, 59f, 66f, paint)
        paint.textSize = 12f
        while (paint.measureText(tile.footer) > 106 && paint.textSize > 8) paint.textSize -= 1
        canvas.drawText(tile.footer, 59f, 104f, paint)
        return bitmap
    }

    private fun perform(action: PluginAction, scope: CoroutineScope) {
        when (action) {
            is PluginAction.Deck -> {
                val result = runDeckAction(action.command.id, action.component?.let { mapOf("component" to it) }.orEmpty())
                message(result.message)
            }
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
                val handoff = PhoneAction(Target.Phone(action.app), ticket, plugins.generation, time)
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

    fun attachStudio(activity: StudioActivity) { studio = activity }
    fun updateStudio(activity: StudioActivity, state: StudioState) {
        if (activity === studio) decks.value = decks.value.copy(studio = state)
    }
    fun detachStudio(activity: StudioActivity) {
        if (activity === studio) { studio = null; decks.value = decks.value.copy(studio = StudioState()) }
    }
    fun publishFavorites(apps: List<Favorite>) {
        val selected = favoriteApps.selected()
        decks.value = decks.value.copy(favorites = apps.filter { it.component in selected })
    }
    /** Observe until both the physical session and visible screen release their ownership. */
    private fun watchDecks() {
        if (screen == null && output == null) {
            deckWatch?.cancel(); deckWatch = null
            decks.value = decks.value.copy(media = MediaDeckState(), navigationEnabled = false)
            return
        }
        if (deckWatch?.isActive == true) return
        deckWatch = CoroutineScope(Dispatchers.Main.immediate).launch {
            coroutineScope {
                var reload: Job? = null
                fun favoritesChanged() {
                    reload?.cancel()
                    reload = launch {
                        val installed = withContext(Dispatchers.IO) { favoriteApps.discover() }
                        val hadIcons = decks.value.favorites.isNotEmpty()
                        favoriteApps.invalidateIcons()
                        publishFavorites(installed)
                        if (hadIcons) repaint()
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
                    favoritesChanged()
                    while (true) {
                        decks.value = decks.value.copy(media = media.snapshot(), navigationEnabled = NavigationService.connected != null)
                        delay(1000)
                    }
                } finally { app.unregisterReceiver(packages) }
            }
        }
    }
    /** Main thread. User authority is synchronous input, never a parameter a saved workflow can forge. */
    fun runDeckAction(id: String, params: Map<String, String> = emptyMap()): DeckResult {
        val c = DeckCommand.fromId(id) ?: return unavailable("Unknown deck action.")
        val component = params["component"]
        if ((c == DeckCommand.OPEN && (component.isNullOrBlank() || params.size != 1)) ||
            (c != DeckCommand.OPEN && params.isNotEmpty())) return unavailable("Invalid action parameters.")
        if (!app.isUnlockedAndInteractive()) return unavailable("Unlock the phone before using this control.")
        if (c.workflowSafe) return media.execute(c)
        val now = SystemClock.elapsedRealtime()
        val press = physicalPressAt?.takeIf { now - it in 0 until 2_000L && request?.let(app::hasKeypadAccess) == true }
        if (!(visibleKey && screen != null) && press == null) return unavailable("Press a key on the unlocked phone or connected keypad.")
        if (c.deck == "studio" && c != DeckCommand.STUDIO)
            return studio?.press(c) ?: unavailable("Open Pocket Studio on the phone first.")
        if (c.deck == "navigation" && c != DeckCommand.NAV_ACCESS &&
            (screen == null || c in setOf(DeckCommand.HOME, DeckCommand.BACK, DeckCommand.RECENTS)))
            NavigationService.connected?.let { return it.press(c) }
        screen?.let { return it.deckAction(c, component) }
        val target = when (c) {
            DeckCommand.OPEN -> {
                if (favoriteApps.intentFor(component) == null) return unavailable("Favorite is no longer available.")
                Target.FavoriteApp(component!!)
            }
            DeckCommand.STUDIO -> if (studio != null) return ok("Studio is already open.") else Target.Studio
            else -> return unavailable("Open MX Pocket to use this control.")
        }
        val ticket = request ?: return unavailable("Keypad disconnected.")
        val handoff = PhoneAction(target, ticket, plugins.generation, press ?: return unavailable("Press a keypad key."))
        pendingAction = handoff
        onHandoff?.invoke(handoff)
        return ok("Shortcut offered. Tap its notification to open.")
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
