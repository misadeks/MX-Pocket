package tel.misa.mxpocket.shared.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

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
    if (panelArt != null) {
        // Panel art includes the gaps. Place each composited key over its 118px visible LCD region.
        BoxWithConstraints(modifier.fillMaxWidth().aspectRatio(1f)) {
            Image(panelArt, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            val unit = maxWidth / 480
            tiles.forEachIndexed { i, tile ->
                Key(i, tile, onKey, Modifier.offset(x = unit * (23 + i % 3 * 158),
                    y = unit * (6 + i / 3 * 158)).size(unit * 118))
            }
        }
    } else {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(3) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(3) { col ->
                        val i = row * 3 + col
                        Key(i, tiles[i], onKey, Modifier.weight(1f).heightIn(min = 84.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Key(i: Int, tile: KeypadTile, onKey: (Int, Boolean) -> Unit, modifier: Modifier) {
    val foreground = if (tile.color.luminance() > .18f) Color.Black else Color.White
    val description = listOf("Key ${i + 1}", tile.label, tile.footer).filter { it.isNotBlank() }.joinToString(", ")
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(tile.color)
        .clickable(enabled = tile.enabled, role = Role.Button) { onKey(i, true); onKey(i, false) }
        .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        if (tile.image != null) {
            Image(tile.image, null, Modifier.matchParentSize(), contentScale = ContentScale.Fit)
        } else {
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
}
