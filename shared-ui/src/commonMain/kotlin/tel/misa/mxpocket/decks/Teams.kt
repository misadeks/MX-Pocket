package tel.misa.mxpocket.decks

import tel.misa.mxpocket.actions.ActionCall
import tel.misa.mxpocket.deck.*
import tel.misa.mxpocket.plugins.DeckGlyph
import tel.misa.mxpocket.plugins.KeyTile
import tel.misa.mxpocket.i18n.KeypadCopy as K
import tel.misa.mxpocket.i18n.Copy
import tel.misa.mxpocket.i18n.TeamsCallCopy as T
import tel.misa.mxpocket.decks.DeckCommand as C

const val TEAMS = "teams"
const val TEAMS_PACKAGE = "com.microsoft.teams"
const val IN_CALL = "in-call"
val teamsSlots = listOf(C.TEAMS_CAPTIONS, C.TEAMS_HAND, C.TEAMS_CHAT, C.TEAMS_QUIETER,
    C.TEAMS_MIC, C.TEAMS_LOUDER, C.TEAMS_OPEN, C.TEAMS_STATUS, C.TEAMS_ACCESS)

enum class CallStatus(private val title: Copy, private val hint: Copy, private val explanation: Copy) {
    NO_KEYPAD(T.NoKeypad, T.ConnectUsb, T.NoKeypadDetail),
    DISABLED(T.PluginOff, T.Plugins, T.DisabledDetail),
    UNSUPPORTED_OS(T.Android14, T.Required, T.UnsupportedOsDetail),
    PROFILE_NOTICE(T.CallNotice, T.ThisProfile, T.ProfileNoticeDetail),
    NOT_INSTALLED(T.NoTeams, T.ThisProfile, T.NotInstalledDetail),
    LOCKED(T.Locked, T.UnlockPhone, T.LockedDetail),
    OFF(T.StatusOff, K.TeamsSetup, T.OffDetail),
    NEEDS_ACCESS(K.AccessOff, K.TeamsSetup, T.NeedsAccessDetail),
    DISCONNECTED(T.NotReady, K.TeamsSetup, T.DisconnectedDetail),
    TOOLBAR_OFF(T.CallNotice, K.TeamsSetup, T.ToolbarOffDetail),
    TOOLBAR_DISCONNECTED(T.CallNotice, T.NotConnected, T.ToolbarDisconnectedDetail),
    UNAVAILABLE(T.Unknown, K.UseTeams, T.UnavailableDetail),
    UNTYPED(T.CallNotice, T.TypeUnknown, T.UntypedDetail),
    INCOMING(T.Incoming, K.UseTeams, T.IncomingDetail),
    SCREENING(T.Screening, K.UseTeams, T.ScreeningDetail),
    NO_SIGNAL(T.NoSignal, T.CheckFilters, T.NoSignalDetail),
    NOTIFICATION(T.CallNotice, T.ShowControls, T.NotificationDetail),
    JOINED(K.InCall, K.ThisPhone, T.JoinedDetail);

    val label get() = title.text()
    val footer get() = hint.text()
    val detail get() = explanation.text()
}

/** Only display data crosses into rendering. The generation is local, never a notification or meeting ID. */
data class TeamsState(val installed: Boolean = false, val status: CallStatus = CallStatus.NO_KEYPAD,
    val muted: Boolean? = null, val generation: Long = 0, val setupAvailable: Boolean = true) {
    val joined get() = status == CallStatus.JOINED
    fun tile(command: C): KeyTile {
        val (label, footer) = when (command) {
            C.TEAMS_MIC -> when {
                !joined || muted == null -> K.MicUnavailable.text() to K.UseTeams.text()
                muted -> K.UnmuteMic.text() to K.MicMuted.text()
                else -> K.MuteMic.text() to K.MicOn.text()
            }
            C.TEAMS_STATUS -> status.label to status.footer
            C.TEAMS_ACCESS -> command.displayLabel to (if (setupAvailable) K.OnPhone else K.OpenPocket).text()
            C.TEAMS_OPEN -> command.displayLabel to (if (installed) K.ThisPhone else K.NotInstalled).text()
            else -> command.displayLabel to K.UseTeams.text()
        }
        val available = when (command) {
            C.TEAMS_OPEN -> installed
            C.TEAMS_ACCESS -> setupAvailable
            C.TEAMS_STATUS -> true
            C.TEAMS_MIC -> joined && muted != null
            else -> false
        }
        return KeyTile(label, footer, 0xff393968.toInt(), enabled = available, primary = command == C.TEAMS_MIC,
            inputToken = "${command.id}:$generation", readOnly = command == C.TEAMS_STATUS,
            glyph = DeckGlyph.SETTINGS.takeIf { command == C.TEAMS_ACCESS })
    }
}

fun teamsProfile(): DeckProfile {
    fun command(c: C) = DeckKey(action = ActionCall(c.id))
    fun page(label: String, id: String) = DeckKey(label = label, action = ActionCall(DeckActions.PAGE, mapOf(DeckActions.PAGE_ID to id)))
    fun slot(i: Int) = DeckKey(action = ActionCall("plugin.key", mapOf("pluginId" to TEAMS, "index" to "$i")))
    val work = DeckPage("root", "Work", listOf(command(C.TEAMS_OPEN),
        DeckKey(label = "My chat", enabled = false), DeckKey(label = "Meeting link", enabled = false),
        command(C.REVERSE), command(C.ACTIVATE), command(C.FORWARD),
        DeckKey(), page("Call tools", IN_CALL), slot(8)))
    val call = DeckPage(IN_CALL, "In call", (0..7).map(::slot) + page("Back to Work", "root"))
    return DeckProfile(TEAMS, "Teams", work.id, listOf(work, call))
}
