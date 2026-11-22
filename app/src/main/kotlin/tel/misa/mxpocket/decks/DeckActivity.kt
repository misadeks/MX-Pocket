package tel.misa.mxpocket.decks

import android.content.ActivityNotFoundException
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import tel.misa.mxpocket.i18n.AccessCopy
import tel.misa.mxpocket.i18n.Copy
import tel.misa.mxpocket.isUnlockedAndInteractive
import tel.misa.mxpocket.shared.ui.DeckAccess
import tel.misa.mxpocket.shared.ui.DeckAccessScreen
import tel.misa.mxpocket.shared.ui.PocketTheme

abstract class DeckActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) =
        super.attachBaseContext(tel.misa.mxpocket.ui.LanguagePreference.activityContext(newBase))

    protected fun page(heading: () -> String, content: @Composable () -> Unit) {
        setContent {
            val pageTitle = heading()
            LaunchedEffect(pageTitle) { title = pageTitle }
            PocketTheme(tel.misa.mxpocket.ui.ThemePreference.current(this), content)
        }
    }
}

internal enum class ListenerAccess(val service: Class<*>) {
    MEDIA(MediaSessionAccess::class.java), TEAMS(TeamsCallListener::class.java);

    fun open(host: Activity) {
        if (Build.VERSION.SDK_INT >= 30) {
            val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(host, service).flattenToString())
            try {
                host.startActivity(detail)
                return
            } catch (_: ActivityNotFoundException) { /* Some Settings apps only implement the list. */ }
        }
        host.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }
}

class DeckAccessActivity : DeckActivity() {
    private var mediaGranted by mutableStateOf(false)

    private enum class SettingsError(val copy: Copy) {
        UNAVAILABLE(AccessCopy.SettingsUnavailable), RESTRICTED(AccessCopy.RestrictedSettings)
    }

    companion object {
        internal fun accessibilitySettings() = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

        fun intent(context: Context, command: DeckCommand): Intent {
            require(command == DeckCommand.MEDIA_ACCESS || command == DeckCommand.NAV_ACCESS)
            return Intent(context, DeckAccessActivity::class.java).putExtra("access", command.id)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val access = when (DeckCommand.fromId(intent.getStringExtra("access").orEmpty())) {
            DeckCommand.MEDIA_ACCESS -> DeckAccess.Media
            DeckCommand.NAV_ACCESS -> DeckAccess.Navigation
            else -> { finish(); return }
        }
        page({ access.title }) {
            var error by rememberSaveable { mutableStateOf<SettingsError?>(null) }
            DeckAccessScreen(access, error?.copy?.text().orEmpty(), mediaGranted = mediaGranted, onOpenSettings = {
                if (isUnlockedAndInteractive()) {
                    error = try {
                        if (access == DeckAccess.Media) ListenerAccess.MEDIA.open(this)
                        else startActivity(accessibilitySettings())
                        null
                    } catch (_: ActivityNotFoundException) { SettingsError.UNAVAILABLE }
                    catch (_: SecurityException) { SettingsError.RESTRICTED }
                }
            }, onDone = ::finish)
        }
    }

    override fun onResume() {
        super.onResume()
        if (intent.getStringExtra("access") == DeckCommand.MEDIA_ACCESS.id) mediaGranted = hasMediaSessionAccess()
    }

}
