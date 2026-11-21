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
import org.junit.rules.TestRule
import tel.misa.mxpocket.plugins.KeyTile
import tel.misa.mxpocket.shared.ui.*
import kotlin.math.roundToInt

/** Draw the actual Compose window at one pixel per LCD coordinate, without PixelCopy's frame callback. */
internal class ComposeMirror<R : TestRule, A : ComponentActivity>(
    private val ui: AndroidComposeTestRule<R,A>, onTap: (Int) -> Unit = {},
) {
    private var tiles by mutableStateOf(emptyList<KeypadTile>())
    private var art by mutableStateOf<Bitmap?>(null)
    init {
        ui.runOnIdle {
            ui.activity.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    if (tiles.size == 9) KeypadGrid(tiles, { i, down -> if (!down) onTap(i) },
                        Modifier.requiredSize(480.dp).testTag("lcd"), art?.asImageBitmap())
                }
            }
        }
    }

    fun render(frame: List<KeyTile>, images: List<Bitmap>, background: Bitmap? = null): Bitmap {
        ui.runOnIdle {
            art = background
            tiles = frame.mapIndexed { i, key -> key.toGridTile(true, images[i].asImageBitmap()) }
        }
        val bounds = ui.onNodeWithTag("lcd").fetchSemanticsNode().boundsInWindow
        return ui.runOnIdle {
            val window = ui.activity.window.decorView
            val bitmap = Bitmap.createBitmap(window.width, window.height, Bitmap.Config.ARGB_8888)
            window.draw(Canvas(bitmap))
            Bitmap.createBitmap(bitmap, bounds.left.roundToInt(), bounds.top.roundToInt(), 480, 480)
        }
    }
}
