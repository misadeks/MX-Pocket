package tel.misa.mxpocket.shared.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.Image
import org.junit.Test
import org.junit.Assert.assertEquals
import java.io.File
import tel.misa.mxpocket.FocusTimer
import tel.misa.mxpocket.deck.defaultDeck
import tel.misa.mxpocket.plugins.present

@OptIn(ExperimentalTestApi::class)
class WorkspaceRenderTest {
    @Test fun switchingLayoutsKeepsNavigationAndKeypadInPlace() {
        for ((width, fontScale) in listOf(390 to 1f, 390 to 1.6f, 1120 to 1f)) runDesktopComposeUiTest(width, 940) {
            val short = PocketScreenState("Not connected", "Focus", FocusTimer().present(0).keys.map { it.toGridTile(false) },
                listOf(ProfileChoice("focus", "Focus")), "focus")
            val profile = "A long profile name including / punctuation"
            val page = "A long page name that does not fit on one line"
            val extended = short.copy(profiles = listOf(ProfileChoice("custom", profile)), activeId = "custom",
                pluginTitle = "$profile / $page", reason = "Matched an automatic selection rule with a long description")
            val state = mutableStateOf(short)
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                    PocketTheme { HomeScreen(state.value) { event ->
                        if (event is PocketScreenEvent.ChangePage) state.value = if (event.delta > 0) extended else short
                    } }
                }
            }
            val next = onNodeWithContentDescription("Next page")
            val firstKey = onNodeWithContentDescription("Key 1, Start, Focus")
            val navBounds = next.fetchSemanticsNode().boundsInRoot
            val keyBounds = firstKey.fetchSemanticsNode().boundsInRoot
            onNodeWithText("Selected layout", ignoreCase = true).assertDoesNotExist()
            onNodeWithText("Layout preview", ignoreCase = true).assertDoesNotExist()
            next.performClick()
            onNodeWithText(profile).assertExists()
            onNodeWithText(page).assertExists()
            assertEquals(navBounds, next.fetchSemanticsNode().boundsInRoot)
            assertEquals(keyBounds, firstKey.fetchSemanticsNode().boundsInRoot)
            onNodeWithContentDescription("Previous page").performClick()
            onNodeWithText("Focus").assertExists()
            assertEquals(navBounds, next.fetchSemanticsNode().boundsInRoot)
        }
    }

    @Test fun connectedUsbHomeMirrorsTheKeypadWithoutMaintenanceControls() = runDesktopComposeUiTest(390, 844) {
        val state = PocketScreenState("Keypad session ready.", "Focus", FocusTimer().present(0).keys.map { it.toGridTile(true) },
            defaultDeck().profiles.map { ProfileChoice(it.id, it.name) }, "focus", usbReady = true, connectionActive = true)
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                PocketTheme { PocketWorkspace(WorkspaceTab.HOME, {}, state, emptyList(), {}, {}, {}) }
            }
        }
        onNodeWithText("USB connected").assertExists()
        onNodeWithContentDescription("Disconnect device").assertHasClickAction()
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
                status = if (desktop) "Virtual keypad connected. No USB messages are sent." else "Connect a USB keypad.",
                pluginTitle = "Focus", tiles = FocusTimer().present(0).keys.map { it.toGridTile(true) },
                profiles = deck.profiles.map { ProfileChoice(it.id, it.name) }, activeId = "focus",
                reason = deck.selectionReason, simulator = if (desktop) DesktopSimulator("Windows simulator: no USB connection.",
                    virtualGrid(1, deck, FocusTimer().present(0), null)) else null)
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    PocketTheme(if (desktop) ThemeMode.DARK else ThemeMode.LIGHT) {
                        PocketWorkspace(WorkspaceTab.HOME, {}, state, emptyList(), {}, {}, {})
                    }
                }
            }
            if (desktop) {
                onNodeWithContentDescription("Connect to device").assertDoesNotExist()
                onNodeWithContentDescription("Key 1, Start, Focus").assertHasClickAction()
                onNodeWithText("Repaint all keys").assertDoesNotExist()
            } else {
                onNodeWithContentDescription("Connect to device").assertHasClickAction()
                onNodeWithContentDescription("Key 1, Start, Focus").assertHasNoClickAction()
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
