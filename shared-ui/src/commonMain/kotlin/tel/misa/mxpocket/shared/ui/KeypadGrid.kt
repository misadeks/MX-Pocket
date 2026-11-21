package tel.misa.mxpocket.shared.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection

@Composable
fun PocketTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) darkColorScheme(primary = Color(0xFFAEC6FF))
            else lightColorScheme(primary = Color(0xFF245CA0)),
        content = content,
    )
}

data class KeypadTile(
    val label: String,
    val footer: String = "",
    val color: Color,
    val enabled: Boolean = true,
    val image: ImageBitmap? = null,
)

/** A completed touch, mouse, keyboard or accessibility click emits one logical down/up pair. */
@Composable
fun KeypadGrid(
    tiles: List<KeypadTile>,
    onKey: (index: Int, pressed: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    panelArt: ImageBitmap? = null,
) {
    require(tiles.size == 9) { "A keypad frame must have nine tiles" }
    val textDirection = LocalLayoutDirection.current
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        if (panelArt != null) ArtPanel(tiles, onKey, modifier, panelArt, textDirection)
        else PlainPanel(tiles, onKey, modifier, textDirection)
    }
}

@Composable
private fun ArtPanel(tiles: List<KeypadTile>, onKey: (Int, Boolean) -> Unit, modifier: Modifier,
    panelArt: ImageBitmap, textDirection: LayoutDirection) {
        BoxWithConstraints(modifier.fillMaxWidth()) {
            val side = maxWidth
            val height = maxOf(side, 252.dp)
            val top = (height - side) / 2
            val unit = side / 480
            val keySize = unit * 118
            Box(Modifier.fillMaxWidth().height(height)) {
                Image(panelArt, null, Modifier.offset(y = top).size(side), contentScale = ContentScale.FillBounds)
                tiles.forEachIndexed { i, tile ->
                    val x = unit * (23 + i % 3 * 158)
                    val y = top + unit * (6 + i / 3 * 158)
                    val row = i / 3
                    val targetHeight = maxOf(keySize, 84.dp)
                    val targetTop = (y + (keySize - targetHeight) / 2)
                        .coerceIn(84.dp * row, height - 84.dp * (2 - row) - targetHeight)
                    if (tile.image != null) Image(tile.image, null,
                        Modifier.offset(x, y).size(keySize), contentScale = ContentScale.FillBounds)
                    Box(Modifier.offset(x, targetTop).size(keySize, targetHeight).keyAction(i, tile, onKey),
                        contentAlignment = Alignment.Center) {
                        if (tile.image == null) TileLabel(i, tile, textDirection)
                    }
                }
            }
        }
}

@Composable
private fun PlainPanel(tiles: List<KeypadTile>, onKey: (Int, Boolean) -> Unit, modifier: Modifier,
    textDirection: LayoutDirection) {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(3) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(3) { col ->
                        val i = row * 3 + col
                        val tile = tiles[i]
                        val shape = if (tile.image == null) RoundedCornerShape(8.dp) else RectangleShape
                        Box(Modifier.weight(1f).heightIn(min = 84.dp).clip(shape).background(tile.color)
                            .keyAction(i, tile, onKey), contentAlignment = Alignment.Center) {
                            if (tile.image == null) TileLabel(i, tile, textDirection)
                            else Image(tile.image, null, Modifier.matchParentSize(), contentScale = ContentScale.Fit)
                        }
                    }
                }
            }
    }
}

private fun Modifier.keyAction(i: Int, tile: KeypadTile, onKey: (Int, Boolean) -> Unit): Modifier {
    val description = listOf("Key ${i + 1}", tile.label, tile.footer).filter { it.isNotBlank() }.joinToString(", ")
    return clickable(enabled = tile.enabled, role = Role.Button) { onKey(i, true); onKey(i, false) }
        .semantics { contentDescription = description }
}

@Composable
private fun TileLabel(i: Int, tile: KeypadTile, textDirection: LayoutDirection) {
    val foreground = if (tile.color.luminance() > .18f) Color.Black else Color.White
    CompositionLocalProvider(LocalLayoutDirection provides textDirection) {
        Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${i + 1}", color = foreground, style = MaterialTheme.typography.labelSmall)
                Text(tile.label, color = foreground, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (tile.footer.isNotBlank()) Text(tile.footer, color = foreground,
                    style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
