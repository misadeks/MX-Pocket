package tel.misa.mxpocket.shared.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.Image
import org.junit.Test
import java.io.File
import tel.misa.mxpocket.FocusTimer
import tel.misa.mxpocket.deck.defaultDeck
import tel.misa.mxpocket.plugins.present

@OptIn(ExperimentalTestApi::class)
class WorkspaceRenderTest {
    @Test fun connectedUsbHomeMirrorsTheKeypadWithoutMaintenanceControls() = runDesktopComposeUiTest(390, 844) {
        val state = PocketScreenState("Keypad session ready.", "Focus", FocusTimer().present(0).keys.map { it.toGridTile(true) },
            defaultDeck().profiles.map { ProfileChoice(it.id, it.name) }, "focus", usbReady = true, connectionActive = true,
            hardwareSummary = true)
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                PocketTheme { PocketWorkspace(WorkspaceTab.HOME, {}, state, emptyList(), {}, {}, {}) }
            }
        }
        onNodeWithText("Connected: USB session active").assertExists()
        // Home is the live keypad; the old summary, maintenance buttons, panel note and virtual switch are gone.
        onNodeWithContentDescription("Key 1, Start, Focus").assertHasNoClickAction()
        onNodeWithText("KEYPAD SHOWS:").assertDoesNotExist()
        onNodeWithText("Repaint all nine LCDs").assertDoesNotExist()
        onNodeWithText("Choose custom image for all nine keys").assertDoesNotExist()
        onNodeWithText("480 × 480", substring = true).assertDoesNotExist()
        onNodeWithText("Connect virtual keypad").assertDoesNotExist()
        val file = File("build/reports/workspace-preview/home-usb-state-fixture.png")
        file.parentFile.mkdirs()
        Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).use { image ->
            image.encodeToData()!!.use { file.writeBytes(it.bytes) }
        }
    }

    @Test fun compactAndWideExposeOnlyTheHostControlsAndRenderTheSameBoard() {
        for ((width, height) in listOf(390 to 844, 1120 to 760)) runDesktopComposeUiTest(width, height) {
            val desktop = width == 1120
            val deck = defaultDeck()
            val state = PocketScreenState(
                status = if (desktop) "Virtual keypad connected. No USB messages are sent." else "Virtual keypad ready.",
                pluginTitle = "Focus", tiles = FocusTimer().present(0).keys.map { it.toGridTile(true) },
                profiles = deck.profiles.map { ProfileChoice(it.id, it.name) }, activeId = "focus",
                reason = deck.selectionReason, simulating = true,
                deviceLimit = if (desktop) "Windows simulator: no USB connection, phone apps, camera, media sessions or Android access grants." else null)
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    PocketTheme { PocketWorkspace(WorkspaceTab.HOME, {}, state, emptyList(), {}, {}, {}) }
                }
            }
            if (desktop) {
                onNodeWithText("Connect keypad").assertDoesNotExist()
                onNodeWithText("Repaint all keys").assertDoesNotExist()
            } else {
                onNodeWithText("Connect keypad").assertExists()
            }
            onNodeWithContentDescription("Key 2, 25:00, Remaining").assertHasNoClickAction()
            val pixels = onRoot().captureToImage()
            val path = File("build/reports/workspace-preview/home-${width}.png")
            path.parentFile.mkdirs()
            Image.makeFromBitmap(pixels.asSkiaBitmap()).use { image ->
                image.encodeToData()!!.use { path.writeBytes(it.bytes) }
            }
        }
    }
}
