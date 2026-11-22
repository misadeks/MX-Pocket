package tel.misa.mxpocket.shared.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
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

/** Corner radius of a key window, in 480-unit panel coordinates (118 is the key width). */
const val KEY_CORNER = 12

/**
 * Images occupy the nine real LCD windows. Hit areas can extend into the gutters.
 * With [onSwap], a key can be dragged onto another one; the pair is reported once on release.
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun KeypadGrid(tiles: List<KeypadTile>, onKey: (Int, Boolean) -> Unit,
    modifier: Modifier = Modifier, panelArt: ImageBitmap? = null, onHold: Map<Int, () -> Unit> = emptyMap(),
    onSwap: ((Int, Int) -> Unit)? = null) {
    require(tiles.size == 9) { "A keypad frame must have nine tiles" }
    val direction = LocalLayoutDirection.current
    val currentSwap by rememberUpdatedState(onSwap)
    var dragFrom by remember { mutableStateOf<Int?>(null) }
    var dragAt by remember { mutableStateOf(Offset.Zero) }
    var dragDelta by remember { mutableStateOf(Offset.Zero) }
    val outlineColor = androidx.compose.material3.MaterialTheme.colorScheme.primary
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(modifier.fillMaxWidth()) {
            val side = maxWidth
            val height = maxOf(side, 252.dp)
            val top = (height - side) / 2
            val unit = side / 480
            val keySize = unit * 118
            val keyShape = RoundedCornerShape(unit * KEY_CORNER)
            val density = LocalDensity.current
            val unitPx = with(density) { unit.toPx() }
            val topPx = with(density) { top.toPx() }
            // The nearest key centre; centres are 158 units apart starting at (82, 65).
            fun keyAt(p: Offset): Int {
                val col = ((p.x / unitPx - 82f) / 158f).roundToInt().coerceIn(0, 2)
                val row = (((p.y - topPx) / unitPx - 65f) / 158f).roundToInt().coerceIn(0, 2)
                return row * 3 + col
            }
            val target = dragFrom?.let { keyAt(dragAt + dragDelta) }
            val drag = if (onSwap == null) Modifier else Modifier.pointerInput(unitPx, topPx) {
                detectDragGestures(
                    onDragStart = { p -> dragFrom = keyAt(p); dragAt = p; dragDelta = Offset.Zero },
                    onDrag = { change, amount -> if (dragFrom != null) { change.consume(); dragDelta += amount } },
                    onDragEnd = {
                        val from = dragFrom
                        val to = keyAt(dragAt + dragDelta)
                        dragFrom = null; dragDelta = Offset.Zero
                        if (from != null && to != from) currentSwap?.invoke(from, to)
                    },
                    onDragCancel = { dragFrom = null; dragDelta = Offset.Zero })
            }
            Box(Modifier.fillMaxWidth().height(height).then(drag)) {
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
                    val dragging = dragFrom == i
                    // Selection and drop outlines sit just outside the LCD window, never over its pixels.
                    if (tile.selected || (target == i && !dragging)) Box(Modifier.absoluteOffset(x - 3.dp, y - 3.dp)
                        .size(keySize + 6.dp).border(2.dp, outlineColor, RoundedCornerShape(unit * KEY_CORNER + 3.dp)))
                    val lift = if (dragging) Modifier.zIndex(1f).graphicsLayer {
                        translationX = dragDelta.x; translationY = dragDelta.y
                        scaleX = 1.06f; scaleY = 1.06f; shadowElevation = 12f; shape = keyShape; clip = true
                    } else Modifier
                    CompositionLocalProvider(LocalLayoutDirection provides direction) {
                        KeyFaceWindow(i, tile, Modifier.absoluteOffset(x,y).size(keySize).then(lift).clip(keyShape))
                    }
                    if (!tile.enabled && !tile.readOnly && tile.face?.enabled == true)
                        Box(Modifier.offset(x,y).size(keySize).then(lift).clip(keyShape).background(Color.Black.copy(alpha = .35f)))
                    val description = tile.accessibleName ?: listOfNotNull("Key ${i + 1}".takeIf { tile.showNumber }, tile.label, tile.footer)
                        .filter(String::isNotBlank).joinToString(", ")
                    val swaps = if (onSwap == null) emptyList() else (0 until 9).filter { it != i }.map { other ->
                        CustomAccessibilityAction("Swap with key ${other + 1}") { currentSwap?.invoke(i, other); true }
                    }
                    val tap = { onKey(i, true); onKey(i, false) }
                    val hold = onHold[i]
                    val click = when {
                        tile.readOnly -> Modifier
                        hold == null -> Modifier.clickable(enabled = tile.enabled, role = Role.Button, onClick = tap)
                        else -> Modifier.combinedClickable(enabled = tile.enabled, role = Role.Button,
                            onLongClickLabel = "Hold", onLongClick = hold, onClick = tap)
                    }
                    Box(Modifier.offset(x, targetTop).size(keySize, targetHeight).then(click).semantics {
                        contentDescription = description
                        if (tile.selected) selected = true
                        if (swaps.isNotEmpty()) customActions = swaps
                    })
                }
            }
        }
    }
}
