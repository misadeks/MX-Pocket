package tel.misa.mxpocket.shared.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import tel.misa.mxpocket.decks.CallStatus
import tel.misa.mxpocket.i18n.AccessCopy
import tel.misa.mxpocket.i18n.Copy
import tel.misa.mxpocket.i18n.KeypadCopy

enum class DeckAccess(private val titleCopy: Copy, private val disclosureCopy: Copy) {
    Media(AccessCopy.MediaAccess, AccessCopy.MediaDisclosure),
    Navigation(AccessCopy.NavigationAccess, AccessCopy.NavigationDisclosure);

    val title get() = titleCopy.text()
    val disclosure get() = disclosureCopy.text()
}

data class TeamsSetupState(val enabled: Boolean, val notifications: Boolean, val toolbar: Boolean,
    val supported: Boolean, val singleProfile: Boolean = true, val message: String = "")
enum class TeamsSetupAction { TOGGLE, NOTIFICATIONS, TOOLBAR, DONE }

val TeamsToolbarDisclosure get() = AccessCopy.TeamsToolbarInstructions.text()

@Composable
fun TeamsSetupScreen(state: TeamsSetupState, status: CallStatus, onAction: (TeamsSetupAction) -> Unit) {
    SubsidiaryPage(AccessCopy.TeamsControls.text(), { onAction(TeamsSetupAction.DONE) }) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(KeypadCopy.CallStatus.text(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(status.detail, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            Text(AccessCopy.TeamsSwitching.text())
            Text(AccessCopy.TeamsDisclosure.text())
            if (!state.supported) Text(AccessCopy.TeamsUnsupported.text())
            if (!state.singleProfile) Text(AccessCopy.TeamsMultipleProfiles.text())
            OutlinedButton({ onAction(TeamsSetupAction.TOGGLE) }, enabled = state.supported || state.enabled) {
                Text(if (state.enabled) AccessCopy.DisableTeams.text() else AccessCopy.EnableTeams.text())
            }
            Text(AccessCopy.SharedListenerHint.text())
            if (state.notifications && status == CallStatus.NO_SIGNAL) Text(AccessCopy.TeamsFilterInstructions.text())
            OutlinedButton({ onAction(TeamsSetupAction.NOTIFICATIONS) }) {
                Text(if (state.notifications) AccessCopy.NotificationAccessOn.text() else AccessCopy.NotificationAccessOff.text())
            }
            Text(AccessCopy.TeamsToolbarInstructions.text())
            OutlinedButton({ onAction(TeamsSetupAction.TOOLBAR) }) {
                Text(if (state.toolbar) AccessCopy.AccessibilityOn.text() else AccessCopy.AccessibilityOff.text())
            }
            SubsidiaryStatus(state.message)
        }
    }
}

val CameraControlsDisclosure get() = AccessCopy.CameraDisclosure.text()

@Composable
internal fun SubsidiaryPage(title: String, onDone: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Surface { SubsidiaryColumn(title, onDone, content) }
}

@Composable
private fun SubsidiaryColumn(title: String, onDone: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().wrapContentWidth(Alignment.CenterHorizontally)
        .widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 20.dp)) {
        Text(title, Modifier.padding(top = 16.dp, bottom = 12.dp).semantics { heading() },
            style = MaterialTheme.typography.headlineMedium)
        Column(Modifier.fillMaxWidth().weight(1f), content = content)
        HorizontalDivider()
        TextButton(onClick = onDone, modifier = Modifier.align(Alignment.End).padding(vertical = 4.dp)) {
            Text(AccessCopy.Done.text())
        }
    }
}

@Composable
internal fun SubsidiaryStatus(message: String) {
    if (message.isNotBlank()) Text(message, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.bodyMedium)
}

/** Pass null for settings on a platform without this Android access mechanism. */
@Composable
fun DeckAccessScreen(access: DeckAccess, message: String, onOpenSettings: (() -> Unit)?, onDone: () -> Unit,
    listenerGranted: Boolean = false, musicEnabled: Boolean = false, onToggleMusic: (() -> Unit)? = null) {
    SubsidiaryPage(access.title, onDone) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (onOpenSettings != null) {
                Text(access.disclosure)
                if (access == DeckAccess.Media) {
                    if (onToggleMusic != null) OutlinedButton(onToggleMusic) {
                        Text(if (musicEnabled) AccessCopy.DisableMusic.text() else AccessCopy.EnableMusic.text())
                    }
                    Text(AccessCopy.SharedListenerHint.text())
                    Text(if (listenerGranted) AccessCopy.NotificationAccessOn.text() else AccessCopy.NotificationAccessOff.text())
                }
                Text(AccessCopy.OptionalAccess.text(),
                    style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = onOpenSettings) { Text(AccessCopy.OpenAndroidSettings.text()) }
                if (access == DeckAccess.Navigation) {
                    Text(AccessCopy.TalkBackHint.text())
                    Text(AccessCopy.ShortcutHint.text())
                }
            } else Text(AccessCopy.NoAndroidSettings.text())
            SubsidiaryStatus(message)
        }
    }
}

@Composable
fun PhoneActionResultScreen(message: String, onDone: () -> Unit) {
    SubsidiaryPage(AccessCopy.PhoneShortcuts.text(), onDone) {
        Column(Modifier.verticalScroll(rememberScrollState())) { SubsidiaryStatus(message) }
    }
}

/** Consent is never inferred from dismissal or a return from system settings. */
@Composable
fun DirectShortcutConsentDialog(hasOverlayAccess: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(AccessCopy.DirectTitle.text()) },
        text = {
            Text(DirectShortcutDisclosure, Modifier.verticalScroll(rememberScrollState()))
        },
        confirmButton = { TextButton(onClick = onConfirm) {
            Text(if (hasOverlayAccess) AccessCopy.EnableShortcuts.text() else AccessCopy.OpenAndroidSettings.text())
        } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(AccessCopy.Cancel.text()) } })
}

val DirectShortcutDisclosure get() = AccessCopy.DirectDisclosure.text()
