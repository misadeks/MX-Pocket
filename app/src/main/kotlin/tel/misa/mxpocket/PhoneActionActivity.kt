package tel.misa.mxpocket

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
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import tel.misa.mxpocket.shared.ui.PhoneActionResultScreen
import tel.misa.mxpocket.shared.ui.PocketTheme
import tel.misa.mxpocket.decks.FavoriteApps
import tel.misa.mxpocket.i18n.AccessCopy
import tel.misa.mxpocket.plugins.PhoneApp
import tel.misa.mxpocket.plugins.displayName
import tel.misa.mxpocket.decks.viewfinder
import tel.misa.mxpocket.decks.teamsLauncher
import tel.misa.mxpocket.ui.LanguagePreference

private enum class ShortcutFailure {
    STALE, LOCKED, TEAMS_MISSING, FAVORITE_UNAVAILABLE, NO_VIEWFINDER, APP_MISSING, RESTRICTED, NO_PHONE_HANDLER, PHONE_RESTRICTED;

    fun text(context: Context, app: PhoneApp?) = when (this) {
        STALE -> LanguagePreference.resourcesFor(context).getString(R.string.phone_action_stale)
        LOCKED -> LanguagePreference.resourcesFor(context).getString(R.string.phone_action_locked)
        TEAMS_MISSING -> AccessCopy.TeamsNotInstalled.text()
        FAVORITE_UNAVAILABLE -> AccessCopy.FavoriteUnavailable.text()
        NO_VIEWFINDER -> AccessCopy.NoViewfinder.text()
        APP_MISSING -> AccessCopy.AppNotInstalled.text()
        RESTRICTED -> AccessCopy.ShortcutRestricted.text()
        NO_PHONE_HANDLER -> AccessCopy.NoPhoneHandler.text(requireNotNull(app).displayName().uppercase())
        PHONE_RESTRICTED -> AccessCopy.PhoneRestricted.text(requireNotNull(app).displayName().uppercase())
    }
}

/** Only opened by tapping the system notification. The intent identifies a request, not a target. */
class PhoneActionActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) =
        super.attachBaseContext(tel.misa.mxpocket.ui.LanguagePreference.activityContext(newBase))

    private var failure: ShortcutFailure? = null
    private var phoneApp: PhoneApp? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle(R.string.phone_action_title)
        phoneApp = savedInstanceState?.getString("phoneApp")?.let { id -> PhoneApp.entries.find { it.name == id } }
        savedInstanceState?.getString("failureCode")?.let { id ->
            ShortcutFailure.entries.find { it.name == id }?.let(::showFailure)
        }
    }

    override fun onResume() {
        super.onResume()
        if (failure != null || isFinishing) return
        val session = (application as PocketApplication).session
        val action = session.takePhoneAction(intent.data?.lastPathSegment)
        if (action != null) {
            getSystemService(NotificationManager::class.java).cancel(PHONE_NOTICE)
        }
        phoneApp = (action?.target as? PocketSession.Target.Phone)?.app
        val error = when {
            action == null -> ShortcutFailure.STALE
            !isUnlockedAndInteractive() -> ShortcutFailure.LOCKED
            !hasKeypadAccess(action.ticket) -> ShortcutFailure.STALE
            else -> tryShortcut(action.target)
        }
        if (error != null) {
            session.message(error.text(this, phoneApp))
            showFailure(error)
        } else finish()
    }

    private fun showFailure(problem: ShortcutFailure) {
        failure = problem
        setContent { PocketTheme(tel.misa.mxpocket.ui.ThemePreference.current(this)) {
            PhoneActionResultScreen(problem.text(this, phoneApp), ::finish)
        } }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("failureCode", failure?.name)
        outState.putString("phoneApp", phoneApp?.name)
    }
}

internal const val PHONE_NOTICE = 2
internal class DirectShortcuts(private val context: Context) {
    private val prefs = context.getSharedPreferences("phone-shortcuts", Context.MODE_PRIVATE)
    // The old "direct" opt-in explicitly excluded ordinary saved favorites.
    private val consent = "direct-with-favorites"
    val optedIn get() = prefs.getBoolean(consent, false)
    var enabled: Boolean
        get() = optedIn && Settings.canDrawOverlays(context)
        set(value) { prefs.edit().putBoolean(consent, value && Settings.canDrawOverlays(context)).apply() }
}

internal fun Context.isUnlockedAndInteractive() = !getSystemService(KeyguardManager::class.java).isKeyguardLocked &&
    getSystemService(PowerManager::class.java).isInteractive

internal fun Context.hasKeypadAccess(ticket: PocketSession.Request): Boolean {
    val usb = getSystemService(UsbManager::class.java)
    return usb.keypad(ticket.name)?.let(usb::hasPermission) == true
}

internal fun Context.launchShortcut(target: PocketSession.Target): String? =
    tryShortcut(target)?.text(this, (target as? PocketSession.Target.Phone)?.app)

private fun Context.tryShortcut(target: PocketSession.Target): ShortcutFailure? {
    val intent = when (target) {
        PocketSession.Target.Teams -> teamsLauncher() ?: return ShortcutFailure.TEAMS_MISSING
        is PocketSession.Target.Phone -> return tryPhoneApp(target.app)
        is PocketSession.Target.FavoriteApp -> intentForFavoriteShortcut(target.component,
            (applicationContext as PocketApplication).session.favoriteApps) ?: return ShortcutFailure.FAVORITE_UNAVAILABLE
        is PocketSession.Target.Studio -> viewfinder(target.mode) ?: return ShortcutFailure.NO_VIEWFINDER
    }
    return startShortcut(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Resolve from the saved selection at use time, including when retrying from the notification. */
@Suppress("DEPRECATION")
private fun Context.intentForFavoriteShortcut(component: String, favorites: FavoriteApps): Intent? {
    val launcher = favorites.intentFor(component) ?: return null
    val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
    val flags = PackageManager.MATCH_DEFAULT_ONLY
    val camera = packageManager.resolveActivity(intent, flags)?.activityInfo ?: return launcher
    if (camera.packageName != launcher.component?.packageName) return launcher
    if (!camera.enabled || !camera.exported) return null
    val name = ComponentName(camera.packageName, camera.name)
    // A resolver/chooser does not itself handle the viewfinder action.
    if (packageManager.queryIntentActivities(intent, flags).none {
            it.activityInfo.packageName == name.packageName && it.activityInfo.name == name.className
        }) return null
    return intent.setComponent(name)
}

private fun Context.startShortcut(intent: Intent): ShortcutFailure? = try {
    startActivity(intent)
    null
} catch (_: ActivityNotFoundException) { ShortcutFailure.APP_MISSING }
catch (_: SecurityException) { ShortcutFailure.RESTRICTED }

internal fun Context.launchPhoneApp(app: PhoneApp): String? = tryPhoneApp(app)?.text(this, app)

private fun Context.tryPhoneApp(app: PhoneApp): ShortcutFailure? {
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
        ShortcutFailure.NO_PHONE_HANDLER
    } catch (_: SecurityException) {
        ShortcutFailure.PHONE_RESTRICTED
    }
}
