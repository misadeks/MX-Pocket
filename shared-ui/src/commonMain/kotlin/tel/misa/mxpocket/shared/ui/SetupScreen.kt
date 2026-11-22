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
    NAVIGATION("Navigate other apps", "Use keypad buttons for Back, Home, scrolling and focus."),
    PROFILES("Switch profiles when an app opens", "Show a matching keypad layout while the phone is unlocked."),
    TEAMS("Show Teams call notices", "Show read-only call status on the keypad. No microphone control is needed."),
    TEAMS_CONTROLS("Use the Teams mic key", "Recognize the joined call toolbar and operate a visible mic button from the keypad.")
}

enum class SetupAction(val label: String) {
    USB("Allow this keypad"), POST("Allow session notifications"), APP_NOTICES("Open notification settings"),
    CHANNEL("Open session channel"), AUTO("Enable plug-in handling"), MEDIA("Open notification access"),
    CAMERA("Enable keypad camera controls"), OVERLAY("Open special access"),
    DIRECT("Enable one-press shortcuts"), NAVIGATION("Open accessibility settings"),
    USAGE("Open usage access settings"), DETECTION("Enable foreground detection"), SWITCHING("Enable profile switching"),
    TEAMS("Enable Teams call status"), TEAMS_NOTICES("Open Teams notification access"), TOOLBAR("Open Teams accessibility settings"),
    AUDIO_PLUGIN("Enable Audio"), STUDIO_PLUGIN("Enable Studio"), NAV_PLUGIN("Enable Navigation"), TEAMS_PLUGIN("Enable Teams")
}

data class SetupStep(val feature: SetupFeature, val title: String, val detail: String, val status: String,
    val ready: Boolean, val action: SetupAction? = null, val applicable: Boolean = true)

data class SetupOption(val feature: SetupFeature, val title: String = feature.title,
    val detail: String = feature.summary, val unavailableReason: String? = null)

data class SetupScreenState(
    val selected: Set<SetupFeature>, val reviewing: Boolean, val steps: List<SetupStep>,
    val message: String, val firstRun: Boolean,
    val options: List<SetupOption> = SetupFeature.entries.map { SetupOption(it) },
    val introduction: String = "First, review USB access and the keypad session notification. Choose any extras below. Nothing turns on until you take its setup action.",
    val reviewHint: String = "Allow only what you want to use. You can finish with steps left off.",
    val connectHint: String? = if (steps.any { it.feature == SetupFeature.USB })
        "Setup does not connect the keypad. To start a session, tap Connect on Home." else null
)

sealed interface SetupEvent {
    data class Select(val feature: SetupFeature) : SetupEvent
    data class Act(val action: SetupAction) : SetupEvent
    data object Review : SetupEvent
    data object Edit : SetupEvent
    data object Finish : SetupEvent
    data object Cancel : SetupEvent
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
                Text(if (reviewing) "Your setup checklist" else "Set up your keypad",
                    Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(8.dp))
                if (!reviewing) {
                    Text(state.introduction)
                    Spacer(Modifier.height(8.dp))
                    if (state.options.any { it.unavailableReason == null })
                        Text("Optional features", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
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
                    if (steps.isNotEmpty()) Text("${steps.count { it.ready }} of ${steps.count { it.applicable }} settings ready", style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary)
                    Text(state.reviewHint)
                    steps.groupBy { it.feature }.forEach { (feature, group) ->
                        Spacer(Modifier.height(24.dp))
                        Text(feature.title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                        group.forEach { step ->
                            Spacer(Modifier.height(12.dp))
                            Text(step.title, style = MaterialTheme.typography.titleMedium)
                            if (step.detail.isNotEmpty()) Text(step.detail, Modifier.padding(vertical = 4.dp), style = MaterialTheme.typography.bodyMedium)
                            Text(step.status, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            step.action?.let { action ->
                                OutlinedButton(onClick = { onEvent(SetupEvent.Act(action)) }) { Text(action.label) }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider()
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
                Text(if (reviewing) "Finish setup" else "Review setup steps")
            }
            if (reviewing) TextButton(onClick = { onEvent(SetupEvent.Edit) }, modifier = Modifier.fillMaxWidth()) { Text("Change choices") }
            else TextButton(onClick = { onEvent(SetupEvent.Cancel) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (firstRun) "Not now" else "Cancel")
            }
        }
    }
}
