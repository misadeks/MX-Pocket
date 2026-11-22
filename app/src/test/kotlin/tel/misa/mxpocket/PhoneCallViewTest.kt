package tel.misa.mxpocket

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.os.UserHandle
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
import tel.misa.mxpocket.calls.*
import tel.misa.mxpocket.deck.PHONE_CALL_VIEW
import tel.misa.mxpocket.deck.PROFILE_HOME
import tel.misa.mxpocket.driver.KeyEvent
import tel.misa.mxpocket.plugins.*

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
    private fun action(id: Int) = PendingIntent.getBroadcast(app, id, Intent("test.call.$id").setPackage(app.packageName),
        PendingIntent.FLAG_IMMUTABLE).also {
        shadowOf(it).setCreatorPackage(pkg)
        shadowOf(it).setCreatorUid(Process.myUid())
    }
    private fun note(accept: PendingIntent? = action(1), decline: PendingIntent? = action(2), poster: String = pkg,
        user: UserHandle = Process.myUserHandle(), person: Person? = null, posted: Long = System.currentTimeMillis()) =
        StatusBarNotification(pkg, poster, 1, null, Process.myUid(), 0, 0, Notification().apply {
            category = Notification.CATEGORY_CALL
            extras.putString(Notification.EXTRA_TEMPLATE, Notification.CallStyle::class.java.name)
            extras.putInt(Notification.EXTRA_CALL_TYPE, Notification.CallStyle.CALL_TYPE_INCOMING)
            extras.putParcelable(Notification.EXTRA_CALL_PERSON, person)
            extras.putParcelable(Notification.EXTRA_ANSWER_INTENT, accept)
            extras.putParcelable(Notification.EXTRA_DECLINE_INTENT, decline)
        }, user, posted)

    @Test fun onlyOneCurrentLocalDialerNoticeSuppliesDistinctControlsAndDisplayLabel() {
        fun read(vararg notes: StatusBarNotification) = dialerNotice(arrayOf(*notes), pkg, LineState.RINGING, 0)
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
        assertNull(dialerNotice(arrayOf(note()), pkg, LineState.OFF_HOOK, 0))
    }

    @Test fun pinsDismissalAndUncertaintyNeverRearmAnEpisode() {
        val deck = session.deck
        fun current() = deck.operatingState.value.activeProfileId
        deck.setAutomatic(false).getOrThrow()
        deck.showPhoneCall(true); deck.setAutomatic(true); deck.showPhoneCall(true)
        assertEquals(PROFILE_HOME, current())
        deck.showPhoneCall(false); deck.showPhoneCall(true)
        assertEquals(PHONE_CALL_VIEW, current())
        assertFalse(deck.exportJson().contains(PHONE_CALL_VIEW))
        assertFalse(deck.operatingState.value.visibleProfiles.any { it.id == PHONE_CALL_VIEW })
        deck.changePage(-1).getOrThrow()
        assertEquals(PROFILE_HOME, current())
        deck.showPhoneCall(null); deck.showPhoneCall(true)
        assertEquals(PROFILE_HOME, current())
        deck.setPinned("focus").getOrThrow()
        deck.showPhoneCall(false); deck.showPhoneCall(true)
        deck.setPinned(null); deck.showPhoneCall(true)
        assertEquals("focus", current())
        deck.showPhoneCall(false); deck.showPhoneCall(true)
        deck.selectProfile("studio").getOrThrow()
        deck.showPhoneCall(true); deck.showPhoneCall(false)
        assertEquals("studio", current())
        deck.showPhoneCall(true); deck.showPhoneCall(false)
        assertEquals("studio", current())
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
        shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationListenerAccessGranted(access.listener, true)
        val controller = Robolectric.buildService(PhoneCallListener::class.java).create()
        val listener = controller.get()
        listener.onListenerConnected()
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
        session.deck.setAutomatic(true).getOrThrow()
        app.connectKeypad()
        try {
            // An already-held physical key on the outgoing layout cannot acquire a call action.
            app.emit(KeyEvent(7, true)); main.idle()
            val previous = session.deck.operatingState.value.activeProfileId
            SystemClock.sleep(5)
            state(TelephonyManager.CALL_STATE_RINGING); post(note(person = caller)); main.idle()
            assertEquals(PHONE_CALL_VIEW, session.deck.operatingState.value.activeProfileId)
            assertEquals("Fixture caller", session.frame().keys[4].label)
            assertEquals(Color.MAGENTA, app.lcd.getValue(CALL_PHOTO).getPixel(59, 59))
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
            assertEquals("Dialing or active", session.frame().keys[0].label)
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
            state(TelephonyManager.CALL_STATE_IDLE); state(TelephonyManager.CALL_STATE_OFFHOOK); main.idle()
            assertEquals(PHONE_CALL_VIEW, session.deck.operatingState.value.activeProfileId)
            shadowOf(app).denyPermissions(Manifest.permission.READ_PHONE_STATE)
            session.refreshPhoneCall(); main.idle()
            assertEquals("focus", session.deck.operatingState.value.activeProfileId)
            assertFalse(session.logText().contains("Fixture caller"))
            assertFalse(session.deck.exportJson().contains("Fixture caller"))
        } finally { session.disconnect(); controller.destroy() }
    }
}
