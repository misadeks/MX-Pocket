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
import tel.misa.mxpocket.i18n.*
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
    EditorHeading(LayoutCopy.Rules.text())
    profile.rules.forEachIndexed { i, rule -> if (rule.signal == FOREGROUND_APP) {
        EditorNote("${rule.value} → ${profile.displayName()}")
        TextButton(onClick = { remove(i) }, enabled = writable) { Text(LayoutCopy.RemoveMapping.text()) }
    } }
    if (foreground == null) EditorNote(LayoutCopy.AndroidOnly.text())
    else {
        OutlinedButton(onClick = { choosing = !choosing }, enabled = writable && profile.rules.size < DeckLimits.RULES) {
            Text((if (choosing) LayoutCopy.CancelApps else LayoutCopy.ChooseApp).text())
        }
        if (choosing) {
            var apps by remember { mutableStateOf<List<MappingApp>?>(null) }
            var loaded by remember { mutableStateOf(false) }
            var query by rememberSaveable { mutableStateOf("") }
            LaunchedEffect(Unit) { apps = foreground.discover(); loaded = true }
            Text(LayoutCopy.ShowForApp.text(profile.displayName()))
            OutlinedTextField(query, { query = it }, label = { Text(LayoutCopy.FindApp.text()) }, singleLine = true)
            val results = apps.orEmpty().filter { it.label.contains(query, true) || it.packageId.contains(query, true) }
            when {
                !loaded -> EditorNote(LayoutCopy.LoadingApps.text())
                apps == null -> EditorError(LayoutCopy.RestrictedApps.text())
                results.isEmpty() -> EditorNote(LayoutCopy.NoApps.text())
                else -> LazyColumn(Modifier.heightIn(max = 320.dp)) { items(results, key = { it.packageId }) { app ->
                    val mapped = profiles.filter { p -> p.rules.any { it.signal == FOREGROUND_APP && it.value == app.packageId } }
                    val visible = mapped.filterNot { it.id in disabledIds || it.id == GAME_PROFILE }
                    TextButton(onClick = { addApp(app.packageId) { choosing = false } },
                        enabled = writable && mapped.isEmpty() && app.packageId.length <= DeckLimits.RULE_VALUE) {
                        Column {
                            Text(app.label)
                            Text(when {
                                mapped.isEmpty() -> app.packageId
                                visible.isEmpty() -> LayoutCopy.MappedUnavailable.text()
                                else -> LayoutCopy.MappedTo.text(visible.joinToString { it.displayName() })
                            })
                        }
                    }
                } }
            }
        }
        EditorNote(LayoutCopy.UsageHint.text())
        EditorSwitch(LayoutCopy.DetectApps.text(), foreground.enabled, true, foreground.setEnabled)
        if (foreground.enabled) {
            var error by remember { mutableStateOf<String?>(null) }
            EditorNote((if (foreground.granted) LayoutCopy.UsageGranted else LayoutCopy.UsageDenied).text())
            TextButton(onClick = { error = foreground.openSettings() }) { Text(LayoutCopy.UsageSettings.text()) }
            error?.let { EditorError(it) }
        }
    }
    TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) LayoutCopy.HideAdvanced.text() else LayoutCopy.AdvancedRules.text(profile.rules.size)) }
    if (!advanced) return
    EditorNote(LayoutCopy.RuleHint.text(profile.displayName()))
    if (profile.rules.isEmpty()) {
        EditorNote(LayoutCopy.NoRules.text())
    } else {
        for ((index, rule) in profile.rules.withIndex()) {
            val sample = signals[rule.signal]
            val matches = rule.matches(signals)
            Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text("${rule.signal} = ${rule.value}", style = MaterialTheme.typography.titleMedium)
                EditorNote(LayoutCopy.RuleStatus.text(rule.weight, when {
                    matches -> LayoutCopy.Matches.text(rule.weight)
                    sample == null -> LayoutCopy.NotObserved.text()
                    else -> LayoutCopy.Mismatch.text(sample)
                }))
                EditorActions {
                    TextButton(onClick = { edit(index) }, enabled = writable) { Text(LayoutCopy.EditRule.text()) }
                    TextButton(onClick = { remove(index) }, enabled = writable) { Text(LayoutCopy.RemoveRule.text()) }
                }
            }
        }
    }
    EditorActions {
        OutlinedButton(onClick = { edit(-1) }, enabled = writable && profile.rules.size < DeckLimits.RULES) { Text(LayoutCopy.AddRule.text()) }
        TextButton(onClick = { showSignals = !showSignals }) { Text(if (showSignals) LayoutCopy.HideSignals.text() else LayoutCopy.ObservedSignals.text(signals.size)) }
    }
    if (showSignals) {
        if (signals.isEmpty()) EditorNote(LayoutCopy.NoSignals.text())
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
        signal.isBlank() -> LayoutCopy.MissingSignal.text()
        !signalId.matches(signal) -> LayoutCopy.SignalSyntax.text()
        value.isBlank() || value.length > DeckLimits.RULE_VALUE -> LayoutCopy.ValueLength.text(DeckLimits.RULE_VALUE)
        number == null || number !in 1..DeckLimits.MAX_WEIGHT -> LayoutCopy.WeightRange.text(DeckLimits.MAX_WEIGHT)
        else -> null
    }
    AlertDialog(onDismissRequest = close, title = { Text((if (initial == null) LayoutCopy.AddContextRule else LayoutCopy.EditContextRule).text()) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    var open by remember { mutableStateOf(false) }
                    OutlinedButton(onClick = { open = true }, enabled = signals.isNotEmpty()) { Text(LayoutCopy.ChooseSignal.text()) }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        signals.entries.sortedBy { it.key }.forEach { (key, sample) ->
                            DropdownMenuItem(text = { Text("$key: $sample") }, onClick = { signal = key; value = sample; open = false })
                        }
                    }
                }
                OutlinedTextField(signal, { signal = it }, Modifier.fillMaxWidth(), label = { Text(LayoutCopy.SignalName.text()) }, singleLine = true)
                EditorNote(signals[signal]?.let { LayoutCopy.Observed.text(it) } ?: LayoutCopy.NoSample.text())
                OutlinedTextField(value, { value = it }, Modifier.fillMaxWidth(),
                    label = { Text((if (signal == "hour") LayoutCopy.HourOrRange else LayoutCopy.MatchValue).text()) }, singleLine = true)
                if (signal == "hour") EditorNote(LayoutCopy.HourHint.text())
                OutlinedTextField(weight, { weight = it }, Modifier.fillMaxWidth(), label = { Text(LayoutCopy.Weight.text(DeckLimits.MAX_WEIGHT)) },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                if (attempted && error != null) EditorError(error)
            }
        }, dismissButton = { TextButton(onClick = close) { Text(LayoutCopy.Cancel.text()) } },
        confirmButton = { Button(onClick = { attempted = true; if (error == null && number != null) save(DeckRule(signal, value, number)) },
            enabled = writable) { Text(LayoutCopy.SaveRule.text()) } })
}

private val signalId = Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}")
