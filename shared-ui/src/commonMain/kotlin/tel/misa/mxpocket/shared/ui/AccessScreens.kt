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

enum class DeckAccess(val title: String, val disclosure: String) {
    Media("Media session access",
        "Android grants broad notification access. The Audio service only queries active media sessions; it does not read, store or act on notifications. With Audio selected, the connected, authorized keypad shows track titles and local cover art even when the phone is locked. Switch profiles or revoke access to stop showing them. Basic media keys and volume work without access."),
    Navigation("Navigation access",
        "The MX Pocket navigation accessibility service can inspect the current window and act across apps. On a key press it can go Back or Home, open Recents, scroll, move focus or activate an item. With a separate Studio opt-in it can press a labeled camera button. It does not read or store screen text, record keys or automate gestures. Without access, navigation stays inside MX Pocket.")
}

data class TeamsSetupState(val enabled: Boolean, val notifications: Boolean, val toolbar: Boolean,
    val supported: Boolean, val singleProfile: Boolean = true, val message: String = "")
enum class TeamsSetupAction { TOGGLE, NOTIFICATIONS, TOOLBAR, DONE }

const val ListenerPickerHint = "If Android lists two entries named only MX Pocket, go back without choosing either. You do not need to enable both."
const val MediaListenerHint = "For Audio, choose MX Pocket media sessions, not MX Pocket Teams calls. $ListenerPickerHint"
const val TeamsListenerHint = "For Teams, choose MX Pocket Teams calls, not MX Pocket media sessions. $ListenerPickerHint"
const val TeamsListenerDisclosure = "Android grants broad notification access. MX Pocket checks the posting app, profile and call notification type, not messages or caller names. No call details are saved."
const val TeamsToolbarDisclosure = "For automatic switching on a single-profile phone, MX Pocket also checks for the visible Mic and Hang up buttons in Teams. Only a fresh physical center-key press can operate the mic. To allow this, select MX Pocket Teams call controls in Accessibility."

@Composable
fun TeamsSetupScreen(state: TeamsSetupState, status: CallStatus, onAction: (TeamsSetupAction) -> Unit) {
    SubsidiaryPage("Teams call controls", { onAction(TeamsSetupAction.DONE) }) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Call status", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(status.detail, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            Text("With a wired keypad, show call notices from Teams in this Android profile. A confirmed call can switch the keypad to In call. Leaving restores your previous page unless you chose another.")
            Text(TeamsListenerDisclosure)
            if (!state.supported) Text("Call observation needs Android 14 or later. You can still use Work and open Call tools manually.")
            if (!state.singleProfile) Text("With multiple Android profiles, only notification status is available. MX Pocket does not inspect the Teams window, switch pages or operate the mic.")
            OutlinedButton({ onAction(TeamsSetupAction.TOGGLE) }, enabled = state.supported || state.enabled) {
                Text(if (state.enabled) "Turn off Teams call status" else "Enable Teams call status")
            }
            Text("Call status is on by default. It cannot read notifications unless you allow MX Pocket Teams calls in Notification access. Audio access is separate.")
            if (state.notifications && status == CallStatus.NO_SIGNAL) Text("After an update, Android may keep an older filter. Open Notification access below. If available, check that Teams, Notifications (alerting), Silent and Conversations are allowed. If these choices are missing or Teams does not publish a call notice, use Teams directly.")
            Text(TeamsListenerHint)
            OutlinedButton({ onAction(TeamsSetupAction.NOTIFICATIONS) }) {
                Text("Notification access: ${if (state.notifications) "on" else "off"}")
            }
            Text(TeamsToolbarDisclosure)
            OutlinedButton({ onAction(TeamsSetupAction.TOOLBAR) }) {
                Text("Accessibility access: ${if (state.toolbar) "on" else "off"}")
            }
            SubsidiaryStatus(state.message)
        }
    }
}

const val CameraControlsDisclosure = "Open Camera and Open Video only open your phone's viewfinder; MX Pocket needs no camera permission. " +
    "Optional keypad controls use the MX Pocket navigation accessibility service. On a physical Studio Shutter, Record or Stop press, " +
    "MX Pocket checks the active window for the default camera and clicks a matching labeled camera button. " +
    "It does not monitor the viewfinder, save screen content, or check whether a capture succeeded. " +
    "Keep this off to use the camera's own controls."

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
            Text("Done")
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
    mediaGranted: Boolean? = null) {
    SubsidiaryPage(access.title, onDone) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (onOpenSettings != null) {
                Text(access.disclosure)
                if (access == DeckAccess.Media) {
                    Text(MediaListenerHint)
                    mediaGranted?.let { Text(if (it) "Media session access allowed" else "Media session access not granted") }
                }
                Text("Access is optional. You can turn it off in the same settings at any time.",
                    style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = onOpenSettings) { Text("Open Android settings") }
            } else Text("This Android access setting is not available on this platform.")
            SubsidiaryStatus(message)
        }
    }
}

@Composable
fun PhoneActionResultScreen(message: String, onDone: () -> Unit) {
    SubsidiaryPage("Keypad app shortcuts", onDone) {
        Column(Modifier.verticalScroll(rememberScrollState())) { SubsidiaryStatus(message) }
    }
}

/** Consent is never inferred from dismissal or a return from system settings. */
@Composable
fun DirectShortcutConsentDialog(hasOverlayAccess: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("Open apps directly from keypad") },
        text = {
            Text(DirectShortcutDisclosure, Modifier.verticalScroll(rememberScrollState()))
        },
        confirmButton = { TextButton(onClick = onConfirm) {
            Text(if (hasOverlayAccess) "Enable shortcuts" else "Open Android settings")
        } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

const val DirectShortcutDisclosure = "On an unlocked phone, each physical keypad press can open a saved favorite, a Phone shortcut, or a camera viewfinder without a notification tap. Opening the viewfinder does not take a photo or record. Android requires “Display over other apps”, which also allows drawing over other apps. MX Pocket does not draw overlays. Leave this off to use notifications, or revoke access in Android settings at any time."
