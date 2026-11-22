package tel.misa.mxpocket.shared.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tel.misa.mxpocket.deck.*
import java.io.File

class KeyColorPickerTest {
    @get:Rule val ui = createComposeRule()
    @get:Rule val folder = TemporaryFolder()

    @OptIn(ExperimentalTestApi::class)
    @Test fun keyboardMixesByOneAndSavedKeyRendersInTheWideLayout() {
        val file = File(folder.root, "deck.json")
        val repo = DeckRepository(file)
        val store = repo.asEditorStore()
        val screen = PocketScreenState("", "", emptyList(), emptyList(), "launcher", simulator = DesktopSimulator(""))
        ui.setContent {
            CompositionLocalProvider(LocalDensity provides Density(.75f)) {
                Box(Modifier.requiredWidth(960.dp)) { PocketTheme {
                    LayoutEditor(store, emptyList(), emptyMap(), {}, {}, live = screen)
                } }
            }
        }
        val key = ui.onNodeWithContentDescription("Key 1,", substring = true)
        fun click(text: String) = ui.onNode(hasText(text) and hasClickAction()).performScrollTo().performClick()
        key.performScrollTo().performClick()
        click("Key color")
        ui.onNodeWithText("Hex color").performScrollTo().performTextReplacement("#80112233")
        val red = ui.onNodeWithContentDescription("Red")
        red.performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus)
        red.assertIsFocused()
        red.performKeyInput { pressKey(Key.DirectionRight) }
        ui.onNodeWithText("Hex color").assertTextContains("#80122233")
        click("Amber")
        red.performSemanticsAction(SemanticsActions.RequestFocus)
        red.performKeyInput { pressKey(Key.DirectionLeft) }
        val chosen = 0xffe8a73c.toInt()
        ui.onNodeWithText("Hex color").assertTextContains("#E8A73C")
        ui.onNodeWithText("Apply color").performClick()
        click("Save key")
        ui.waitUntil(5000) { repo.state.value.activePage.keys[0].color == chosen }
        assertEquals(chosen, DeckRepository(file).state.value.activePage.keys[0].color)
        val pixels = key.performScrollTo().captureToImage().toPixelMap()
        assertTrue("The saved key should show the chosen pixels in its LCD window",
            (0 until pixels.width).any { x -> pixels[x, pixels.height / 2].toArgb() == chosen })
        key.performClick()
        click("Key color")
        ui.onNodeWithText("Hex color").assertTextContains("#E8A73C")
        click("Key default")
        ui.onNodeWithText("Apply color").performClick()
        click("Save key")
        ui.waitUntil(5000) { repo.state.value.activePage.keys[0].color == DeckKey().color }
        assertEquals(DeckKey().color, DeckRepository(file).state.value.activePage.keys[0].color)
    }
}
