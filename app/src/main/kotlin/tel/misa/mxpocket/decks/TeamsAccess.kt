package tel.misa.mxpocket.decks

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.os.UserManager
import android.service.notification.StatusBarNotification
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityManager
import tel.misa.mxpocket.NotificationAccess

@Suppress("DEPRECATION")
internal fun Context.teamsLauncher(): Intent? {
    val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(TEAMS_PACKAGE)
    val launcher = packageManager.resolveActivity(query, 0)?.activityInfo ?: return null
    if (launcher.packageName != TEAMS_PACKAGE || !launcher.exported || !launcher.enabled || !launcher.applicationInfo.enabled) return null
    return Intent.makeMainActivity(ComponentName(TEAMS_PACKAGE, launcher.name))
}

internal class TeamsAccess(private val app: Context) {
    private val notifications = NotificationAccess(app)
    var enabled: Boolean
        get() = notifications.teams
        set(value) { notifications.teams = value }
    val granted get() = notifications.granted
    val toolbarGranted get() = app.getSystemService(AccessibilityManager::class.java)
        .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
            val service = it.resolveInfo.serviceInfo
            ComponentName(service.packageName, service.name) == ComponentName(app, TeamsToolbarService::class.java)
        }
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
    data object Untyped : TeamsObservation
    data object Incoming : TeamsObservation
    data object Screening : TeamsObservation
    class Ongoing(val key: String, val posted: Long) : TeamsObservation
}

internal fun teamsObservation(notifications: Array<StatusBarNotification>): TeamsObservation {
    if (Build.VERSION.SDK_INT < 34) return TeamsObservation.Unavailable
    fun Notification.callStyle() = extras.getString(Notification.EXTRA_TEMPLATE) == Notification.CallStyle::class.java.name
    val calls = notifications.filter { sbn ->
        localTeams(sbn) && sbn.opPkg == TEAMS_PACKAGE && sbn.notification.let {
            it.category == Notification.CATEGORY_CALL || it.callStyle()
        }
    }
    if (calls.size > 1) return TeamsObservation.Unavailable
    val n = calls.singleOrNull() ?: return TeamsObservation.None
    if (!n.notification.callStyle()) return TeamsObservation.Untyped
    return when (n.notification.extras.getInt(Notification.EXTRA_CALL_TYPE)) {
        Notification.CallStyle.CALL_TYPE_ONGOING -> TeamsObservation.Ongoing(n.key, n.postTime)
        Notification.CallStyle.CALL_TYPE_INCOMING -> TeamsObservation.Incoming
        Notification.CallStyle.CALL_TYPE_SCREENING -> TeamsObservation.Screening
        else -> TeamsObservation.Untyped
    }
}
