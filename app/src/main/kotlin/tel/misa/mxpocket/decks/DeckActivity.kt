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
import tel.misa.mxpocket.isUnlockedAndInteractive
import tel.misa.mxpocket.shared.ui.DeckAccess
import tel.misa.mxpocket.shared.ui.DeckAccessScreen
import tel.misa.mxpocket.shared.ui.PocketTheme

abstract class DeckActivity : ComponentActivity() {
    protected fun page(heading: String, content: @Composable () -> Unit) {
        title = heading
        setContent { PocketTheme(content = content) }
    }
}

class DeckAccessActivity : DeckActivity() {
    companion object {
        internal fun settings(media: Boolean) =
            Intent(if (media) Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS else Settings.ACTION_ACCESSIBILITY_SETTINGS)

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
        page(access.title) {
            var message by rememberSaveable { mutableStateOf("") }
            DeckAccessScreen(access, message, onOpenSettings = {
                if (isUnlockedAndInteractive()) {
                    message = try {
                        startActivity(settings(access == DeckAccess.Media))
                        ""
                    } catch (_: ActivityNotFoundException) { "These settings are unavailable on this device." }
                    catch (_: SecurityException) { "Android restricted these settings." }
                }
            }, onDone = ::finish)
        }
    }
}
