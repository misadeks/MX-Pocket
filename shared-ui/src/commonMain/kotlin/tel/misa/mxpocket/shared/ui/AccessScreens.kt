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

enum class DeckAccess(val title: String, val disclosure: String) {
    Media("Media session access",
        "Android grants broad notification access. MX Pocket only queries active media sessions; it does not read, store or act on notifications. With Audio selected, the connected, authorized keypad shows track titles and local cover art even when the phone is locked. Switch profiles or revoke access to stop showing them. Basic media keys and volume work without access."),
    Navigation("Navigation access",
        "The MX Pocket navigation accessibility service can inspect the current window and act across apps. On a key press it can go Back or Home, open Recents, scroll, move focus or activate an item. With a separate Studio opt-in it can press a labeled camera button. It does not read or store screen text, record keys or automate gestures. Without access, navigation stays inside MX Pocket.")
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
fun DeckAccessScreen(access: DeckAccess, message: String, onOpenSettings: (() -> Unit)?, onDone: () -> Unit) {
    SubsidiaryPage(access.title, onDone) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (onOpenSettings != null) {
                Text(access.disclosure)
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
