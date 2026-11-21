package tel.misa.mxpocket.shared.ui

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

enum class SetupFeature(val title: String, val summary: String) {
    USB("Use a USB keypad", "Keep the keys active when MX Pocket is in the background."),
    AUTO("Connect on plug-in", "Let Android offer MX Pocket when you attach the keypad."),
    MUSIC("Show player controls and artwork", "Use track details and controls supplied by the active player."),
    STUDIO("Control the phone camera", "Open the native viewfinder. Optionally use the physical Studio keys for shutter, record and stop."),
    DIRECT("Open apps with one press", "Open a supported shortcut from your physical keypad without a notification tap."),
    NAVIGATION("Navigate other apps", "Use keypad buttons for Back, Home, scrolling and focus.")
}

enum class SetupAction(val label: String) {
    USB("Allow this keypad"), POST("Allow session notifications"), APP_NOTICES("Open notification settings"),
    CHANNEL("Open session channel"), AUTO("Enable plug-in handling"), MEDIA("Open notification access"),
    CAMERA("Manage camera controls"), OVERLAY("Open special access"),
    DIRECT("Enable one-press shortcuts"), NAVIGATION("Open accessibility settings")
}

data class SetupStep(val title: String, val detail: String, val status: String,
    val ready: Boolean = false, val action: SetupAction? = null)

data class SetupOption(val feature: SetupFeature, val title: String = feature.title,
    val detail: String = feature.summary, val unavailableReason: String? = null)

data class SetupScreenState(
    val selected: Set<SetupFeature>, val reviewing: Boolean, val steps: List<SetupStep>,
    val message: String, val firstRun: Boolean,
    val options: List<SetupOption> = SetupFeature.entries.map { SetupOption(it) },
    val introduction: String = "The virtual keypad, basic media keys and volume work without permissions. Pick the extras you want help setting up.",
    val reviewHint: String = "Tap a step to change it. If Android restricts an option, leave it off. You can finish at any time.",
    val connectHint: String? = if (selected.any { it in setOf(SetupFeature.USB, SetupFeature.AUTO, SetupFeature.DIRECT) })
        "When ready, use Connect keypad on Home to start the session." else null
)

sealed interface SetupEvent {
    data class Select(val feature: SetupFeature) : SetupEvent
    data class Act(val action: SetupAction) : SetupEvent
    data object Review : SetupEvent
    data object Edit : SetupEvent
    data object Finish : SetupEvent
    data object Cancel : SetupEvent
    data object TryVirtual : SetupEvent
}

@Composable
fun SetupScreen(state: SetupScreenState, onEvent: (SetupEvent) -> Unit) {
    Surface { SetupContent(state, onEvent) }
}

@Composable
private fun SetupContent(state: SetupScreenState, onEvent: (SetupEvent) -> Unit) {
    val (selected, reviewing, steps, message, firstRun) = state
    Column(Modifier.fillMaxSize().safeDrawingPadding().wrapContentWidth(Alignment.CenterHorizontally)
        .widthIn(max = 640.dp).fillMaxWidth()) {
        key(reviewing) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp)) {
                Text(if (reviewing) "Set up your features" else "How will you use your keypad?",
                    Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(8.dp))
                if (!reviewing) {
                    Text(state.introduction)
                    Spacer(Modifier.height(8.dp))
                    state.options.forEach { option ->
                        val feature = option.feature
                        val enabled = option.unavailableReason == null
                        Row(Modifier.fillMaxWidth().toggleable(enabled && feature in selected, enabled = enabled, role = Role.Checkbox) { onEvent(SetupEvent.Select(feature)) }
                            .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(option.title, style = MaterialTheme.typography.titleMedium)
                                Text(option.unavailableReason ?: option.detail, style = MaterialTheme.typography.bodyMedium)
                            }
                            Spacer(Modifier.width(12.dp))
                            Checkbox(enabled && feature in selected, onCheckedChange = null, enabled = enabled)
                        }
                        HorizontalDivider()
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Changing this list does not revoke access or turn off features. Reopen it in Settings > Feature setup.",
                        style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("${steps.count { it.ready }} of ${steps.size} steps ready", style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary)
                    Text(state.reviewHint)
                    steps.forEach { step ->
                        Spacer(Modifier.height(20.dp))
                        Text(step.title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                        Text(step.detail, Modifier.padding(vertical = 6.dp), style = MaterialTheme.typography.bodyMedium)
                        Text(step.status, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        step.action?.let { action ->
                            OutlinedButton(onClick = { onEvent(SetupEvent.Act(action)) }) { Text(action.label) }
                        }
                    }
                    state.connectHint?.let { hint ->
                        Spacer(Modifier.height(12.dp))
                        Text(hint, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        HorizontalDivider()
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            if (message.isNotEmpty()) Text(message, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { onEvent(if (reviewing) SetupEvent.Finish else SetupEvent.Review) }, modifier = Modifier.fillMaxWidth()) {
                Text(when { reviewing -> "Finish setup"; selected.isEmpty() -> "Continue without extras"; else -> "Review setup steps" })
            }
            if (reviewing) TextButton(onClick = { onEvent(SetupEvent.Edit) }, modifier = Modifier.fillMaxWidth()) { Text("Change choices") }
            else if (firstRun) TextButton(onClick = { onEvent(SetupEvent.TryVirtual) }, modifier = Modifier.fillMaxWidth()) { Text("Skip and try virtual keypad") }
            else TextButton(onClick = { onEvent(SetupEvent.Cancel) }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        }
    }
}
