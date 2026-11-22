package tel.misa.mxpocket.shared.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import tel.misa.mxpocket.actions.ActionCall
import tel.misa.mxpocket.deck.*
import tel.misa.mxpocket.decks.navigationCommands

data class BindingOption(val label: String, val call: ActionCall)

val factoryKeyBindings = defaultDeck().profiles.flatMap { profile ->
    profile.pages.single { it.id == profile.rootPageId }.keys.mapIndexedNotNull { physical, key ->
        val call = key.action ?: return@mapIndexedNotNull null
        if (call.id != "plugin.key") return@mapIndexedNotNull null
        val suffix = if (profile.id == "navigation") ": ${navigationCommands[call.params.getValue("index").toInt()].label}" else ""
        BindingOption("${profile.name} key ${physical + 1}$suffix", call)
    }
}

/** Page IDs are profile-local; a duplicated profile can have the same page IDs with different names. */
fun localBindings(options: List<BindingOption>, pages: List<DeckPage>): List<BindingOption> {
    val pageOptions = options.filter { it.call.id == DeckActions.PAGE }.associateBy { it.call.params[DeckActions.PAGE_ID] }
    // The host knows the active deck; the editor can also prepare an inactive profile.
    return (pages.map { page ->
        val call = pageOptions[page.id]?.call ?: ActionCall(DeckActions.PAGE, mapOf(DeckActions.PAGE_ID to page.id))
        BindingOption("Open page: ${page.name}", call)
    } + options.filterNot { it.call.id == DeckActions.PAGE }).distinctBy { it.call }
}

internal fun bindingName(call: ActionCall?, options: List<BindingOption>): String {
    if (call == null) return "Unassigned"
    return options.firstOrNull { it.call == call.commandBinding() }?.label ?: when (call.id) {
        "plugin.key" -> {
            val number = call.params["index"]?.toIntOrNull()?.plus(1)
            factoryKeyBindings.firstOrNull { it.call == call }?.label ?: "${call.params["pluginId"] ?: "Plugin"} slot ${number ?: "?"}"
        }
        else -> "${call.id} (unavailable)"
    }
}

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

/** Owned by the editor, not by either layout branch or by the action picker. */
internal class KeyDraft(val original: DeckKey) {
    var label by mutableStateOf(original.label)
    var icon by mutableStateOf(original.icon)
    var color by mutableStateOf(displayColor(original.color))
    var enabled by mutableStateOf(original.enabled)
    var call by mutableStateOf(original.action)
    var picking by mutableStateOf(false)
    var query by mutableStateOf("")
    var attempted by mutableStateOf(false)

    val error: String? get() = when {
        label.length > DeckLimits.LABEL -> "Use ${DeckLimits.LABEL} characters or fewer for the label."
        icon.length > DeckLimits.ICON -> "Use ${DeckLimits.ICON} characters or fewer for the icon."
        parseTileColor(color) == null -> "Enter six hex digits (RGB) or eight (ARGB), for example #284454."
        else -> null
    }

    fun preview() = DeckKey(icon, label, parseTileColor(color) ?: original.color, call, enabled)

    companion object {
        // A flat list of strings is saveable by both hosts, including before the color is valid.
        val saver = listSaver<KeyDraft, String>(save = {
            val savedCall = it.original.action.parts()
            listOf(it.original.icon, it.original.label, it.original.color.toString(), it.original.enabled.toString(),
                it.label, it.icon, it.color, it.enabled.toString(), it.picking.toString(), it.query, it.attempted.toString(),
                savedCall.size.toString()) + savedCall + it.call.parts()
        }, restore = { p ->
            val end = 12 + p[11].toInt()
            KeyDraft(DeckKey(p[0], p[1], p[2].toInt(), p.subList(12, end).call(), p[3].toBoolean())).apply {
                label = p[4]; icon = p[5]; color = p[6]; enabled = p[7].toBoolean()
                picking = p[8].toBoolean(); query = p[9]; attempted = p[10].toBoolean(); call = p.drop(end).call()
            }
        })
    }
}

@Composable
internal fun KeyFields(index: Int, draft: KeyDraft, options: List<BindingOption>, writable: Boolean,
                       onClose: () -> Unit, onSave: (DeckKey) -> Unit) = with(draft) {
    EditorHeading("Edit key ${index + 1}")
    EditorNote("Row ${index / 3 + 1}, column ${index % 3 + 1}")
    OutlinedTextField(label, { label = it }, Modifier.fillMaxWidth().testTag("key-label"), enabled = writable,
        label = { Text("Key label") }, singleLine = true, isError = attempted && label.length > DeckLimits.LABEL)
    OutlinedTextField(icon, { icon = it }, Modifier.fillMaxWidth(), enabled = writable,
        label = { Text("Icon text or emoji") }, singleLine = true, isError = attempted && icon.length > DeckLimits.ICON)
    OutlinedTextField(color, { color = it }, Modifier.fillMaxWidth(), enabled = writable,
        label = { Text("Key color") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        placeholder = { Text("#284454") }, isError = attempted && parseTileColor(color) == null)
    Box {
        var open by remember { mutableStateOf(false) }
        TextButton(onClick = { open = true }, enabled = writable) { Text("Choose a preset color") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((name, hex) in palette) {
                DropdownMenuItem(text = { Text(name) }, leadingIcon = {
                    Surface(Modifier.size(20.dp), shape = MaterialTheme.shapes.small,
                        color = androidx.compose.ui.graphics.Color(parseTileColor(hex)!!)) {}
                }, onClick = { color = hex; open = false })
            }
        }
    }
    Text("Assigned action", style = MaterialTheme.typography.titleSmall)
    OutlinedButton(onClick = { query = ""; picking = true }, enabled = writable,
        modifier = Modifier.fillMaxWidth().testTag("choose-binding")) { Text(bindingName(call, options)) }
    EditorSwitch("Enabled on deck", enabled, writable) { enabled = it }
    if (attempted) error?.let { EditorError(it) }
    EditorActions {
        Button(onClick = { attempted = true; if (error == null) onSave(preview()) }, enabled = writable) { Text("Save key") }
        TextButton(onClick = onClose) { Text("Cancel") }
    }
}

private val palette = listOf(
    "Slate" to "#284454", "Blue" to "#245CB5", "Teal" to "#176A63",
    "Amber" to "#E9A73C", "Berry" to "#923F62", "Graphite" to "#373B42",
)

@Composable
internal fun BindingDialog(options: List<BindingOption>, draft: KeyDraft) {
    val query = draft.query
    val selected = draft.call
    fun close() { draft.picking = false }
    fun choose(call: ActionCall?) { draft.call = call; close() }
    val filtered = remember(options, query) {
        options.filter { it.label.contains(query.trim(), ignoreCase = true) || it.call.id.contains(query.trim(), ignoreCase = true) }
    }
    AlertDialog(onDismissRequest = ::close, title = { Text("Choose an action") }, text = {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(query, { draft.query = it }, Modifier.fillMaxWidth(), label = { Text("Search actions") }, singleLine = true)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                item {
                    DropdownMenuItem(text = { Text(if (selected == null) "✓ Unassigned" else "Unassigned") }, onClick = { choose(null) })
                }
                if (filtered.isEmpty()) item { EditorNote("No matching actions.") }
                items(filtered) { option ->
                    DropdownMenuItem(text = { Text(if (selected == option.call) "✓ ${option.label}" else option.label) },
                        onClick = { choose(option.call) })
                }
            }
        }
    }, confirmButton = { TextButton(onClick = ::close) { Text("Back to key") } })
}
