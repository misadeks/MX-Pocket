package tel.misa.mxpocket.shared.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import tel.misa.mxpocket.deck.DeckKey
import tel.misa.mxpocket.i18n.LayoutCopy

// The stored default inherits a binding's background; it isn't an opaque slate override.
private val defaultColor = DeckKey().color
private val presets = mapOf(
    defaultColor to LayoutCopy.DefaultKeyColor, 0xff245cb5.toInt() to LayoutCopy.Blue,
    0xff176a63.toInt() to LayoutCopy.Teal, 0xffe9a73c.toInt() to LayoutCopy.Amber,
    0xff923f62.toInt() to LayoutCopy.Berry, 0xff373b42.toInt() to LayoutCopy.Graphite,
)

@Composable
private fun ColorSample(argb: Int, modifier: Modifier = Modifier) {
    if (argb == defaultColor) {
        Box(modifier, contentAlignment = Alignment.Center) { PocketIcon(ShellIcon.LAYERS) }
    } else {
        Surface(modifier, shape = MaterialTheme.shapes.small, color = Color(argb),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {}
    }
}

private fun colorSummary(argb: Int): String {
    val name = presets[argb]?.text() ?: return displayColor(argb)
    return if (argb == defaultColor) name else "$name · ${displayColor(argb)}"
}

@Composable
internal fun KeyColorField(draft: KeyDraft, writable: Boolean) {
    val current = parseTileColor(draft.color)
    OutlinedButton(onClick = { draft.pendingColor = draft.color }, enabled = writable,
        modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            current?.let { ColorSample(it, Modifier.size(32.dp)) }
            Column(Modifier.weight(1f)) {
                Text(LayoutCopy.KeyColor.text(), style = MaterialTheme.typography.labelMedium)
                Text(current?.let(::colorSummary) ?: draft.color, style = MaterialTheme.typography.bodyLarge)
            }
            PocketIcon(ShellIcon.CHEVRON_RIGHT)
        }
    }
    draft.pendingColor?.let { pending ->
        KeyColorDialog(pending, { draft.pendingColor = it }, writable,
            dismiss = { draft.pendingColor = null }, apply = { draft.color = pending; draft.pendingColor = null })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeyColorDialog(text: String, change: (String) -> Unit, writable: Boolean, dismiss: () -> Unit, apply: () -> Unit) {
    val color = parseTileColor(text)
    AlertDialog(onDismissRequest = dismiss, title = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(LayoutCopy.KeyColor.text())
            if (color != null) Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ColorSample(color, Modifier.size(32.dp))
                Text(colorSummary(color), style = MaterialTheme.typography.titleMedium)
            }
        }
    }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text(LayoutCopy.Palette.text(), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((argb, name) in presets) {
                    val selected = color == argb
                    FilterChip(selected, onClick = { change(displayColor(argb)) }, enabled = writable,
                        label = { Text(name.text()) },
                        leadingIcon = { ColorSample(argb, Modifier.size(18.dp)) },
                        trailingIcon = if (selected) ({ PocketIcon(ShellIcon.CHECK) }) else null)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(LayoutCopy.CustomColor.text(), style = MaterialTheme.typography.titleSmall)
            for ((shift, name) in listOf(16 to LayoutCopy.Red, 8 to LayoutCopy.Green, 0 to LayoutCopy.Blue)) {
                val channel = color?.let { (it ushr shift) and 0xff }
                val label = name.text()
                fun updateChannel(value: Int) {
                    if (color != null) change(displayColor((color and (0xff shl shift).inv()) or (value.coerceIn(0, 255) shl shift)))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(label, Modifier.widthIn(min = 48.dp))
                    Slider(value = channel?.toFloat() ?: 0f, onValueChange = { updateChannel(it.roundToInt()) },
                        valueRange = 0f..255f, steps = 254, enabled = writable && color != null,
                        colors = SliderDefaults.colors(activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent),
                        modifier = Modifier.weight(1f).onPreviewKeyEvent { event ->
                            val delta = when (event.key) {
                                Key.DirectionLeft, Key.DirectionDown -> -1
                                Key.DirectionRight, Key.DirectionUp -> 1
                                else -> return@onPreviewKeyEvent false
                            }
                            if (channel == null || !writable) return@onPreviewKeyEvent false
                            if (event.type == KeyEventType.KeyDown) updateChannel(channel + delta)
                            true
                        }.semantics {
                            contentDescription = label
                            channel?.let { stateDescription = it.toString() }
                        })
                    Text(channel?.toString() ?: "—", Modifier.widthIn(min = 28.dp))
                }
            }
            OutlinedTextField(text, change, Modifier.fillMaxWidth(), enabled = writable, singleLine = true,
                label = { Text(LayoutCopy.HexColor.text()) }, placeholder = { Text("#RRGGBB") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), isError = color == null,
                supportingText = if (color == null) ({ Text(LayoutCopy.InvalidHex.text()) }) else null)
        }
    }, confirmButton = {
        TextButton(onClick = apply, enabled = writable && color != null) { Text(LayoutCopy.ApplyColor.text()) }
    }, dismissButton = { TextButton(onClick = dismiss) { Text(LayoutCopy.Cancel.text()) } })
}
