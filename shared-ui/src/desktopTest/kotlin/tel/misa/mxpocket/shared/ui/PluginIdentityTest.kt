package tel.misa.mxpocket.shared.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.*
import androidx.compose.ui.test.*
import org.junit.Assert.*
import org.junit.Test
import tel.misa.mxpocket.actions.ActionCall
import tel.misa.mxpocket.deck.*
import tel.misa.mxpocket.decks.*
import tel.misa.mxpocket.plugins.*

@OptIn(ExperimentalTestApi::class)
class PluginIdentityTest {
    @Test fun everyFactoryProfileHasDistinctIdentityAndReadableInkInBothThemes() {
        val profiles = defaultDeck().profiles
        val neutral = PocketStyle.accent("custom")
        val accents = profiles.map { PocketStyle.accent(it.id) }
        assertEquals(profiles.size, accents.distinctBy { it.tint }.size)
        assertEquals(profiles.size, accents.distinctBy { it.fill }.size)
        assertFalse(neutral in accents)
        assertEquals(neutral, PocketStyle.accent("work"))
        assertEquals("Work", profiles.single { it.id == "teams" }.pages.first().name)
        for (accent in accents + neutral) {
            assertTrue((Color(accent.tint).luminance() + .05f) / (Color(accent.tile).luminance() + .05f) >= 4.5f)
            val lightTile = Color(accent.tint).copy(alpha = .12f).compositeOver(Color.White)
            assertTrue((lightTile.luminance() + .05f) / (Color(accent.fill).luminance() + .05f) >= 4.5f)
        }
    }

    @Test fun launcherIdentityFollowsBindingNotLabelAndLeavesKeyOverridesAndCallStatusAlone() {
        for (app in IntegrationApp.entries) {
            val id = app.name.lowercase()
            val binding = DeckKey(action = ActionCall(DeckActions.PROFILE, mapOf(DeckActions.PROFILE_ID to id)))
            val natural = binding.present(null, "My profile", "", true)
            assertEquals(app, natural.glyph)
            assertEquals(PocketStyle.accent(id).fill, natural.background)
            val user = binding.copy(icon = "*", color = Color.Red.toArgb(), label = "Custom")
                .present(null, "Ignored", "", true)
            assertEquals("*", user.icon)
            assertEquals("Custom", user.label)
            assertEquals(Color.Red.toArgb(), user.background)
        }
        val open = TeamsState(installed = true).tile(DeckCommand.TEAMS_OPEN)
        assertEquals(IntegrationApp.TEAMS, open.glyph)
        assertEquals(open.glyph, teamsProfile().pages.first().keys.first().bindingPreview()?.glyph)
        assertTrue(open.enabled)
        assertFalse(TeamsState().tile(DeckCommand.TEAMS_OPEN).enabled)
        assertNull(TeamsState().tile(DeckCommand.TEAMS_STATUS).glyph)
        val oneNote = DeckKey(action = ActionCall("phone.open", mapOf("app" to "ONENOTE")))
        assertEquals(IntegrationApp.ONENOTE, oneNote.bindingGlyph())
        assertEquals(PocketStyle.accent("onenote").fill, oneNote.present(null, "Open OneNote", "", true).background)
        assertNull(oneNote.copy(action = ActionCall("phone.open", mapOf("app" to "CAMERA"))).bindingGlyph())
    }

    @Test fun unavailablePluginHasANamedDisabledSwitchWithInstalledOrFallbackIdentity() {
        for (theme in listOf(ThemeMode.DARK, ThemeMode.LIGHT)) runDesktopComposeUiTest(600, 610) {
            val installed = ImageBitmap(32, 32).also {
                Canvas(it).drawRect(0f, 0f, 32f, 32f, Paint().apply { color = Color.Cyan })
            }
            val state = PocketScreenState("", "", emptyList(), emptyList(), "")
            val plugins = listOf("teams" to "Teams", "onenote" to "OneNote").map { (id, title) ->
                PluginItem(id, title, false, "Android only", "Unavailable on Windows.", false, false, null, canToggle = false)
            }
            setContent {
                CompositionLocalProvider(LocalIntegrationIcons provides mapOf(IntegrationApp.TEAMS to installed)) {
                    PocketTheme(theme) { PocketWorkspace(WorkspaceTab.PLUGINS, {}, state, plugins, {}, {}, {}) }
                }
            }
            plugins.forEach { onNodeWithContentDescription(it.title).assertIsNotEnabled().assertIsOff() }
            onAllNodesWithText("Unavailable on Windows.").assertCountEquals(2)
            onNodeWithText("Make default profile").assertDoesNotExist()
            val pixels = onRoot().captureToImage().toPixelMap()
            val colors = buildSet { for (y in 0 until pixels.height) for (x in 0 until pixels.width) add(pixels[x, y].toArgb()) }
            assertTrue(Color.Cyan.toArgb() in colors)
            val fallback = PocketStyle.accent("onenote")
            assertTrue((if (theme == ThemeMode.DARK) fallback.tint else fallback.fill) in colors)
        }
    }
}
