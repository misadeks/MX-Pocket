package tel.misa.mxpocket.i18n

import tel.misa.mxpocket.deck.DeckKey
import tel.misa.mxpocket.deck.DeckPage
import tel.misa.mxpocket.deck.DeckProfile
import tel.misa.mxpocket.deck.PROFILE_HOME
import tel.misa.mxpocket.deck.TEAMS_INCOMING_VIEW
import tel.misa.mxpocket.deck.MORE_PROFILES
import tel.misa.mxpocket.deck.DeckActions
import tel.misa.mxpocket.deck.ACCESSIBILITY_PAGE
import tel.misa.mxpocket.decks.IN_CALL
import tel.misa.mxpocket.decks.TEAMS
import tel.misa.mxpocket.decks.teamsProfile
import tel.misa.mxpocket.i18n.KeypadCopy as K

private val deckNames = mapOf("focus" to K.Focus, "audio" to K.Audio, "phone" to K.Phone,
    "launcher" to K.Launcher, "studio" to K.Studio, "navigation" to K.Navigation,
    "game.xo" to K.TicTacToe, PROFILE_HOME to WorkspaceCopy.Home, TEAMS to K.Teams, TEAMS_INCOMING_VIEW to K.Teams)

fun builtInDeckName(id: String): String = if (id == "navigation") K.Navigate.text() else deckNames[id]?.text() ?: id

/** Native descriptors can contain a resource resolved before an in-app language switch. */
fun builtInPluginTitle(id: String, title: String): String {
    if (id == "navigation" && title in listOf(K.Navigation.en, K.AndroidNavigation.en)) return K.Navigate.text()
    val name = when (id) {
        "audio" -> K.MediaDeck
        "launcher" -> K.AppLauncher
        "studio" -> K.PocketStudio
        "navigation" -> K.AndroidNavigation
        else -> deckNames[id]
    } ?: return title
    return if (title == name.en || title == name.fr || title == name.sr) name.text() else title
}

private fun Copy?.ifFactoryValue(raw: String) = if (this != null && raw == en) text() else raw

private fun profileTitle(id: String?, name: String) = if (id == "navigation" && name == K.Navigation.en)
    K.Navigate.text() else deckNames[id].ifFactoryValue(name)

fun DeckProfile.displayName(): String = profileTitle(id, name)

fun DeckProfile.displayPageName(page: DeckPage): String = when {
    id == TEAMS && page.id == "root" -> K.Work
    id == TEAMS && page.id == IN_CALL -> K.InCall
    id == TEAMS_INCOMING_VIEW -> CallCopy.Incoming
    id == "navigation" && page.id == ACCESSIBILITY_PAGE -> K.Accessibility
    id == PROFILE_HOME || page.id == "root" -> deckNames[id]
    else -> null
}.ifFactoryValue(page.name)

private val teamsTemplate = teamsProfile()
private val teamsCaptions = listOf(K.MyChat, K.MeetingLink, K.FocusTimer, K.CallTools, K.BackToWork)

fun DeckProfile.displayKeyLabel(page: DeckPage, key: DeckKey): String {
    if (id == PROFILE_HOME) return when (key.action?.id) {
        MORE_PROFILES -> LauncherCopy.More.text()
        DeckActions.PROFILE -> profileTitle(key.action.params[DeckActions.PROFILE_ID], key.label)
        else -> key.label
    }
    if (id == "navigation" && page.id == ACCESSIBILITY_PAGE && page.keys[8] === key &&
        key.action?.id == DeckActions.ROOT && key.holdAction == null) return K.BackToNavigation.ifFactoryValue(key.label)
    if (id != TEAMS || key.label.isEmpty()) return key.label
    // Match the factory slot AND binding, not arbitrary user text with a familiar spelling.
    val slot = page.keys.indexOfFirst { it === key }.takeIf { it >= 0 }
        ?: page.keys.indices.singleOrNull { page.keys[it] == key } ?: return key.label
    val factory = teamsTemplate.pages.find { it.id == page.id }?.keys?.getOrNull(slot) ?: return key.label
    if (factory.label != key.label || factory.action != key.action || factory.holdAction != key.holdAction)
        return key.label
    return teamsCaptions.find { it.en == factory.label }.ifFactoryValue(key.label)
}
