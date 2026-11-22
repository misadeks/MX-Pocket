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
import org.junit.Rule
import org.junit.Test

class KeypadGridTest {
    @get:Rule val rule = createComposeRule()

    @Test fun narrowRtlPanelPreservesCornersAndKeyHitRegions() {
        val art = ImageBitmap(480, 480)
        Canvas(art).drawRect(0f, 0f, 480f, 480f, Paint().apply { color = Color.Black })
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
                KeypadGrid(List(9) { KeypadTile("Tile", color = Color.Blue, image = key) },
                    { index, down -> events.add(index to down) }, Modifier.width(240.dp).testTag("panel"), art)
            }
        }
        // A 240px square is centered in 252px: first key x=12..70, y=9..67, with 6px rounded corners.
        val panel = rule.onNodeWithTag("panel")
        val pixels = panel.captureToImage().toPixelMap()
        assertEquals(Color.Black.toArgb(), pixels[12, 9].toArgb())
        assertEquals(Color.Red.toArgb(), pixels[15, 12].toArgb())
        assertEquals(Color.Black.toArgb(), pixels[70, 67].toArgb())
        assertEquals(Color.Green.toArgb(), pixels[67, 64].toArgb())
        assertEquals(Color.Black.toArgb(), pixels[40, 77].toArgb())
        rule.onNodeWithContentDescription("Key 1, Tile").assertHeightIsAtLeast(84.dp)
        panel.performTouchInput { click(Offset(40f, 77f)) }
        assertEquals(listOf(0 to true, 0 to false), events)
    }
}
