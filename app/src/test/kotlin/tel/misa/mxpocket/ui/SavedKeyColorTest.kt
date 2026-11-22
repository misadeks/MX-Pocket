package tel.misa.mxpocket.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tel.misa.mxpocket.deck.DeckRepository
import tel.misa.mxpocket.deck.asEditorStore
import tel.misa.mxpocket.shared.ui.PocketTheme
import tel.misa.mxpocket.shared.ui.SavedProfilesEditor
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SavedKeyColorTest {
    @get:Rule val ui = createComposeRule()
    @get:Rule val folder = TemporaryFolder()

    @Test fun presetAndCustomColorStayOnTheBrowsedKeyAndCancelDiscardsOnlyThePicker() {
        val file = File(folder.root, "deck.json")
        val repo = DeckRepository(file)
        val before = repo.state.value
        val store = repo.asEditorStore()
        ui.setContent { PocketTheme {
            SavedProfilesEditor(store, emptySet(), emptyList(), emptyMap(), {}, {}, null) { _, _ -> }
        } }
        fun click(text: String) = ui.onNode(hasText(text) and hasClickAction()).performScrollTo().performClick()
        fun openKey() = ui.onNodeWithContentDescription("Key 1,", substring = true).performScrollTo().performClick()
        fun channel(name: String, value: Int) = ui.onNodeWithContentDescription(name)
            .performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(value.toFloat()) }
        click("Focus")
        click("Root page")
        openKey()
        ui.onNodeWithTag("key-label").performTextReplacement("Timer")
        click("Key color")
        click("Teal")
        ui.onNode(hasText("Teal") and hasClickAction()).assertIsSelected()
        ui.onNodeWithText("Apply color").performClick()
        ui.onNodeWithText("Teal · #176A63").assertExists()
        click("Key color")
        ui.onNodeWithText("Hex color").performScrollTo().performTextReplacement("#12")
        ui.onNodeWithText("Apply color").assertIsNotEnabled()
        ui.onNode(hasText("Cancel") and hasAnyAncestor(isDialog())).performClick()
        ui.onNodeWithText("Teal · #176A63").assertExists()
        ui.onNodeWithTag("key-label").assertTextContains("Timer")
        assertEquals(before, repo.state.value)

        click("Key color")
        channel("Red", 18)
        channel("Green", 52)
        channel("Blue", 86)
        ui.onNodeWithText("Hex color").assertTextContains("#123456")
        ui.onNodeWithText("Apply color").performClick()
        assertEquals(before, repo.state.value)
        click("Save key")
        ui.waitUntil(5000) { repo.state.value != before }
        val after = DeckRepository(file).state.value
        val profile = before.profiles.single { it.id == "focus" }
        val page = profile.pages.single { it.id == profile.rootPageId }
        val expected = page.keys[0].copy(label = "Timer", color = 0xff123456.toInt())
        assertEquals(before.copy(profiles = before.profiles.map {
            if (it != profile) it else it.copy(pages = it.pages.map { p ->
                if (p != page) p else p.copy(keys = listOf(expected) + p.keys.drop(1))
            })
        }), after)
        openKey()
        ui.onNodeWithText("#123456").assertExists()
    }
}
