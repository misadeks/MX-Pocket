package tel.misa.mxpocket.setup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun SetupScreen(selected: Set<Feature>, reviewing: Boolean, steps: List<SetupStep>, message: String,
    firstRun: Boolean, select: (Feature) -> Unit, review: () -> Unit, edit: () -> Unit,
    act: (SetupAction) -> Unit, done: () -> Unit, virtual: () -> Unit) {
    BackHandler(enabled = reviewing, onBack = edit)
    Column(Modifier.fillMaxSize().safeDrawingPadding().wrapContentWidth(Alignment.CenterHorizontally)
        .widthIn(max = 640.dp).fillMaxWidth()) {
        key(reviewing) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp)) {
                Text(if (reviewing) "Set up your features" else "How will you use your keypad?",
                    Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(8.dp))
                if (!reviewing) {
                    Text("The virtual keypad, basic media keys and volume work without permissions. Pick the extras you want help setting up.")
                    Spacer(Modifier.height(8.dp))
                    Feature.entries.forEach { feature ->
                        Row(Modifier.fillMaxWidth().toggleable(feature in selected, role = Role.Checkbox) { select(feature) }
                            .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(feature.title, style = MaterialTheme.typography.titleMedium)
                                Text(feature.summary, style = MaterialTheme.typography.bodyMedium)
                            }
                            Spacer(Modifier.width(12.dp))
                            Checkbox(feature in selected, onCheckedChange = null)
                        }
                        HorizontalDivider()
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Changing this list does not revoke access or turn off features. Reopen it in Settings > Feature setup.",
                        style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("${steps.count { it.ready }} of ${steps.size} steps ready", style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary)
                    Text("Tap a step to change it. If Android restricts an option, leave it off. You can finish at any time.")
                    steps.forEach { step ->
                        Spacer(Modifier.height(20.dp))
                        Text(step.title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                        Text(step.detail, Modifier.padding(vertical = 6.dp), style = MaterialTheme.typography.bodyMedium)
                        Text(step.status, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        step.action?.let { action ->
                            OutlinedButton(onClick = { act(action) }) { Text(action.label) }
                        }
                    }
                    if (Feature.USB in selected || Feature.AUTO in selected || Feature.DIRECT in selected) {
                        Spacer(Modifier.height(12.dp))
                        Text("When ready, use Connect keypad on Home to start the session.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        HorizontalDivider()
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            if (message.isNotEmpty()) Text(message, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium)
            Button(onClick = if (reviewing) done else review, modifier = Modifier.fillMaxWidth()) {
                Text(when { reviewing -> "Finish setup"; selected.isEmpty() -> "Continue without extras"; else -> "Review setup steps" })
            }
            if (reviewing) TextButton(onClick = edit, modifier = Modifier.fillMaxWidth()) { Text("Change choices") }
            else if (firstRun) TextButton(onClick = virtual, modifier = Modifier.fillMaxWidth()) { Text("Skip and try virtual keypad") }
            else TextButton(onClick = done, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        }
    }
}
