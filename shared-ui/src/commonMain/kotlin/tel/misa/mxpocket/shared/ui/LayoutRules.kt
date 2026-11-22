package tel.misa.mxpocket.shared.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import tel.misa.mxpocket.deck.*
import tel.misa.mxpocket.FOREGROUND_APP
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items

data class MappingApp(val packageId: String, val label: String)
data class ForegroundControls(val enabled: Boolean, val granted: Boolean,
    val setEnabled: (Boolean) -> Unit, val openSettings: () -> String?,
    val discover: suspend () -> List<MappingApp>?)

@Composable
internal fun RuleSection(profile: DeckProfile, profiles: List<DeckProfile>, signals: Map<String, String>, writable: Boolean,
                         foreground: ForegroundControls?, addApp: (String, () -> Unit) -> Unit,
                         edit: (Int) -> Unit, remove: (Int) -> Unit, disabledIds: Set<String>) {
    var showSignals by rememberSaveable { mutableStateOf(false) }
    var choosing by rememberSaveable { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    EditorHeading("Context rules")
    profile.rules.forEachIndexed { i, rule -> if (rule.signal == FOREGROUND_APP) {
        EditorNote("${rule.value} → ${profile.name}")
        TextButton(onClick = { remove(i) }, enabled = writable) { Text("Remove mapping") }
    } }
    if (foreground == null) EditorNote("Installed-app discovery and foreground detection require Android.")
    else {
        OutlinedButton(onClick = { choosing = !choosing }, enabled = writable && profile.rules.size < DeckLimits.RULES) {
            Text(if (choosing) "Cancel app selection" else "Choose app")
        }
        if (choosing) {
            var apps by remember { mutableStateOf<List<MappingApp>?>(null) }
            var loaded by remember { mutableStateOf(false) }
            var query by rememberSaveable { mutableStateOf("") }
            LaunchedEffect(Unit) { apps = foreground.discover(); loaded = true }
            Text("When this app is open, show ${profile.name}")
            OutlinedTextField(query, { query = it }, label = { Text("Find an app") }, singleLine = true)
            val results = apps.orEmpty().filter { it.label.contains(query, true) || it.packageId.contains(query, true) }
            when {
                !loaded -> EditorNote("Loading apps…")
                apps == null -> EditorError("Android restricted the list of launchable apps.")
                results.isEmpty() -> EditorNote("No matching launchable apps in this Android profile.")
                else -> LazyColumn(Modifier.heightIn(max = 320.dp)) { items(results, key = { it.packageId }) { app ->
                    val mapped = profiles.filter { p -> p.rules.any { it.signal == FOREGROUND_APP && it.value == app.packageId } }
                    TextButton(onClick = { addApp(app.packageId) { choosing = false } },
                        enabled = writable && mapped.isEmpty() && app.packageId.length <= DeckLimits.RULE_VALUE) {
                        Column {
                            Text(app.label)
                            Text(when {
                                mapped.isEmpty() -> app.packageId
                                mapped.all { it.id in disabledIds } -> "Mapped to a disabled plugin"
                                else -> "Mapped to ${mapped.filterNot { it.id in disabledIds }.joinToString { it.name }}"
                            })
                        }
                    }
                } }
            }
        }
        EditorNote("Usage Access lets MX Pocket check for mapped apps while switching is available and the phone is unlocked. No usage history is saved or sent.")
        EditorSwitch("Detect foreground apps", foreground.enabled, true, foreground.setEnabled)
        if (foreground.enabled) {
            var error by remember { mutableStateOf<String?>(null) }
            EditorNote("Android Usage Access: ${if (foreground.granted) "granted" else "not granted"}.")
            TextButton(onClick = { error = foreground.openSettings() }) { Text("Open usage access settings") }
            error?.let { EditorError(it) }
        }
    }
    TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide advanced rules" else "Advanced rules (${profile.rules.size})") }
    if (!advanced) return
    EditorNote("Matching rules add their weight to ${profile.name}’s score. A signal that isn’t observed adds nothing.")
    if (profile.rules.isEmpty()) {
        EditorNote("No rules for this profile.")
    } else {
        for ((index, rule) in profile.rules.withIndex()) {
            val sample = signals[rule.signal]
            val matches = rule.matches(signals)
            Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text("${rule.signal} = ${rule.value}", style = MaterialTheme.typography.titleMedium)
                EditorNote(buildString {
                    append("Weight ${rule.weight} · ")
                    when {
                        matches -> append("Matches now (+${rule.weight})")
                        sample == null -> append("Not observed")
                        else -> append("Does not match (now: $sample)")
                    }
                })
                EditorActions {
                    TextButton(onClick = { edit(index) }, enabled = writable) { Text("Edit rule") }
                    TextButton(onClick = { remove(index) }, enabled = writable) { Text("Remove rule") }
                }
            }
        }
    }
    EditorActions {
        OutlinedButton(onClick = { edit(-1) }, enabled = writable && profile.rules.size < DeckLimits.RULES) { Text("Add rule") }
        TextButton(onClick = { showSignals = !showSignals }) { Text(if (showSignals) "Hide signals" else "Observed signals (${signals.size})") }
    }
    if (showSignals) {
        if (signals.isEmpty()) EditorNote("No signals observed yet.")
        else for ((signal, value) in signals.entries.sortedBy { it.key }) EditorNote("$signal: $value")
    }
}

