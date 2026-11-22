package tel.misa.mxpocket.decks

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Process
import android.os.UserManager
import android.service.notification.StatusBarNotification
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityManager
import androidx.core.content.ContextCompat
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
    // Accessibility nodes have no user handle. Only the personal user may inspect them,
    // and only if Android positively reports that every other listed profile is paused.
    val toolbarProfileEligible: Boolean get() {
        if (Build.VERSION.SDK_INT < 34) return false
        return try {
            val users = app.getSystemService(UserManager::class.java) ?: return false
            val me = Process.myUserHandle()
            val profiles = users.userProfiles
            !users.isProfile && profiles.count { it == me } == 1 &&
                profiles.all { it == me || users.isQuietModeEnabled(it) }
        } catch (_: RuntimeException) {
            // A denied/failed Android profile query leaves the toolbar's owner unknown.
            false
        }
    }
}

/** A profile transition invalidates prior evidence, even if the next snapshot is already quiet again. */
internal fun Context.observeTeamsProfiles(changed: () -> Unit): BroadcastReceiver {
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = changed()
    }
    val filter = IntentFilter().apply {
        addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
        addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)
        if (Build.VERSION.SDK_INT >= 34) {
            addAction(Intent.ACTION_PROFILE_ADDED)
            addAction(Intent.ACTION_PROFILE_REMOVED)
        }
        if (Build.VERSION.SDK_INT >= 35) {
            addAction(Intent.ACTION_PROFILE_AVAILABLE)
            addAction(Intent.ACTION_PROFILE_UNAVAILABLE)
        }
    }
    ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    return receiver
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
    data object Incoming : TeamsObservation
    data object Screening : TeamsObservation
    data class Notice(val key: String, val posted: Long, val typedOngoing: Boolean) : TeamsObservation
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
    val type = n.notification.extras.getInt(Notification.EXTRA_CALL_TYPE)
    return when (type) {
        Notification.CallStyle.CALL_TYPE_INCOMING -> TeamsObservation.Incoming
        Notification.CallStyle.CALL_TYPE_SCREENING -> TeamsObservation.Screening
        else -> {
            val ongoing = n.notification.callStyle() && type == Notification.CallStyle.CALL_TYPE_ONGOING
            if (ongoing || n.notification.category == Notification.CATEGORY_CALL)
                TeamsObservation.Notice(n.key, n.postTime, ongoing)
            else TeamsObservation.Unavailable
        }
    }
}
