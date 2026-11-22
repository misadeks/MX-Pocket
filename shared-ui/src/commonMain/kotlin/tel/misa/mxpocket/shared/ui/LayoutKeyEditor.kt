package tel.misa.mxpocket.shared.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import tel.misa.mxpocket.actions.ActionCall
import tel.misa.mxpocket.deck.*
import tel.misa.mxpocket.i18n.*

fun parseTileColor(text: String): Int? {
    val hex = text.trim().removePrefix("#")
    if (hex.length !in listOf(6, 8) || hex.any { it !in "0123456789abcdefABCDEF" }) return null
    return (if (hex.length == 6) "FF$hex" else hex).toLong(16).toInt()
}

private fun displayColor(color: Int): String = "#" + if (color ushr 24 == 255)
    (color and 0xffffff).toString(16).uppercase().padStart(6, '0')
else color.toUInt().toString(16).uppercase().padStart(8, '0')

private fun ActionCall?.parts() = this?.let { listOf(it.id) + it.params.flatMap { (k, v) -> listOf(k, v) } }.orEmpty()
private fun List<String>.call(): ActionCall? = firstOrNull()?.let { id ->
    ActionCall(id, drop(1).chunked(2).associate { (k, v) -> k to v })
}

internal enum class BindingTarget(val summary: Copy, val picker: Copy) {
    TAP(LayoutCopy.TapBinding, LayoutCopy.ChooseTap), HOLD(LayoutCopy.HoldBinding, LayoutCopy.ChooseHold)
}

/** Owned by the editor, not by either layout branch or by the action picker. */
internal class KeyDraft(val original: DeckKey) {
    var label by mutableStateOf(original.label)
    var icon by mutableStateOf(original.icon)
    var color by mutableStateOf(displayColor(original.color))
    var enabled by mutableStateOf(original.enabled)
    var call by mutableStateOf(original.action)
    var holdCall by mutableStateOf(original.holdAction)
    var picking by mutableStateOf<BindingTarget?>(null)
    var query by mutableStateOf("")
    var category by mutableStateOf<ActionCategory?>(null)
    var attempted by mutableStateOf(false)

    fun pickerBack() {
        when {
            query.isNotEmpty() -> query = ""
            category != null -> category = null
            else -> picking = null
        }
    }

    val error: String? get() = when {
        label.length > DeckLimits.LABEL -> LayoutCopy.LabelLength.text(DeckLimits.LABEL)
        icon.length > DeckLimits.ICON -> LayoutCopy.IconLength.text(DeckLimits.ICON)
        parseTileColor(color) == null -> LayoutCopy.InvalidHex.text()
        else -> null
    }

    fun binding(target: BindingTarget) = when (target) {
        BindingTarget.TAP -> call
        BindingTarget.HOLD -> holdCall
    }

    fun preview() = original.copy(icon = icon, label = label, color = parseTileColor(color) ?: original.color,
        action = call, enabled = enabled, holdAction = holdCall)

    companion object {
        // A flat list of strings is saveable by both hosts, including before the color is valid.
        val saver = listSaver<KeyDraft, String>(save = {
            listOf(it.original.icon, it.original.label, it.original.color.toString(), it.original.enabled.toString(),
                it.label, it.icon, it.color, it.enabled.toString(), it.picking?.name.orEmpty(), it.query, it.attempted.toString()) +
                listOf(it.original.action, it.original.holdAction, it.call, it.holdCall).flatMap { call ->
                    val parts = call.parts()
                    listOf(parts.size.toString()) + parts
                } + listOf(it.category?.name.orEmpty())
        }, restore = { p ->
            val parts = p.listIterator(11)
            fun nextCall() = List(parts.next().toInt()) { parts.next() }.call()
            // The old saver used a boolean for the Tap picker and stored only the two Tap calls.
            val legacy = p[8] == "true" || p[8] == "false"
            val original = DeckKey(p[0], p[1], p[2].toInt(), nextCall(), p[3].toBoolean(), if (legacy) null else nextCall())
            KeyDraft(original).apply {
                label = p[4]; icon = p[5]; color = p[6]; enabled = p[7].toBoolean()
                picking = if (p[8] == "true") BindingTarget.TAP else BindingTarget.entries.find { it.name == p[8] }
                query = p[9]; attempted = p[10].toBoolean()
                call = if (legacy) parts.asSequence().toList().call() else nextCall()
                holdCall = if (legacy) null else nextCall()
                if (parts.hasNext()) category = ActionCategory.entries.find { it.name == p.last() }
            }
        })
    }
}

