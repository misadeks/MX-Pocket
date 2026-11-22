package tel.misa.mxpocket.decks

import android.content.ActivityNotFoundException
import android.os.Build
import android.os.Bundle
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import tel.misa.mxpocket.isUnlockedAndInteractive
import tel.misa.mxpocket.PocketApplication
import tel.misa.mxpocket.shared.ui.*

class TeamsAccessActivity : DeckActivity() {
    private val access by lazy { TeamsAccess(this) }
    private val session get() = (application as PocketApplication).session
    private var state by mutableStateOf(TeamsSetupState(false, false, false, false))
    private fun refresh() {
        state = TeamsSetupState(access.enabled, access.granted, access.toolbarGranted,
            Build.VERSION.SDK_INT >= 34, access.singleProfile, state.message)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page("Teams call controls") {
            val deck by session.deckState.collectAsState()
            TeamsSetupScreen(state, deck.teams.status, ::act)
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
                if (notifications) ListenerAccess.TEAMS.open(this)
                else startActivity(DeckAccessActivity.accessibilitySettings())
            } catch (_: ActivityNotFoundException) { state = state.copy(message = "These settings are unavailable.") }
            catch (_: SecurityException) { state = state.copy(message = "Android restricted these settings.") }
            TeamsSetupAction.DONE -> Unit
        }
        refresh()
    }
}
