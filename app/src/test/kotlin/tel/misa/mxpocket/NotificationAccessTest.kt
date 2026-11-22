package tel.misa.mxpocket

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.Process
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import android.telecom.TelecomManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tel.misa.mxpocket.calls.LineState
import tel.misa.mxpocket.calls.PhoneCallAccess
import tel.misa.mxpocket.decks.TEAMS_PACKAGE
import tel.misa.mxpocket.decks.TeamsAccess
import tel.misa.mxpocket.decks.TeamsEvents
import tel.misa.mxpocket.decks.TeamsObservation

internal fun Context.enableMusicSessions(): ComponentName = NotificationAccess(this).run {
    music = true
    shadowOf(getSystemService(NotificationManager::class.java)).setNotificationListenerAccessGranted(component, true)
    component
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationAccessTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val notices get() = shadowOf(app.getSystemService(NotificationManager::class.java))

    @Test fun oldGrantsAndSettingsDoNotAuthorizeAnySharedUse() {
        for (old in listOf("decks.MediaSessionAccess", "decks.TeamsCallListener", "calls.PhoneCallListener")) {
            notices.setNotificationListenerAccessGranted(ComponentName(app.packageName, "${app.packageName}.$old"), true)
        }
        app.getSharedPreferences("teams-call", 0).edit().putBoolean("observe", true).apply()
        app.getSharedPreferences("phone-call-view", 0).edit().putBoolean("enabled", true).putBoolean("details", true).apply()
        val shared = NotificationAccess(app)
        assertFalse(shared.granted)
        notices.setNotificationListenerAccessGranted(shared.component, true)
        assertFalse(shared.music)
        assertFalse(TeamsAccess(app).enabled)
        assertTrue(PhoneCallAccess(app).enabled) // Basic ringing remains an independent option.
        assertFalse(PhoneCallAccess(app).details)
    }

    @Test fun sharedListenerOnlyWakesOptedInOwnerForItsOwnLocalPublisher() {
        val access = NotificationAccess(app)
        app.enableMusicSessions()
        val pkg = "fixture.dialer"
        shadowOf(app.getSystemService(TelecomManager::class.java)).setDefaultDialerPackage(pkg)
        val controller = Robolectric.buildService(PocketNotificationListener::class.java).create()
        val listener = controller.get()
        listener.onListenerConnected()
        var teams = 0
        var phone = 0
        TeamsEvents.refresh = { teams++ }
        PocketNotificationListener.refreshPhone = { phone++ }
        fun event(publisher: String, user: UserHandle = Process.myUserHandle(), poster: String = publisher) =
            object : StatusBarNotification(publisher, poster, 1, null, 10000, 0, 0, Notification(), user, 1L) {
                // The framework constructor reads its own payload to build the group key.
                var constructed = false
                override fun getNotification(): Notification {
                    check(!constructed) { "Unexpected payload read" }
                    return super.getNotification()
                }
            }.also { it.constructed = true }
        fun deliver(n: StatusBarNotification) {
            listener.onNotificationPosted(n)
            listener.onNotificationRemoved(n)
        }
        try {
            val teamsEvent = event(TEAMS_PACKAGE)
            val phoneEvent = event(pkg)
            shadowOf(listener).addActiveNotification(teamsEvent)
            shadowOf(listener).addActiveNotification(phoneEvent)
            deliver(teamsEvent); deliver(phoneEvent)
            assertSame(TeamsObservation.Unavailable, listener.teamsSnapshot())
            assertNull(listener.phoneSnapshot(LineState.RINGING, null).notice)
            assertEquals(0 to 0, teams to phone)

            access.teams = true
            deliver(phoneEvent); deliver(teamsEvent)
            assertEquals(2 to 0, teams to phone)
            access.teams = false
            access.dialer = true
            deliver(teamsEvent); deliver(phoneEvent)
            assertEquals(2 to 2, teams to phone)
            access.teams = true
            val work = UserHandle.getUserHandleForUid(1_010_000)
            deliver(event(TEAMS_PACKAGE, work)); deliver(event(pkg, work))
            deliver(event("some.other.app"))
            deliver(event(TEAMS_PACKAGE, poster = "delegate")); deliver(event(pkg, poster = "delegate"))
            assertEquals(2 to 2, teams to phone)

            notices.setNotificationListenerAccessGranted(access.component, false)
            deliver(teamsEvent); deliver(phoneEvent)
            assertSame(TeamsObservation.Unavailable, listener.teamsSnapshot())
            assertNull(listener.phoneSnapshot(LineState.RINGING, null).notice)
            assertEquals(2 to 2, teams to phone)
        } finally {
            TeamsEvents.refresh = null
            PocketNotificationListener.refreshPhone = null
            controller.destroy()
        }
    }
}
