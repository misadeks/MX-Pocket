package tel.misa.mxpocket.decks

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.os.UserManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

internal const val TEAMS_PACKAGE = "com.microsoft.teams"

@Suppress("DEPRECATION")
internal fun Context.teamsLauncher(): Intent? {
    val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(TEAMS_PACKAGE)
    val launcher = packageManager.resolveActivity(query, 0)?.activityInfo ?: return null
    if (launcher.packageName != TEAMS_PACKAGE || !launcher.exported || !launcher.enabled || !launcher.applicationInfo.enabled) return null
    return Intent.makeMainActivity(ComponentName(TEAMS_PACKAGE, launcher.name))
}

internal class TeamsAccess(private val app: Context) {
    private val prefs = app.getSharedPreferences("teams-call", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = prefs.getBoolean("observe", false)
        set(value) { prefs.edit().putBoolean("observe", value).apply() }
    val granted get() = app.getSystemService(NotificationManager::class.java)
        .isNotificationListenerAccessGranted(ComponentName(app, TeamsCallListener::class.java))
    // Accessibility nodes do not expose their Android user. Do not click across a profile boundary.
    val singleProfile get() = app.getSystemService(UserManager::class.java).userProfiles == listOf(Process.myUserHandle())
}

/** Main-thread system callbacks only wake an active session. No listener retains a notification or action. */
internal object TeamsEvents {
    var refresh: (() -> Unit)? = null
}

internal fun localTeams(sbn: StatusBarNotification) =
    sbn.packageName == TEAMS_PACKAGE && sbn.user == Process.myUserHandle()

internal sealed interface TeamsObservation {
    data object Unavailable : TeamsObservation
    data object None : TeamsObservation
    class Ongoing(val key: String, val posted: Long) : TeamsObservation
}

internal fun teamsObservation(notifications: Array<StatusBarNotification>): TeamsObservation {
    if (Build.VERSION.SDK_INT < 34) return TeamsObservation.Unavailable
    val calls = notifications.filter { sbn ->
        localTeams(sbn) && sbn.opPkg == TEAMS_PACKAGE && sbn.notification.let {
            it.extras.getString(Notification.EXTRA_TEMPLATE) == Notification.CallStyle::class.java.name
        }
    }
    if (calls.size > 1) return TeamsObservation.Unavailable
    val n = calls.singleOrNull() ?: return TeamsObservation.None
    return if (n.notification.extras.getInt(Notification.EXTRA_CALL_TYPE) == Notification.CallStyle.CALL_TYPE_ONGOING)
        TeamsObservation.Ongoing(n.key, n.postTime) else TeamsObservation.None
}

class TeamsCallListener : NotificationListenerService() {
    companion object { internal var current: TeamsCallListener? = null; private set }
    internal var boundary = 0L
        private set
    override fun onListenerConnected() { current = this; TeamsEvents.refresh?.invoke() }
    override fun onListenerDisconnected() { clear() }
    override fun onDestroy() { clear(); super.onDestroy() }
    private fun clear() {
        if (current === this) { current = null; TeamsEvents.refresh?.invoke() }
    }
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (localTeams(sbn)) TeamsEvents.refresh?.invoke()
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (localTeams(sbn)) {
            // A key may immediately be reused; invalidate even if a successor is already in the snapshot.
            if (TeamsEvents.refresh != null && TeamsAccess(this).enabled && Build.VERSION.SDK_INT >= 34 && sbn.notification.extras.getString(Notification.EXTRA_TEMPLATE) ==
                Notification.CallStyle::class.java.name) boundary++
            TeamsEvents.refresh?.invoke()
        }
    }
    internal fun snapshot(): TeamsObservation = try {
        activeNotifications?.let(::teamsObservation) ?: TeamsObservation.Unavailable
    } catch (_: SecurityException) { TeamsObservation.Unavailable }
}
