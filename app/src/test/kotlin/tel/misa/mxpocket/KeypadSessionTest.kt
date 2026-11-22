package tel.misa.mxpocket

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState as P
import android.os.PowerManager
import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.ViewConfiguration
import java.time.Duration
import tel.misa.mxpocket.shared.ui.PocketScreenEvent
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowUsbManager
import org.robolectric.shadows.ShadowSettings
import org.robolectric.util.ReflectionHelpers
import tel.misa.mxpocket.driver.KeyEvent
import tel.misa.mxpocket.plugins.PhoneApp
import tel.misa.mxpocket.decks.DeckCommand
import tel.misa.mxpocket.decks.CameraMode
import tel.misa.mxpocket.decks.CameraControlsAccess
import tel.misa.mxpocket.decks.NavigationService
import tel.misa.mxpocket.actions.ActionCall
import tel.misa.mxpocket.deck.DeckJson
import tel.misa.mxpocket.deck.DeckKey
import tel.misa.mxpocket.decks.Favorite
import tel.misa.mxpocket.workflow.StepKind
import tel.misa.mxpocket.workflow.WorkflowDefinition
import tel.misa.mxpocket.workflow.WorkflowPhase
import tel.misa.mxpocket.workflow.WorkflowStep

private fun device(name: String, product: Int = 0xc354) =
    ReflectionHelpers.newInstance(UsbDevice::class.java).also {
        ReflectionHelpers.setField(it, "mName", name)
        ReflectionHelpers.setField(it, "mVendorId", 0x046d)
        ReflectionHelpers.setField(it, "mProductId", product)
        ReflectionHelpers.setField(it, "mConfigurations", emptyArray<UsbConfiguration>())
    }

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = KeypadSessionTest.FakeApp::class, shadows = [KeypadSessionTest.ConsentUsb::class])
@LooperMode(LooperMode.Mode.PAUSED)
class KeypadSessionTest {
    class FakeApp : TestPocketApplication() {
        var claims = 0
        var closes = 0
        lateinit var emit: (KeyEvent) -> Unit
        val lcd = mutableMapOf<Int, Bitmap>()
        override fun createSession() = PocketSession(this) { _, _, event, _, _ ->
            emit = event
            claims++
            lcd.clear()
            object : KeypadOutput {
                override fun close() { closes++; lcd.clear() }
                override suspend fun setKeyImage(index: Int, bitmap: Bitmap) { lcd[index] = bitmap }
            }
        }

        internal fun connectKeypad(active: PocketSession = session) {
            val usb = getSystemService(UsbManager::class.java)
            val keypad = device("test-keypad")
            shadowOf(usb).addOrUpdateUsbDevice(keypad, true)
            active.connect(checkNotNull(active.reserve(keypad)), usb, keypad, {}, { error("Unexpected shortcut") })
            shadowOf(Looper.getMainLooper()).idle() // Complete the initial LCD writes before sending a down.
            check(active.ready) { active.status }
        }

