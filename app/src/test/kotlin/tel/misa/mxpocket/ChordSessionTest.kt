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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import tel.misa.mxpocket.actions.ActionCall
import tel.misa.mxpocket.deck.DeckKey
import tel.misa.mxpocket.decks.CameraControlsAccess
import tel.misa.mxpocket.decks.DeckCommand
import tel.misa.mxpocket.decks.NavigationService
import tel.misa.mxpocket.decks.ok
import tel.misa.mxpocket.driver.KeyEvent
import tel.misa.mxpocket.driver.KeyInput
import tel.misa.mxpocket.plugins.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
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
    private val writes = mutableListOf<String>()
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
    private fun status(index: Int, text: String) {
        assertTrue(runBlocking {
            session.actions.dispatch(ActionCall("keypad.status", mapOf("key" to "$index", "text" to text))).success
        })
        main.idle()
    }
    private fun connect() {
        session.connect(session.reserve(device)!!, usb, device, {}, { error("Unexpected shortcut") })
        main.idle()
    }

    @Before fun setup() {
        shadowOf(usb).addOrUpdateUsbDevice(device, true)
        val studio = DeckPlugin("studio", "Studio", PluginCapability.STUDIO)
        val chordStudio = object : PocketPlugin by studio {
            override val descriptor = studio.descriptor.copy(chords = setOf(KeyChord(0, 8)))
            override fun onChord(chord: KeyChord, host: PluginHost) {
                events += "shutter chord"
                host.dispatch(PluginAction.Deck(DeckCommand.SHUTTER))
            }
        }
        session = PocketSession(app, listOf(probe, chordStudio)) { _, _, event, _, _ ->
            emit = event
            object : GridOutput {
                override fun close() {}
                override suspend fun setKeyImage(index: Int, bitmap: Bitmap) {
                    writes += "key $index"
                    uploadGate?.await()
                }
                override suspend fun writeGrid(art: Bitmap?, tiles: List<Bitmap>, clearOldGaps: Boolean,
                    current: () -> Boolean): GridWrite {
                    check(current())
                    writes += "grid"
                    uploadGate?.await()
                    return GridWrite.ACKNOWLEDGED
                }
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

    @Test fun chordNeedsBothOriginalReceiptsButMayCompleteDuringAnUnrelatedSparseWrite() {
        val gate = CompletableDeferred<Unit>()
        uploadGate = gate
        report(1)
        status(4, "Updating")
        report(1, 9); report()
        assertEquals(listOf("grid", "key 4"), writes)
        assertEquals(listOf("0&8"), events)
        gate.complete(Unit); main.idle()

        events.clear()
        report(1)
        status(8, "New partner label") // Same action, new receipt for the partner.
        report(1, 9); waitWindow(); report()
        assertEquals(listOf("0+", "8+", "0-", "8-"), events)
        assertEquals("key 8", writes.last())
        events.clear()
        report(1, 9); report()
        assertEquals(listOf("0&8"), events)
    }

    @Test fun lateSparseAckCannotReplayBufferedOrHeldDownAcrossAPageSwitch() {
        val gate = CompletableDeferred<Unit>()
        uploadGate = gate
        report(1)
        status(0, "Changing")
        waitWindow(); report()
        assertTrue(events.isEmpty()) // Deferred single no longer matches the original pixels.
        report(1)
        session.selectProfile("studio"); session.selectProfile("focus"); main.idle()
        val nextGrid = CompletableDeferred<Unit>()
        uploadGate = nextGrid
        gate.complete(Unit); main.idle()
        event(1, true); report(1, 9)
        nextGrid.complete(Unit); main.idle()
        event(9, true); report()
        assertEquals(listOf("grid", "key 0", "grid"), writes)
        assertTrue(events.isEmpty())
        report(1, 9); report()
        assertEquals(listOf("0&8"), events)
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
        effect = PluginAction.Deck(DeckCommand.SHUTTER)
        report(1, 9); report(); report(1); report()
        assertEquals("Press a key on the unlocked phone or connected keypad.", session.status)
        assertEquals(0, captures)
        effect = PluginAction.OpenApp(PhoneApp.CAMERA)
        report(4); event(4, true); report()
        assertEquals(2, launches) // Positive control: unconfigured fresh down works, repeat does not.
    }

    @Config(shadows = [CameraWindowFixture::class])
    @Test fun acknowledgedStudioShutterRequiresAnImmediateSingleEvenWhenTheCameraIsReady() {
        installTestCamera(app)
        val service = Robolectric.buildService(NavigationService::class.java).create()
        try {
            val navigation = service.get()
            ReflectionHelpers.callInstanceMethod<Unit>(navigation, "onServiceConnected")
            val button = cameraNode("Take picture")
            var clicks = 0
            shadowOf(button).setOnPerformActionListener { _, _ -> clicks++; true }
            (shadowOf(navigation) as CameraWindowFixture).root = button
            CameraControlsAccess(app).enabled = true
            session.selectProfile("studio"); main.idle()

            report(9); report(9, 1); report() // Complete with the acknowledged Shutter key itself.
            assertEquals(listOf("shutter chord"), events)
            assertEquals(0, clicks)
            report(1); waitWindow(); report() // Replay the very same Studio Shutter binding.
            report(1); report() // Tap/release fallback is not authority either.
            assertEquals(0, clicks)

            session.deck.setKey("studio", "root", 8, DeckKey()).getOrThrow()
            main.idle()
            val gate = CompletableDeferred<Unit>()
            uploadGate = gate
            status(0, "Updating shutter")
            report(1)
            gate.complete(Unit); main.idle(); event(1, true); report()
            assertEquals("A held shutter must not acquire authority from a late ACK", 0, clicks)
            val lock = shadowOf(app.getSystemService(KeyguardManager::class.java))
            lock.setKeyguardLocked(true); report(1); report(); lock.setKeyguardLocked(false)
            assertEquals(0, clicks)
            report(1); report()
            assertEquals(1, clicks) // Same grant, camera, binding and key; now an immediate single.
        } finally { session.disconnect(); service.destroy() }
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
