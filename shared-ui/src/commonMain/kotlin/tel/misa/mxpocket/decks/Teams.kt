package tel.misa.mxpocket.decks

import tel.misa.mxpocket.actions.ActionCall
import tel.misa.mxpocket.deck.*
import tel.misa.mxpocket.plugins.DeckGlyph
import tel.misa.mxpocket.plugins.KeyTile
import tel.misa.mxpocket.plugins.legend
import tel.misa.mxpocket.shared.ui.PocketStyle
import tel.misa.mxpocket.decks.DeckCommand as C

const val TEAMS = "teams"
const val TEAMS_PACKAGE = "com.microsoft.teams"
const val IN_CALL = "in-call"
val teamsSlots = listOf(C.TEAMS_CAPTIONS, C.TEAMS_HAND, C.TEAMS_CHAT, C.TEAMS_QUIETER,
    C.TEAMS_MIC, C.TEAMS_LOUDER, C.TEAMS_OPEN, C.TEAMS_STATUS, C.TEAMS_ACCESS)

enum class CallStatus(val label: String, val footer: String, val detail: String) {
    NO_KEYPAD("No keypad", "Connect USB", "Connect and authorize the wired keypad. A virtual keypad cannot observe calls."),
    DISABLED("Plugin off", "Plugins", "Enable the Teams plugin to use call status."),
    UNSUPPORTED_OS("Android 14+", "Required", "Call status needs Android 14 or later."),
    PROFILE_NOTICE("Call notice", "This profile", "Teams posted an ongoing call notification in this Android profile. With multiple profiles, MX Pocket cannot verify the call or operate the mic."),
    NOT_INSTALLED("No Teams", "This profile", "No launchable Teams app was found in this Android profile."),
    LOCKED("Locked", "Unlock phone", "Unlock the phone to resume call observation."),
    OFF("Status off", "Teams setup", "Teams call status is off. No calls are being observed."),
    NEEDS_ACCESS("Access off", "Teams setup", "Notification access for MX Pocket Teams calls is off. Audio and Navigation access do not enable Teams."),
    DISCONNECTED("Not ready", "Teams setup", "Teams notification access is on, but the listener is not connected. Check the service in Android settings."),
    TOOLBAR_OFF("Call notice", "Teams setup", "Teams posted an ongoing call notification. The Teams accessibility service is off; it is optional for read-only status."),
    TOOLBAR_DISCONNECTED("Call notice", "Not connected", "Teams posted an ongoing call notification. Accessibility access is on, but its service is not connected."),
    UNAVAILABLE("Unknown", "Use Teams", "No unique call signal is available. Check the call in Teams."),
    UNTYPED("Call notice", "Type unknown", "Teams posted a call notification without a supported call type. Check the call in Teams."),
    INCOMING("Incoming", "Use Teams", "Teams reports an incoming call, not a joined call."),
    SCREENING("Screening", "Use Teams", "Teams reports call screening, not a joined call."),
    NO_SIGNAL("No signal", "Check filters", "No eligible ongoing Teams call notification is visible. This does not mean there is no call."),
    NOTIFICATION("Call notice", "Show controls", "Teams posted an ongoing call notification. Open the meeting on this phone and show its controls to confirm the call."),
    JOINED("In call", "This phone", "A local Teams call was confirmed on this phone.");
}

/** Only display data crosses into rendering. The generation is local, never a notification or meeting ID. */
data class TeamsState(val installed: Boolean = false, val status: CallStatus = CallStatus.NO_KEYPAD,
    val muted: Boolean? = null, val generation: Long = 0, val setupAvailable: Boolean = true) {
    val joined get() = status == CallStatus.JOINED
    fun tile(command: C): KeyTile {
        val (label, footer) = when (command) {
            C.TEAMS_MIC -> when {
                !joined || muted == null -> "Mic unavailable" to "Use Teams"
                muted -> "Unmute mic" to "Mic muted"
                else -> "Mute mic" to "Mic on"
            }
            C.TEAMS_STATUS -> status.label to status.footer
            C.TEAMS_ACCESS -> command.label to if (setupAvailable) "On phone" else "Open MX Pocket"
            C.TEAMS_OPEN -> command.label to if (installed) "This phone" else "Not installed"
            else -> command.label to "Use Teams"
        }
        val available = when (command) {
            C.TEAMS_OPEN -> installed
            C.TEAMS_ACCESS -> setupAvailable
            C.TEAMS_STATUS -> true
            C.TEAMS_MIC -> joined && muted != null
            else -> false
        }
        return KeyTile(label, footer, PocketStyle.accent(TEAMS).fill, enabled = available, primary = command == C.TEAMS_MIC,
            inputToken = "${command.id}:$generation", readOnly = command == C.TEAMS_STATUS,
            glyph = if (command == C.TEAMS_ACCESS) DeckGlyph.SETTINGS else command.legend())
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
