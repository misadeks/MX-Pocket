package tel.misa.mxpocket.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tel.misa.mxpocket.actions.ActionCall
import tel.misa.mxpocket.deck.*
import tel.misa.mxpocket.shared.ui.LayoutEditor
import tel.misa.mxpocket.shared.ui.PocketTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1000dp-h900dp")
class AdaptiveLayoutEditorTest {
    @get:Rule val ui = createComposeRule()
    @get:Rule val folder = TemporaryFolder()
    private val file get() = File(folder.root, "deck.json")
    private val repo by lazy { DeckRepository(file) }
    private val width = mutableStateOf(380.dp)
    private var back: (() -> Unit)? = null

    private fun open() {
        val store = repo.asEditorStore()
        ui.setContent {
            PocketTheme {
                Box(Modifier.width(width.value).fillMaxHeight()) {
                    LayoutEditor(store, emptyList(), mapOf("dock" to "desk"), {}, {},
                        backHandler = { enabled, onBack -> SideEffect { back = onBack.takeIf { enabled } } })
                }
            }
        }
    }

    @Test fun keyAndBindingDraftFollowTheSelectedPageAcrossBreakpointsWithoutNavigatingTheDeck() {
        val pageId = repo.createPage("audio", "Mixer").getOrThrow()
        repo.setKey("audio", pageId, 2, DeckKey(label = "Muted", enabled = false)).getOrThrow()
        val before = repo.state.value
        open()
        ui.onNodeWithText("Focus / Focus ▾").performClick()
        ui.onNodeWithText("Audio").performClick()
        ui.onNodeWithText("Audio / Audio ▾").performClick()
        ui.onNodeWithText("Mixer").performClick()
        ui.onNodeWithContentDescription("Key 3, Muted", substring = true).performClick()
        ui.onNodeWithText("Key positions").assertDoesNotExist()
        ui.onNodeWithTag("key-label").performTextReplacement("Mix down")
        ui.onNodeWithTag("choose-binding").performScrollTo().performClick()
        ui.onNodeWithText("Search actions").performTextInput("Mixer")
        ui.runOnIdle { width.value = 760.dp }
        ui.onNodeWithText("Open page: Mixer").performClick()
        ui.onNodeWithTag("wide-editor").assertExists()
        ui.onNodeWithText("Manage profile").assertExists()
        ui.onNodeWithText("Key positions").assertExists()
        ui.onNodeWithText("Edit key 3").assertExists()
        ui.onNodeWithContentDescription("Key 3, Mix down, Off", substring = true).assertExists()
        ui.runOnIdle {
            assertSame(before, repo.state.value)
            width.value = 380.dp
        }
        ui.onNodeWithTag("key-label").assertTextContains("Mix down")
        ui.onNodeWithText("Save key").performScrollTo().performClick()
        ui.waitUntil(5000) { repo.state.value != before }
        val saved = DeckRepository(file).state.value
        assertEquals("focus", saved.activeProfileId)
        val key = saved.profiles.single { it.id == "audio" }.pages.single { it.id == pageId }.keys[2]
        assertEquals("Mix down", key.label)
        assertFalse(key.enabled)
        assertEquals(DeckActions.PAGE, key.action?.id)
        assertEquals(pageId, key.action?.params?.get(DeckActions.PAGE_ID))
    }

    @Test fun holdPickerSetsChangesAndClearsOnlyHoldAndPreviewOnlySelectsTheKey() {
        fun page(name: String) = ActionCall(DeckActions.PAGE,
            mapOf(DeckActions.PAGE_ID to repo.createPage("focus", name).getOrThrow()))
        val tap = page("Mixer")
        val hold = page("Levels")
        val original = DeckKey(label = "Controls", action = tap)
        repo.setKey("focus", "root", 0, original).getOrThrow()
        fun previewKey() = ui.onNodeWithContentDescription("Key 1, Controls", substring = true)
        fun choose(target: String, option: String) {
            ui.onNodeWithTag(target).performScrollTo().performClick()
            ui.onNodeWithText(option).performClick()
        }
        fun save(expected: DeckKey) {
            ui.onNodeWithText("Save key").performScrollTo().performClick()
            ui.waitUntil(5000) { repo.state.value.activePage.keys[0] == expected }
            assertEquals("root", DeckRepository(file).state.value.activePage.id)
        }
        open()
        previewKey().performClick()
        choose("choose-hold-binding", "Open page: Mixer")
        choose("choose-hold-binding", "Open page: Levels")
        val both = original.copy(holdAction = hold)
        assertEquals(original, repo.state.value.activePage.keys[0])
        save(both)

        // A long preview press on a key with two real navigation bindings still just selects it.
        previewKey().performTouchInput { longClick() }
        ui.onNodeWithTag("choose-binding").assertTextContains("Tap: Open page: Mixer")
        ui.onNodeWithTag("choose-hold-binding").assertTextContains("Hold: Open page: Levels")
        choose("choose-binding", "Unassigned")
        save(both.copy(action = null))
        previewKey().performClick()
        choose("choose-hold-binding", "Unassigned")
        save(original.copy(action = null))
        assertEquals(listOf("root"), repo.state.value.histories["focus"])
    }

    @Test fun ruleDraftSurvivesResizeAndBackUnwindsOnlyTheCurrentEditorLayer() {
        open()
        ui.runOnIdle { assertNull(back) }
        ui.onNodeWithText("Profile settings and rules").performScrollTo().performClick()
        ui.onNodeWithText("Advanced rules", substring = true).performScrollTo().performClick()
        ui.onNodeWithText("Add rule").performScrollTo().performClick()
        ui.onNodeWithText("Matching value").performTextReplacement("studio")
        ui.runOnIdle { width.value = 760.dp }
        ui.onNodeWithText("Matching value").assertTextContains("studio")
        ui.runOnIdle { width.value = 380.dp }
        ui.onNodeWithText("Save rule").performClick()
        ui.waitUntil(5000) { repo.state.value.activeProfile.rules == listOf(DeckRule("dock", "studio")) }
        ui.onNodeWithText("Add context rule").assertDoesNotExist()
        ui.runOnIdle { requireNotNull(back).invoke() }
        ui.onNodeWithText("Key positions").assertExists()
        ui.onNodeWithContentDescription("Key 1,", substring = true).performClick()
        ui.onNodeWithTag("choose-binding").performScrollTo().performClick()
        ui.runOnIdle { requireNotNull(back).invoke() }
        ui.onNodeWithText("Edit key 1").assertExists()
        ui.runOnIdle { requireNotNull(back).invoke() }
        ui.onNodeWithText("Key positions").assertExists()
        ui.runOnIdle { assertNull(back) }
    }
}
