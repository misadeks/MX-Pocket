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

import tel.misa.mxpocket.i18n.AccessCopy
import tel.misa.mxpocket.i18n.Copy

enum class SetupFeature(private val titleCopy: Copy, private val summaryCopy: Copy) {
    USB(AccessCopy.Usb, AccessCopy.UsbSummary),
    AUTO(AccessCopy.Auto, AccessCopy.AutoSummary),
    MUSIC(AccessCopy.Music, AccessCopy.MusicSummary),
    STUDIO(AccessCopy.Studio, AccessCopy.StudioSummary),
    DIRECT(AccessCopy.Direct, AccessCopy.DirectSummary),
    NAVIGATION(AccessCopy.Navigation, AccessCopy.NavigationSummary),
    PROFILES(AccessCopy.FeatureProfiles, AccessCopy.FeatureProfilesSummary),
    TEAMS(AccessCopy.FeatureTeams, AccessCopy.FeatureTeamsSummary),
    TEAMS_CONTROLS(AccessCopy.FeatureMic, AccessCopy.FeatureMicSummary);

    val title get() = titleCopy.text()
    val summary get() = summaryCopy.text()
}

enum class SetupAction(private val copy: Copy) {
    USB(AccessCopy.AllowKeypad), POST(AccessCopy.AllowNotices), APP_NOTICES(AccessCopy.OpenNoticeSettings),
    CHANNEL(AccessCopy.OpenChannel), AUTO(AccessCopy.EnableAuto), MEDIA(AccessCopy.OpenNotificationAccess),
    CAMERA(AccessCopy.EnableCamera), OVERLAY(AccessCopy.OpenSpecialAccess),
    DIRECT(AccessCopy.EnableDirect), NAVIGATION(AccessCopy.OpenAccessibility),
    USAGE(AccessCopy.OpenUsage), DETECTION(AccessCopy.EnableDetection), SWITCHING(AccessCopy.EnableSwitching),
    TEAMS(AccessCopy.EnableTeams), TEAMS_NOTICES(AccessCopy.OpenTeamsNotices), TOOLBAR(AccessCopy.OpenTeamsToolbar),
    AUDIO_PLUGIN(AccessCopy.EnableAudio), STUDIO_PLUGIN(AccessCopy.EnableStudio), NAV_PLUGIN(AccessCopy.EnableNavigation), TEAMS_PLUGIN(AccessCopy.EnableTeamsPlugin);

    val label get() = copy.text()
}

data class SetupStep(val feature: SetupFeature, val title: String, val detail: String, val status: String,
    val ready: Boolean, val action: SetupAction? = null, val applicable: Boolean = true)

data class SetupOption(val feature: SetupFeature, val title: String = feature.title,
    val detail: String = feature.summary, val unavailableReason: String? = null)

data class SetupScreenState(
    val selected: Set<SetupFeature>, val reviewing: Boolean, val steps: List<SetupStep>,
    val message: String, val firstRun: Boolean,
    val options: List<SetupOption> = SetupFeature.entries.map { SetupOption(it) },
    val introduction: String = AccessCopy.Introduction.text(),
    val reviewHint: String = AccessCopy.ReviewHint.text(),
    val connectHint: String? = if (steps.any { it.feature == SetupFeature.USB })
        AccessCopy.ConnectHint.text() else null
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
                Text(if (reviewing) AccessCopy.ReviewFeatures.text() else AccessCopy.ChooseFeatures.text(),
                    Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(8.dp))
                if (!reviewing) {
                    Text(state.introduction)
                    Spacer(Modifier.height(8.dp))
                    if (state.options.any { it.unavailableReason == null })
                        Text(AccessCopy.OptionalFeatures.text(), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
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
                    Text(AccessCopy.ChoicesHint.text(),
                        style = MaterialTheme.typography.bodySmall)
                } else {
                    if (steps.isNotEmpty()) Text(AccessCopy.StepsReady.text(steps.count { it.applicable }, steps.count { it.ready }), style = MaterialTheme.typography.titleMedium,
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
                Text(if (reviewing) AccessCopy.FinishSetup.text() else AccessCopy.ReviewSteps.text())
            }
            if (reviewing) TextButton(onClick = { onEvent(SetupEvent.Edit) }, modifier = Modifier.fillMaxWidth()) { Text(AccessCopy.EditChoices.text()) }
            else TextButton(onClick = { onEvent(SetupEvent.Cancel) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (firstRun) AccessCopy.NotNow.text() else AccessCopy.Cancel.text())
            }
        }
    }
}
