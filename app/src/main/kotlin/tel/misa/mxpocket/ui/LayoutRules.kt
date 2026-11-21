package tel.misa.mxpocket.ui

import android.content.ComponentName
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tel.misa.mxpocket.APP_RULE_WEIGHT
import tel.misa.mxpocket.FOREGROUND_APP
import tel.misa.mxpocket.deck.*
import tel.misa.mxpocket.decks.Favorite

@Composable
internal fun RuleSection(profile: DeckProfile, profiles: List<DeckProfile>, signals: Map<String, String>, writable: Boolean,
                         discoverApps: () -> List<Favorite>, detectionControls: @Composable () -> Unit,
                         save: (List<DeckRule>, () -> Unit) -> Unit) {
    var editing by rememberSaveable { mutableStateOf<Int?>(null) }
    var removing by rememberSaveable { mutableStateOf<Int?>(null) }
    var showSignals by rememberSaveable { mutableStateOf(false) }
    var chooseApp by rememberSaveable { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    EditorHeading("Context rules")
    EditorNote("Choose an app to show ${profile.name} when that app is open. Its keys stay the same; none are pressed.")
    OutlinedButton(onClick = { chooseApp = !chooseApp }, enabled = writable && profile.rules.size < DeckLimits.RULES) {
        Text(if (chooseApp) "Cancel app selection" else "Choose app")
    }
    if (chooseApp) {
        AppRulePicker(profile.name, profiles, discoverApps, writable) { pkg ->
            save(profile.rules + DeckRule(FOREGROUND_APP, pkg, APP_RULE_WEIGHT)) { chooseApp = false }
        }
    }
    for ((index, rule) in profile.rules.withIndex()) {
        if (rule.signal == FOREGROUND_APP) {
            EditorNote("${rule.value} → ${profile.name}")
            TextButton(onClick = { removing = index }, enabled = writable) { Text("Remove mapping") }
        }
    }
    EditorNote("Optional: Usage Access lets MX Pocket read app activity on this device. It checks for your mappings only while switching is available and the phone is unlocked. No usage history is saved or sent.")
    detectionControls()
    TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide advanced rules" else "Advanced rules (${profile.rules.size})") }
    if (advanced) AdvancedRules(profile, signals, writable, showSignals, { showSignals = !showSignals },
        edit = { editing = it }, remove = { removing = it })
    editing?.let { index ->
        val original = profile.rules.getOrNull(index)
        if (index == -1 || original != null) RuleDialog(original, signals, writable, close = { editing = null }) { rule ->
            val next = profile.rules.toMutableList()
            if (index == -1) next.add(rule) else next[index] = rule
            save(next) { editing = null }
        }
    }
    removing?.let { index ->
        profile.rules.getOrNull(index)?.let { rule ->
            EditorConfirm("Remove rule?", "${rule.signal} = ${rule.value} will no longer contribute to ${profile.name}’s score.",
                "Remove", writable, { removing = null }) {
                save(profile.rules.filterIndexed { i, _ -> i != index }) { removing = null }
            }
        }
    }
}

@Composable
private fun AdvancedRules(profile: DeckProfile, signals: Map<String, String>, writable: Boolean,
                          showSignals: Boolean, toggleSignals: () -> Unit, edit: (Int) -> Unit, remove: (Int) -> Unit) {
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
        TextButton(onClick = toggleSignals) { Text(if (showSignals) "Hide signals" else "Observed signals (${signals.size})") }
    }
    if (showSignals) {
        if (signals.isEmpty()) EditorNote("No signals observed yet.")
        else for ((signal, value) in signals.toSortedMap()) EditorNote("$signal: $value")
    }
}

@Composable
private fun AppRulePicker(profileName: String, profiles: List<DeckProfile>, discover: () -> List<Favorite>,
                          writable: Boolean, choose: (String) -> Unit) {
    var apps by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var restricted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try {
            apps = withContext(Dispatchers.IO) {
                discover().mapNotNull { app -> ComponentName.unflattenFromString(app.component)?.packageName?.let { it to app.label } }
                    .distinctBy { it.first }
            }
        } catch (_: SecurityException) { restricted = true }
    }
    var search by rememberSaveable { mutableStateOf("") }
    Text("When this app is open, show $profileName", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), label = { Text("Find an app") }, singleLine = true)
    val results = apps?.filter { (pkg, label) -> label.contains(search, true) || pkg.contains(search, true) }
    when {
        restricted -> EditorError("Android restricted the list of launchable apps.")
        results == null -> EditorNote("Loading apps…")
        results.isEmpty() -> EditorNote("No matching launchable apps in this Android profile.")
        else -> LazyColumn(Modifier.heightIn(max = 320.dp)) {
            items(results, key = { it.first }) { (pkg, label) ->
                val mapped = profiles.filter { p -> p.rules.any { it.signal == FOREGROUND_APP && it.value == pkg } }
                TextButton(onClick = { choose(pkg) }, modifier = Modifier.fillMaxWidth(),
                    enabled = writable && mapped.isEmpty() && pkg.length <= DeckLimits.RULE_VALUE) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(label)
                        Text(if (mapped.isEmpty()) pkg else "Mapped to ${mapped.joinToString { it.name }}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun RuleDialog(initial: DeckRule?, signals: Map<String, String>, writable: Boolean, close: () -> Unit, save: (DeckRule) -> Unit) {
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
                        signals.toSortedMap().forEach { (key, sample) ->
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