        fun press(controlId: Int) {
            emit(KeyEvent(controlId, true))
            emit(KeyEvent(controlId, false))
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
    @Implements(UsbManager::class)
    class ConsentUsb : ShadowUsbManager() {
        lateinit var reply: PendingIntent
        @Implementation fun requestPermission(device: UsbDevice, callback: PendingIntent) {
            check(!hasPermission(device))
            reply = callback
        }
    }

    private val app get() = RuntimeEnvironment.getApplication() as FakeApp
    private val usb get() = app.getSystemService(UsbManager::class.java)
    private val access get() = UsbAccess(app)
    private val main get() = shadowOf(Looper.getMainLooper())

    @Before fun grantNotifications() {
        shadowOf(app).grantPermissions("tel.misa.mxpocket.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        if (Build.VERSION.SDK_INT >= 33) shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun attached(device: UsbDevice) = Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        .putExtra(UsbManager.EXTRA_DEVICE, device)
    private fun deliver(intent: Intent) {
        Robolectric.buildActivity(MainActivity::class.java, intent).setup().use { /* no button press */ }
    }

    @Test fun inactiveOneNoteKeysAreGrayAndDisabledWithoutUnavailableMarkers() {
        val session = app.session
        app.connectKeypad()
        try {
            session.selectProfile("onenote")
            main.idle()
            val action = ActionCall("plugin.key", mapOf("pluginId" to "onenote", "index" to "0"))
            session.deck.setKey("onenote", "root", 0, DeckKey(label = "My pen", action = action)).getOrThrow()
            main.idle()
            val keys = session.frame().keys
            assertTrue(keys.filterIndexed { index, _ -> index != 4 }.all { !it.enabled && it.background == 0xff494c51.toInt() })
            assertTrue(keys[4].enabled)
            assertTrue(keys.all { it.footer.isEmpty() })
            assertEquals("My pen", keys[0].label)
            assertNull(keys[0].glyph)
            val status = session.status
            app.press(1)
            assertEquals(status, session.status)
        } finally { session.disconnect() }
    }

    @Test fun authorizedAudioDisplaysAndControlsThePlayerWithScreenOffOrKeyguardLocked() {
        val media = MediaSession(app,"external player")
        val player = media.controller
        val controller = shadowOf(player)
        controller.setPackageName("example.music")
        controller.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,"Private track").build())
        fun state(playing: Boolean) = controller.executeOnPlaybackStateChanged(P.Builder().setState(
            if (playing) P.STATE_PLAYING else P.STATE_PAUSED, 0, 1f)
            .setActions(P.ACTION_PLAY or P.ACTION_PAUSE or P.ACTION_SKIP_TO_NEXT or P.ACTION_SEEK_TO).build())
        state(true)
        shadowOf(app.getSystemService(MediaSessionManager::class.java)).addController(player)
        app.enableMusicSessions()
        val real = device("test-keypad")
        shadowOf(usb).addOrUpdateUsbDevice(real,true)
        val session = app.session
        session.selectProfile("audio")
        val flow = WorkflowDefinition(name = "Skip", steps = listOf(WorkflowStep(StepKind.ACTION, target = "media.next")))
        assertNull(kotlinx.coroutines.runBlocking { session.workflows.save(flow) })
        session.deck.setKey("audio","root",0,DeckKey(action = ActionCall("workflow.run",mapOf("workflowId" to flow.id)))).getOrThrow()
        val service = Robolectric.buildService(KeypadService::class.java).create()
        service.get().onStartCommand(KeypadService.command(app,session.reserve(real)!!),0,1)
        main.idle()
        val power = shadowOf(app.getSystemService(PowerManager::class.java))
        val lock = shadowOf(app.getSystemService(KeyguardManager::class.java))
        val audio = app.getSystemService(AudioManager::class.java)
        fun press(id: Int) { app.emit(KeyEvent(id,true)); app.emit(KeyEvent(id,false)); main.idle() }
        fun tick() = main.idle()
        assertEquals("Private track",session.deckState.value.media.title)
        for (screenOn in listOf(false,true)) {
            power.setIsInteractive(screenOn)
            lock.setKeyguardLocked(screenOn)
            app.sendBroadcast(Intent(if (screenOn) Intent.ACTION_SCREEN_ON else Intent.ACTION_SCREEN_OFF))
            state(true); tick()
            assertEquals("Pause",session.frame().keys[4].label)
            assertFalse(session.frame().keys[6].enabled)
            assertFalse(session.frame().keys[8].enabled)
            assertEquals("Private track",session.deckState.value.media.title)
            assertFalse(session.runDeckAction("media.play-pause").success)
            assertFalse(session.runDeckAction("studio.open").success)
            assertFalse(session.actions.let { kotlinx.coroutines.runBlocking { it.dispatch(ActionCall("media.next")) } }.success)
            val controls = shadowOf(player.transportControls)
            press(5); assertEquals(P.ACTION_PAUSE,controls.lastPerformedAction)
            press(1); assertEquals(WorkflowPhase.FAILED,session.workflows.status.value?.phase)
            assertEquals(P.ACTION_PAUSE,controls.lastPerformedAction)
            state(false); tick()
            press(5); assertEquals(P.ACTION_PLAY,controls.lastPerformedAction)
            press(6); assertEquals(P.ACTION_SKIP_TO_NEXT,controls.lastPerformedAction)
            press(4); assertEquals(P.ACTION_SKIP_TO_NEXT,controls.lastPerformedAction)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC,3,0)
            repeat(2) {
                press(3)
                val actual = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                assertTrue(actual > 3)
                assertEquals(actual,session.deckState.value.media.volume)
                assertEquals("$actual / ${audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}",session.frame().keys[1].footer)
                assertEquals(actual,session.frame().keys[1].volumeLevel?.current)
            }
            press(7); press(8); press(9)
            assertEquals(P.ACTION_SKIP_TO_NEXT,controls.lastPerformedAction)
            assertNull(shadowOf(app).nextStartedActivity)
        }
        shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationListenerAccessGranted(
            NotificationAccess(app).component,false)
        app.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF)); main.idle()
        assertNull(session.deckState.value.media.playing)
        press(5)
        val fallback = shadowOf(audio).dispatchedMediaKeyEvents
        assertEquals(listOf(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.ACTION_UP),fallback.map { it.action })
        assertTrue(fallback.all { it.keyCode == android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE })
        shadowOf(usb).revokePermission(real,app.packageName)
        state(true); tick(); press(5)
        assertEquals(2,shadowOf(audio).dispatchedMediaKeyEvents.size)
        assertEquals(P.ACTION_SKIP_TO_NEXT,shadowOf(player.transportControls).lastPerformedAction)
        session.disconnect(); service.destroy(); media.release()
    }

    @Test fun disconnectedMediaActionCannotUseTheScreenOffException() {
        val session = app.session
        val audio = app.getSystemService(AudioManager::class.java)
        Robolectric.buildActivity(MainActivity::class.java).setup().use {
            session.selectProfile("audio")
            audio.setStreamVolume(AudioManager.STREAM_MUSIC,3,0)
            val before = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(false)
            assertFalse(session.runDeckAction(DeckCommand.LOUDER.id).success)
            assertEquals(before,audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        }
    }

    @Test fun manualPermissionCanBeDeniedRetriedAndCompletedAfterRecreationWithOptInOff() {
        val real = device("test-keypad")
        val session = app.session
        fun assertDisconnected() {
            assertFalse(session.ready)
            assertEquals("Disconnected", session.connectionLabel)
            assertNull(session.request)
        }
        shadowOf(usb).addOrUpdateUsbDevice(real, false)
        assertFalse(access.automatic)
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            fun click() = controller.get().handleScreenEvent(PocketScreenEvent.ToggleConnection)
            click()
            (shadowOf(usb) as ConsentUsb).reply.send()
            main.idle()
            assertNull(shadowOf(app).nextStartedService)
            assertTrue(session.status.contains("denied"))
            assertDisconnected()
            click()
            val grant = (shadowOf(usb) as ConsentUsb).reply
            controller.recreate()
            shadowOf(usb).addOrUpdateUsbDevice(real, true)
            grant.send()
            main.idle()
            val command = shadowOf(app).nextStartedService
            assertNotNull(command)
            val service = Robolectric.buildService(KeypadService::class.java).create()
            service.get().onStartCommand(command, 0, 1)
            controller.recreate()
            controller.pause().stop()
            assertEquals(1, app.claims)
            assertEquals(0, app.closes)
            assertTrue(session.ready)
            shadowOf(usb).removeUsbDevice(real)
            app.sendBroadcast(Intent(UsbManager.ACTION_USB_DEVICE_DETACHED).putExtra(UsbManager.EXTRA_DEVICE, real))
            main.idle()
            assertDisconnected()
            assertEquals(1, app.closes)
            assertTrue(shadowOf(service.get()).isStoppedBySelf)
            service.destroy()
            controller.start().resume()
        }
    }

    @Test fun pendingPermissionCannotStartAfterDetachAndLockedOrHiddenNotificationCannotStart() {
        val real = device("test-keypad")
        shadowOf(usb).addOrUpdateUsbDevice(real, false)
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            controller.get().handleScreenEvent(PocketScreenEvent.ToggleConnection)
            val stale = (shadowOf(usb) as ConsentUsb).reply
            shadowOf(usb).removeUsbDevice(real)
            app.sendBroadcast(Intent(UsbManager.ACTION_USB_DEVICE_DETACHED).putExtra(UsbManager.EXTRA_DEVICE, real))
            main.idle()
            assertNull(app.session.request)
            shadowOf(usb).addOrUpdateUsbDevice(real, true)
            stale.send()
            main.idle()
            assertNull(shadowOf(app).nextStartedService)
            access.automatic = true
            val lock = shadowOf(app.getSystemService(KeyguardManager::class.java))
            lock.setKeyguardLocked(true)
            controller.newIntent(attached(real))
            assertNull(shadowOf(app).nextStartedService)
            lock.setKeyguardLocked(false)
            shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
            controller.get().handleScreenEvent(PocketScreenEvent.ToggleConnection)
            assertNull(shadowOf(app).nextStartedService)
        }
    }

    @Test fun attachRequiresOptInOneRealMatchingDeviceAndLiveGrant() {
        val real = device("test-keypad")
        shadowOf(usb).addOrUpdateUsbDevice(real, true)
        deliver(attached(real))
        assertNull(shadowOf(app).nextStartedService)
        access.automatic = true
        val unrelated = device(real.deviceName, 123)
        shadowOf(usb).removeUsbDevice(real)
        shadowOf(usb).addOrUpdateUsbDevice(unrelated, true)
        deliver(attached(real)) // Supported extra cannot substitute for a different real device.
        assertNull(shadowOf(app).nextStartedService)
        shadowOf(usb).removeUsbDevice(unrelated)
        shadowOf(usb).addOrUpdateUsbDevice(real, false)
        deliver(attached(real))
        assertNull(shadowOf(app).nextStartedService)
        shadowOf(usb).addOrUpdateUsbDevice(real, true)
        deliver(attached(real))
        assertNotNull(shadowOf(app).nextStartedService)
        val ticket = app.session.request
        deliver(attached(real))
        assertEquals(ticket, app.session.request)
        assertNull(shadowOf(app).nextStartedService)
        app.session.disconnect()
        deliver(attached(real))
        assertNull(shadowOf(app).nextStartedService)
        val next = device("replugged-keypad")
        shadowOf(usb).removeUsbDevice(real)
        shadowOf(usb).addOrUpdateUsbDevice(next, true)
        deliver(attached(next))
        assertNotNull(shadowOf(app).nextStartedService)
        assertNotEquals(ticket, app.session.request)
        shadowOf(usb).removeUsbDevice(next)
        shadowOf(usb).addOrUpdateUsbDevice(real, true)
        deliver(attached(real)) // Arrival outruns detach of the previous reservation.
        assertNotNull(shadowOf(app).nextStartedService)
        assertEquals(real.deviceName, app.session.request?.name)
    }

    @Test fun serviceRevalidatesRevokedGrantAndRejectsStartAfterProcessLoss() {
        val real = device("test-keypad")
        shadowOf(usb).addOrUpdateUsbDevice(real, true)
        access.automatic = true
        deliver(attached(real))
        val command = shadowOf(app).nextStartedService
        assertNotNull(command)
        shadowOf(usb).revokePermission(real, app.packageName)
        val controller = Robolectric.buildService(KeypadService::class.java).create()
        val service = controller.get()
        assertEquals(android.app.Service.START_NOT_STICKY, service.onStartCommand(command, 0, 1))
        assertNull(app.session.request)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertNull(shadowOf(service).lastForegroundNotification)
        shadowOf(usb).addOrUpdateUsbDevice(real, true)
        service.onStartCommand(command, 0, 2) // no live reservation
        assertNull(shadowOf(service).lastForegroundNotification)
        controller.destroy()
    }

    @Test fun servicePromotesOnceAndOnlyItsCurrentNotificationCanStopIt() {
        val real = device("test-keypad")
        shadowOf(usb).addOrUpdateUsbDevice(real, true)
        val ticket = app.session.reserve(real)!!
        val command = KeypadService.command(app, ticket)
        val controller = Robolectric.buildService(KeypadService::class.java).create()
        val service = controller.get()
        service.onStartCommand(command, 0, 1)
        val notice = shadowOf(service).lastForegroundNotification
        assertNotNull(notice.contentIntent)
        if (Build.VERSION.SDK_INT >= 29) assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            service.foregroundServiceType)
        val stop = shadowOf(notice.actions.single().actionIntent).savedIntent
        service.onStartCommand(command, 0, 2)
        service.onStartCommand(Intent(stop).setData(null), 0, 3)
        assertEquals(ticket, app.session.request)
        service.onStartCommand(stop, 0, 4)
        assertNull(app.session.request)
        assertTrue(shadowOf(service).isForegroundStopped)
        assertTrue(shadowOf(service).isStoppedBySelf)
        controller.destroy()
    }

    private fun press(control: Int) = app.press(control)

    @Config(shadows = [ChordSessionTest.LongAccessibilityDelay::class])
    @Test fun physicalPageButtonsBrowseRootsTapHomeAndHoldForGameInEitherOrder() {
        app.connectKeypad()
        val session = app.session
        val repo = session.deck
        fun expectProfile(id: String) {
            assertEquals(id, repo.operatingState.value.activeProfileId)
        }
        expectProfile("@home")
        assertEquals("Focus", session.frame().keys.first().label)
        press(1); expectProfile("focus")
        session.setPluginEnabled("audio", false)
        val ids = listOf("phone", "launcher", "studio", "navigation", "teams", "onenote", "@home", "focus")
        ids.forEach { press(0x01a2); expectProfile(it) }
        val hold = ViewConfiguration.getLongPressTimeout().toLong()
        for ((first, second) in listOf(0x01a1 to 0x01a2, 0x01a2 to 0x01a1)) {
            val backup = repo.exportJson()
            app.emit(KeyEvent(first, true)); main.idle(); expectProfile("focus")
            main.idleFor(Duration.ofMillis(hold))
            app.emit(KeyEvent(second, true)); main.idle()
            main.idleFor(Duration.ofMillis(hold - 1)); expectProfile("focus")
            app.emit(KeyEvent(first, false)); main.idle(); expectProfile("@home")
            app.emit(KeyEvent(second, false)); main.idle()
            main.idleFor(Duration.ofMillis(hold)); expectProfile("@home")
            press(1); expectProfile("focus")

            app.emit(KeyEvent(first, true)); app.emit(KeyEvent(second, true)); main.idle()
            main.idleFor(Duration.ofMillis(hold - 1)); expectProfile("focus")
            main.idleFor(Duration.ofMillis(1)); expectProfile("game.xo")
            app.emit(KeyEvent(second, false)); app.emit(KeyEvent(first, false)); main.idle()
            expectProfile("game.xo")
            press(first); expectProfile("focus")
            assertEquals(backup, repo.exportJson())
        }
        session.setPluginEnabled("game.xo", false)
        app.emit(KeyEvent(0x01a1, true)); app.emit(KeyEvent(0x01a2, true)); main.idle()
        main.idleFor(Duration.ofMillis(hold))
        app.emit(KeyEvent(0x01a2, false)); app.emit(KeyEvent(0x01a1, false)); main.idle()
        expectProfile("focus")
        app.emit(KeyEvent(0x01a1, true)); app.emit(KeyEvent(0x01a2, true))
        app.emit(KeyEvent(0x01a2, false)); app.emit(KeyEvent(0x01a1, false)); main.idle()
        expectProfile("@home")
        session.disconnect()
    }

    @Test fun cancelledPageButtonChordNeverNavigatesEvenAfterReconnect() {
        val session = app.session
        app.connectKeypad()
        press(1)
        val before = session.deck.operatingState.value
        val hold = Duration.ofMillis(ViewConfiguration.getLongPressTimeout().toLong())
        fun chord() { app.emit(KeyEvent(0x01a1, true)); app.emit(KeyEvent(0x01a2, true)) }
        fun expectUnchanged() { main.idleFor(hold); assertEquals(before, session.deck.operatingState.value) }
        chord(); main.idle()
        app.emit(KeyEvent(0x01a1, false, synthetic = true)); main.idle()
        expectUnchanged()
        app.emit(KeyEvent(0x01a2, false)); main.idle()
        chord(); main.idle(); session.cancelPhysicalGesture()
        app.emit(KeyEvent(0x01a2, false)); app.emit(KeyEvent(0x01a1, false))
        expectUnchanged()
        chord(); session.cancelPhysicalGesture() // Both USB downs already posted, but not handled yet.
        expectUnchanged()
        app.emit(KeyEvent(0x01a2, false)); app.emit(KeyEvent(0x01a1, false)); main.idle()
        chord(); main.idle()
        val oldInput = app.emit
        session.disconnect(); app.connectKeypad()
        oldInput(KeyEvent(0x01a2, false)); oldInput(KeyEvent(0x01a1, false))
        expectUnchanged()
        press(0x01a2); assertEquals("audio", session.deck.operatingState.value.activeProfileId)
        session.disconnect()
    }

    @Test fun physicalPageButtonsRespectImportedOrderAndNeverScrollIntoFolders() {
        val repo = app.session.deck
        val desk = repo.createProfile("Desk").getOrThrow()
        val leaf = repo.createPage(desk, "Details").getOrThrow()
        repo.setKey(desk, "root", 0, DeckKey(action = ActionCall("page.open", mapOf("pageId" to leaf)))).getOrThrow()
        val state = repo.state.value
        repo.importJson(DeckJson.encode(state.copy(profiles = state.profiles.reversed()))).getOrThrow()
        repo.setPinned("focus").getOrThrow()
        app.connectKeypad()
        press(0x01a2) // From Focus wrap to Home, then the first imported profile.
        press(0x01a2)
        assertEquals(desk, repo.state.value.activeProfileId)
        assertEquals(desk, repo.state.value.pinnedProfileId)
        press(1)
        assertEquals(leaf, repo.state.value.activePage.id)
        press(0x01a1)
        assertEquals("@home", repo.operatingState.value.activeProfileId)
        press(0x01a2)
        assertEquals("root", repo.state.value.activePage.id)
        assertEquals(desk, repo.state.value.activeProfileId)
        app.session.disconnect()
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun screenReplacementAndBackgroundKeepOneInputOwnerAndFinishInFlightLcdBeforeSwitch() {
        val real = device("test-keypad")
        val uploads = mutableListOf<Int>()
        val nextPageImage = CompletableDeferred<Unit>()
        var closed = 0
        var opens = 0
        var stops = 0
        lateinit var event: (KeyEvent) -> Unit
        lateinit var driverFailed: (Throwable) -> Unit
        val session = PocketSession(app) { _, _, onEvent, _, error ->
            opens++
            event = onEvent
            driverFailed = error
            object : KeypadOutput {
                override fun close() { closed++ }
                override suspend fun setKeyImage(index: Int, bitmap: Bitmap) {
                    uploads.add(index)
                    if (uploads.size == 10) nextPageImage.await()
                }
            }
        }
        var handoff: PhoneApp? = null
        val screen = object : PocketSession.Screen {
            override fun render() {}
            override fun openApp(app: PhoneApp): Boolean = error("Background action used an old Activity")
        }
        session.attach(screen)
        val ticket = session.reserve(real)!!
        session.selectProfile("focus")
        session.connect(ticket, usb, real, { stops++ }, {
            handoff = (it.target as PocketSession.Target.Phone).app
            tel.misa.mxpocket.decks.ok("Shortcut offered")
        })
        main.idle()
        session.detach(screen)
        event(KeyEvent(1, true))
        event(KeyEvent(1, false))
        main.idle()
        assertEquals("Pause", session.plugins.render().keys[0].label)
        // Focus's updated Start/Pause tile is now in flight. Switch to Phone and try Camera.
        event(KeyEvent(0x01a2, true)) // Focus -> Media
        event(KeyEvent(0x01a2, false))
        event(KeyEvent(0x01a2, true)) // Media -> Phone, while the old frame is in flight.
        event(KeyEvent(0x01a2, false))
        event(KeyEvent(1, true))
        event(KeyEvent(1, false))
        main.idle()
        assertNull(handoff)
        assertEquals((0..8).toList() + 0, uploads)
        nextPageImage.complete(Unit)
        main.idle()
        assertEquals((0..8).toList() + 0 + (0..8), uploads)
        event(KeyEvent(1, true))
        main.idle()
        assertEquals(PhoneApp.CAMERA, handoff)
        session.attach(screen)
        assertNull(session.reserve(real))
        assertEquals("phone", session.plugins.selectedId)
        assertEquals(1, opens)
        session.disconnect()
        assertEquals(1, closed)
        assertEquals(1, stops)
        event(KeyEvent(0x01a1, true))
        driverFailed(IllegalStateException("Late error"))
        val nextTicket = session.reserve(real)!!
        main.idle()
        assertEquals(nextTicket, session.request)
        assertEquals("phone", session.plugins.selectedId)
    }

    @Test fun backgroundPhoneKeyOffersExactSingleTapTargetAndRejectsStaleTaps() {
        val real = device("test-keypad")
        shadowOf(usb).addOrUpdateUsbDevice(real, true)
        val ticket = app.session.reserve(real)!!
        val service = Robolectric.buildService(KeypadService::class.java).create()
        service.get().onStartCommand(KeypadService.command(app, ticket), 0, 1)
        val manager = shadowOf(app.getSystemService(NotificationManager::class.java))
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            fun press(key: Int) {
                app.emit(KeyEvent(key, true))
                app.emit(KeyEvent(key, false))
                main.idle()
            }
            fun shortcut(): Intent = shadowOf(manager.getNotification(PHONE_NOTICE).contentIntent).savedIntent
            fun tap(intent: Intent): Intent? =
                Robolectric.buildActivity(PhoneActionActivity::class.java, intent).setup().use {
                    shadowOf(it.get()).nextStartedActivity
                }
            press(3) // Open Phone from the startup launcher.
            press(1)
            assertEquals("android.media.action.STILL_IMAGE_CAMERA", shadowOf(controller.get()).nextStartedActivity?.action)
            controller.pause().stop()
            press(1)
            assertNull(shadowOf(app).nextStartedActivity)
            val camera = shortcut()
            assertEquals(PhoneActionActivity::class.java.name, camera.component?.className)
            assertEquals("Open Camera", manager.getNotification(PHONE_NOTICE).extras.getString(Notification.EXTRA_TITLE))
            press(3)
            val dialer = shortcut()
            assertNull(tap(camera))
            val target = tap(dialer)
            assertEquals(Intent.ACTION_DIAL, target?.action)
            assertEquals("tel:", target?.dataString)
            assertNull(tap(dialer))
            press(1)
            val missing = shortcut()
            shadowOf(app).checkActivities(true)
            try {
                assertNull(tap(missing))
                assertEquals("No app can handle CAMERA.", app.session.status)
            } finally {
                shadowOf(app).checkActivities(false)
            }
            // A system grant alone is not consent to the feature. Revocation takes effect at the next press.
            ShadowSettings.setCanDrawOverlays(true)
            press(1)
            assertNull(shadowOf(app).nextStartedActivity)
            DirectShortcuts(app).enabled = true
            press(3)
            val direct = shadowOf(app).nextStartedActivity
            assertEquals(Intent.ACTION_DIAL, direct?.action)
            assertEquals("tel:", direct?.dataString)
            shadowOf(app).checkActivities(true)
            try {
                press(1) // No camera handler installed: Android explicitly rejects the start.
                assertNull(shadowOf(app).nextStartedActivity)
                assertEquals(app.getString(R.string.phone_action_failed),
                    manager.getNotification(PHONE_NOTICE).extras.getString(Notification.EXTRA_TEXT))
                assertTrue(app.session.status.contains("No app can handle CAMERA."))
            } finally {
                shadowOf(app).checkActivities(false)
            }
            app.emit(KeyEvent(1, true))
            app.emit(KeyEvent(1, false))
            SystemClock.sleep(2_001) // Backlogged input must not interrupt an unrelated Activity.
            main.idle()
            assertNull(shadowOf(app).nextStartedActivity)
            ShadowSettings.setCanDrawOverlays(false)
            press(1)
            assertNull(shadowOf(app).nextStartedActivity)
            val locked = shortcut()
            val lock = shadowOf(app.getSystemService(KeyguardManager::class.java))
            lock.setKeyguardLocked(true)
            ShadowSettings.setCanDrawOverlays(true)
            press(1)
            assertNull(shadowOf(app).nextStartedActivity)
            assertNull(tap(locked))
            assertNull(tap(shortcut()))
            lock.setKeyguardLocked(false)
            DirectShortcuts(app).enabled = false
            press(1)
            val expired = shortcut()
            SystemClock.sleep(PocketSession.PHONE_ACTION_TTL + 1)
            assertNull(tap(expired))
            press(1)
            val revoked = shortcut()
            shadowOf(usb).revokePermission(real, app.packageName)
            assertNull(tap(revoked))
            shadowOf(usb).addOrUpdateUsbDevice(real, true)
            press(1)
            val disconnected = shortcut()
            app.session.disconnect()
            assertNull(manager.getNotification(PHONE_NOTICE))
            assertNull(tap(disconnected))
            controller.start().resume()
        }
        service.destroy()
    }

    @Config(shadows = [CameraWindowFixture::class])
    @Test fun onlyAnAcknowledgedFreshPhysicalStudioPressCanClickTheOptedInCamera() {
        installTestCamera(app)
        val service = Robolectric.buildService(NavigationService::class.java).create()
        val navigation = service.get()
        ReflectionHelpers.callInstanceMethod<Unit>(navigation, "onServiceConnected")
        val window = shadowOf(navigation) as CameraWindowFixture
        var clicks = 0
        val button = cameraNode("Take picture")
        shadowOf(button).setOnPerformActionListener { _, _ -> clicks++; true }
        window.root = button
        val real = device("test-keypad")
        shadowOf(usb).addOrUpdateUsbDevice(real, true)
        val session = app.session
        val ticket = session.reserve(real)!!
        val keypad = Robolectric.buildService(KeypadService::class.java).create()
        keypad.get().onStartCommand(KeypadService.command(app, ticket), 0, 1)
        try {
            session.selectProfile("studio")
            main.idle()
            press(1)
            assertEquals(0, clicks)
            CameraControlsAccess(app).enabled = true
            session.selectProfile("phone")
            session.selectProfile("studio")
            press(1) // Arrived with an invalidated receipt.
            assertEquals(0, clicks)
            press(1)
            assertEquals(1, clicks)
            assertTrue(session.status.contains("Shutter requested"))
            press(2) // Photo-mode control must not start video.
            assertNull(shadowOf(app).nextStartedActivity)
            val lock = shadowOf(app.getSystemService(KeyguardManager::class.java))
            lock.setKeyguardLocked(true); press(1); lock.setKeyguardLocked(false)
            val power = shadowOf(app.getSystemService(android.os.PowerManager::class.java))
            power.setIsInteractive(false); press(1); power.setIsInteractive(true)
            app.emit(KeyEvent(1, true)); app.emit(KeyEvent(1, false))
            SystemClock.sleep(2_001); main.idle()
            window.root = cameraNode("Take picture", "sample.other")
            press(1)
            window.root = button
            assertFalse(session.runDeckAction(DeckCommand.SHUTTER.id, fromPhysicalKey = true).success)
            val custom = session.deck.createProfile("My camera").getOrThrow()
            session.deck.setKey(custom, "root", 0, DeckKey(action = ActionCall("plugin.key",
                mapOf("pluginId" to "studio", "index" to "0")))).getOrThrow()
            session.selectProfile(custom); main.idle()
            assertFalse(session.frame().keys.first().enabled)
            press(1)
            session.selectProfile("studio"); main.idle()
            shadowOf(usb).revokePermission(real, app.packageName); press(1)
            session.disconnect(); press(1)
            assertEquals(1, clicks)
            assertNull(shadowOf(app).nextStartedActivity)
        } finally { session.disconnect(); keypad.destroy(); service.destroy() }
    }

    @Test fun studioRequestsOnlyTheViewfinderOrFallsBackToNotificationWithoutDirectAccess() {
        val native = installTestCamera(app)
        val real = device("test-keypad")
        shadowOf(usb).addOrUpdateUsbDevice(real, true)
        val ticket = app.session.reserve(real)!!
        val service = Robolectric.buildService(KeypadService::class.java).create()
        service.get().onStartCommand(KeypadService.command(app, ticket), 0, 1)
        val manager = shadowOf(app.getSystemService(NotificationManager::class.java))
        app.session.selectProfile("studio")
        main.idle()
        fun press(key: Int) {
            app.emit(KeyEvent(key, true)); app.emit(KeyEvent(key, false)); main.idle()
        }
        ShadowSettings.setCanDrawOverlays(true)
        DirectShortcuts(app).enabled = true
        press(2) // Record without a camera-controls grant.
        assertNull(manager.getNotification(PHONE_NOTICE))
        assertNull(shadowOf(app).nextStartedActivity)
        assertFalse(app.session.runDeckAction(DeckCommand.STUDIO.id).success)
        press(8)
        val viewfinder = shadowOf(app).nextStartedActivity
        assertEquals(native, viewfinder?.component)
        assertEquals(CameraMode.PHOTO.action, viewfinder.action)
        assertNull(viewfinder.extras)
        assertEquals(app.getString(R.string.phone_action_retry),
            manager.getNotification(PHONE_NOTICE).extras.getString(Notification.EXTRA_TEXT))
        ShadowSettings.setCanDrawOverlays(false)
        press(7)
        assertNull(shadowOf(app).nextStartedActivity)
        assertEquals(app.getString(R.string.phone_action_note),
            manager.getNotification(PHONE_NOTICE).extras.getString(Notification.EXTRA_TEXT))
        val open = shadowOf(manager.getNotification(PHONE_NOTICE).contentIntent).savedIntent
        Robolectric.buildActivity(PhoneActionActivity::class.java, open).setup().use {
            val target = shadowOf(it.get()).nextStartedActivity
            assertEquals(native, target.component)
            assertEquals(CameraMode.VIDEO.action, target.action)
            assertNull(target.extras) // No capture action can be embedded in this handoff.
        }
        press(8)
        val stale = shadowOf(manager.getNotification(PHONE_NOTICE).contentIntent).savedIntent
        shadowOf(usb).revokePermission(real, app.packageName)
        Robolectric.buildActivity(PhoneActionActivity::class.java, stale).setup().use {
            assertNull(shadowOf(it.get()).nextStartedActivity)
        }
        app.session.disconnect()
        service.destroy()
    }

    @Test fun customCameraBindingRequestsTheViewfinderButCannotCarryPhysicalAuthorityIntoAWorkflow() {
        val real = device("test-keypad")
        shadowOf(usb).addOrUpdateUsbDevice(real, true)
        val session = app.session
        val profile = session.deck.createProfile("My controls").getOrThrow()
        val camera = ActionCall("phone.open", mapOf("app" to "CAMERA"))
        session.deck.setKey(profile,"root",0,DeckKey(action = camera)).getOrThrow()
        session.selectProfile(profile)
        val ticket = session.reserve(real)!!
        val service = Robolectric.buildService(KeypadService::class.java).create()
        service.get().onStartCommand(KeypadService.command(app,ticket),0,1)
        main.idle()
        val manager = shadowOf(app.getSystemService(NotificationManager::class.java))
        ShadowSettings.setCanDrawOverlays(true)
        DirectShortcuts(app).enabled = true
        assertFalse(kotlinx.coroutines.runBlocking { session.actions.dispatch(camera) }.success)
        assertNull(shadowOf(app).nextStartedActivity)
        assertNull(manager.getNotification(PHONE_NOTICE))
        fun press() { app.emit(KeyEvent(1,true)); app.emit(KeyEvent(1,false)); main.idle() }
        Robolectric.buildActivity(MainActivity::class.java).setup().use {
            press()
            assertEquals(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA, shadowOf(app).nextStartedActivity?.action)
            assertNull(manager.getNotification(PHONE_NOTICE))
            app.emit(KeyEvent(1,true)); app.emit(KeyEvent(1,false))
            SystemClock.sleep(2_001)
            main.idle()
            assertNull(shadowOf(app).nextStartedActivity)
        }
        press()
        assertEquals(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA,shadowOf(app).nextStartedActivity?.action)
        assertEquals(app.getString(R.string.phone_action_retry),manager.getNotification(PHONE_NOTICE).extras.getString(Notification.EXTRA_TEXT))
        DirectShortcuts(app).enabled = false
        press()
        assertNull(shadowOf(app).nextStartedActivity)
        assertEquals(app.getString(R.string.phone_action_note),manager.getNotification(PHONE_NOTICE).extras.getString(Notification.EXTRA_TEXT))
        val stale = shadowOf(manager.getNotification(PHONE_NOTICE).contentIntent).savedIntent
        session.deck.setKey(profile,"root",0,DeckKey()).getOrThrow()
        main.idle()
        Robolectric.buildActivity(PhoneActionActivity::class.java,stale).setup().use {
            assertNull(shadowOf(it.get()).nextStartedActivity)
        }
        session.disconnect()
        service.destroy()
    }

    @Test fun eachFreshLauncherPressRequestsItsSavedAppAfterTheHostStops() {
        val session = app.session
        val first = ComponentName("sample.notes", "sample.notes.Entry")
        val second = ComponentName("sample.reader", "sample.reader.Entry")
        val favorites = listOf(first, second).map {
            registerLauncher(it)
            session.favoriteApps.setSelected(it.flattenToString(), true)
            Favorite(it.flattenToString(), it.className)
        }
        val real = device("test-keypad")
        shadowOf(usb).addOrUpdateUsbDevice(real, true)
        session.selectProfile("launcher")
        val ticket = session.reserve(real)!!
        val service = Robolectric.buildService(KeypadService::class.java).create()
        service.get().onStartCommand(KeypadService.command(app, ticket), 0, 1)
        ShadowSettings.setCanDrawOverlays(true)
        DirectShortcuts(app).enabled = true
        val manager = shadowOf(app.getSystemService(NotificationManager::class.java))
        fun press(key: Int) {
            app.emit(KeyEvent(key, true)); app.emit(KeyEvent(key, false)); main.idle()
        }
        fun assertLauncher(name: ComponentName, intent: Intent?) {
            assertEquals(name, intent?.component)
            assertEquals(Intent.ACTION_MAIN, requireNotNull(intent).action)
            assertEquals(setOf(Intent.CATEGORY_LAUNCHER), intent.categories)
            assertNull(intent.data)
            assertNull(intent.extras)
        }
        Robolectric.buildActivity(MainActivity::class.java).setup().use { host ->
            session.publishFavorites(favorites)
            main.idle()
            press(1)
            assertLauncher(first, shadowOf(host.get()).nextStartedActivity)
            assertNull(manager.getNotification(PHONE_NOTICE))
            host.pause().stop()

            val retries = listOf(1 to first, 2 to second).map { (key, name) ->
                press(key)
                val launched = shadowOf(app).nextStartedActivity
                assertLauncher(name, launched)
                assertTrue(launched.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
                val note = manager.getNotification(PHONE_NOTICE)
                assertEquals(app.getString(R.string.phone_action_retry), note.extras.getString(Notification.EXTRA_TEXT))
                assertEquals(Notification.VISIBILITY_PRIVATE, note.visibility)
                shadowOf(note.contentIntent).savedIntent
            }
            fun tap(intent: Intent) = Robolectric.buildActivity(PhoneActionActivity::class.java, intent).setup().use {
                shadowOf(it.get()).nextStartedActivity
            }
            assertNull(tap(retries.first()))
            assertLauncher(second, tap(retries.last()))
            assertNull(tap(retries.last()))

            val call = ActionCall("launcher.open", mapOf("component" to first.flattenToString()))
            assertFalse(kotlinx.coroutines.runBlocking { session.actions.dispatch(call) }.success)
            app.emit(KeyEvent(1, true, synthetic = true)); app.emit(KeyEvent(1, false, synthetic = true))
            main.idle()
            app.emit(KeyEvent(1, true)); app.emit(KeyEvent(1, false))
            SystemClock.sleep(2_001)
            main.idle()
            assertNull(shadowOf(app).nextStartedActivity)
            assertNull(manager.getNotification(PHONE_NOTICE))
            host.start().resume()
        }
        session.disconnect()
        service.destroy()
    }

    private fun handler(name: ComponentName) = ResolveInfo().apply {
        isDefault = true
        activityInfo = ActivityInfo().apply {
            packageName = name.packageName; this.name = name.className; exported = true; enabled = true
            applicationInfo = ApplicationInfo().apply { packageName = name.packageName }
        }
        nonLocalizedLabel = name.className
    }

    @Suppress("DEPRECATION")
    private fun registerLauncher(name: ComponentName) {
        val pm = shadowOf(app.packageManager)
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.addResolveInfoForIntent(launcher, handler(name))
        pm.addResolveInfoForIntent(Intent(launcher).setPackage(name.packageName), handler(name))
    }

    @Suppress("DEPRECATION")
    @Test fun onlyTheDefaultCameraFavoriteCanRequestADirectViewfinder() {
        val pm = shadowOf(app.packageManager)
        val session = app.session
        val camera = ComponentName("sample.camera","sample.camera.Entry")
        val viewfinder = ComponentName(camera.packageName,"sample.camera.Viewfinder")
        val other = ComponentName("sample.other","sample.other.Camera")
        val profile = session.deck.createProfile("Camera controls").getOrThrow()
        listOf(camera,other).forEachIndexed { index, name ->
            registerLauncher(name)
            session.favoriteApps.setSelected(name.flattenToString(),true)
            session.deck.setKey(profile,"root",index,DeckKey(label = "Camera",
                action = ActionCall("launcher.open",mapOf("component" to name.flattenToString())))).getOrThrow()
        }
        pm.addResolveInfoForIntent(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),handler(viewfinder))
        val real = device("test-keypad")
        shadowOf(usb).addOrUpdateUsbDevice(real,true)
        session.selectProfile(profile)
        val ticket = session.reserve(real)!!
        val service = Robolectric.buildService(KeypadService::class.java).create()
        service.get().onStartCommand(KeypadService.command(app,ticket),0,1)
        session.publishFavorites(listOf(camera,other).map { Favorite(it.flattenToString(),"Camera") })
        main.idle()
        ShadowSettings.setCanDrawOverlays(true)
        // A prior opt-in whose disclosure excluded ordinary favorites cannot authorize them on upgrade.
        app.getSharedPreferences("phone-shortcuts", 0).edit().putBoolean("direct", true).apply()
        val manager = shadowOf(app.getSystemService(NotificationManager::class.java))
        fun press(key: Int) { app.emit(KeyEvent(key,true)); app.emit(KeyEvent(key,false)); main.idle() }
        press(2)
        assertNull(shadowOf(app).nextStartedActivity)
        assertEquals(app.getString(R.string.phone_action_note), manager.getNotification(PHONE_NOTICE).extras.getString(Notification.EXTRA_TEXT))
        DirectShortcuts(app).enabled = true
        press(1)
        val opened = shadowOf(app).nextStartedActivity
        assertEquals(viewfinder,opened?.component)
        assertEquals(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA,opened.action)
        assertNull(opened.extras)
        val cameraTap = shadowOf(manager.getNotification(PHONE_NOTICE).contentIntent).savedIntent
        Robolectric.buildActivity(PhoneActionActivity::class.java, cameraTap).setup().use {
            val retry = shadowOf(it.get()).nextStartedActivity
            assertEquals(viewfinder, retry?.component)
            assertEquals(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA, retry.action)
        }
        press(2)
        val ordinary = shadowOf(app).nextStartedActivity
        assertEquals(other, ordinary?.component)
        assertEquals(Intent.ACTION_MAIN, ordinary.action)
        assertEquals(app.getString(R.string.phone_action_retry), manager.getNotification(PHONE_NOTICE).extras.getString(Notification.EXTRA_TEXT))

        ShadowSettings.setCanDrawOverlays(false)
        press(2)
        assertNull(shadowOf(app).nextStartedActivity)
        assertEquals(app.getString(R.string.phone_action_note),manager.getNotification(PHONE_NOTICE).extras.getString(Notification.EXTRA_TEXT))
        val tap = shadowOf(manager.getNotification(PHONE_NOTICE).contentIntent).savedIntent
        session.favoriteApps.setSelected(other.flattenToString(), false)
        Robolectric.buildActivity(PhoneActionActivity::class.java, tap).setup().use {
            assertNull(shadowOf(it.get()).nextStartedActivity)
        }
        ShadowSettings.setCanDrawOverlays(true)
        press(2)
        assertNull(shadowOf(app).nextStartedActivity)
        assertNull(manager.getNotification(PHONE_NOTICE))
        session.disconnect()
        service.destroy()
    }
}
