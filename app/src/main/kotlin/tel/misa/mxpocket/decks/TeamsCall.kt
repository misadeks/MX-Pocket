package tel.misa.mxpocket.decks

import tel.misa.mxpocket.i18n.KeypadCopy as K

import android.content.Context
import android.os.Build
import tel.misa.mxpocket.isUnlockedAndInteractive
import tel.misa.mxpocket.PocketNotificationListener

/** Ephemeral identity, never displayed, persisted, exported or logged. */
internal class TeamsCall(private val app: Context) {
    val access = TeamsAccess(app)
    private var owner: PocketNotificationListener? = null
    private var notice: TeamsObservation.Notice? = null
    private var boundary = -1L
    private var revision = -1L
    private var confirmedOngoing = false
    private var waitingForChange: Boolean? = null
    private var window: Int? = null
    private var toolbarService: TeamsToolbarService? = null
    var state = TeamsState()
        private set

    fun forgetToolbar() {
        confirmedOngoing = false
        waitingForChange = null
        window = null
        toolbarService = null
    }

    fun refresh(physical: Boolean, visible: Boolean, installed: Boolean, pluginEnabled: Boolean): TeamsState {
        val blocked = when {
            !pluginEnabled -> CallStatus.DISABLED
            !physical -> CallStatus.NO_KEYPAD
            Build.VERSION.SDK_INT < 34 -> CallStatus.UNSUPPORTED_OS
            !app.isUnlockedAndInteractive() -> CallStatus.LOCKED
            !access.enabled -> CallStatus.OFF
            !installed -> CallStatus.NOT_INSTALLED
            !access.granted -> CallStatus.NEEDS_ACCESS
            PocketNotificationListener.current == null -> CallStatus.DISCONNECTED
            else -> null
        }
        val listener = PocketNotificationListener.current.takeIf { blocked == null }
        if (owner !== listener) owner?.forgetTeams()
        val observation = listener?.teamsSnapshot() ?: TeamsObservation.Unavailable
        val currentNotice = observation as? TeamsObservation.Notice
        // The posting user is available on notifications, but not on accessibility nodes.
        val toolbarGranted = currentNotice != null && access.toolbarGranted
        val toolbarBlocked = when {
            currentNotice == null -> null
            !access.toolbarProfileEligible -> CallStatus.PROFILE_NOTICE
            !toolbarGranted -> CallStatus.TOOLBAR_OFF
            TeamsToolbarService.current == null -> CallStatus.TOOLBAR_DISCONNECTED
            else -> null
        }
        val service = TeamsToolbarService.current.takeIf { currentNotice != null && toolbarBlocked == null }
        val toolbar = service?.inspect()?.first
        val newCall = owner !== listener || notice?.key != currentNotice?.key ||
            notice?.typedOngoing != currentNotice?.typedOngoing || boundary != (listener?.teamsBoundary ?: -1L)
        if (newCall || service == null || toolbarService !== service) {
            confirmedOngoing = false
            waitingForChange = null
        }
        // Only an explicitly ongoing notice can maintain a previously confirmed call in the
        // background. An untyped call notice requires the joined controls on every refresh.
        if (toolbar != null && currentNotice?.typedOngoing == true) confirmedOngoing = true
        val muted = toolbar?.muted
        if (muted != null && muted != waitingForChange) waitingForChange = null
        val status = blocked ?: toolbarBlocked ?: when {
            toolbar != null || confirmedOngoing -> CallStatus.JOINED
            currentNotice != null -> if (currentNotice.typedOngoing) CallStatus.NOTIFICATION else CallStatus.UNTYPED
            observation == TeamsObservation.None -> CallStatus.NO_SIGNAL
            observation == TeamsObservation.Incoming -> CallStatus.INCOMING
            observation == TeamsObservation.Screening -> CallStatus.SCREENING
            else -> CallStatus.UNAVAILABLE
        }
        val next = TeamsState(installed, status, muted.takeIf { waitingForChange == null }, state.generation, visible)
        val currentRevision = listener?.teamsRevision ?: -1L
        val changed = newCall || notice != currentNotice || revision != currentRevision ||
            toolbarService !== service || window != toolbar?.window || next != state
        state = if (changed) next.copy(generation = state.generation + 1) else next
        owner = listener
        toolbarService = service
        boundary = listener?.teamsBoundary ?: -1L
        notice = currentNotice
        revision = currentRevision
        window = toolbar?.window
        return state
    }

    fun pressMic(expected: TeamsState, authorized: () -> Boolean): DeckResult {
        fun eligible(): Boolean {
            if (access.toolbarProfileEligible) return true
            forgetToolbar()
            return false
        }
        val service = toolbarService
        if (expected != state || !expected.joined || expected.muted == null || service == null || !authorized() ||
            !access.enabled || !access.granted || !access.toolbarGranted || !eligible())
            return unavailable(K.ChangedCallControls.text())
        val sent = service.inspect { toolbar ->
            if (!authorized()) return@inspect false
            val current = PocketNotificationListener.current
            val call = current?.teamsSnapshot() as? TeamsObservation.Notice
            current != null && current === owner && current.teamsBoundary == boundary &&
                current.teamsRevision == revision && call != null && call == notice && expected == state &&
                service === TeamsToolbarService.current &&
                toolbar.muted == expected.muted && toolbar.window == window && waitingForChange == null &&
                access.enabled && access.granted && access.toolbarGranted && eligible()
        }.second
        if (!sent) return unavailable(K.UseMeetingMic.text())
        waitingForChange = expected.muted
        return ok(K.MicRequested.text())
    }
}
