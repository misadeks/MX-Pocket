package tel.misa.mxpocket.decks

import tel.misa.mxpocket.actions.ActionCall
import tel.misa.mxpocket.deck.*
import tel.misa.mxpocket.plugins.DeckGlyph
import tel.misa.mxpocket.plugins.KeyTile
import tel.misa.mxpocket.i18n.KeypadCopy as K
import tel.misa.mxpocket.decks.DeckCommand as C

const val TEAMS = "teams"
const val IN_CALL = "in-call"
val teamsSlots = listOf(C.TEAMS_CAPTIONS, C.TEAMS_HAND, C.TEAMS_CHAT, C.TEAMS_QUIETER,
    C.TEAMS_MIC, C.TEAMS_LOUDER, C.TEAMS_OPEN, C.TEAMS_STATUS, C.TEAMS_ACCESS)

/** Only display data crosses into rendering. The generation is local, never a notification or meeting ID. */
data class TeamsState(val installed: Boolean = false, val observed: Boolean = false, val joined: Boolean = false,
    val muted: Boolean? = null, val generation: Long = 0, val setupAvailable: Boolean = true) {
    fun tile(command: C): KeyTile {
        val (label, footer) = when (command) {
            C.TEAMS_MIC -> when {
                !joined || muted == null -> K.MicUnavailable.text() to K.UseTeams.text()
                muted -> K.UnmuteMic.text() to K.MicMuted.text()
                else -> K.MuteMic.text() to K.MicOn.text()
            }
            C.TEAMS_STATUS -> command.displayLabel to when {
                joined -> K.InCall
                observed -> K.NoConfirmedCall
                else -> K.StateUnavailable
            }.text()
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

fun teamsProfile(includeFocus: Boolean = true): DeckProfile {
    fun command(c: C) = DeckKey(action = ActionCall(c.id))
    fun page(label: String, id: String) = DeckKey(label = label, action = ActionCall(DeckActions.PAGE, mapOf(DeckActions.PAGE_ID to id)))
    fun slot(i: Int) = DeckKey(action = ActionCall("plugin.key", mapOf("pluginId" to TEAMS, "index" to "$i")))
    val work = DeckPage("root", "Work", listOf(command(C.TEAMS_OPEN),
        DeckKey(label = "My chat", enabled = false), DeckKey(label = "Meeting link", enabled = false),
        command(C.REVERSE), command(C.ACTIVATE), command(C.FORWARD),
        if (includeFocus) DeckKey(label = "Focus timer", action = ActionCall(DeckActions.PROFILE, mapOf(DeckActions.PROFILE_ID to "focus")))
        else DeckKey(), page("Call tools", IN_CALL), slot(8)))
    val call = DeckPage(IN_CALL, "In call", (0..7).map(::slot) + page("Back to Work", "root"))
    return DeckProfile(TEAMS, "Teams", work.id, listOf(work, call))
}
