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

    @Test fun teamsFallbackMovesDownWithoutMovingTheCaptionOrCustomIcon() {
        val face = TeamsState(installed = true).tile(DeckCommand.TEAMS_OPEN)
        val bitmap = ImageBitmap(118, 118)
        Canvas(bitmap).paintKeyFace(0, face) { text, _ -> text.center }
        val pixels = bitmap.toPixelMap()
        // Above the conversation outline is clear; its horizontal top stroke crosses y=21.
        assertEquals(0f, pixels[59, 17].alpha, 0f)
        assertTrue(pixels[59, 21].alpha > .9f)
        fun captions(tile: KeyTile) = buildList {
            Canvas(ImageBitmap(118, 118)).paintKeyFace(0, tile) { text, _ -> add(text); text.center }
        }
        val other = face.copy(glyph = IntegrationApp.ONENOTE)
        assertEquals(captions(other), captions(face))
        assertEquals(captions(other.copy(icon = "*")), captions(face.copy(icon = "*")))
    }

    @Test fun teamsHomeCentersVisiblePixelsAboveTheRenderedLabel() = runDesktopComposeUiTest(150, 150) {
        val key = defaultDeck().homeProjection(0).activePage.keys.single { it.action?.targetProfileId() == TEAMS }
        val tiles = listOf(key.present(null, key.label, "", true, onHome = true),
            key.copy(label = "A longer profile name").present(null, "", "Disabled", false, onHome = true))
        // The same mark with radically different transparent margins must land in the same place.
        val icons = listOf(0f to 3f, 36f to 42f).map { (x, y) -> ImageBitmap(64, 64).also {
            Canvas(it).drawRect(x, y, x + 24f, y + 16f, Paint().apply { color = Color.Cyan })
        } }
        val images = mutableListOf<Pair<ImageBitmap, Int>>()
        setContent {
            images.clear()
            for (tile in tiles) for (icon in icons + listOf(null)) {
                CompositionLocalProvider(LocalIntegrationIcons provides
                    (icon?.let { mapOf(IntegrationApp.TEAMS to it) } ?: emptyMap())) {
                    images += rememberKeyPixels(0, tile) to tile.background
                }
            }
        }
        runOnIdle {
            for ((image, background) in images) {
                val pixels = image.toPixelMap()
                fun ink(x: Int, y: Int) = pixels[x, y].toArgb() != background
                val rows = (0 until pixels.height).filter { y -> (0 until pixels.width).any { x -> ink(x, y) } }
                val (markBottom, labelTop) = rows.zipWithNext().first { (a, b) -> b > a + 1 }
                val xs = (0 until pixels.width).filter { x -> (rows.first()..markBottom).any { y -> ink(x, y) } }
                assertEquals(58.5, (xs.first() + xs.last()) / 2.0, 1.0)
                assertEquals(rows.first().toDouble(), (labelTop - markBottom - 1).toDouble(), 3.0)
            }
        }
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
