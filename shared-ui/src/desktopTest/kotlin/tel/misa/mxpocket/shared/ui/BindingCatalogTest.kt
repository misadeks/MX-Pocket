package tel.misa.mxpocket.shared.ui

import org.junit.Assert.*
import org.junit.Test
import tel.misa.mxpocket.actions.ActionCall
import tel.misa.mxpocket.deck.*
import tel.misa.mxpocket.decks.DeckCommand as C

class BindingCatalogTest {
    private val profiles = defaultDeck().visibleProfiles
    private val focus = profiles.single { it.id == "focus" }
    private val audio = profiles.single { it.id == "audio" }
    private val component = "example.app/.Main"
    private val global = globalBindings(profiles, listOf(component to "Music"))

    @Test fun slotsBelongToTheEditedProfileNotItsNameOrWhatItWasCopiedFrom() {
        val custom = focus.copy(id = "custom", pages = focus.pages + DeckPage("mix", "Mix"))
        fun options(profile: DeckProfile) = bindingsFor(profile, global, emptySet())
        val timer = options(focus.copy(name = "Work")).filter { it.call.slotOwner() != null }
        assertEquals(setOf("focus"), timer.map { it.call.slotOwner() }.toSet())
        assertEquals(setOf("0", "2", "3", "5", "6", "7", "8"), timer.map { it.call.params["index"] }.toSet())
        val mute = options(audio).single { it.call.slotOwner() != null }
        assertEquals("Toggle mute", mute.label)
        assertEquals("audio", mute.call.slotOwner())
        val customOptions = options(custom)
        assertFalse(customOptions.any { it.call.slotOwner() != null })
        assertEquals("Open page: Mix", customOptions.single { it.call.id == DeckActions.PAGE }.label)
        assertFalse(options(focus).any { it.call.id == DeckActions.PAGE })
        assertTrue(customOptions.any { it.call == ActionCall(DeckActions.PROFILE, mapOf("profileId" to "audio")) })
        assertEquals("Launch Music", customOptions.single { it.call == ActionCall(C.OPEN.id, mapOf("component" to component)) }.label)
        val disabled = bindingsFor(custom, global, setOf("audio", "launcher", "phone"))
        assertFalse(disabled.any { it.call.targetProfileId() == "audio" || it.call.id == C.OPEN.id ||
            it.call.id.startsWith("media.") || it.call.id == "phone.open" })
    }

    @Test fun editableIntentsExcludeFixedDemosReadoutsAndCommandsThatCannotWorkOnAnArbitraryKey() {
        val calls = profiles.flatMap { bindingsFor(it, global, emptySet()) }.map { it.call }.toSet()
        assertTrue(calls.containsAll(listOf(ActionCall(C.STUDIO.id), ActionCall("phone.open", mapOf("app" to "SETTINGS")),
            ActionCall("phone.open", mapOf("app" to "DIALER")), ActionCall("phone.open", mapOf("app" to "ALARMS")))))
        assertFalse(calls.any { it.id == "workflow.run" || it.slotOwner() in setOf("phone", "studio", "teams", "navigation", "onenote", GAME_PROFILE) })
        assertFalse(calls.any { it.id == "phone.open" && it.params["app"] in setOf("CAMERA", "WEB") })
        assertFalse(calls.any { call -> C.fromId(call.id)?.let { it.nativeControl || it.legacyCapture ||
            it.deck == "teams" && it !in setOf(C.TEAMS_OPEN, C.TEAMS_ACCESS) || it == C.VOLUME_LEVEL } == true })
        assertEquals(global.size, global.distinctBy { it.call }.size)
    }
}
