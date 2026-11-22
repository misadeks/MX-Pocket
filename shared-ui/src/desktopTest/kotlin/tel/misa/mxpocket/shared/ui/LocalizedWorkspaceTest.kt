package tel.misa.mxpocket.shared.ui

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.Image
import org.junit.Assert.assertFalse
import org.junit.Test
import tel.misa.mxpocket.i18n.*
import tel.misa.mxpocket.FocusTimer
import tel.misa.mxpocket.plugins.present
import java.io.File

@OptIn(ExperimentalTestApi::class)
class LocalizedWorkspaceTest {
    @Test fun selectorUpdatesChromeAndConnectionControlsAtBothWidths() {
        val previous = AppLocale.current
        try {
            for ((width, language) in listOf(390 to AppLanguage.FRENCH, 1120 to AppLanguage.SERBIAN_LATIN)) {
                AppLocale.update(AppLanguage.ENGLISH, emptyList())
                runDesktopComposeUiTest(width, if (width == 390) 844 else 760) {
                    val choice = mutableStateOf<AppLanguage?>(null)
                    setContent {
                        CompositionLocalProvider(LocalDensity provides Density(1f)) {
                            val state = PocketScreenState(WorkspaceCopy.NotConnected.text(), "Studio",
                                FocusTimer().present(0).keys.map { it.toGridTile(false) },
                                listOf(ProfileChoice("studio", "Studio")), "studio",
                                simulator = if (width < 840) null else DesktopSimulator(WorkspaceCopy.Simulator.text()),
                                language = choice.value)
                            PocketTheme(ThemeMode.LIGHT) {
                                PocketWorkspace(WorkspaceTab.SETTINGS, {}, state, emptyList(), { event ->
                                    if (event is PocketScreenEvent.SetLanguage) {
                                        choice.value = event.language
                                        AppLocale.update(event.language, listOf("en"))
                                    }
                                }, {}, {})
                            }
                        }
                    }
                    onNodeWithText("Language").performClick()
                    onNodeWithText(language.nativeName).performClick()
                    onNodeWithText(WorkspaceCopy.Home.text()).assertIsDisplayed()
                    onNodeWithText(language.nativeName).assertIsDisplayed()
                    val button = if (width == 390) WorkspaceCopy.ConnectKeypad else WorkspaceCopy.ConnectVirtual
                    onNodeWithText(button.text()).assertHasClickAction().performScrollTo().assertIsDisplayed()
                    val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                    onNodeWithText(button.text(), useUnmergedTree = true)
                        .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
                    val label = layouts.single()
                    // Skia can round measured bounds by a fraction of a pixel. Check the actual
                    // visible lines against the control, not hasVisualOverflow's unrounded width.
                    for (line in 0 until label.lineCount) {
                        assertFalse("${button.text()} line $line clipped: ${label.size} / ${label.getLineRight(line)}",
                            label.isLineEllipsized(line) || label.getLineLeft(line) < -1 ||
                                label.getLineRight(line) > label.size.width + 1 ||
                                label.getLineBottom(line) > label.size.height + 1)
                    }
                    onNodeWithText(WorkspaceCopy.Setup.text()).performScrollTo()
                    val file = File("build/reports/workspace-preview/settings-${language.tag}-$width.png")
                    file.parentFile.mkdirs()
                    Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).use { image ->
                        image.encodeToData()!!.use { file.writeBytes(it.bytes) }
                    }
                }
            }
        } finally { AppLocale.update(previous, emptyList()) }
    }
}
