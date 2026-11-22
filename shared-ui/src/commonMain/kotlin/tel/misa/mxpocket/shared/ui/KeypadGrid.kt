package tel.misa.mxpocket.shared.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.AbsoluteRoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import org.jetbrains.compose.resources.painterResource
import tel.misa.mxpocket.plugins.*
import tel.misa.mxpocket.shared.resources.Res
import tel.misa.mxpocket.shared.resources.mx_keypad_housing
import kotlin.math.roundToInt

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
    // Virtual/editor faces use the LCD painter at panel resolution, not at the
    // current zoom. Android's live adapter supplies the already composited image.
    val appImage = (face.glyph as? IntegrationApp)?.let { LocalIntegrationIcons.current[it] }
    val pixels = remember(index, face, direction, measurer, appImage) {
        val bitmap = ImageBitmap(118, 118)
        val canvas = Canvas(bitmap)
        canvas.drawRect(0f, 0f, 118f, 118f, Paint().apply { color = Color(face.background) })
        canvas.paintKeyFace(index, face, appImage) { text, ink ->
            val layout = measurer.measure(AnnotatedString(text.text), TextStyle(color = ink, fontSize = text.size.sp,
                fontWeight = if (text.bold) FontWeight.Bold else FontWeight.Normal, textAlign = TextAlign.Center),
                maxLines = text.lines, overflow = TextOverflow.Ellipsis, density = Density(1f), layoutDirection = direction,
                constraints = Constraints(minWidth = 104, maxWidth = 104))
            canvas.save(); canvas.translate(7f, text.center - layout.size.height / 2f)
            layout.multiParagraph.paint(canvas, color = ink)
            canvas.restore()
        }
        bitmap
    }
    Image(pixels, null, modifier, contentScale = ContentScale.FillBounds)
}

/** Positions in the approved SVG's 480 x 561 viewBox, not in LCD panel coordinates. */
private const val SHELL_WIDTH = 480
private const val SHELL_HEIGHT = 561
private const val WINDOW = 106
private const val PITCH = 125
private fun left(index: Int) = 62 + index % 3 * PITCH
private fun top(index: Int) = 63 + index / 3 * PITCH

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun KeypadGrid(tiles: List<KeypadTile>, onKey: (Int, Boolean) -> Unit,
    modifier: Modifier = Modifier, onHold: Map<Int, () -> Unit> = emptyMap(), onSwap: ((Int, Int) -> Unit)? = null) {
    require(tiles.size == 9) { "A keypad frame must have nine tiles" }
    val direction = LocalLayoutDirection.current
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(modifier.fillMaxWidth()) {
            // Leave room for three disjoint 84dp targets above the page buttons.
            // Pan unusually narrow viewports instead of routing a press to a neighbour.
            val width = maxOf(maxWidth, 312.dp)
            val unit = width / SHELL_WIDTH
            val keySize = unit * WINDOW
            val scroll = if (maxWidth < width) Modifier.horizontalScroll(rememberScrollState()) else Modifier
            val unitPx = with(LocalDensity.current) { unit.toPx() }
            val currentSwap by rememberUpdatedState(onSwap)
            var dragFrom by remember { mutableStateOf<Int?>(null) }
            var dragTo by remember { mutableStateOf<Int?>(null) }
            fun keyAt(p: Offset): Int? {
                val col = ((p.x / unitPx - left(0) - WINDOW / 2) / PITCH).roundToInt()
                val row = ((p.y / unitPx - top(0) - WINDOW / 2) / PITCH).roundToInt()
                return (row * 3 + col).takeIf { row in 0..2 && col in 0..2 }
            }
            val drag = if (onSwap == null) Modifier else Modifier.pointerInput(unitPx) {
                try {
                    detectDragGestures(
                        onDragStart = { dragFrom = keyAt(it); dragTo = dragFrom },
                        onDrag = { change, _ -> if (dragFrom != null) { change.consume(); dragTo = keyAt(change.position) } },
                        onDragEnd = {
                            val from = dragFrom; val to = dragTo
                            dragFrom = null; dragTo = null
                            if (from != null && to != null && from != to) currentSwap?.invoke(from, to)
                        },
                        onDragCancel = { dragFrom = null; dragTo = null })
                } finally { dragFrom = null; dragTo = null }
            }
            Box(scroll.width(width).height(unit * SHELL_HEIGHT).then(drag)) {
                tiles.forEachIndexed { i, tile ->
                    CompositionLocalProvider(LocalLayoutDirection provides direction) {
                        KeyFaceWindow(i, tile, Modifier.absoluteOffset(unit * left(i), unit * top(i)).size(keySize))
                    }
                }
                Image(painterResource(Res.drawable.mx_keypad_housing), null, Modifier.matchParentSize(),
                    contentScale = ContentScale.FillBounds)
                tiles.forEachIndexed { i, tile ->
                    val x = unit * left(i)
                    val y = unit * top(i)
                    val targetHeight = maxOf(keySize, 84.dp)
                    val stride = maxOf(unit * PITCH, targetHeight)
                    val targetTop = unit * (top(4) + WINDOW / 2) + stride * (i / 3 - 1) - targetHeight / 2
                    val source = remember { MutableInteractionSource() }
                    val focused by source.collectIsFocusedAsState()
                    val pressed by source.collectIsPressedAsState()
                    if (tile.selected || focused || pressed || i == dragFrom || i == dragTo) {
                        val rim = 3.dp
                        val radius = unit * 12 + rim
                        val shape = AbsoluteRoundedCornerShape(if (i == 0) unit * 32 + rim else radius,
                            if (i == 2) unit * 32 + rim else radius, radius, radius)
                        Box(Modifier.offset(x - rim, y - rim).size(keySize + rim * 2)
                            .border(2.dp, MaterialTheme.colorScheme.primary, shape))
                    }
                    val description = tile.accessibleName ?: listOfNotNull("Key ${i + 1}".takeIf { tile.showNumber }, tile.label, tile.footer)
                        .filter(String::isNotBlank).joinToString(", ")
                    val tap = { onKey(i, true); onKey(i, false) }
                    val hold = onHold[i]
                    val click = when {
                        tile.readOnly -> Modifier
                        hold == null -> Modifier.clickable(source, indication = null, enabled = tile.enabled, role = Role.Button, onClick = tap)
                        else -> Modifier.combinedClickable(source, indication = null, enabled = tile.enabled, role = Role.Button,
                            onLongClickLabel = "Hold", onLongClick = hold, onClick = tap)
                    }
                    Box(Modifier.offset(x, targetTop).size(keySize, targetHeight).then(click).semantics {
                        contentDescription = description
                        if (tile.selected) selected = true
                        if (onSwap != null) customActions = tiles.indices.filter { it != i }.map { other ->
                            CustomAccessibilityAction("Swap with key ${other + 1}") { currentSwap?.invoke(i, other); true }
                        }
                    })
                }
            }
        }
    }
}
