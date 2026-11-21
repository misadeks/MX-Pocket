package tel.misa.mxpocket.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.util.Locale
import tel.misa.mxpocket.actions.ActionCall
import tel.misa.mxpocket.deck.*

/** Page IDs are profile-local; a duplicated profile can have the same page IDs with different names. */
internal fun localBindings(options: List<BindingOption>, pages: List<DeckPage>): List<BindingOption> {
    val pageOptions = options.filter { it.call.id == DeckActions.PAGE }.associateBy { it.call.params[DeckActions.PAGE_ID] }
    // The host knows the active deck; the editor can also prepare an inactive profile.
    return (pages.map { page ->
        val call = pageOptions[page.id]?.call ?: ActionCall(DeckActions.PAGE, mapOf(DeckActions.PAGE_ID to page.id))
        BindingOption("Open page: ${page.name}", call)
    } + options.filterNot { it.call.id == DeckActions.PAGE }).distinctBy { it.call }
}

internal fun bindingName(call: ActionCall?, options: List<BindingOption>): String {
    if (call == null) return "Unassigned"
    val displayCall = if (call.slotOwner() == "audio" && call.id.startsWith("media."))
        call.copy(params = call.params - DeckActions.LEGACY_SLOT_OWNER) else call
    return options.firstOrNull { it.call == displayCall }?.label ?: when (call.id) {
        "plugin.key" -> {
            val number = call.params["index"]?.toIntOrNull()?.plus(1)
            "${call.params["pluginId"] ?: "Plugin"} key ${number ?: "?"}"
        }
        else -> "${call.id} (unavailable)"
    }
}

internal fun parseTileColor(text: String): Int? {
    val hex = text.trim().removePrefix("#")
    if (hex.length !in listOf(6, 8) || hex.any { it !in "0123456789abcdefABCDEF" }) return null
    return (if (hex.length == 6) "FF$hex" else hex).toLong(16).toInt()
}

private fun displayColor(color: Int): String =
    if (color ushr 24 == 255) String.format(Locale.ROOT, "#%06X", color and 0xffffff)
    else String.format(Locale.ROOT, "#%08X", color)

private val callSaver = listSaver<MutableState<ActionCall?>, String>(
    save = { state -> state.value?.let { listOf(it.id) + it.params.flatMap { (k, v) -> listOf(k, v) } } ?: listOf("") },
    restore = { parts ->
        mutableStateOf(parts.first().takeIf { it.isNotEmpty() }?.let { ActionCall(it, parts.drop(1).chunked(2).associate { (k, v) -> k to v }) })
    },
)

@Composable
internal fun KeyEditDialog(index: Int, initial: DeckKey, options: List<BindingOption>, writable: Boolean,
                           onClose: () -> Unit, onSave: (DeckKey) -> Unit) {
    var label by rememberSaveable { mutableStateOf(initial.label) }
    var icon by rememberSaveable { mutableStateOf(initial.icon) }
    var color by rememberSaveable { mutableStateOf(displayColor(initial.color)) }
    var enabled by rememberSaveable { mutableStateOf(initial.enabled) }
    var call by rememberSaveable(saver = callSaver) { mutableStateOf(initial.action) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var attempted by rememberSaveable { mutableStateOf(false) }
    val colorValue = parseTileColor(color)
    val error = when {
        label.length > DeckLimits.LABEL -> "Use ${DeckLimits.LABEL} characters or fewer for the label."
        icon.length > DeckLimits.ICON -> "Use ${DeckLimits.ICON} characters or fewer for the icon."
        colorValue == null -> "Enter six hex digits (RGB) or eight (ARGB), for example #284454."
        else -> null
    }
    if (picking) {
        BindingDialog(options, call, close = { picking = false }) { call = it; picking = false }
    } else {
        AlertDialog(onDismissRequest = onClose, title = { Text("Edit key ${index + 1}") }, text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EditorNote("Row ${index / 3 + 1}, column ${index % 3 + 1}")
                OutlinedTextField(label, { label = it }, Modifier.fillMaxWidth(), label = { Text("Key label") }, singleLine = true,
                    isError = attempted && label.length > DeckLimits.LABEL)
                OutlinedTextField(icon, { icon = it }, Modifier.fillMaxWidth(), label = { Text("Icon text or emoji") }, singleLine = true,
                    isError = attempted && icon.length > DeckLimits.ICON)
                OutlinedTextField(color, { color = it }, Modifier.fillMaxWidth(), label = { Text("Key color") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    placeholder = { Text("#284454") }, isError = attempted && colorValue == null)
                Box {
                    var open by remember { mutableStateOf(false) }
                    TextButton(onClick = { open = true }) { Text("Choose a preset color") }
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
                OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(bindingName(call, options))
                }
                EditorSwitch("Enabled on deck", enabled, writable) { enabled = it }
                if (attempted && error != null) EditorError(error)
            }
        }, dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
            confirmButton = { Button(onClick = {
                attempted = true
                if (error == null && colorValue != null) onSave(DeckKey(icon, label, colorValue, call, enabled))
            }, enabled = writable) { Text("Save key") } })
    }
}

private val palette = listOf(
    "Slate" to "#284454", "Blue" to "#245CB5", "Teal" to "#176A63",
    "Amber" to "#E9A73C", "Berry" to "#923F62", "Graphite" to "#373B42",
)

@Composable
private fun BindingDialog(options: List<BindingOption>, selected: ActionCall?, close: () -> Unit, choose: (ActionCall?) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(options, query) {
        options.filter { it.label.contains(query.trim(), ignoreCase = true) || it.call.id.contains(query.trim(), ignoreCase = true) }
    }
    AlertDialog(onDismissRequest = close, title = { Text("Choose an action") }, text = {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Search actions") }, singleLine = true)
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
    }, confirmButton = { TextButton(onClick = close) { Text("Back to key") } })
}
