package tel.misa.mxpocket.shared.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tel.misa.mxpocket.FocusTimer
import tel.misa.mxpocket.deck.*
import tel.misa.mxpocket.plugins.present

class WorkspaceWindowTest {
    @get:Rule val ui = createComposeRule()
    @get:Rule val temp = TemporaryFolder()

    @Test fun switchingOffTheEditedProfileHidesItsKeysButNotACustomNamesake() {
        val repo = DeckRepository(File(temp.root, "deck.json"))
        val custom = repo.duplicateProfile("audio", "Audio").getOrThrow()
        repo.renameProfile("audio", "My player").getOrThrow()
        repo.selectProfile("audio").getOrThrow()
        val store = repo.asEditorStore()
        ui.setContent {
            CompositionLocalProvider(LocalDensity provides Density(.75f)) {
                Box(Modifier.requiredWidth(900.dp)) { PocketTheme {
                    LayoutEditor(store, availableFactoryKeyBindings(repo.state.collectAsState().value.disabledPluginIds),
                        emptyMap(), {}, {})
                } }
            }
        }
        ui.onNodeWithText("Swap keys").performScrollTo().performClick()
        ui.onNodeWithContentDescription("Key 1,", substring = true).performClick()
        ui.onNodeWithTag("key-label").assertExists()
        ui.runOnIdle { repo.setDisabledPlugins(setOf("audio")) }
        ui.onNodeWithTag("key-label").assertDoesNotExist()
        ui.onNodeWithText("My player", substring = true).assertDoesNotExist()
        ui.onNodeWithText("Tic Tac Toe", substring = true).assertDoesNotExist()
        ui.onNodeWithText("Audio").performClick()
        ui.waitUntil(5_000) { repo.state.value.activeProfileId == custom }
        ui.onNodeWithText("Swap keys").performScrollTo().performClick()
        ui.onNodeWithContentDescription("Key 1,", substring = true).performClick()
        ui.onNodeWithTag("choose-binding").performClick()
        ui.onNodeWithText("Audio key 1").assertDoesNotExist()
    }

    @Test fun resizingAcrossNavigationRailPreservesTheOpenEditorAndNeverRunsAKey() {
        val repo = DeckRepository(File(temp.root, "deck.json"))
        val store = repo.asEditorStore()
        var width by mutableStateOf(380.dp)
        var tab by mutableStateOf(WorkspaceTab.HOME)
        val events = mutableListOf<PocketScreenEvent>()
        val screen = PocketScreenState("Virtual keypad connected", "Focus",
            FocusTimer().present(0).keys.map { it.toGridTile(true) },
            listOf(ProfileChoice("focus", "Focus")), "focus", simulating = true)
        ui.setContent {
            // The test surface is fixed in pixels; change layout constraints, not platform.
            CompositionLocalProvider(LocalDensity provides Density(.75f)) {
                Box(Modifier.requiredWidth(width).fillMaxHeight()) {
                    PocketTheme {
                        PocketWorkspace(tab, { tab = it }, screen, emptyList(), events::add,
                            editor = { LayoutEditor(store, emptyList(), emptyMap(), {}, {}) }, workflows = {})
                    }
                }
            }
        }
        fun assertFocusBoard() {
            screen.tiles.forEachIndexed { index, tile ->
                ui.onNodeWithContentDescription("Key ${index + 1}, ${tile.label}, ${tile.footer}").assertExists()
            }
        }
        assertFocusBoard()
        ui.runOnIdle { width = 900.dp }
        assertFocusBoard()
        ui.runOnIdle { width = 380.dp }
        ui.onNodeWithContentDescription("Key 1, Start, Focus").performClick()
        assertEquals(listOf(PocketScreenEvent.VirtualKey(0)), events)
        events.clear()
        ui.onNodeWithText("Profiles").performClick()
        ui.onNodeWithText("Swap keys").performScrollTo().performClick()
        ui.onNodeWithContentDescription("Key 1,", substring = true).performClick()
        ui.onNodeWithTag("key-label").performTextReplacement("New label")
        ui.runOnIdle { width = 900.dp }
        ui.onNodeWithTag("wide-editor").assertExists()
        ui.onNodeWithText("Manage profile").assertExists()
        ui.onNodeWithTag("key-label").assertTextContains("New label")
        ui.runOnIdle { width = 380.dp }
        ui.onNodeWithTag("compact-editor").assertExists()
        ui.onNodeWithTag("key-label").assertTextContains("New label")
        ui.runOnIdle {
            assertTrue(events.isEmpty())
            assertEquals("", repo.state.value.activePage.keys.first().label)
        }
    }
}
