package tel.misa.mxpocket.decks

import tel.misa.mxpocket.i18n.KeypadCopy as K

import android.content.Context
import android.os.Build
import tel.misa.mxpocket.isUnlockedAndInteractive

/** Ephemeral identity, never displayed, persisted, exported or logged. */
internal class TeamsCall(private val app: Context) {
    val access = TeamsAccess(app)
    private var owner: TeamsCallListener? = null
    private var key: String? = null
    private var boundary = -1L
    private var posted = -1L
    private var confirmed = false
    private var waitingForChange: Boolean? = null
    private var window: Int? = null
    private var toolbarService: TeamsToolbarService? = null
    var state = TeamsState()
        private set

    fun refresh(physical: Boolean, visible: Boolean, installed: Boolean): TeamsState {
        val allowed = physical && installed && access.enabled && Build.VERSION.SDK_INT >= 34 &&
            app.isUnlockedAndInteractive() && access.granted && access.toolbarGranted && access.singleProfile
        val listener = TeamsCallListener.current.takeIf { allowed }
        if (owner !== listener) owner?.forget()
        val observation = listener?.snapshot() ?: TeamsObservation.Unavailable
        val ongoing = observation as? TeamsObservation.Ongoing
        val service = TeamsToolbarService.current.takeIf { ongoing != null }
        val toolbar = service?.inspect()?.first
        val newCall = owner !== listener || key != ongoing?.key || boundary != (listener?.boundary ?: -1L)
        if (newCall || service == null || toolbarService !== service) {
            confirmed = false
            waitingForChange = null
        }
        // A template is publisher-reported, not proof on its own. Require the phone's joined
        // controls before trusting it. It can then maintain the same call while backgrounded.
        if (toolbar != null) confirmed = true
        val muted = toolbar?.muted
        if (muted != null && muted != waitingForChange) waitingForChange = null
        val next = TeamsState(installed, observation != TeamsObservation.Unavailable && TeamsToolbarService.current != null,
            confirmed, muted.takeIf { waitingForChange == null }, state.generation, visible)
        val stamp = ongoing?.posted ?: -1L
        val changed = newCall || stamp != posted || toolbarService !== service || window != toolbar?.window || next != state
        state = if (changed) next.copy(generation = state.generation + 1) else next
        owner = listener
        toolbarService = service
        boundary = listener?.boundary ?: -1L
        key = ongoing?.key
        posted = stamp
        window = toolbar?.window
        return state
    }

    fun pressMic(expected: TeamsState, authorized: () -> Boolean): DeckResult {
        val service = toolbarService
        if (expected != state || !expected.joined || expected.muted == null || service == null || !authorized())
            return unavailable(K.ChangedCallControls.text())
        val sent = service.inspect { toolbar ->
            val current = TeamsCallListener.current
            val call = current?.snapshot() as? TeamsObservation.Ongoing
            current != null && current === owner && current.boundary == boundary && call != null &&
                call.key == key && call.posted == posted && service === TeamsToolbarService.current &&
                toolbar.muted == expected.muted && toolbar.window == window && waitingForChange == null &&
                access.enabled && access.granted && access.toolbarGranted && access.singleProfile && authorized()
        }.second
        if (!sent) return unavailable(K.UseMeetingMic.text())
        waitingForChange = expected.muted
        return ok(K.MicRequested.text())
    }
}
