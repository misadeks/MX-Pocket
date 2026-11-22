package tel.misa.mxpocket.decks

import android.content.ActivityNotFoundException
import android.os.Build
import android.os.Bundle
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import tel.misa.mxpocket.i18n.AccessCopy
import tel.misa.mxpocket.i18n.Copy
import tel.misa.mxpocket.isUnlockedAndInteractive
import tel.misa.mxpocket.PocketApplication
import tel.misa.mxpocket.NotificationAccess
import tel.misa.mxpocket.shared.ui.*

class TeamsAccessActivity : DeckActivity() {
    private val access by lazy { TeamsAccess(this) }
    private val session get() = (application as PocketApplication).session
    private var problem by mutableStateOf<Copy?>(null)
    private var state by mutableStateOf(TeamsSetupState(false, false, false, false))
    private fun refresh() {
        state = TeamsSetupState(access.enabled, access.granted, access.toolbarGranted,
            Build.VERSION.SDK_INT >= 34, access.singleProfile)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page({ AccessCopy.TeamsControls.text() }) {
            val deck by session.deckState.collectAsState()
            TeamsSetupScreen(state.copy(message = problem?.text().orEmpty()), deck.teams.status, ::act)
        }
    }
    override fun onResume() { super.onResume(); refresh() }
    private fun act(action: TeamsSetupAction) {
        if (action == TeamsSetupAction.DONE) { finish(); return }
        if (!isUnlockedAndInteractive()) return
        when (action) {
            TeamsSetupAction.TOGGLE -> {
                access.enabled = !access.enabled && state.supported
                TeamsEvents.refresh?.invoke()
            }
            TeamsSetupAction.NOTIFICATIONS, TeamsSetupAction.TOOLBAR -> try {
                val notifications = action == TeamsSetupAction.NOTIFICATIONS
                if (notifications) NotificationAccess(this).open(this)
                else startActivity(DeckAccessActivity.accessibilitySettings())
                problem = null
            } catch (_: ActivityNotFoundException) { problem = AccessCopy.TeamsSettingsUnavailable }
            catch (_: SecurityException) { problem = AccessCopy.RestrictedSettings }
            TeamsSetupAction.DONE -> Unit
        }
        refresh()
    }
}