@Composable
internal fun KeyFields(index: Int, draft: KeyDraft, options: List<BindingOption>, writable: Boolean,
                       onClose: () -> Unit, onSave: (DeckKey) -> Unit) = with(draft) {
    EditorHeading(LayoutCopy.EditKey.text(index + 1))
    EditorNote(LayoutCopy.Coordinates.text(index / 3 + 1, index % 3 + 1))
    OutlinedTextField(label, { label = it }, Modifier.fillMaxWidth().testTag("key-label"), enabled = writable,
        label = { Text(LayoutCopy.KeyLabel.text()) }, singleLine = true, isError = attempted && label.length > DeckLimits.LABEL)
    OutlinedTextField(icon, { icon = it }, Modifier.fillMaxWidth(), enabled = writable,
        label = { Text(LayoutCopy.IconText.text()) }, singleLine = true, isError = attempted && icon.length > DeckLimits.ICON)
    OutlinedTextField(color, { color = it }, Modifier.fillMaxWidth(), enabled = writable,
        label = { Text(LayoutCopy.KeyColor.text()) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        placeholder = { Text("#284454") }, isError = attempted && parseTileColor(color) == null)
    Box {
        var open by remember { mutableStateOf(false) }
        TextButton(onClick = { open = true }, enabled = writable) { Text(LayoutCopy.ChooseColor.text()) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((name, hex) in palette) {
                DropdownMenuItem(text = { Text(name.text()) }, leadingIcon = {
                    Surface(Modifier.size(20.dp), shape = MaterialTheme.shapes.small,
                        color = androidx.compose.ui.graphics.Color(parseTileColor(hex)!!)) {}
                }, onClick = { color = hex; open = false })
            }
        }
    }
    Text(LayoutCopy.KeyActions.text(), style = MaterialTheme.typography.titleSmall)
    for (target in BindingTarget.entries) {
        OutlinedButton(onClick = { query = ""; category = null; picking = target }, enabled = writable,
            modifier = Modifier.fillMaxWidth().testTag(if (target == BindingTarget.TAP) "choose-binding" else "choose-hold-binding")) {
            Text(target.summary.text(bindingName(binding(target), options)))
        }
        options.find { it.call == binding(target) }?.unavailableReason?.let { EditorNote(it) }
    }
    EditorSwitch(LayoutCopy.EnabledOnDeck.text(), enabled, writable) { enabled = it }
    if (attempted) error?.let { EditorError(it) }
    EditorActions {
        Button(onClick = { attempted = true; if (error == null) onSave(preview()) }, enabled = writable) { Text(LayoutCopy.SaveKey.text()) }
        TextButton(onClick = onClose) { Text(LayoutCopy.Cancel.text()) }
    }
}

private val palette = listOf(
    LayoutCopy.Slate to "#284454", LayoutCopy.Blue to "#245CB5", LayoutCopy.Teal to "#176A63",
    LayoutCopy.Amber to "#E9A73C", LayoutCopy.Berry to "#923F62", LayoutCopy.Graphite to "#373B42",
)

@Composable
internal fun BindingDialog(options: List<BindingOption>, draft: KeyDraft) {
    val target = draft.picking ?: return
    AlertDialog(onDismissRequest = draft::pickerBack, title = { Text(target.picker.text()) },
        text = { BindingChoices(options, draft, Modifier.heightIn(max = 380.dp)) },
        confirmButton = { TextButton(onClick = { draft.picking = null }) { Text(LayoutCopy.BackToKey.text()) } })
}

@Composable
internal fun BindingChoices(options: List<BindingOption>, draft: KeyDraft, listModifier: Modifier = Modifier) {
    val query = draft.query.trim()
    val target = draft.picking ?: return
    val current = draft.binding(target)
    fun close() { draft.picking = null }
    fun choose(call: ActionCall?) {
        when (target) {
            BindingTarget.TAP -> draft.call = call
            BindingTarget.HOLD -> draft.holdCall = call
        }
        close()
    }
    val categories = ActionCategory.entries.filter { category -> options.any { it.category == category } }
    val group = draft.category?.takeIf { it in categories }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(draft.query, { draft.query = it }, Modifier.fillMaxWidth(), label = { Text(LayoutCopy.SearchActions.text()) }, singleLine = true)
        if (group != null && query.isEmpty()) {
            TextButton(onClick = { draft.category = null }) {
                PocketIcon(ShellIcon.CHEVRON_LEFT)
                Text(BindingCopy.Categories.text())
            }
        }
        key(group, query.isNotEmpty()) {
            LazyColumn(listModifier.fillMaxWidth().testTag("action-list")) {
                if (group == null && query.isEmpty()) {
                    items(categories) { category ->
                        LibraryRow(category.title.text(), null, true) { draft.category = category }
                    }
                    item { BindingRow(LayoutCopy.Unassigned.text(), current == null) { choose(null) } }
                } else {
                    val results = if (query.isEmpty()) options.filter { it.category == group } else
                        options.filter { it.label.contains(query, ignoreCase = true) || it.category.title.text().contains(query, ignoreCase = true) }
                    if (results.isEmpty()) item { EditorNote(LayoutCopy.NoMatchingActions.text()) }
                    for ((category, choices) in results.groupBy { it.category }) {
                        item { EditorHeading(category.title.text()) }
                        items(choices) { option ->
                            if (option.unavailableReason != null) UnavailableRow(option.label, option.unavailableReason)
                            else BindingRow(option.label, current == option.call) { choose(option.call) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BindingRow(label: String, chosen: Boolean, choose: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, onClick = choose,
        modifier = Modifier.semantics { selected = chosen },
        trailingIcon = { if (chosen) PocketIcon(ShellIcon.CHECK) })
}
