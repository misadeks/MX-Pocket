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

internal enum class BindingTarget(val label: String) { TAP("Tap"), HOLD("Hold") }

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
    var attempted by mutableStateOf(false)

    val error: String? get() = when {
        label.length > DeckLimits.LABEL -> "Use ${DeckLimits.LABEL} characters or fewer for the label."
        icon.length > DeckLimits.ICON -> "Use ${DeckLimits.ICON} characters or fewer for the icon."
        parseTileColor(color) == null -> "Enter six hex digits (RGB) or eight (ARGB), for example #284454."
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
                }
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
    Text("Key actions", style = MaterialTheme.typography.titleSmall)
    for (target in BindingTarget.entries) {
        OutlinedButton(onClick = { query = ""; picking = target }, enabled = writable,
            modifier = Modifier.fillMaxWidth().testTag(if (target == BindingTarget.TAP) "choose-binding" else "choose-hold-binding")) {
            Text("${target.label}: ${bindingName(binding(target), options)}")
        }
    }
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
    val target = draft.picking ?: return
    AlertDialog(onDismissRequest = { draft.picking = null }, title = { Text("Choose a ${target.label.lowercase()} action") },
        text = { BindingChoices(options, draft, Modifier.heightIn(max = 380.dp)) },
        confirmButton = { TextButton(onClick = { draft.picking = null }) { Text("Back to key") } })
}

@Composable
internal fun BindingChoices(options: List<BindingOption>, draft: KeyDraft, listModifier: Modifier = Modifier) {
    val query = draft.query
    val target = draft.picking ?: return
    val selected = draft.binding(target)?.commandBinding()
    fun close() { draft.picking = null }
    fun choose(call: ActionCall?) {
        when (target) {
            BindingTarget.TAP -> draft.call = call
            BindingTarget.HOLD -> draft.holdCall = call
        }
        close()
    }
    val filtered = remember(options, query) {
        options.filter { it.label.contains(query.trim(), ignoreCase = true) || it.call.id.contains(query.trim(), ignoreCase = true) }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(query, { draft.query = it }, Modifier.fillMaxWidth(), label = { Text("Search actions") }, singleLine = true)
        LazyColumn(listModifier.fillMaxWidth()) {
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
}
