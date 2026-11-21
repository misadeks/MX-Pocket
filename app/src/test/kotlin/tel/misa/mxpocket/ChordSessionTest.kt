package tel.misa.mxpocket

import android.app.KeyguardManager
import android.graphics.Bitmap
import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Looper
import android.os.SystemClock
import java.time.Duration
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import tel.misa.mxpocket.actions.ActionCall
import tel.misa.mxpocket.deck.DeckKey
import tel.misa.mxpocket.decks.DeckCommand
import tel.misa.mxpocket.decks.ok
import tel.misa.mxpocket.driver.KeyEvent
import tel.misa.mxpocket.driver.KeyInput
import tel.misa.mxpocket.plugins.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class ChordSessionTest {
    private val app get() = RuntimeEnvironment.getApplication() as PocketApplication
    private val main get() = shadowOf(Looper.getMainLooper())
    private val usb get() = app.getSystemService(UsbManager::class.java)
    private val device = ReflectionHelpers.newInstance(UsbDevice::class.java).apply {
        ReflectionHelpers.setField(this, "mName", "test-keypad")
        ReflectionHelpers.setField(this, "mVendorId", 0x046d)
        ReflectionHelpers.setField(this, "mProductId", 0xc354)
        ReflectionHelpers.setField(this, "mConfigurations", emptyArray<UsbConfiguration>())
    }
    private val events = mutableListOf<String>()
    private var token = "initial"
    private var effect: PluginAction? = null
    private var launches = 0
    private var captures = 0
    private val probe = object : PocketPlugin {
        override val descriptor = PluginDescriptor("probe", "Probe", setOf(PluginCapability.OPEN_APPS, PluginCapability.STUDIO),
            chords = setOf(KeyChord(0, 8), KeyChord(1, 8)))
        override fun render(nowMillis: Long) = PluginFrame(List(9) {
            KeyTile("Key $it", "", 0xff222222.toInt(), inputToken = if (it == 0) token else null)
        })
        override fun onKey(event: PluginKeyEvent, host: PluginHost) {
            events += "${event.index}${if (event.pressed) "+" else "-"}"
            if (event.pressed) effect?.let(host::dispatch)
        }
        override fun onChord(chord: KeyChord, host: PluginHost) {
            events += "${chord.first}&${chord.second}"
            effect?.let(host::dispatch)
        }
    }
    private lateinit var emit: (KeyEvent) -> Unit
    private lateinit var session: PocketSession
    private var uploadGate: CompletableDeferred<Unit>? = null
    private val decoder = KeyInput(0x0b)
    private fun report(vararg keys: Int) {
        val grid = ByteArray(32).apply {
            this[0] = 0x13; this[1] = -1; this[2] = 2; this[5] = 1
            keys.forEachIndexed { i, key -> this[6 + i] = key.toByte() }
        }
        decoder.accept(grid).forEach(emit)
        main.idle()
    }
    private fun event(id: Int, down: Boolean, synthetic: Boolean = false) {
        emit(KeyEvent(id, down, synthetic))
        main.idle()
    }
    private fun waitWindow() = main.idleFor(Duration.ofMillis(150))
    private fun connect() {
        session.connect(session.reserve(device)!!, usb, device, {}, { error("Unexpected shortcut") })
        main.idle()
    }

    @Before fun setup() {
        shadowOf(usb).addOrUpdateUsbDevice(device, true)
        session = PocketSession(app, listOf(probe)) { _, _, event, _, _ ->
            emit = event
            object : KeypadOutput {
                override fun close() {}
                override suspend fun setKeyImage(index: Int, bitmap: Bitmap) { uploadGate?.await() }
            }
        }
        for (i in 0..8) session.deck.setKey("focus", "root", i,
            DeckKey(action = ActionCall("plugin.key", mapOf("pluginId" to "probe", "index" to "$i")))).getOrThrow()
        session.attach(object : PocketSession.Screen {
            override fun render() {}
            override fun openApp(app: PhoneApp): Boolean { launches++; return true }
            override fun deckAction(command: DeckCommand, component: String?) = ok("OK").also { captures++ }
        })
        connect()
    }

    @Test fun heldReportsMatchOnceInEitherOrderWithoutReusingAHeldModifier() {
        report(1); main.idleFor(Duration.ofMillis(149)); report(1, 9); report(1, 9); event(9, true)
        report(9); report(9, 1); report()
        assertEquals(listOf("0&8", "0+", "0-"), events)
        events.clear()
        report(9); report(9, 1, 2); report(2); report()
        assertEquals(listOf("0&8", "1+", "1-"), events)
        events.clear()
        report(9); report(2, 9); report()
        assertEquals(listOf("1&8"), events)
    }

    @Test fun tapTimeoutAndNonmatchingDownCommitInOrderButUnconfiguredKeysNeverWait() {
        report(4)
        assertEquals(listOf("3+"), events)
        report()
        report(9); assertEquals(listOf("3+", "3-"), events)
        report()
        report(1); report(1, 2) // Not a pair. Commit 1, then consider 2.
        assertEquals(listOf("3+", "3-", "8+", "8-", "0+"), events)
        waitWindow()
        report(1, 2, 9); report()
        assertEquals(listOf("3+", "3-", "8+", "8-", "0+", "1+", "8+", "0-", "1-", "8-"), events)
        // When the partner isn't bound and enabled on this page, both former prefixes are immediate.
        session.deck.setKey("focus", "root", 8, DeckKey()).getOrThrow()
        main.idle(); events.clear()
        report(1, 2)
        assertEquals(listOf("0+", "1+"), events)
    }

    @Test fun staleBindingsSyntheticCleanupAndReconnectNeverReplayPendingDowns() {
        event(1, true)
        token = "rebound" // Not yet painted or ACKed; logical identity changed under the first key.
        event(9, true); event(1, false); event(9, false)
        assertFalse("Stale first key must not run or match", events.any { it.startsWith("0") })
        events.clear()
        event(1, true); event(9, false, synthetic = true); event(1, false)
        event(1, true)
        session.deck.swapKeys("focus", "root", 0, 8).getOrThrow()
        main.idle(); event(1, true); event(1, false); waitWindow()
        assertTrue(events.isEmpty())
        event(9, true)
        val stale = emit
        session.disconnect(); connect(); stale(KeyEvent(1, true)); waitWindow()
        assertTrue(events.isEmpty())
        event(9, true); event(1, true)
        assertEquals(listOf("0&8"), events)
    }

    @Test fun rebindDuringGestureCannotTurnDroppedDownOrRepeatIntoWorkflowStart() {
        report(1)
        val gate = CompletableDeferred<Unit>()
        uploadGate = gate
        session.deck.setKey("focus", "root", 8, DeckKey(action =
            ActionCall("workflow.run", mapOf("workflowId" to "keypad-check")))).getOrThrow()
        main.idle()
        report(1, 9) // Workflow binding is still uploading.
        gate.complete(Unit); main.idle()
        event(9, true); report(); waitWindow()
        assertTrue(events.isEmpty())
        assertNull(session.workflows.status.value)
        report(9)
        assertEquals("keypad-check", session.workflows.status.value?.workflowId)
    }

    @Test fun chordAndDeferredSingleCannotBorrowLaunchCaptureOrPhoneTapAuthority() {
        effect = PluginAction.OpenApp(PhoneApp.CAMERA)
        report(1)
        session.phoneKey(8) // Virtual tap is a separate ordinary foreground input, never a physical partner.
        assertEquals(listOf("8+", "8-"), events)
        report(1, 9); report()
        report(1); waitWindow(); report()
        assertEquals(listOf("8+", "8-", "0&8", "0+", "0-"), events)
        assertEquals(1, launches)
        effect = PluginAction.Deck(DeckCommand.PHOTO)
        report(1, 9); report(); report(1); report()
        assertEquals("Press a key on the unlocked phone or connected keypad.", session.status)
        assertEquals(0, captures)
        effect = PluginAction.OpenApp(PhoneApp.CAMERA)
        report(4); event(4, true); report()
        assertEquals(2, launches) // Positive control: unconfigured fresh down works, repeat does not.
    }

    @Test fun screenBoundaryGrantLossAndOldArrivalCannotCompleteAPair() {
        val lock = shadowOf(app.getSystemService(KeyguardManager::class.java))
        event(1, true)
        lock.setKeyguardLocked(true)
        event(9, true); event(1, false); event(9, false)
        lock.setKeyguardLocked(false)
        event(1, true); session.cancelPhysicalGesture(); main.idle(); waitWindow(); event(1, false)
        event(1, true); shadowOf(usb).revokePermission(device, app.packageName)
        event(9, true); event(1, false); event(9, false)
        shadowOf(usb).addOrUpdateUsbDevice(device, true)
        emit(KeyEvent(1, true)); SystemClock.sleep(2_001); main.idle()
        event(1, false); waitWindow()
        assertTrue(events.isEmpty())
    }
}
