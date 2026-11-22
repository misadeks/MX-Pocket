package tel.misa.mxpocket.decks

import tel.misa.mxpocket.actions.ActionCall
import tel.misa.mxpocket.deck.*
import tel.misa.mxpocket.plugins.DeckGlyph
import tel.misa.mxpocket.plugins.KeyTile
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
                !joined || muted == null -> "Mic unavailable" to "Use Teams"
                muted -> "Unmute mic" to "Mic muted"
                else -> "Mute mic" to "Mic on"
            }
            C.TEAMS_STATUS -> "Call status" to when {
                joined -> "In call"
                observed -> "No confirmed call"
                else -> "State unavailable"
            }
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
