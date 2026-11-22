package tel.misa.mxpocket.shared.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import tel.misa.mxpocket.plugins.*

data class KeypadTile(
    val label: String, val footer: String = "", val color: Color, val enabled: Boolean = true,
    val image: ImageBitmap? = null, val icon: String = "", val readOnly: Boolean = false,
    val showNumber: Boolean = true, val accessibleName: String? = null,
    val face: KeyTile? = null, val selected: Boolean = false,
)

fun KeyTile.toGridTile(inputEnabled: Boolean, image: ImageBitmap? = null): KeypadTile {
    val name = when {
        glyph == PlayerGlyph.TRACK && readOnly -> listOf("Now playing", label, footer)
        volumeLevel != null -> listOf(label, if (volumeLevel.muted) "Muted" else "Sound on", volumeLevel.description, footer)
        glyph is NavigationGlyph || glyph is PlayerGlyph && !showLabel -> listOf(label, footer)
        else -> emptyList()
    }
    return KeypadTile(label, footer, Color(background), enabled && inputEnabled, image, icon, readOnly,
        accessibleName = name.takeIf { it.isNotEmpty() }?.filter(String::isNotBlank)?.distinct()?.joinToString(", "),
        face = this)
}

@Composable
private fun KeyFaceWindow(index: Int, tile: KeypadTile, modifier: Modifier) {
    tile.image?.let { Image(it, null, modifier, contentScale = ContentScale.FillBounds); return }
    val measurer = rememberTextMeasurer()
    val direction = LocalLayoutDirection.current
    val face = tile.face ?: KeyTile(tile.label, tile.footer, tile.color.toArgb(), icon = tile.icon,
        enabled = tile.enabled, readOnly = tile.readOnly)
    androidx.compose.foundation.Canvas(modifier) {
            val canvas = drawContext.canvas
            canvas.save()
            canvas.scale(size.width / 118f, size.height / 118f)
            canvas.clipRect(0f, 0f, 118f, 118f)
            canvas.drawRect(0f, 0f, 118f, 118f, Paint().apply { color = Color(face.background) })
            canvas.paintKeyFace(index, face) { text, ink ->
                val layout = measurer.measure(AnnotatedString(text.text), TextStyle(color = ink, fontSize = text.size.sp,
                    fontWeight = if (text.bold) FontWeight.Bold else FontWeight.Normal, textAlign = TextAlign.Center),
                    maxLines = text.lines, overflow = TextOverflow.Ellipsis, density = Density(1f), layoutDirection = direction,
                    constraints = Constraints(minWidth = 104, maxWidth = 104))
                canvas.save(); canvas.translate(7f, text.center - layout.size.height / 2f)
                layout.multiParagraph.paint(canvas, color = ink)
                canvas.restore()
        }
            canvas.restore()
    }
}

/** Images occupy the nine real LCD windows. Hit areas can extend into the gutters. */
@Composable
fun KeypadGrid(tiles: List<KeypadTile>, onKey: (Int, Boolean) -> Unit,
    modifier: Modifier = Modifier, panelArt: ImageBitmap? = null) {
    require(tiles.size == 9) { "A keypad frame must have nine tiles" }
    val direction = LocalLayoutDirection.current
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(modifier.fillMaxWidth()) {
            val side = maxWidth
            val height = maxOf(side, 252.dp)
            val top = (height - side) / 2
            val unit = side / 480
            val keySize = unit * 118
            Box(Modifier.fillMaxWidth().height(height)) {
                Box(Modifier.offset(y = top).size(side).background(Color.Black)) {
                    if (panelArt != null) Image(panelArt, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                }
                tiles.forEachIndexed { i, tile ->
                    val x = unit * (23 + i % 3 * 158)
                    val y = top + unit * (6 + i / 3 * 158)
                    val row = i / 3
                    val targetHeight = maxOf(keySize, 84.dp)
                    val targetTop = (y + (keySize - targetHeight) / 2)
                        .coerceIn(84.dp * row, height - 84.dp * (2 - row) - targetHeight)
                    val outline = if (tile.selected) Modifier.border(2.dp, androidx.compose.material3.MaterialTheme.colorScheme.primary) else Modifier
                    CompositionLocalProvider(LocalLayoutDirection provides direction) {
                        KeyFaceWindow(i, tile, Modifier.absoluteOffset(x,y).size(keySize).then(outline))
                    }
                    if (!tile.enabled && !tile.readOnly && tile.face?.enabled == true)
                        Box(Modifier.offset(x,y).size(keySize).background(Color.Black.copy(alpha = .35f)))
                    val description = tile.accessibleName ?: listOfNotNull("Key ${i + 1}".takeIf { tile.showNumber }, tile.label, tile.footer)
                        .filter(String::isNotBlank).joinToString(", ")
                    val click = if (tile.readOnly) Modifier else Modifier.clickable(enabled = tile.enabled, role = Role.Button) {
                        onKey(i, true); onKey(i, false)
                    }
                    Box(Modifier.offset(x, targetTop).size(keySize, targetHeight).then(click).semantics {
                        contentDescription = description
                        if (tile.selected) selected = true
                    })
                }
            }
        }
    }
}
