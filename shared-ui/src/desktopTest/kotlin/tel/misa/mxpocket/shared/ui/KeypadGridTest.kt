package tel.misa.mxpocket.shared.ui

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test

class KeypadGridTest {
    @get:Rule val rule = createComposeRule()

    @Test fun svgPageCapsRouteSimulatorNavigationWithoutDispatchingAnLcdKey() {
        val pages = mutableListOf<Int>()
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                KeypadGrid(List(9) { KeypadTile("Tile", color = Color.Blue) }, { _, _ -> error("LCD key") },
                    Modifier.width(312.dp).testTag("panel"), onPage = { pages.add(it) })
            }
        }
        val panel = rule.onNodeWithTag("panel")
        panel.performTouchInput { click(Offset(75f, 307f)); click(Offset(156f, 307f)) }
        rule.runOnIdle { assertEquals(listOf(-1, 1), pages) }
    }

    @Test fun narrowRtlBoardMasksFacesAndRoutesEachWindowToItsOwnKey() {
        val key = ImageBitmap(118, 118)
        val canvas = Canvas(key)
        val paint = Paint().apply { color = Color.Blue }
        canvas.drawRect(0f, 0f, 118f, 118f, paint)
        paint.color = Color.Red
        canvas.drawRect(0f, 0f, 10f, 10f, paint)
        paint.color = Color.Green
        canvas.drawRect(108f, 108f, 118f, 118f, paint)
        val events = mutableListOf<Pair<Int, Boolean>>()
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f), LocalLayoutDirection provides LayoutDirection.Rtl) {
                KeypadGrid(List(9) { KeypadTile("Tile", color = Color.Blue, image = key, selected = it == 0) },
                    { index, down -> events.add(index to down) }, Modifier.width(312.dp).testTag("panel"))
            }
        }
        val panel = rule.onNodeWithTag("panel")
        val pixels = panel.captureToImage().toPixelMap()
        assertEquals(365, pixels.height)
        // The SVG masks the outer 32-unit rounded corner; the selection rim must
        // not paint over the blue LCD. A 12-unit inner corner exposes more pixels.
        assertNotEquals(Color.Red.toArgb(), pixels[42, 43].toArgb())
        assertEquals(Color.Red.toArgb(), pixels[126, 45].toArgb())
        assertEquals(Color.Green.toArgb(), pixels[105, 105].toArgb())
        rule.onNodeWithContentDescription("Key 1, Tile").assertIsSelected()
        for (i in 0..8) {
            rule.onNodeWithContentDescription("Key ${i + 1}, Tile").assertHeightIsAtLeast(84.dp)
            val x = (115 + 125 * (i % 3)) * .65f
            val y = (116 + 125 * (i / 3)) * .65f
            assertEquals(Color.Blue.toArgb(), pixels[x.toInt(), y.toInt()].toArgb())
            panel.performTouchInput { click(Offset(x, y)) }
        }
        // The housing's decorative page caps don't dispatch LCD keys.
        panel.performTouchInput { click(Offset(75f, 307f)) }
        panel.performTouchInput { click(Offset(156f, 307f)) }
        assertEquals((0..8).flatMap { listOf(it to true, it to false) }, events)
    }
}
