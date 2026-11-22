package tel.misa.mxpocket.decks

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

    fun refresh(physical: Boolean, visible: Boolean, installed: Boolean, pluginEnabled: Boolean): TeamsState {
        val blocked = when {
            !pluginEnabled -> CallStatus.DISABLED
            !physical -> CallStatus.NO_KEYPAD
            Build.VERSION.SDK_INT < 34 -> CallStatus.UNSUPPORTED_OS
            !app.isUnlockedAndInteractive() -> CallStatus.LOCKED
            !access.enabled -> CallStatus.OFF
            !installed -> CallStatus.NOT_INSTALLED
            !access.granted -> CallStatus.NEEDS_ACCESS
            TeamsCallListener.current == null -> CallStatus.DISCONNECTED
            else -> null
        }
        val listener = TeamsCallListener.current.takeIf { blocked == null }
        if (owner !== listener) owner?.forget()
        val observation = listener?.snapshot() ?: TeamsObservation.Unavailable
        val ongoing = observation as? TeamsObservation.Ongoing
        // The posting user is available on notifications, but not on accessibility nodes.
        val toolbarBlocked = when {
            ongoing == null -> null
            !access.singleProfile -> CallStatus.PROFILE_NOTICE
            !access.toolbarGranted -> CallStatus.TOOLBAR_OFF
            TeamsToolbarService.current == null -> CallStatus.TOOLBAR_DISCONNECTED
            else -> null
        }
        val service = TeamsToolbarService.current.takeIf { ongoing != null && toolbarBlocked == null }
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
        val status = blocked ?: toolbarBlocked ?: when {
            confirmed -> CallStatus.JOINED
            ongoing != null -> CallStatus.NOTIFICATION
            observation == TeamsObservation.None -> CallStatus.NO_SIGNAL
            observation == TeamsObservation.Untyped -> CallStatus.UNTYPED
            observation == TeamsObservation.Incoming -> CallStatus.INCOMING
            observation == TeamsObservation.Screening -> CallStatus.SCREENING
            else -> CallStatus.UNAVAILABLE
        }
        val next = TeamsState(installed, status, muted.takeIf { waitingForChange == null }, state.generation, visible)
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
        if (expected != state || !expected.joined || expected.muted == null || service == null || !authorized() ||
            !access.singleProfile)
            return unavailable("Call controls changed. Check the center key.")
        val sent = service.inspect { toolbar ->
            val current = TeamsCallListener.current
            val call = current?.snapshot() as? TeamsObservation.Ongoing
            current != null && current === owner && current.boundary == boundary && call != null &&
                call.key == key && call.posted == posted && service === TeamsToolbarService.current &&
                toolbar.muted == expected.muted && toolbar.window == window && waitingForChange == null &&
                access.enabled && access.granted && access.toolbarGranted && access.singleProfile && authorized()
        }.second
        if (!sent) return unavailable("Mic unavailable. Use the mic in Teams.")
        waitingForChange = expected.muted
        return ok("Mic requested. Waiting for Teams.")
    }
}
