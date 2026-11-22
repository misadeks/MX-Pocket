package tel.misa.mxpocket

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertArrayEquals
import org.junit.rules.TestRule
import tel.misa.mxpocket.plugins.KeyTile
import tel.misa.mxpocket.shared.ui.*
import kotlin.math.roundToInt

/** At this width each SVG aperture rounds to exactly 118 screen pixels. */
internal class ComposeMirror<R : TestRule, A : ComponentActivity>(
    private val ui: AndroidComposeTestRule<R,A>,
) {
    private var tiles by mutableStateOf(emptyList<KeypadTile>())
    init {
        ui.runOnIdle {
            ui.activity.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    if (tiles.size == 9) KeypadGrid(tiles, { _, _ -> error("Read-only mirror received input") },
                        Modifier.requiredSize(534.dp, 624.dp).testTag("lcd"))
                }
            }
        }
    }

    fun render(frame: List<KeyTile>, images: List<Bitmap>): Bitmap {
        ui.runOnIdle {
            tiles = frame.mapIndexed { i, key -> key.toGridTile(true, images[i].asImageBitmap()).copy(readOnly = true) }
        }
        val bounds = ui.onNodeWithTag("lcd").fetchSemanticsNode().boundsInWindow
        return ui.runOnIdle {
            val window = ui.activity.window.decorView
            val bitmap = Bitmap.createBitmap(window.width, window.height, Bitmap.Config.ARGB_8888)
            window.draw(Canvas(bitmap))
            Bitmap.createBitmap(bitmap, bounds.left.roundToInt(), bounds.top.roundToInt(), 534, 624)
        }
    }
}

/** Compare every source pixel inside the aperture, excluding its antialiased boundary. */
internal fun assertAperturePixels(board: Bitmap, index: Int, tile: Bitmap) {
    val scale = board.width / 480f
    val left = ((62 + 125 * (index % 3)) * scale).roundToInt()
    val top = ((63 + 125 * (index / 3)) * scale).roundToInt()
    // The approved faceplate texture includes a filtered alpha fringe around
    // each opening; compare the unobstructed pixels, not that material edge.
    for (y in 5 until 113) {
        fun inset(radius: Int): Int {
            val r = radius * scale
            val dy = (r - (if (y < 59) y else 117 - y)).coerceAtLeast(0f)
            val inner = r - 5f
            return kotlin.math.ceil(r - kotlin.math.sqrt((inner * inner - dy * dy).coerceAtLeast(0f))).toInt()
        }
        val start = inset(if (index == 0 && y < 59) 32 else 12)
        val width = 118 - start - inset(if (index == 2 && y < 59) 32 else 12)
        val expected = IntArray(width)
        val actual = IntArray(width)
        tile.getPixels(expected, 0, width, start, y, width, 1)
        board.getPixels(actual, 0, width, left + start, top + y, width, 1)
        assertArrayEquals("Key ${index + 1}, row $y", expected, actual)
    }
}
