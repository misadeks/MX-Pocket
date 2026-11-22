package tel.misa.mxpocket

import android.app.KeyguardManager
import android.graphics.Bitmap
import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Looper
import android.os.SystemClock
import android.view.ViewConfiguration
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
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
import tel.misa.mxpocket.workflow.TriggerKind
import tel.misa.mxpocket.workflow.WorkflowOrigin
import tel.misa.mxpocket.workflow.WorkflowTrigger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ChordSessionTest {
    @Implements(ViewConfiguration::class)
    class LongAccessibilityDelay {
        companion object {
            @JvmStatic @Implementation fun getLongPressTimeout() = 2_700
        }
    }
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
    private var readOnly = false
    private lateinit var probeHost: PluginHost
    private var effect: PluginAction? = null
    private var launches = 0
    private var captures = 0
    private val probe = object : PocketPlugin {
        override val descriptor = PluginDescriptor("probe", "Probe", setOf(PluginCapability.OPEN_APPS, PluginCapability.STUDIO),
            chords = setOf(KeyChord(0, 8), KeyChord(1, 8)))
        override fun onActivate(host: PluginHost) { probeHost = host }
        override fun render(nowMillis: Long) = PluginFrame(List(9) {
            KeyTile("Key $it", "", 0xff222222.toInt(), inputToken = if (it == 0) token else null,
                readOnly = it == 0 && readOnly)
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
    private val holdDelay get() = ViewConfiguration.getLongPressTimeout().toLong()
    private fun pluginSlot(index: Int) = ActionCall("plugin.key", mapOf("pluginId" to "probe", "index" to "$index"))
    private fun bindHold(index: Int, hold: ActionCall?, tap: ActionCall? = pluginSlot(index)) {
        session.deck.setKey("focus", "root", index, DeckKey(action = tap, holdAction = hold)).getOrThrow()
        main.idle()
    }
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

    @Test fun releaseBeforeAtAndAfterDeadlineUsesArrivalEvenWhenTheTimerIsBehindTheRelease() {
        bindHold(3, pluginSlot(5))
        for (duration in listOf(holdDelay - 1, holdDelay, holdDelay + 1)) {
            events.clear()
            // Queue both edges before main handles down. An overdue callback cannot beat this up.
            emit(KeyEvent(4, true)); SystemClock.sleep(duration); emit(KeyEvent(4, false))
            main.idle()
            val chosen = if (duration < holdDelay) "3" else "5"
            assertEquals(listOf("$chosen+", "$chosen-"), events)
        }
        events.clear()
        event(4, true); event(4, true)
        main.idleFor(Duration.ofMillis(holdDelay - 1))
        assertTrue(events.isEmpty())
        main.idleFor(Duration.ofMillis(1)); assertEquals(listOf("5+"), events)
        event(4, true); main.idleFor(Duration.ofMillis(holdDelay)); event(4, false); event(4, false)
        assertEquals(listOf("5+", "5-"), events)
    }

    @Test fun chordConsumesBothHoldsButSingleDeadlineStillRunsFromOriginalDown() {
        bindHold(0, pluginSlot(5)); bindHold(8, pluginSlot(6))
        report(1); report(1, 9)
        main.idleFor(Duration.ofMillis(holdDelay * 2)); report()
        assertEquals(listOf("0&8"), events)
        events.clear()
        report(1); waitWindow(); assertTrue(events.isEmpty())
        main.idleFor(Duration.ofMillis(holdDelay - PhysicalChordInput.WINDOW_MS))
        assertEquals(listOf("5+"), events)
        report(); report(9); report()
        assertEquals(listOf("5+", "5-", "8+", "8-"), events)
    }

    @Config(shadows = [LongAccessibilityDelay::class])
    @Test fun longAccessibilityDelayIsNotClampedToTheFreshDownAuthorityWindow() {
        bindHold(0, pluginSlot(5))
        report(1); main.idleFor(Duration.ofMillis(2_699))
        assertTrue(events.isEmpty())
        main.idleFor(Duration.ofMillis(1)); report()
        assertEquals(listOf("5+", "5-"), events)
    }

    @Test fun otherDownsDoNotCancelIndependentHoldTimersAndHoldOnlyNeverTaps() {
        bindHold(3, pluginSlot(5), tap = null); bindHold(4, pluginSlot(6))
        report(4); report(); assertTrue(events.isEmpty())
        report(4, 5, 8)
        assertEquals(listOf("7+"), events) // Unconfigured key is still immediate.
        main.idleFor(Duration.ofMillis(holdDelay)); report()
        assertEquals(listOf("7+", "5+", "6+", "5-", "6-", "7-"), events)
    }

    @Test fun delayedChoicesCannotSurvivePageBindingSessionGrantOrInputCancellation() {
        bindHold(3, pluginSlot(5))
        event(4, true); session.selectProfile("studio"); session.selectProfile("focus"); main.idle()
        event(4, false)
        event(4, true); bindHold(3, pluginSlot(6)); event(4, true); event(4, false)
        event(4, true); event(4, false, synthetic = true)
        event(4, true); session.cancelPhysicalGesture(); main.idle(); event(4, false)
        event(4, true); shadowOf(usb).revokePermission(device, app.packageName)
        main.idleFor(Duration.ofMillis(holdDelay)); event(4, false)
        shadowOf(usb).addOrUpdateUsbDevice(device, true)
        event(4, true)
        val old = emit
        session.disconnect(); connect(); old(KeyEvent(4, false))
        main.idleFor(Duration.ofMillis(holdDelay)); assertTrue(events.isEmpty())
        event(4, true); main.idleFor(Duration.ofMillis(holdDelay)); event(4, false)
        assertEquals(listOf("6+", "6-"), events)
    }

    @Test fun holdRechecksSecondaryPluginRoleAndIdentityAndCannotAcquireFreshDownAuthority() {
        bindHold(3, pluginSlot(0))
        event(4, true); readOnly = true; main.idleFor(Duration.ofMillis(holdDelay)); event(4, false)
        readOnly = false
        event(4, true); token = "replacement"; main.idleFor(Duration.ofMillis(holdDelay)); event(4, false)
        assertTrue(events.isEmpty())
        effect = PluginAction.OpenApp(PhoneApp.CAMERA)
        event(4, true); main.idleFor(Duration.ofMillis(holdDelay)); event(4, false)
        assertEquals(listOf("0+", "0-"), events)
        assertEquals(0, launches)
        effect = PluginAction.Deck(DeckCommand.SHUTTER)
        session.phoneKey(3, hold = true)
        assertEquals(0, captures)
    }

    @Test fun aVirtualHoldCannotStartAPhysicalOnlyWorkflowButAuthorizedPhysicalHoldCan() {
        val flow = session.workflows.definitions.value.first { it.id == "keypad-check" }
        assertNull(runBlocking { session.workflows.save(flow.copy(trigger = WorkflowTrigger(TriggerKind.PHYSICAL))) })
        bindHold(3, ActionCall("workflow.run", mapOf("workflowId" to flow.id)))
        session.phoneKey(3, hold = true)
        assertNull(session.workflows.status.value)
        event(4, true); main.idleFor(Duration.ofMillis(holdDelay)); event(4, false)
        assertEquals(WorkflowOrigin.PHYSICAL, session.workflows.status.value?.origin)
        assertTrue(events.isEmpty())
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

    @Test fun changingOnlyReadOnlyRevokesBufferedInputAndRequiresAcknowledgmentBeforeRearming() {
        for (viaTimeout in listOf(false, true)) {
            report(1)
            val disarm = CompletableDeferred<Unit>()
            uploadGate = disarm
            readOnly = true; probeHost.invalidate(); main.idle()
            if (viaTimeout) waitWindow()
            report()
            assertTrue(events.isEmpty())
            disarm.complete(Unit); main.idle()

            val rearm = CompletableDeferred<Unit>()
            uploadGate = rearm
            readOnly = false; probeHost.invalidate(); main.idle()
            report(1) // Identical pixels, but the actionable role has not been acknowledged.
            rearm.complete(Unit); main.idle()
            event(1, true); waitWindow(); report()
            assertTrue(events.isEmpty())
        }
        report(1); waitWindow(); report()
        assertEquals(listOf("0+", "0-"), events)
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
