package tel.misa.mxpocket

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import tel.misa.mxpocket.actions.ActionCall
import tel.misa.mxpocket.deck.DeckKey
import tel.misa.mxpocket.decks.*
import tel.misa.mxpocket.driver.KeyEvent
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = KeypadSessionTest.FakeApp::class, shadows = [CameraWindowFixture::class])
@LooperMode(LooperMode.Mode.PAUSED)
class TeamsCallTest {
    private val app get() = RuntimeEnvironment.getApplication() as KeypadSessionTest.FakeApp
    private val main get() = shadowOf(Looper.getMainLooper())
    private fun note(pkg: String = TEAMS_PACKAGE, type: Int = Notification.CallStyle.CALL_TYPE_ONGOING,
        styled: Boolean = true, user: UserHandle = Process.myUserHandle(), id: Int = 1,
        poster: String = pkg, kind: String = Notification.CATEGORY_CALL) =
        StatusBarNotification(pkg, poster, id, null, 10000, 0, 0, Notification().apply {
            category = kind
            if (styled) extras.putString(Notification.EXTRA_TEMPLATE, Notification.CallStyle::class.java.name)
            extras.putInt(Notification.EXTRA_CALL_TYPE, type)
        }, user, 1L)

    @Test fun androidMayDeliverCallsInAnyNotificationRankingClass() {
        // NotificationManager filters snapshots as well as callbacks, before teamsObservation runs.
        val info = app.packageManager.getServiceInfo(ComponentName(app, TeamsCallListener::class.java), PackageManager.GET_META_DATA)
        assertFalse(info.metaData?.containsKey(NotificationListenerService.META_DATA_DEFAULT_FILTER_TYPES) == true)
        assertFalse(info.metaData?.containsKey(NotificationListenerService.META_DATA_DISABLED_FILTER_TYPES) == true)
    }

