package tel.misa.mxpocket.shared.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import tel.misa.mxpocket.FocusTimer
import tel.misa.mxpocket.plugins.present

class WorkspacePageFrameTest {
    @get:Rule val ui = createComposeRule()

    // The Windows companion: Settings opens on the Language row, with no Android setup button above it.
    private val state = PocketScreenState("Virtual keypad ready", "Focus",
        FocusTimer().present(0).keys.map { it.toGridTile(true) }, listOf(ProfileChoice("focus", "Focus")), "focus",
        simulator = DesktopSimulator("Windows simulator", supportsUsb = true))

    /** Title offset from the page's left (rail side) and top edges. */
    private fun titleOffset(title: String): Pair<Dp, Dp> {
        val page = ui.onNodeWithTag("workspace-page").getUnclippedBoundsInRoot()
        val heading = ui.onNode(hasText(title) and isHeading()).getUnclippedBoundsInRoot()
        return (heading.left - page.left) to (heading.top - page.top)
    }

    private fun gap(above: String, below: String): Dp {
        val title = ui.onNode(hasText(above) and isHeading()).getUnclippedBoundsInRoot()
        return ui.onNodeWithText(below).getUnclippedBoundsInRoot().top - title.bottom
    }

    @Test fun everyRailTabPlacesItsTitleAndContentAtTheSameScaledGaps() {
        var width by mutableStateOf(1200.dp)
        var tab by mutableStateOf(WorkspaceTab.HOME)
        ui.setContent {
            CompositionLocalProvider(LocalDensity provides Density(.5f)) {
                Box(Modifier.requiredWidth(width).requiredHeight(900.dp)) {
                    PocketTheme {
                        PocketWorkspace(tab, { tab = it }, state, emptyList(), {},
                            profiles = { SimulatorProfiles(state, {}) {} }, workflows = {})
                    }
                }
            }
        }
        val titles = mapOf(WorkspaceTab.HOME to "MX Pocket", WorkspaceTab.PROFILES to "Profiles",
            WorkspaceTab.PLUGINS to "Plugins", WorkspaceTab.SETTINGS to "Settings")
        val gutters = mutableListOf<Dp>()
        for (size in listOf(1200.dp, 1600.dp)) {
            ui.runOnIdle { width = size; tab = WorkspaceTab.SETTINGS }
            val pageWidth = ui.onNodeWithTag("workspace-page").getUnclippedBoundsInRoot().width
            val gutter = titleOffset("Settings").first
            // Left gap, top gap and title gap keep one ratio to the page width as the window resizes.
            assertEquals(.025f, gutter.value / pageWidth.value, .001f)
            for ((target, title) in titles) {
                ui.runOnIdle { tab = target }
                val (left, top) = titleOffset(title)
                assertEquals("$title left", gutter.value, left.value, .01f)
                // The Home title shares a row with the slightly taller connection chip.
                assertEquals("$title top", gutter.value, top.value, 1.5f)
            }
            // One test pixel is 2dp at this density, so a half-gutter can round by up to 1dp.
            ui.runOnIdle { tab = WorkspaceTab.SETTINGS }
            assertEquals(gutter.value / 2, gap("Settings", "Language").value, 1f)
            ui.runOnIdle { tab = WorkspaceTab.PLUGINS }
            assertEquals(gutter.value / 2, gap("Plugins", "No plugins installed.").value, 1f)
            gutters += gutter
        }
        assertTrue("The gutter grows with the window", gutters[1] > gutters[0])
    }

    @Test fun compactWindowsKeepTheirOwnSpacing() {
        ui.setContent {
            CompositionLocalProvider(LocalDensity provides Density(.5f)) {
                Box(Modifier.requiredWidth(400.dp).requiredHeight(800.dp)) {
                    PocketTheme { PocketWorkspace(WorkspaceTab.SETTINGS, {}, state, emptyList(), {}, {}, {}) }
                }
            }
        }
        val (left, top) = titleOffset("Settings")
        assertEquals(16f, left.value, .01f)
        assertEquals(12f, top.value, .01f)
        assertEquals(12f, gap("Settings", "Language").value, 1f)
    }
}
