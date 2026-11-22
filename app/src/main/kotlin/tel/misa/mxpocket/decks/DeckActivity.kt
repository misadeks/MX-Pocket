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
import tel.misa.mxpocket.isUnlockedAndInteractive
import tel.misa.mxpocket.shared.ui.DeckAccess
import tel.misa.mxpocket.shared.ui.DeckAccessScreen
import tel.misa.mxpocket.shared.ui.PocketTheme

abstract class DeckActivity : ComponentActivity() {
    protected fun page(heading: String, content: @Composable () -> Unit) {
        title = heading
        setContent { PocketTheme(tel.misa.mxpocket.ui.ThemePreference.current(this), content) }
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
    private var message by mutableStateOf("")
    private var mediaGranted by mutableStateOf(false)

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
        message = savedInstanceState?.getString("message").orEmpty()
        page(access.title) {
            DeckAccessScreen(access, message, mediaGranted = mediaGranted, onOpenSettings = {
                if (isUnlockedAndInteractive()) {
                    message = try {
                        if (access == DeckAccess.Media) ListenerAccess.MEDIA.open(this)
                        else startActivity(accessibilitySettings())
                        ""
                    } catch (_: ActivityNotFoundException) { "These settings are unavailable on this device." }
                    catch (_: SecurityException) { "Android restricted these settings." }
                }
            }, onDone = ::finish)
        }
    }

    override fun onResume() {
        super.onResume()
        if (intent.getStringExtra("access") == DeckCommand.MEDIA_ACCESS.id) mediaGranted = hasMediaSessionAccess()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("message", message)
    }
}
