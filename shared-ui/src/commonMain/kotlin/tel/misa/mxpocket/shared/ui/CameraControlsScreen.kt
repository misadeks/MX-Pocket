package tel.misa.mxpocket.shared.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import tel.misa.mxpocket.i18n.AccessCopy

data class StudioCaptureOption(val id: String, val label: String)

sealed interface CameraControlsState {
    data object Unavailable : CameraControlsState
    data class Available(
        val enabled: Boolean, val hasCaptureDirectory: Boolean, val message: String = "",
        val captureChoices: List<StudioCaptureOption>? = null
    ) : CameraControlsState
}

sealed interface CameraControlsEvent {
    data class SetEnabled(val enabled: Boolean) : CameraControlsEvent
    data object OpenSettings : CameraControlsEvent
    data object ListCaptures : CameraControlsEvent
    data object DismissCaptures : CameraControlsEvent
    data class OpenCapture(val id: String) : CameraControlsEvent
    data object Done : CameraControlsEvent
}

@Composable
fun CameraControlsScreen(state: CameraControlsState, onEvent: (CameraControlsEvent) -> Unit) {
    SubsidiaryPage(AccessCopy.StudioControls.text(), { onEvent(CameraControlsEvent.Done) }) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state is CameraControlsState.Available) {
                Text(CameraControlsDisclosure)
                Text(DeckAccess.Navigation.disclosure, style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { onEvent(CameraControlsEvent.SetEnabled(!state.enabled)) }) {
                    Text(if (state.enabled) AccessCopy.DisableCamera.text() else AccessCopy.EnableCamera.text())
                }
                Text(AccessCopy.CameraInstructions.text(),
                    style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { onEvent(CameraControlsEvent.OpenSettings) }) {
                    Text(AccessCopy.OpenAccessibility.text())
                }
                if (state.hasCaptureDirectory) OutlinedButton(onClick = { onEvent(CameraControlsEvent.ListCaptures) }) {
                    Text(AccessCopy.OpenCaptures.text())
                }
                SubsidiaryStatus(state.message)
            } else Text(AccessCopy.CameraNotAvailable.text())
        }
    }
    (state as? CameraControlsState.Available)?.captureChoices?.let { choices ->
        val dismiss = { onEvent(CameraControlsEvent.DismissCaptures) }
        AlertDialog(onDismissRequest = dismiss, title = { Text(AccessCopy.PreviousCaptures.text()) },
            text = {
                LazyColumn {
                    items(choices, key = { it.id }) { capture ->
                        TextButton(onClick = { onEvent(CameraControlsEvent.OpenCapture(capture.id)) },
                            modifier = Modifier.fillMaxWidth()) { Text(capture.label) }
                    }
                }
            }, confirmButton = { TextButton(onClick = dismiss) { Text(AccessCopy.Cancel.text()) } })
    }
}