    @Test fun onlyOneLocalTypedOngoingTeamsCallIsEligible() {
        for (rejected in listOf(note("com.android.dialer"), note("other.voip"), note(poster = "delegate"),
            note(user = UserHandle.getUserHandleForUid(1_010_000)), note(styled = false, kind = Notification.CATEGORY_MESSAGE))) {
            assertSame(TeamsObservation.None, teamsObservation(arrayOf(rejected)))
        }
        assertSame(TeamsObservation.Untyped, teamsObservation(arrayOf(note(styled = false))))
        assertSame(TeamsObservation.Incoming, teamsObservation(arrayOf(note(type = Notification.CallStyle.CALL_TYPE_INCOMING))))
        assertSame(TeamsObservation.Screening, teamsObservation(arrayOf(note(type = Notification.CallStyle.CALL_TYPE_SCREENING))))
        assertSame(TeamsObservation.Unavailable, teamsObservation(arrayOf(note(), note(id = 2, styled = false))))
        assertSame(TeamsObservation.None, teamsObservation(emptyArray()))
        assertSame(TeamsObservation.Unavailable, teamsObservation(arrayOf(note(), note(id = 2))))
        assertTrue(teamsObservation(arrayOf(note("com.android.dialer"), note())) is TeamsObservation.Ongoing)
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun typedEventAndJoinedToolbarDrivePhysicalPageMicAndRestore() {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val handler = ResolveInfo().apply { activityInfo = ActivityInfo().apply {
            packageName = TEAMS_PACKAGE; name = "$TEAMS_PACKAGE.TestLauncher"; enabled = true; exported = true
            applicationInfo = ApplicationInfo().apply { packageName = TEAMS_PACKAGE; enabled = true }
        } }
        shadowOf(app.packageManager).addResolveInfoForIntent(launcher, handler)
        shadowOf(app.packageManager).addResolveInfoForIntent(Intent(launcher).setPackage(TEAMS_PACKAGE), handler)
        assertEquals(TEAMS_PACKAGE, app.teamsLauncher()?.component?.packageName)
        val listenerController = Robolectric.buildService(TeamsCallListener::class.java).create()
        val serviceController = Robolectric.buildService(TeamsToolbarService::class.java).create()
        val listener = listenerController.get()
        listener.onListenerConnected()
        val service = serviceController.get()
        ReflectionHelpers.callInstanceMethod<Unit>(service, "onServiceConnected")
        val info = android.accessibilityservice.AccessibilityServiceInfo().also {
            ReflectionHelpers.setField(it, "mResolveInfo", ResolveInfo().apply {
                serviceInfo = android.content.pm.ServiceInfo().apply {
                    packageName = app.packageName; name = TeamsToolbarService::class.java.name
                }
            })
        }
        shadowOf(app.getSystemService(android.view.accessibility.AccessibilityManager::class.java))
            .setEnabledAccessibilityServiceList(listOf(info))
        val window = shadowOf(service) as CameraWindowFixture
        val mic = cameraNode("Mic", TEAMS_PACKAGE).apply { stateDescription = "unmuted" }
        val leave = cameraNode("Hang up", TEAMS_PACKAGE)
        var clicks = 0
        shadowOf(mic).setOnPerformActionListener { _, _ -> clicks++; true }
        shadowOf(leave).setOnPerformActionListener { _, _ -> fail("Must never hang up"); false }
        val root = cameraNode(pkg = TEAMS_PACKAGE)
        shadowOf(root).addChild(mic)
        window.root = root
        val usb = app.getSystemService(UsbManager::class.java)
        val device = ReflectionHelpers.newInstance(UsbDevice::class.java).also {
            ReflectionHelpers.setField(it, "mName", "test-keypad")
            ReflectionHelpers.setField(it, "mVendorId", 0x046d)
            ReflectionHelpers.setField(it, "mProductId", 0xc354)
            ReflectionHelpers.setField(it, "mConfigurations", emptyArray<UsbConfiguration>())
        }
        shadowOf(usb).addOrUpdateUsbDevice(device, true)
        val session = app.session
        val notices = shadowOf(app.getSystemService(NotificationManager::class.java))
        notices.setNotificationListenerAccessGranted(ComponentName(app, MediaSessionAccess::class.java), true)
        val teams = ComponentName(app, TeamsCallListener::class.java)
        val call = note()
        fun signal() { listener.onNotificationPosted(call); main.idle() }
        fun center() { app.emit(KeyEvent(5, true)); app.emit(KeyEvent(5, false)); main.idle() }
        val deadline = Duration.ofMillis(android.view.ViewConfiguration.getLongPressTimeout().toLong() + 1)
        val probe = DeckKey(action = ActionCall(DeckCommand.LOUDER.id), holdAction = ActionCall(DeckCommand.QUIETER.id))
        val audio = app.getSystemService(AudioManager::class.java)
        fun volume() = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        fun probeEdge(down: Boolean) { app.emit(KeyEvent(1, down)); main.idle() }
        session.connect(session.reserve(device)!!, usb, device, {}, { error("Unexpected launch") })
        try {
            // Let the IO dispatcher finish the ordinary package-discovery pass.
            for (attempt in 1..100) {
                main.idle()
                if (session.deckState.value.teams.installed) break
                Thread.sleep(10)
            }
            assertTrue("Teams launcher discovered", session.deckState.value.teams.installed)
            session.selectProfile(TEAMS)
            session.deck.openPage(IN_CALL).getOrThrow()
            main.idle()
            fun bitmap(status: CallStatus) = session.frame().keys[7].let { tile ->
                assertEquals(status, session.deckState.value.teams.status)
                assertEquals(status.label, tile.label)
                assertEquals(status.footer, tile.footer)
                assertTrue(tile.readOnly)
                session.keyImage(7, tile).also { image ->
                    for (rows in listOf(25..71, 88..112)) {
                        val ink = rows.sumOf { y -> (5..112).count { x ->
                            android.graphics.Color.red(image.getPixel(x, y)) > 160
                        } }
                        assertTrue("Missing readout ink in $rows: $ink", ink > 40)
                    }
                }
            }
            bitmap(CallStatus.NEEDS_ACCESS) // the Audio grant cannot supply Teams evidence
            notices.setNotificationListenerAccessGranted(teams, true)
            signal()
            val noSignal = bitmap(CallStatus.NO_SIGNAL)
            TeamsAccess(app).enabled = false
            shadowOf(listener).addActiveNotification(call)
            signal()
            bitmap(CallStatus.OFF) // both Android grants are held, but the saved opt-out wins
            TeamsAccess(app).enabled = true
            signal()
            val partial = bitmap(CallStatus.NOTIFICATION)
            assertFalse("A typed ongoing notification with no recognized joined toolbar must change the readout", partial.sameAs(noSignal))
            val slot = session.deck.state.value.activePage.keys[7]
            session.deck.setKey(TEAMS, IN_CALL, 7, DeckKey(label = "Meeting", action = ActionCall(DeckCommand.TEAMS_STATUS.id))).getOrThrow()
            main.idle()
            session.frame().keys[7].also {
                assertTrue(it.readOnly)
                assertEquals("Meeting", it.label)
                assertEquals("Call notice", it.footer)
            }
            session.deck.setKey(TEAMS, IN_CALL, 7, slot).getOrThrow()
            main.idle()
            val accessManager = shadowOf(app.getSystemService(android.view.accessibility.AccessibilityManager::class.java))
            accessManager.setEnabledAccessibilityServiceList(emptyList()); signal()
            bitmap(CallStatus.TOOLBAR_OFF)
            accessManager.setEnabledAccessibilityServiceList(listOf(info)); signal()
            session.selectProfile("focus")
            signal()
            assertEquals("focus", session.deck.state.value.activeProfileId) // prejoin mic only
            assertFalse(session.deckState.value.teams.joined)
            session.deck.setKey("focus", "root", 0, probe).getOrThrow()
            session.deck.setKey("teams", "in-call", 0, probe).getOrThrow()
            main.idle()
            // A real dual binding with distinguishable effects, not a disabled/no-op probe.
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, 3, 0)
            probeEdge(true); probeEdge(false)
            assertEquals(4, volume())
            probeEdge(true); main.idleFor(deadline); probeEdge(false)
            assertEquals(3, volume())
            probeEdge(true)
            shadowOf(root).addChild(leave)
            mic.isClickable = false // join evidence, but not a mic action
            service.onAccessibilityEvent(null); main.idle()
            assertEquals("in-call", session.deck.state.value.activePage.id)
            assertFalse(partial.sameAs(bitmap(CallStatus.JOINED)))
            main.idleFor(deadline); probeEdge(false)
            assertEquals(3, volume()) // joining cancels both choices on the old page
            assertFalse(session.frame().keys[4].enabled)
            center()
            app.emit(KeyEvent(8, true)); app.emit(KeyEvent(8, false)); main.idle()
            assertEquals(0, clicks)
            mic.isClickable = true
            service.onAccessibilityEvent(null); main.idle()
            assertEquals("Mute mic", session.frame().keys[4].label)
            assertEquals(0, clicks)
            assertFalse(session.runDeckAction("teams.mic").success) // no physical press
            center()
            assertEquals(1, clicks)
            assertFalse(session.frame().keys[4].enabled) // no optimistic state or repeated toggle
            center()
            assertEquals(1, clicks)
            mic.stateDescription = "muted"
            service.onAccessibilityEvent(null); main.idle()
            assertEquals("Unmute mic", session.frame().keys[4].label)
            for (long in listOf(false, true)) {
                val token = session.frame().keys[4].inputToken
                probeEdge(true)
                mic.stateDescription = if (long) "muted" else "unmuted"
                service.onAccessibilityEvent(null); main.idle()
                assertNotEquals(token, session.frame().keys[4].inputToken)
                if (long) main.idleFor(deadline)
                probeEdge(false)
                assertEquals(3, volume()) // same page, other key: even a queued tap is invalid now
            }
            val defaultMic = session.deck.state.value.activePage.keys[4]
            session.deck.setKey("teams", "in-call", 4, defaultMic.copy(action = probe.action, holdAction = defaultMic.action)).getOrThrow()
            main.idle()
            assertFalse(session.frame(hold = true).keys[4].enabled)
            app.emit(KeyEvent(5, true)); main.idle(); main.idleFor(deadline)
            app.emit(KeyEvent(5, false)); main.idle()
            assertEquals(1, clicks)
            assertEquals(3, volume())
            session.deck.setKey("teams", "in-call", 4, defaultMic).getOrThrow(); main.idle()
            val keyguard = shadowOf(app.getSystemService(android.app.KeyguardManager::class.java))
            keyguard.setKeyguardLocked(true)
            signal()
            assertFalse(session.deckState.value.teams.joined)
            assertEquals("focus", session.deck.state.value.activeProfileId)
            keyguard.setKeyguardLocked(false)
            signal()
            assertEquals("in-call", session.deck.state.value.activePage.id)
            window.root = null
            service.onAccessibilityEvent(null); main.idle()
            assertEquals("in-call", session.deck.state.value.activePage.id)
            assertFalse(session.frame().keys[4].enabled)
            window.root = root
            signal()
            app.emit(KeyEvent(5, true)) // buffered against an older call/frame
            listener.cancelAllNotifications()
            listener.onNotificationRemoved(call)
            main.idle()
            assertEquals(1, clicks)
            assertEquals("focus", session.deck.state.value.activeProfileId)
            shadowOf(listener).addActiveNotification(call); signal()
            session.selectProfile("phone")
            signal()
            notices.setNotificationListenerAccessGranted(teams, false)
            signal()
            assertFalse(session.deckState.value.teams.joined)
            notices.setNotificationListenerAccessGranted(teams, true)
            signal()
            assertEquals("phone", session.deck.state.value.activeProfileId)
            val profiles = shadowOf(app.getSystemService(UserManager::class.java))
            profiles.addProfile(0, 10, "Test profile", org.robolectric.shadows.ShadowUserManager.FLAG_MANAGED_PROFILE)
            assertFalse(TeamsAccess(app).singleProfile)
            val reads = window.rootReads
            signal()
            assertEquals(CallStatus.PROFILE_NOTICE, session.deckState.value.teams.status)
            assertNull(session.deckState.value.teams.muted)
            assertEquals(reads, window.rootReads)
            assertEquals("phone", session.deck.state.value.activeProfileId)
            session.selectProfile(TEAMS)
            session.deck.openPage(IN_CALL).getOrThrow(); main.idle()
            bitmap(CallStatus.PROFILE_NOTICE)
            center()
            assertEquals(1, clicks)
            assertEquals(reads, window.rootReads)
            session.selectProfile("phone")
            listener.cancelAllNotifications()
            shadowOf(listener).addActiveNotification(note(user = UserHandle.getUserHandleForUid(1_010_000))); signal()
            assertEquals(CallStatus.NO_SIGNAL, session.deckState.value.teams.status)
            listener.onListenerDisconnected(); main.idle()
            assertFalse(session.deckState.value.teams.joined)
            assertEquals("phone", session.deck.state.value.activeProfileId)
        } finally {
            session.disconnect()
            serviceController.destroy(); listenerController.destroy()
        }
    }
}
