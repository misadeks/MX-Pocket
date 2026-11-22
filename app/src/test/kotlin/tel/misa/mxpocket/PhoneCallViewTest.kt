package tel.misa.mxpocket

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.os.UserHandle
import android.provider.Settings
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.StatusBarNotification
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSubscriptionManager.SubscriptionInfoBuilder
import org.robolectric.util.ReflectionHelpers
import tel.misa.mxpocket.calls.*
import tel.misa.mxpocket.deck.PHONE_CALL_VIEW
import tel.misa.mxpocket.deck.PROFILE_HOME
import tel.misa.mxpocket.deck.importLegacyPhone
import tel.misa.mxpocket.driver.KeyEvent
import tel.misa.mxpocket.i18n.CallCopy
import tel.misa.mxpocket.plugins.*
import tel.misa.mxpocket.workflow.StepKind
import tel.misa.mxpocket.workflow.WorkflowDefinition
import tel.misa.mxpocket.workflow.WorkflowStep

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = KeypadSessionTest.FakeApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class PhoneCallViewTest {
    private val app get() = RuntimeEnvironment.getApplication() as KeypadSessionTest.FakeApp
    private val session get() = app.session
    private val main get() = shadowOf(Looper.getMainLooper())
    private val telecom get() = app.getSystemService(TelephonyManager::class.java)
    private val pkg = "test.fixture.dialer"
    private val samsungPhone = "com.samsung.android.dialer"
    private val inCallUi = "com.samsung.android.incallui"
    private val inCallUid = 1001
    private fun action(id: Int, owner: String = pkg, uid: Int = Process.myUid()) =
        PendingIntent.getBroadcast(app, id, Intent("test.call.$id").setPackage(app.packageName),
        PendingIntent.FLAG_IMMUTABLE).also {
        shadowOf(it).setCreatorPackage(owner)
        shadowOf(it).setCreatorUid(uid)
    }
    private fun note(accept: PendingIntent? = action(1), decline: PendingIntent? = action(2), poster: String = pkg,
        user: UserHandle = Process.myUserHandle(), person: Person? = null, posted: Long = System.currentTimeMillis()) =
        StatusBarNotification(pkg, poster, 1, null, Process.myUid(), 0, 0, Notification().apply {
            category = Notification.CATEGORY_CALL
            actions = listOfNotNull(accept, decline).map { Notification.Action.Builder(null, "", it).build() }.toTypedArray()
            extras.putString(Notification.EXTRA_TEMPLATE, Notification.CallStyle::class.java.name)
            extras.putInt(Notification.EXTRA_CALL_TYPE, Notification.CallStyle.CALL_TYPE_INCOMING)
            extras.putParcelable(Notification.EXTRA_CALL_PERSON, person)
            extras.putParcelable(Notification.EXTRA_ANSWER_INTENT, accept)
            extras.putParcelable(Notification.EXTRA_DECLINE_INTENT, decline)
        }, user, posted)

    @Suppress("DEPRECATION") // Robolectric's PackageManager implements checkSignatures from these fixture signatures.
    private fun installPhonePackage(name: String, uid: Int, flags: Int, signer: String = "phone-fixture") {
        shadowOf(app.packageManager).installPackage(PackageInfo().apply {
            packageName = name
            applicationInfo = ApplicationInfo().apply { this.uid = uid; this.flags = flags }
            signatures = arrayOf(Signature(signer.toByteArray()))
        })
    }

    private fun samsungCall(person: Person, accept: PendingIntent = action(1, inCallUi, inCallUid),
        publisher: String = inCallUi, poster: String = publisher, uid: Int = inCallUid,
        user: UserHandle = Process.myUserHandle()): StatusBarNotification {
        val call = Notification.Builder(app, "call")
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setStyle(Notification.CallStyle.forIncomingCall(person, action(2, inCallUi, inCallUid), accept))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()
        return StatusBarNotification(publisher, poster, 1, null, uid, 0, 0, call, user, System.currentTimeMillis() + 1)
    }

    @Test fun splitPublisherRequiresCurrentSystemPairMatchingSignerAndPublisherOwnedActions() {
        val person = Person.Builder().setName("Fixture").build()
        val call = samsungCall(person)
        fun read(vararg notes: StatusBarNotification, dialer: String = samsungPhone) =
            dialerNotice(arrayOf(*notes), dialer, app.packageManager, LineState.RINGING, 0)
        installPhonePackage(samsungPhone, 10130, ApplicationInfo.FLAG_SYSTEM)
        assertNull(read(call)) // Missing or not visible to PackageManager.
        installPhonePackage(inCallUi, inCallUid, ApplicationInfo.FLAG_SYSTEM, "other-signer")
        assertNull(read(call))
        installPhonePackage(inCallUi, inCallUid, 0)
        assertNull(read(call))
        installPhonePackage(inCallUi, inCallUid, ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)
        installPhonePackage(samsungPhone, 10130, 0)
        assertNull(read(call))
        installPhonePackage(samsungPhone, 10130, ApplicationInfo.FLAG_SYSTEM)
        assertEquals(inCallUi, read(call)!!.stamp.publisher)
        assertNull(read(call, dialer = pkg))
        assertNull(read(samsungCall(person, publisher = "other.incallui")))
        assertNull(read(samsungCall(person, poster = samsungPhone)))
        assertNull(read(samsungCall(person, uid = 10130)))
        assertNull(read(samsungCall(person, user = UserHandle.getUserHandleForUid(1_010_000))))
        assertNull(read(call, samsungCall(person, publisher = samsungPhone, uid = 10130)))
        for (owner in listOf(action(3, samsungPhone, 10130), action(4, samsungPhone, inCallUid), action(5, inCallUi, 10130))) {
            val result = read(samsungCall(person, accept = owner))!!
            assertEquals("Fixture", result.name)
            assertNull(result.stamp.accept)
            assertNull(result.stamp.decline)
        }
    }

    @Test fun onlyOneCurrentLocalDialerNoticeSuppliesDistinctControlsAndDisplayLabel() {
        fun read(vararg notes: StatusBarNotification) = dialerNotice(arrayOf(*notes), pkg, app.packageManager, LineState.RINGING, 0)
        assertNull(read(note(poster = "other")))
        assertNull(read(note(user = UserHandle.getUserHandleForUid(1_010_000))))
        assertNull(read(note(), note()))
        assertNull(read(note(accept = null))!!.stamp.decline)
        assertNull(read(note(decline = action(1)))!!.stamp.accept)
        assertNull(read(note(accept = action(3).also { shadowOf(it).setCreatorPackage("other") }))!!.stamp.accept)
        val withheld = Person.Builder().setUri("tel:0000000").setIcon(Icon.createWithContentUri("content://unused")).build()
        val private = read(note(person = withheld))!!
        assertNull(private.name)
        assertNull(private.icon)
        assertNull(dialerNotice(arrayOf(note()), pkg, app.packageManager, LineState.OFF_HOOK, 0))
        val secured = note().also { n ->
            n.notification.actions[0] = Notification.Action.Builder(n.notification.actions[0])
                .setAuthenticationRequired(true).build()
            n.notification.extras.putString(Notification.EXTRA_TITLE, "Not an identity source")
        }
        val locked = dialerNotice(arrayOf(secured), pkg, app.packageManager, LineState.RINGING, 0, restricted = true) { true }!!
        assertNull(locked.name)
        assertNull(locked.stamp.accept)
        assertNull(locked.stamp.decline)
        assertNull(dialerNotice(arrayOf(secured), pkg, app.packageManager, LineState.RINGING, 0, restricted = true) { false })
    }

    @Test fun pinsDismissalAndUncertaintyNeverRearmAnEpisode() {
        val deck = session.deck
        fun current() = deck.operatingState.value.activeProfileId
        deck.setProfileAutomatic(deck.state.value.activeProfileId, false).getOrThrow()
        deck.showPhoneCall(true)
        assertEquals(PHONE_CALL_VIEW, current())
        assertFalse(deck.exportJson().contains(PHONE_CALL_VIEW))
        assertFalse(deck.operatingState.value.visibleProfiles.any { it.id == PHONE_CALL_VIEW })
        deck.changePage(-1).getOrThrow()
        assertEquals(PROFILE_HOME, current())
        deck.showPhoneCall(null); deck.showPhoneCall(true)
        assertEquals(PROFILE_HOME, current())
        assertEquals(CallCopy.WaitForIdle, deck.phoneCallBlocker)
        deck.setPinned("focus").getOrThrow()
        deck.showPhoneCall(false); deck.showPhoneCall(true)
        assertEquals(CallCopy.Unpin, deck.phoneCallBlocker)
        deck.setPinned(null); deck.showPhoneCall(true)
        assertEquals("focus", current())
        deck.showPhoneCall(false); deck.showPhoneCall(true)
        deck.selectProfile("studio").getOrThrow()
        val page = deck.createPage("studio", "Tools").getOrThrow()
        deck.openPage(page).getOrThrow()
        deck.showPhoneCall(true); deck.showPhoneCall(false)
        assertEquals("studio", current())
        val before = deck.exportJson()
        deck.showPhoneCall(true); assertEquals(PHONE_CALL_VIEW, current())
        deck.showPhoneCall(null); deck.showPhoneCall(true)
        assertEquals("studio", current())
        assertEquals(page, deck.operatingState.value.activePage.id)
        assertEquals(before, deck.exportJson())
        deck.enterGame().getOrThrow(); deck.showPhoneCall(false); deck.showPhoneCall(true)
        assertEquals(CallCopy.LeaveGame, deck.phoneCallBlocker)
        deck.changePage(1).getOrThrow(); deck.showPhoneCall(true)
        assertEquals("studio", current())
    }

    @Test fun enabledWorkflowsCanStillTargetTheRetiredLayout() {
        app.workflowsEnabled = true
        val workflows = checkNotNull(session.workflows)
        val deck = session.deck
        deck.importLegacyPhone()
        val step = WorkflowStep(StepKind.ACTION, "profile.switch", params = mapOf("profileId" to "phone"))
        val workflow = WorkflowDefinition(name = "My shortcuts", steps = listOf(step))
        assertNull(workflows.validate(workflow))
        assertNull(kotlinx.coroutines.runBlocking { workflows.save(workflow) })
        assertTrue(workflows.run(workflow.id, tel.misa.mxpocket.workflow.WorkflowOrigin.MANUAL))
        main.idle()
        assertEquals("phone", deck.operatingState.value.activeProfileId)
    }

    @Test fun physicalKeysRequireCurrentFaceAndLiveDialerCapabilityAndNeverExposeCallerInHome() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_STATE, "${app.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        val sub = SubscriptionInfoBuilder.newBuilder().setId(10).setSimSlotIndex(0).buildSubscriptionInfo()
        shadowOf(app.getSystemService(SubscriptionManager::class.java)).setActiveSubscriptionInfos(sub)
        shadowOf(telecom).setTelephonyManagerForSubscriptionId(10, telecom)
        shadowOf(app.getSystemService(TelecomManager::class.java)).setDefaultDialerPackage(pkg)
        val access = session.phoneAccess
        access.enabled = true
        access.details = true
        val notices = shadowOf(app.getSystemService(NotificationManager::class.java))
        val shared = NotificationAccess(app).component
        val controller = Robolectric.buildService(PocketNotificationListener::class.java).create()
        val listener = controller.get()
        val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        val caller = Person.Builder().setName("Fixture caller").setIcon(Icon.createWithBitmap(bitmap)).build()
        fun post(n: StatusBarNotification) {
            // The shadow appends; Android replaces a notification posted with the same key.
            listener.cancelNotification(n.key)
            shadowOf(listener).addActiveNotification(n)
            listener.onNotificationPosted(n)
        }
        fun state(value: Int) { shadowOf(telecom).setCallState(value); session.refreshPhoneCall() }
        fun count(id: Int) = shadowOf(app).broadcastIntents.count { it.action == "test.call.$id" }
        session.setPluginEnabled("phone", false)
        app.connectKeypad()
        try {
            // An already-held physical key on the outgoing layout cannot acquire a call action.
            app.emit(KeyEvent(7, true)); main.idle()
            val previous = session.deck.operatingState.value.activeProfileId
            SystemClock.sleep(5)
            state(TelephonyManager.CALL_STATE_RINGING); main.idle()
            assertEquals(PHONE_CALL_VIEW, session.deck.operatingState.value.activeProfileId)
            assertTrue(session.frame().keys[CALL_ACCEPT].readOnly) // Basic ringing requires no listener.
            notices.setNotificationListenerAccessGranted(shared, true)
            listener.onListenerConnected(); session.refreshPhoneCall()
            post(note(person = caller)); main.idle()
            assertEquals("Fixture caller", session.frame().keys[4].label)
            assertEquals(Color.MAGENTA, app.lcd.getValue(CALL_PHOTO).getPixel(59, 59))
            notices.setNotificationListenerAccessGranted(shared, false)
            listener.onListenerDisconnected(); main.idle()
            assertEquals(PHONE_CALL_VIEW, session.deck.operatingState.value.activeProfileId)
            assertTrue(session.frame().keys[CALL_ACCEPT].readOnly)
            assertFalse(session.frame().keys.any { it.label.contains("Fixture") })
            assertNotEquals(Color.MAGENTA, app.lcd.getValue(CALL_PHOTO).getPixel(59, 59))
            notices.setNotificationListenerAccessGranted(shared, true)
            listener.onListenerConnected(); session.refreshPhoneCall(); main.idle()
            val home = session.homeTiles()
            assertFalse(home.any { it.label.contains("Fixture") })
            assertNotEquals(Color.MAGENTA, home[CALL_PHOTO].image!!.toPixelMap()[59, 59].toArgb())
            app.emit(KeyEvent(7, true)); app.emit(KeyEvent(7, false)); main.idle()
            assertEquals(0, count(1))
            // Queue the down before replacing the same notification; the old paint receipt is invalid.
            app.emit(KeyEvent(9, true))
            val replacement = note(accept = action(3), decline = action(4), person = caller)
            post(replacement); app.emit(KeyEvent(9, false)); main.idle()
            assertEquals(0, count(2)); assertEquals(0, count(4))
            app.emit(KeyEvent(9, true, synthetic = true)); main.idle()
            assertEquals(0, count(4))
            app.press(9); app.press(9); app.press(7)
            assertEquals(1, count(4)); assertEquals(0, count(3))
            state(TelephonyManager.CALL_STATE_IDLE); main.idle()
            assertEquals(previous, session.deck.operatingState.value.activeProfileId)
            state(TelephonyManager.CALL_STATE_RINGING); main.idle()
            assertTrue(session.frame().keys[CALL_ACCEPT].readOnly) // Old ringing notification survived idle.
            SystemClock.sleep(5)
            post(note(accept = action(3), decline = action(4), person = caller)); main.idle()
            app.press(7); assertEquals(1, count(3))
            state(TelephonyManager.CALL_STATE_OFFHOOK); main.idle()
            assertEquals("Dialing or active", session.frame().keys[CALL_IDENTITY].footer)
            assertFalse(session.frame().keys.any { !it.readOnly })
            app.press(0x01a2); session.refreshPhoneCall(); main.idle()
            assertEquals(previous, session.deck.operatingState.value.activeProfileId)
            // Neither page chord borrows the Answer or Decline key, even with both actions live.
            session.openProfile("focus")
            for (hold in listOf(false, true)) {
                state(TelephonyManager.CALL_STATE_IDLE); main.idle(); SystemClock.sleep(5)
                state(TelephonyManager.CALL_STATE_RINGING); post(note()); main.idle()
                assertFalse(session.frame().keys[CALL_ACCEPT].readOnly)
                app.emit(KeyEvent(0x01a1, true)); app.emit(KeyEvent(0x01a2, true)); main.idle()
                assertEquals(PHONE_CALL_VIEW, session.deck.operatingState.value.activeProfileId)
                if (hold) main.idleFor(java.time.Duration.ofMillis(android.view.ViewConfiguration.getLongPressTimeout().toLong()))
                app.emit(KeyEvent(0x01a1, false)); app.emit(KeyEvent(0x01a2, false)); main.idle()
                session.refreshPhoneCall()
                assertEquals(if (hold) "game.xo" else PROFILE_HOME, session.deck.operatingState.value.activeProfileId)
                assertEquals(0, count(1)); assertEquals(0, count(2))
                session.openProfile("focus")
            }
            state(TelephonyManager.CALL_STATE_IDLE); state(TelephonyManager.CALL_STATE_RINGING); main.idle()
            assertEquals(PHONE_CALL_VIEW, session.deck.operatingState.value.activeProfileId)
            shadowOf(app).denyPermissions(Manifest.permission.READ_PHONE_STATE)
            session.refreshPhoneCall(); main.idle()
            assertEquals("focus", session.deck.operatingState.value.activeProfileId)
            assertFalse(session.logText().contains("Fixture caller"))
            assertFalse(session.deck.exportJson().contains("Fixture caller"))
        } finally { session.disconnect(); controller.destroy() }
    }

    @Test fun onlyRingingTriggersAnEpisodeEvenWhenLockedAndTelecomInCallIsNotDirection() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        val sub = SubscriptionInfoBuilder.newBuilder().setId(10).setSimSlotIndex(0).buildSubscriptionInfo()
        shadowOf(app.getSystemService(SubscriptionManager::class.java)).setActiveSubscriptionInfos(sub)
        shadowOf(telecom).setTelephonyManagerForSubscriptionId(10, telecom)
        val lock = shadowOf(app.getSystemService(android.app.KeyguardManager::class.java))
        val power = shadowOf(app.getSystemService(android.os.PowerManager::class.java))
        session.deck.importLegacyPhone()
        session.phoneAccess.enabled = true
        session.refreshPhoneCall()
        assertEquals(CallCopy.ConnectKeypad, session.phoneCallBlocker)
        shadowOf(telecom).setCallState(TelephonyManager.CALL_STATE_OFFHOOK)
        val telecomCalls = shadowOf(app.getSystemService(TelecomManager::class.java))
        telecomCalls.setIsInCall(true)
        app.connectKeypad() // Cold attach to an ongoing call.
        try {
            assertEquals(PROFILE_HOME, session.deck.operatingState.value.activeProfileId)
            shadowOf(telecom).setCallState(TelephonyManager.CALL_STATE_IDLE)
            session.refreshPhoneCall()
            assertEquals(LineState.CALL, session.phoneAccess.state())
            assertEquals(PROFILE_HOME, session.deck.operatingState.value.activeProfileId)
            telecomCalls.setIsInCall(false)
            lock.setKeyguardLocked(true)
            power.setIsInteractive(false)
            shadowOf(telecom).setCallState(TelephonyManager.CALL_STATE_RINGING)
            session.refreshPhoneCall()
            assertEquals(PHONE_CALL_VIEW, session.deck.operatingState.value.activeProfileId)
            assertTrue(session.frame().keys.all { it.readOnly })
            assertEquals("Incoming call", session.frame().keys[CALL_IDENTITY].footer)
            assertTrue(app.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
            app.press(0x01a1)
            power.setIsInteractive(true); lock.setKeyguardLocked(false)
            shadowOf(telecom).setCallState(TelephonyManager.CALL_STATE_OFFHOOK)
            session.refreshPhoneCall()
            assertEquals(PROFILE_HOME, session.deck.operatingState.value.activeProfileId)
            assertEquals(CallCopy.WaitForIdle, session.phoneCallBlocker)
            shadowOf(telecom).setCallState(TelephonyManager.CALL_STATE_IDLE)
            session.refreshPhoneCall()
            assertNull(session.phoneCallBlocker)
            shadowOf(telecom).setCallState(TelephonyManager.CALL_STATE_RINGING)
            session.refreshPhoneCall()
            assertEquals(PHONE_CALL_VIEW, session.deck.operatingState.value.activeProfileId)
            session.disconnect()
            assertEquals(PROFILE_HOME, session.deck.operatingState.value.activeProfileId)
        } finally { session.disconnect() }
    }

    @Test fun splitDialerRechecksPublisherAndPrivacyBeforeUsingRenderedCallControls() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_STATE, "${app.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        val sub = SubscriptionInfoBuilder.newBuilder().setId(10).setSimSlotIndex(0).buildSubscriptionInfo()
        shadowOf(app.getSystemService(SubscriptionManager::class.java)).setActiveSubscriptionInfos(sub)
        shadowOf(telecom).setTelephonyManagerForSubscriptionId(10, telecom)
        val roles = shadowOf(app.getSystemService(TelecomManager::class.java))
        roles.setDefaultDialerPackage(samsungPhone)
        installPhonePackage(samsungPhone, 10130, ApplicationInfo.FLAG_SYSTEM)
        installPhonePackage(inCallUi, inCallUid, ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)
        val access = session.phoneAccess
        access.enabled = true
        access.details = true
        val shared = NotificationAccess(app).component
        shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationListenerAccessGranted(shared, true)
        val controller = Robolectric.buildService(PocketNotificationListener::class.java).create()
        val listener = controller.get()
        val power = app.getSystemService(android.os.PowerManager::class.java)
        val lock = app.getSystemService(android.app.KeyguardManager::class.java)
        val color = Color.rgb(123, 41, 189)
        val bmp = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        val caller = Person.Builder().setName("Fixture").setIcon(Icon.createWithBitmap(bmp)).build()
        fun attempts() = shadowOf(app).broadcastIntents.count { it.action?.startsWith("test.call.") == true }
        app.connectKeypad()
        try {
            listener.onListenerConnected()
            shadowOf(telecom).setCallState(TelephonyManager.CALL_STATE_RINGING)
            session.refreshPhoneCall(); main.idle()
            assertEquals(PHONE_CALL_VIEW, session.deck.operatingState.value.activeProfileId)
            // postTime is wall time, not Robolectric's simulated monotonic clock. Order it after idle.
            val n = samsungCall(caller)
            shadowOf(listener).addActiveNotification(n)
            listener.onNotificationPosted(n); main.idle()
            assertEquals("Fixture", session.frame().keys[CALL_IDENTITY].label)
            assertEquals(color, app.lcd.getValue(CALL_PHOTO).getPixel(59,59))
            assertFalse(session.frame().keys[CALL_ACCEPT].readOnly)
            assertFalse(session.frame().keys[CALL_DECLINE].readOnly)
            roles.setDefaultDialerPackage(pkg)
            app.press(9) // No notification callback to invalidate the painted face.
            assertEquals(0, attempts())
            roles.setDefaultDialerPackage(samsungPhone)
            session.refreshPhoneCall(); main.idle()
            app.emit(KeyEvent(7, true)) // Delivered after lock, against the old paint receipt.
            shadowOf(power).setIsInteractive(false)
            shadowOf(lock).setKeyguardLocked(true)
            session.refreshPhoneCall(); main.idle()
            assertEquals(0, attempts())
            assertEquals(CallCopy.NoCallerDetails.text(), session.frame().keys[CALL_IDENTITY].label)
            assertNotEquals(color, app.lcd.getValue(CALL_PHOTO).getPixel(59,59))
            // Give this notification an actual lock-screen ranking (Robolectric defaults to an empty map).
            val rank = Ranking()
            ReflectionHelpers.setField(rank, "mKey", n.key)
            ReflectionHelpers.setField(rank, "mMatchesInterruptionFilter", true)
            ReflectionHelpers.setField(rank, "mVisibilityOverride", Ranking.VISIBILITY_NO_OVERRIDE)
            ReflectionHelpers.setField(rank, "mImportance", NotificationManager.IMPORTANCE_HIGH)
            shadowOf(rank).setChannel(NotificationChannel("call", "Call", NotificationManager.IMPORTANCE_HIGH))
            ReflectionHelpers.getField<MutableMap<String, Ranking>>(listener.currentRanking, "mRankings")[n.key] = rank
            Settings.Secure.putInt(app.contentResolver, "lock_screen_show_notifications", 1)
            Settings.Secure.putInt(app.contentResolver, "lock_screen_allow_private_notifications", 1)
            n.notification.visibility = Notification.VISIBILITY_SECRET
            session.refreshPhoneCall(); main.idle()
            assertEquals(CallCopy.NoCallerDetails.text(), session.frame().keys[CALL_IDENTITY].label)
            assertTrue(session.frame().keys.all { it.readOnly })
            n.notification.visibility = Notification.VISIBILITY_PRIVATE
            session.refreshPhoneCall(); main.idle()
            assertEquals("Fixture", session.frame().keys[CALL_IDENTITY].label)
            assertEquals(color, app.lcd.getValue(CALL_PHOTO).getPixel(59,59))
            assertFalse(session.homeTiles().any { it.label.contains("Fixture") })
            app.emit(KeyEvent(7, true)); app.emit(KeyEvent(7, false)); main.idle()
            assertEquals(0, attempts()) // Held across the transition: release is not a fresh action.
            Settings.Secure.putInt(app.contentResolver, "lock_screen_allow_private_notifications", 0)
            app.press(9) // Re-check policy even before a ranking/power broadcast or poll.
            assertEquals(0, attempts())
            assertTrue(session.frame().keys.all { it.readOnly })
            Settings.Secure.putInt(app.contentResolver, "lock_screen_allow_private_notifications", 1)
            session.refreshPhoneCall(); main.idle()
            app.press(9); app.press(7)
            assertEquals(1, attempts())
            assertTrue(lock.isKeyguardLocked)
            assertFalse(power.isInteractive)
            access.enabled = false; session.refreshPhoneCall(); main.idle()
            assertFalse(session.frame().keys.any { it.label == "Fixture" })
            assertNotEquals(PHONE_CALL_VIEW, session.deck.operatingState.value.activeProfileId)
        } finally { session.disconnect(); controller.destroy() }
    }
}
