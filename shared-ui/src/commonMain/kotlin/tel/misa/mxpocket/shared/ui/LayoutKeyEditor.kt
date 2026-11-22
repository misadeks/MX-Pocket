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
import tel.misa.mxpocket.decks.DeckCommand
import tel.misa.mxpocket.decks.navigationCommands
import tel.misa.mxpocket.i18n.*

data class BindingOption(val label: String, val call: ActionCall)

private val factoryProfiles = defaultDeck().visibleProfiles
val factoryKeyBindings: List<BindingOption> by derivedStateOf {
    factoryProfiles.flatMap { profile ->
        profile.pages.single { it.id == profile.rootPageId }.keys.mapIndexedNotNull { physical, key ->
            val call = key.action ?: return@mapIndexedNotNull null
            if (call.id != "plugin.key") return@mapIndexedNotNull null
            val label = if (profile.id == "navigation") LayoutCopy.FactoryCommand.text(profile.displayName(), physical + 1,
                navigationCommands[call.params.getValue("index").toInt()].displayLabel)
            else LayoutCopy.FactoryKey.text(profile.displayName(), physical + 1)
            BindingOption(label, call)
        }
    }
}

private fun ActionCall.blockedInEditor(disabled: Set<String>): Boolean {
    val owner = slotOwner() ?: DeckCommand.fromId(id)?.deck ?: id.substringBefore('.')
    val target = targetProfileId()
    return owner == GAME_PROFILE || owner in disabled || target == GAME_PROFILE || target in disabled
}

fun availableFactoryKeyBindings(disabled: Set<String>) = factoryKeyBindings.filterNot { it.call.blockedInEditor(disabled) }

@Composable
internal fun editorBindings(profile: DeckProfile, options: List<BindingOption>, disabled: Set<String>) =
    remember(profile, options, disabled, AppLocale.current) {
        localBindings(options.filterNot { it.call.blockedInEditor(disabled) }, profile.pages, profile::displayPageName)
    }

/** Page IDs are profile-local; a duplicated profile can have the same page IDs with different names. */
fun localBindings(options: List<BindingOption>, pages: List<DeckPage>, pageName: (DeckPage) -> String = { it.name }): List<BindingOption> {
    val pageOptions = options.filter { it.call.id == DeckActions.PAGE }.associateBy { it.call.params[DeckActions.PAGE_ID] }
    // The host knows the active deck; the editor can also prepare an inactive profile.
    return (pages.map { page ->
        val call = pageOptions[page.id]?.call ?: ActionCall(DeckActions.PAGE, mapOf(DeckActions.PAGE_ID to page.id))
        BindingOption(LayoutCopy.OpenPage.text(pageName(page)), call)
    } + options.filterNot { it.call.id == DeckActions.PAGE }).distinctBy { it.call }
}

internal fun bindingName(call: ActionCall?, options: List<BindingOption>): String {
    if (call == null) return LayoutCopy.Unassigned.text()
    return options.firstOrNull { it.call == call.commandBinding() }?.label ?: when (call.id) {
        "plugin.key" -> {
            val number = call.params["index"]?.toIntOrNull()?.takeIf { it in 0..8 }?.plus(1)
            val slot = factoryKeyBindings.firstOrNull { it.call == call }?.label ?: LayoutCopy.PluginSlot.text(
                call.params["pluginId"]?.let(::builtInDeckName) ?: LayoutCopy.Plugin.text(), number ?: "?")
            LayoutCopy.Unavailable.text(slot)
        }
        else -> LayoutCopy.Unavailable.text(DeckCommand.fromId(call.id)?.displayLabel ?: call.id)
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
    var attempted by mutableStateOf(false)

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
        OutlinedButton(onClick = { query = ""; picking = target }, enabled = writable,
            modifier = Modifier.fillMaxWidth().testTag(if (target == BindingTarget.TAP) "choose-binding" else "choose-hold-binding")) {
            Text(target.summary.text(bindingName(binding(target), options)))
        }
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
    AlertDialog(onDismissRequest = { draft.picking = null }, title = { Text(target.picker.text()) },
        text = { BindingChoices(options, draft, Modifier.heightIn(max = 380.dp)) },
        confirmButton = { TextButton(onClick = { draft.picking = null }) { Text(LayoutCopy.BackToKey.text()) } })
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
        OutlinedTextField(query, { draft.query = it }, Modifier.fillMaxWidth(), label = { Text(LayoutCopy.SearchActions.text()) }, singleLine = true)
        LazyColumn(listModifier.fillMaxWidth()) {
            item {
                DropdownMenuItem(text = { Text((if (selected == null) "✓ " else "") + LayoutCopy.Unassigned.text()) }, onClick = { choose(null) })
            }
            if (filtered.isEmpty()) item { EditorNote(LayoutCopy.NoMatchingActions.text()) }
            items(filtered) { option ->
                DropdownMenuItem(text = { Text(if (selected == option.call) "✓ ${option.label}" else option.label) },
                    onClick = { choose(option.call) })
            }
        }
    }
}
