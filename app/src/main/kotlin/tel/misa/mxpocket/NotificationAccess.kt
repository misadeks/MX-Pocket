package tel.misa.mxpocket

import android.app.Activity
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** A new component and new consent keys: none of the old grants or switches opt in. */
internal class NotificationAccess(context: Context) {
    val component = ComponentName(context, PocketNotificationListener::class.java)
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val prefs = context.getSharedPreferences("notification-uses", Context.MODE_PRIVATE)
    val granted get() = manager.isNotificationListenerAccessGranted(component)
    var music: Boolean
        get() = prefs.getBoolean("music", false)
        set(value) {
            prefs.edit().putBoolean("music", value).apply()
            mediaChanges.update { it + 1 }
        }
    var teams: Boolean
        get() = prefs.getBoolean("teams", false)
        set(value) {
            prefs.edit().putBoolean("teams", value).apply {
                if (!value) remove("teams-caller")
            }.apply()
        }
    var teamsCaller: Boolean
        get() = teams && prefs.getBoolean("teams-caller", false)
        set(value) { prefs.edit().putBoolean("teams-caller", teams && value).apply() }
    var dialer: Boolean
        get() = prefs.getBoolean("dialer", false)
        set(value) { prefs.edit().putBoolean("dialer", value).apply() }

    fun open(host: Activity) {
        if (Build.VERSION.SDK_INT >= 30) {
            val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
            try {
                host.startActivity(detail)
                return
            } catch (_: ActivityNotFoundException) { /* Some Settings apps only implement the list. */ }
        }
        host.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    companion object { val mediaChanges = MutableStateFlow(0L) }
}