@Composable
internal fun RuleDialog(initial: DeckRule?, signals: Map<String, String>, writable: Boolean, close: () -> Unit, save: (DeckRule) -> Unit) {
    val first = signals.keys.sorted().firstOrNull().orEmpty()
    var signal by rememberSaveable { mutableStateOf(initial?.signal ?: first) }
    var value by rememberSaveable { mutableStateOf(initial?.value ?: signals[first].orEmpty()) }
    var weight by rememberSaveable { mutableStateOf((initial?.weight ?: 1).toString()) }
    var attempted by rememberSaveable { mutableStateOf(false) }
    val number = weight.toIntOrNull()
    val error = when {
        signal.isBlank() -> "Choose an observed signal or enter its name."
        !signalId.matches(signal) -> "Use a signal name of 1–64 letters, digits, dots, underscores or hyphens, starting with a letter or digit."
        value.isBlank() || value.length > DeckLimits.RULE_VALUE -> "Enter a value of 1–${DeckLimits.RULE_VALUE} characters."
        number == null || number !in 1..DeckLimits.MAX_WEIGHT -> "Enter a whole-number weight from 1 to ${DeckLimits.MAX_WEIGHT}."
        else -> null
    }
    AlertDialog(onDismissRequest = close, title = { Text(if (initial == null) "Add context rule" else "Edit context rule") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    var open by remember { mutableStateOf(false) }
                    OutlinedButton(onClick = { open = true }, enabled = signals.isNotEmpty()) { Text("Choose observed signal") }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        signals.entries.sortedBy { it.key }.forEach { (key, sample) ->
                            DropdownMenuItem(text = { Text("$key: $sample") }, onClick = { signal = key; value = sample; open = false })
                        }
                    }
                }
                OutlinedTextField(signal, { signal = it }, Modifier.fillMaxWidth(), label = { Text("Signal name") }, singleLine = true)
                EditorNote(signals[signal]?.let { "Observed now: $it" } ?: "Not currently observed")
                OutlinedTextField(value, { value = it }, Modifier.fillMaxWidth(),
                    label = { Text(if (signal == "hour") "Hour or range" else "Matching value") }, singleLine = true)
                if (signal == "hour") EditorNote("Use a local hour (0–23) or an inclusive range, such as 9-17 or 22-5.")
                OutlinedTextField(weight, { weight = it }, Modifier.fillMaxWidth(), label = { Text("Weight (1–${DeckLimits.MAX_WEIGHT})") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                if (attempted && error != null) EditorError(error)
            }
        }, dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
        confirmButton = { Button(onClick = { attempted = true; if (error == null && number != null) save(DeckRule(signal, value, number)) },
            enabled = writable) { Text("Save rule") } })
}

private val signalId = Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}")
