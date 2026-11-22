package tel.misa.mxpocket

import android.app.Notification
import android.app.NotificationManager
import android.app.Person
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Icon
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
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
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowUserManager
import org.robolectric.shadows.ShadowSubscriptionManager.SubscriptionInfoBuilder
import org.robolectric.util.ReflectionHelpers
import tel.misa.mxpocket.actions.ActionCall
import tel.misa.mxpocket.deck.DeckKey
import tel.misa.mxpocket.deck.TEAMS_INCOMING_VIEW
import tel.misa.mxpocket.deck.PHONE_CALL_VIEW
import tel.misa.mxpocket.deck.PROFILE_HOME
import tel.misa.mxpocket.plugins.CALL_IDENTITY
import tel.misa.mxpocket.plugins.CALL_PHOTO
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
        val info = app.packageManager.getServiceInfo(NotificationAccess(app).component, PackageManager.GET_META_DATA)
        assertFalse(info.metaData?.containsKey(NotificationListenerService.META_DATA_DEFAULT_FILTER_TYPES) == true)
        assertFalse(info.metaData?.containsKey(NotificationListenerService.META_DATA_DISABLED_FILTER_TYPES) == true)
    }

    @Test fun onlyOneLocalTeamsCallNoticeIsEligible() {
        for (rejected in listOf(note("com.android.dialer"), note("other.voip"), note(poster = "delegate"),
            note(user = UserHandle.getUserHandleForUid(1_010_000)), note(styled = false, kind = Notification.CATEGORY_MESSAGE))) {
            assertSame(TeamsObservation.None, teamsObservation(arrayOf(rejected)))
        }
        assertFalse((teamsObservation(arrayOf(note(styled = false))) as TeamsObservation.Notice).typedOngoing)
        assertFalse((teamsObservation(arrayOf(note(type = 0))) as TeamsObservation.Notice).typedOngoing)
        assertSame(TeamsObservation.Unavailable, teamsObservation(arrayOf(note(type = 0, kind = Notification.CATEGORY_MESSAGE))))
        assertTrue(teamsObservation(arrayOf(note(type = Notification.CallStyle.CALL_TYPE_INCOMING))) is TeamsObservation.Incoming)
        assertSame(TeamsObservation.Screening, teamsObservation(arrayOf(note(type = Notification.CallStyle.CALL_TYPE_SCREENING))))
        assertSame(TeamsObservation.Unavailable, teamsObservation(arrayOf(note(), note(id = 2, styled = false))))
        assertSame(TeamsObservation.None, teamsObservation(emptyArray()))
        assertSame(TeamsObservation.Unavailable, teamsObservation(arrayOf(note(), note(id = 2))))
        assertTrue((teamsObservation(arrayOf(note("com.android.dialer"), note())) as TeamsObservation.Notice).typedOngoing)
    }

    @Implements(UserManager::class)
    class UnreadableProfile : ShadowUserManager() {
        @Implementation override fun isQuietModeEnabled(user: UserHandle): Boolean =
            throw SecurityException("Unavailable in this test")
    }

    @Test @Config(shadows = [UnreadableProfile::class])
    fun unreadableProfileIsNotTreatedAsPaused() {
        val access = TeamsAccess(app)
        assertTrue(access.toolbarProfileEligible)
        shadowOf(app.getSystemService(UserManager::class.java)).addProfile(0, 10, "Work", ShadowUserManager.FLAG_MANAGED_PROFILE)
        assertFalse(access.toolbarProfileEligible)
    }

    @Test fun everyOtherProfileMustBePaused() {
        val users = app.getSystemService(UserManager::class.java)
        val access = TeamsAccess(app)
        for (id in listOf(10, 11)) {
            shadowOf(users).addProfile(0, id, "Work", ShadowUserManager.FLAG_MANAGED_PROFILE)
            assertFalse(access.toolbarProfileEligible)
            assertTrue(users.requestQuietModeEnabled(true, UserHandle.getUserHandleForUid(id * 100_000)))
            assertTrue(access.toolbarProfileEligible)
        }
    }

    private fun addTeamsLauncher() {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val handler = ResolveInfo().apply { activityInfo = ActivityInfo().apply {
            packageName = TEAMS_PACKAGE; name = "$TEAMS_PACKAGE.TestLauncher"; enabled = true; exported = true
            applicationInfo = ApplicationInfo().apply { packageName = TEAMS_PACKAGE; enabled = true }
        } }
        shadowOf(app.packageManager).addResolveInfoForIntent(launcher, handler)
        shadowOf(app.packageManager).addResolveInfoForIntent(Intent(launcher).setPackage(TEAMS_PACKAGE), handler)
        assertEquals(TEAMS_PACKAGE, app.teamsLauncher()?.component?.packageName)
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun incomingNoticeAlertsOnceAndOnlyShowsConsentedCallStyleDetailsOnUsb() {
        addTeamsLauncher()
        val controller = Robolectric.buildService(PocketNotificationListener::class.java).create()
        val listener = controller.get()
        val access = TeamsAccess(app)
        val session = app.session
        val deck = session.deck
        val lock = shadowOf(app.getSystemService(android.app.KeyguardManager::class.java))
        val pixel = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.MAGENTA) }
        fun incoming(name: String? = null, icon: Icon? = null, styled: Boolean = true) =
            note(type = Notification.CallStyle.CALL_TYPE_INCOMING, styled = styled).also {
                it.notification.extras.putString(Notification.EXTRA_TITLE, "Not a caller field")
                it.notification.extras.putParcelable(Notification.EXTRA_CALL_PERSON,
                    Person.Builder().setName(name).setIcon(icon).build())
            }
        fun post(n: StatusBarNotification) {
            listener.cancelNotification(n.key)
            shadowOf(listener).addActiveNotification(n)
            listener.onNotificationPosted(n)
            main.idle()
        }
        fun remove(n: StatusBarNotification) {
            listener.cancelNotification(n.key)
            listener.onNotificationRemoved(n)
            main.idle()
        }
        fun refresh() { TeamsEvents.refresh?.invoke(); main.idle() }
        fun surface() = deck.operatingState.value.activeProfileId
        fun face(name: String, photo: Boolean) {
            assertEquals(TEAMS_INCOMING_VIEW, surface())
            val keys = session.frame().keys
            assertEquals(name, keys[CALL_IDENTITY].label)
            assertEquals(listOf(CALL_PHOTO, CALL_IDENTITY), keys.indices.filter { !app.lcd.getValue(it).sameAs(app.lcd.getValue(0)) })
            assertEquals(photo, app.lcd.getValue(CALL_PHOTO).getPixel(59, 59) == Color.MAGENTA)
            assertTrue(keys.all { it.readOnly && !it.enabled && it.inputToken == null })
            val home = session.homeTiles()
            assertEquals("Teams", home[CALL_IDENTITY].label)
            assertNotEquals(Color.MAGENTA, home[CALL_PHOTO].image!!.toPixelMap()[59, 59].toArgb())
        }
        val call = incoming("Fixture caller", Icon.createWithBitmap(pixel))
        app.enableMusicSessions()
        session.phoneAccess.details = true // Carrier consent is not Teams consent.
        listener.onListenerConnected()
        app.connectKeypad()
        try {
            for (attempt in 1..100) {
                main.idle()
                if (session.deckState.value.teams.installed) break
                Thread.sleep(10)
            }
            val before = deck.exportJson()
            post(call)
            assertEquals(PROFILE_HOME, surface())
            access.enabled = true; refresh()
            face("Teams", false)
            access.callerDetails = true; refresh()
            face("Fixture caller", true)
            post(incoming(null, Icon.createWithBitmap(pixel))); face("Teams", true)
            post(incoming("Name only", Icon.createWithContentUri("content://unused"))); face("Name only", false)
            post(incoming("Not CallStyle", Icon.createWithBitmap(pixel), styled = false)); face("Teams", false)
            post(call); face("Fixture caller", true)
            post(call.also { it.notification.visibility = Notification.VISIBILITY_SECRET }); face("Teams", false)
            post(call.also { it.notification.visibility = Notification.VISIBILITY_PRIVATE })
            access.callerDetails = false; refresh(); face("Teams", false)
            access.callerDetails = true; refresh()
            lock.setKeyguardLocked(true); refresh()
            assertEquals(PROFILE_HOME, surface())
            assertNotEquals(Color.MAGENTA, app.lcd.getValue(CALL_PHOTO).getPixel(59, 59))
            lock.setKeyguardLocked(false); refresh(); face("Fixture caller", true)
            app.press(0x01a2)
            repeat(3) { post(call); assertEquals(PROFILE_HOME, surface()) }
            remove(call)
            post(call); face("Fixture caller", true)
            shadowOf(app).grantPermissions(android.Manifest.permission.READ_PHONE_STATE)
            val tel = app.getSystemService(TelephonyManager::class.java)
            val sub = SubscriptionInfoBuilder.newBuilder().setId(10).setSimSlotIndex(0).buildSubscriptionInfo()
            shadowOf(app.getSystemService(SubscriptionManager::class.java)).setActiveSubscriptionInfos(sub)
            shadowOf(tel).setTelephonyManagerForSubscriptionId(10, tel)
            session.phoneAccess.enabled = true
            shadowOf(tel).setCallState(TelephonyManager.CALL_STATE_RINGING)
            session.refreshPhoneCall(); post(call)
            assertEquals(PHONE_CALL_VIEW, surface())
            assertTrue(session.frame().keys.none { it.label == "Fixture caller" })
            shadowOf(tel).setCallState(TelephonyManager.CALL_STATE_IDLE)
            session.refreshPhoneCall(); refresh(); face("Fixture caller", true)
            remove(call)
            assertEquals(PROFILE_HOME, surface())
            post(note(type = 0)); assertEquals(PROFILE_HOME, surface())
            post(note(type = Notification.CallStyle.CALL_TYPE_SCREENING)); assertEquals(PROFILE_HOME, surface())
            post(note(type = 0, id = 2)); post(call)
            assertEquals(CallStatus.UNAVAILABLE, session.deckState.value.teams.status)
            assertEquals(PROFILE_HOME, surface())
            assertEquals(before, deck.exportJson())
            assertFalse(session.logText().contains("Fixture caller"))
        } finally {
            session.disconnect()
            controller.destroy()
        }
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun typedEventAndJoinedToolbarDrivePhysicalPageMicAndRestore() {
        addTeamsLauncher()
        val listenerController = Robolectric.buildService(PocketNotificationListener::class.java).create()
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
        val users = app.getSystemService(UserManager::class.java)
        val work = UserHandle.getUserHandleForUid(1_010_000)
        shadowOf(users).addProfile(0, 10, "Work", ShadowUserManager.FLAG_MANAGED_PROFILE)
        assertTrue(users.requestQuietModeEnabled(true, work))
        assertTrue(work in users.userProfiles)
        val notices = shadowOf(app.getSystemService(NotificationManager::class.java))
        val shared = app.enableMusicSessions()
        val call = note()
        fun signal() { listener.onNotificationPosted(call); main.idle() }
        fun center() { app.emit(KeyEvent(5, true)); app.emit(KeyEvent(5, false)); main.idle() }
        val deadline = Duration.ofMillis(android.view.ViewConfiguration.getLongPressTimeout().toLong() + 1)
        val probe = DeckKey(action = ActionCall(DeckCommand.LOUDER.id), holdAction = ActionCall(DeckCommand.QUIETER.id))
        val audio = app.getSystemService(AudioManager::class.java)
        fun volume() = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        fun probeEdge(down: Boolean) { app.emit(KeyEvent(1, down)); main.idle() }
        session.selectProfile("focus")
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
            bitmap(CallStatus.OFF) // Music and a shared grant cannot supply Teams evidence.
            TeamsAccess(app).enabled = true
            TeamsEvents.refresh?.invoke()
            val noSignal = bitmap(CallStatus.NO_SIGNAL)
            TeamsAccess(app).enabled = false
            TeamsEvents.refresh?.invoke()
            shadowOf(listener).addActiveNotification(call)
            signal()
            bitmap(CallStatus.OFF)
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
            session.selectProfile("launcher")
            signal()
            notices.setNotificationListenerAccessGranted(shared, false)
            listener.onListenerDisconnected()
            assertFalse(session.deckState.value.teams.joined)
            notices.setNotificationListenerAccessGranted(shared, true)
            listener.onListenerConnected()
            signal()
            assertEquals("launcher", session.deck.state.value.activeProfileId)
            // Resume after the toolbar has been read but before the physical click. No
            // callback/refresh can run in between these two synchronous authorization checks.
            val controls = TeamsCall(app)
            val joined = controls.refresh(true, true, true, true)
            assertTrue(joined.joined)
            var checks = 0
            assertFalse(controls.pressMic(joined) {
                if (++checks == 2) assertTrue(users.requestQuietModeEnabled(false, work))
                true
            }.success)
            assertEquals(2, checks)
            assertEquals(1, clicks)
            window.root = null
            assertTrue(users.requestQuietModeEnabled(true, work))
            assertEquals(CallStatus.NOTIFICATION, controls.refresh(true, true, true, true).status)
            // A rapid resume/pause between polling ticks must also drop background
            // confirmation in the session, even though the same notice is still present.
            main.idle()
            assertEquals(CallStatus.NOTIFICATION, session.deckState.value.teams.status)
            assertNull(session.deckState.value.teams.muted)
            window.root = root
            signal()
            assertTrue(session.deckState.value.teams.joined)
            assertTrue(users.requestQuietModeEnabled(false, work))
            assertFalse(TeamsAccess(app).toolbarProfileEligible)
            val reads = window.rootReads
            main.idle()
            assertEquals(CallStatus.PROFILE_NOTICE, session.deckState.value.teams.status)
            assertNull(session.deckState.value.teams.muted)
            assertEquals(reads, window.rootReads)
            assertEquals("launcher", session.deck.state.value.activeProfileId)
            session.selectProfile(TEAMS)
            session.deck.openPage(IN_CALL).getOrThrow(); main.idle()
            bitmap(CallStatus.PROFILE_NOTICE)
            center()
            assertEquals(1, clicks)
            assertEquals(reads, window.rootReads)
            session.selectProfile("launcher")
            assertTrue(users.requestQuietModeEnabled(true, work))
            listener.cancelAllNotifications()
            shadowOf(listener).addActiveNotification(note(user = work))
            shadowOf(listener).addActiveNotification(note("other.voip")); signal()
            assertEquals(CallStatus.NO_SIGNAL, session.deckState.value.teams.status)
            val untyped = note(styled = false).also { it.notification.extras.remove(Notification.EXTRA_CALL_TYPE) }
            fun publish(vararg notes: StatusBarNotification) {
                listener.cancelAllNotifications()
                notes.forEach { shadowOf(listener).addActiveNotification(it); listener.onNotificationPosted(it) }
                main.idle()
            }
            window.root = cameraNode(pkg = "other.app")
            publish(untyped)
            assertEquals(CallStatus.UNTYPED, session.deckState.value.teams.status)
            window.root = cameraNode(pkg = TEAMS_PACKAGE).also { shadowOf(it).addChild(cameraNode("Mic", TEAMS_PACKAGE)) }
            service.onAccessibilityEvent(null); main.idle()
            assertEquals(CallStatus.UNTYPED, session.deckState.value.teams.status)
            window.root = root
            for ((type, status) in listOf(Notification.CallStyle.CALL_TYPE_INCOMING to CallStatus.INCOMING,
                Notification.CallStyle.CALL_TYPE_SCREENING to CallStatus.SCREENING)) {
                publish(note(type = type))
                assertEquals(status, session.deckState.value.teams.status)
            }
            publish(untyped, note(id = 2))
            assertEquals(CallStatus.UNAVAILABLE, session.deckState.value.teams.status)
            publish(untyped)
            assertEquals(CallStatus.JOINED, session.deckState.value.teams.status)
            assertEquals(IN_CALL, session.deck.state.value.activePage.id)
            assertEquals(1, clicks) // no automatic action
            val before = controls.refresh(true, true, true, true)
            var dispatchChecks = 0
            assertFalse(controls.pressMic(before) {
                if (++dispatchChecks == 2) listener.onNotificationPosted(untyped) // same key and post time
                true
            }.success)
            assertEquals(2, dispatchChecks)
            main.idle()
            assertNotEquals(before.generation, controls.refresh(true, true, true, true).generation)
            app.emit(KeyEvent(5, true)) // physical down queued before the first replacement
            val page = session.deck.state.value
            repeat(15) {
                listener.onNotificationPosted(untyped); main.idle()
                assertSame(page, session.deck.state.value) // still the original page projection
            }
            app.emit(KeyEvent(5, false)); main.idle()
            assertEquals(1, clicks)
            center()
            assertEquals(2, clicks)
            window.root = null
            service.onAccessibilityEvent(null); main.idle()
            assertEquals(CallStatus.UNTYPED, session.deckState.value.teams.status)
            assertEquals("launcher", session.deck.state.value.activeProfileId)
            window.root = root
            service.onAccessibilityEvent(null); main.idle()
            assertTrue(session.deckState.value.teams.joined)
            assertEquals(IN_CALL, session.deck.state.value.activePage.id)
            val joinedUntyped = controls.refresh(true, true, true, true)
            window.root = null // no refresh before dispatch
            assertFalse(controls.pressMic(joinedUntyped) { true }.success)
            assertEquals(2, clicks)
            publish(call) // upgrading to typed must not inherit the untyped confirmation
            assertEquals(CallStatus.NOTIFICATION, session.deckState.value.teams.status)
            listener.onListenerDisconnected(); main.idle()
            assertFalse(session.deckState.value.teams.joined)
            assertEquals("launcher", session.deck.state.value.activeProfileId)
        } finally {
            session.disconnect()
            serviceController.destroy(); listenerController.destroy()
        }
    }
}
