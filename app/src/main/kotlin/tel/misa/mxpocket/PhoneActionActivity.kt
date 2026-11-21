package tel.misa.mxpocket

import android.app.Activity
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.provider.MediaStore
import android.content.pm.PackageManager
import android.widget.Toast
import tel.misa.mxpocket.plugins.PhoneApp
import tel.misa.mxpocket.decks.viewfinder

/** Only opened by tapping the system notification. The intent identifies a request, not a target. */
class PhoneActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle(R.string.phone_action_title)
    }

    override fun onResume() {
        super.onResume()
        val session = (application as PocketApplication).session
        val action = session.takePhoneAction(intent.data?.lastPathSegment)
        if (action != null) {
            getSystemService(NotificationManager::class.java).cancel(PHONE_NOTICE)
        }
        val error = when {
            action == null -> getString(R.string.phone_action_stale)
            !isUnlockedAndInteractive() -> getString(R.string.phone_action_locked)
            !hasKeypadAccess(action.ticket) -> getString(R.string.phone_action_stale)
            else -> launchShortcut(action.target)
        }
        if (error != null) {
            session.message(error)
            Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}

internal const val PHONE_NOTICE = 2
internal class DirectShortcuts(private val context: Context) {
    private val prefs = context.getSharedPreferences("phone-shortcuts", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = prefs.getBoolean("direct", false) && Settings.canDrawOverlays(context)
        set(value) { prefs.edit().putBoolean("direct", value && Settings.canDrawOverlays(context)).apply() }
}

internal fun Context.isUnlockedAndInteractive() = !getSystemService(KeyguardManager::class.java).isKeyguardLocked &&
    getSystemService(PowerManager::class.java).isInteractive

internal fun Context.hasKeypadAccess(ticket: PocketSession.Request): Boolean {
    val usb = getSystemService(UsbManager::class.java)
    return usb.keypad(ticket.name)?.let(usb::hasPermission) == true
}

internal fun Context.launchShortcut(target: PocketSession.Target): String? {
    val intent = when (target) {
        is PocketSession.Target.Phone -> return launchPhoneApp(target.app)
        is PocketSession.Target.FavoriteApp -> (applicationContext as PocketApplication).session.favoriteApps.intentFor(target.component)
            ?: return "Favorite is no longer available."
        is PocketSession.Target.Studio -> viewfinder(target.mode) ?: return "No default camera viewfinder for this mode."
    }
    return startShortcut(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Only return the resolved viewfinder, never a launcher activity supplied by a favorite. */
@Suppress("DEPRECATION")
internal fun Context.cameraIntentForFavorite(component: String, favorites: tel.misa.mxpocket.decks.FavoriteApps): Intent? {
    val favorite = favorites.intentFor(component)?.component ?: return null
    val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
    val flags = PackageManager.MATCH_DEFAULT_ONLY
    val camera = packageManager.resolveActivity(intent, flags)?.activityInfo ?: return null
    if (camera.packageName != favorite.packageName || !camera.enabled || !camera.exported) return null
    val name = ComponentName(camera.packageName, camera.name)
    // A resolver/chooser does not itself handle the viewfinder action.
    if (packageManager.queryIntentActivities(intent, flags).none {
            it.activityInfo.packageName == name.packageName && it.activityInfo.name == name.className
        }) return null
    return intent.setComponent(name)
}

internal fun Context.startShortcut(intent: Intent): String? = try {
    startActivity(intent)
    null
} catch (_: ActivityNotFoundException) { "App is no longer installed." }
catch (_: SecurityException) { "Android restricted this shortcut." }

internal fun Context.launchPhoneApp(app: PhoneApp): String? {
    val intent = when (app) {
        PhoneApp.CAMERA -> Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        PhoneApp.SETTINGS -> Intent(Settings.ACTION_SETTINGS)
        PhoneApp.DIALER -> Intent(Intent.ACTION_DIAL, Uri.parse("tel:"))
        PhoneApp.WEB -> Intent(Intent.ACTION_VIEW, Uri.parse("https://www.logitech.com/"))
        PhoneApp.ALARMS -> Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS)
    }
    // Keep external apps out of the notification entry Activity's temporary task.
    if (this !is MainActivity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        startActivity(intent)
        null
    } catch (_: ActivityNotFoundException) {
        "No app can handle ${app.name}."
    } catch (_: SecurityException) {
        "Android did not allow ${app.name} to open."
    }
}
