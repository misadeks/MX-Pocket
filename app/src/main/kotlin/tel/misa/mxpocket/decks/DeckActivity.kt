package tel.misa.mxpocket.decks

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import tel.misa.mxpocket.i18n.AccessCopy
import tel.misa.mxpocket.i18n.Copy
import tel.misa.mxpocket.isUnlockedAndInteractive
import tel.misa.mxpocket.NotificationAccess
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

class DeckAccessActivity : DeckActivity() {
    private val notifications by lazy { NotificationAccess(this) }
    private var listenerGranted by mutableStateOf(false)
    private var music by mutableStateOf(false)

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
            DeckAccessScreen(access, error?.copy?.text().orEmpty(), listenerGranted = listenerGranted,
                musicEnabled = music, onToggleMusic = {
                    if (access == DeckAccess.Media && isUnlockedAndInteractive()) {
                        notifications.music = !notifications.music
                        music = notifications.music
                    }
                }, onOpenSettings = {
                if (isUnlockedAndInteractive()) {
                    error = try {
                        if (access == DeckAccess.Media) notifications.open(this)
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
        if (intent.getStringExtra("access") == DeckCommand.MEDIA_ACCESS.id) {
            listenerGranted = notifications.granted
            music = notifications.music
        }
    }

}
