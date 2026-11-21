package tel.misa.mxpocket.decks

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import tel.misa.mxpocket.isUnlockedAndInteractive
import tel.misa.mxpocket.shared.ui.CameraControlsEvent
import tel.misa.mxpocket.shared.ui.CameraControlsScreen
import tel.misa.mxpocket.shared.ui.CameraControlsState
import tel.misa.mxpocket.shared.ui.StudioCaptureOption

class CameraControlsActivity : DeckActivity() {
    private val access by lazy { CameraControlsAccess(this) }
    private val captures by lazy { File(filesDir, "captures") }
    private val mimeTypes = mapOf("jpg" to "image/jpeg", "mp4" to "video/mp4", "txt" to "text/plain")
    private var state by mutableStateOf(CameraControlsState.Available(false, false))
    private var listing: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        state = state.copy(message = savedInstanceState?.getString("message").orEmpty())
        page("Studio camera controls") { CameraControlsScreen(state, ::handle) }
    }

    override fun onResume() {
        super.onResume()
        state = state.copy(enabled = access.enabled, hasCaptureDirectory = captures.isDirectory)
    }

    override fun onPause() {
        listing?.cancel()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("message", state.message)
    }

    private fun handle(event: CameraControlsEvent) {
        when {
            event == CameraControlsEvent.Done -> { finish(); return }
            event == CameraControlsEvent.DismissCaptures -> { state = state.copy(captureChoices = null); return }
            !isUnlockedAndInteractive() -> return
        }
        when (event) {
            is CameraControlsEvent.SetEnabled -> {
                access.enabled = event.enabled
                state = state.copy(enabled = access.enabled)
            }
            CameraControlsEvent.OpenSettings -> {
                val message = try {
                    startActivity(DeckAccessActivity.settings(false))
                    ""
                } catch (_: ActivityNotFoundException) { "Accessibility settings unavailable." }
                catch (_: SecurityException) { "Android restricted access to settings." }
                state = state.copy(message = message)
            }
            CameraControlsEvent.ListCaptures -> listCaptures()
            is CameraControlsEvent.OpenCapture -> openCapture(event.id)
            CameraControlsEvent.Done, CameraControlsEvent.DismissCaptures -> Unit
        }
    }

    private fun listCaptures() {
        listing?.cancel()
        listing = lifecycleScope.launch {
            val options = withContext(Dispatchers.IO) {
                val dates = DateFormat.getDateTimeInstance()
                captures.listFiles().orEmpty().filter { it.isFile && it.extension in mimeTypes }
                    .sortedByDescending { it.name }.map { file ->
                        StudioCaptureOption(file.name, "${file.extension.uppercase()}  ${dates.format(Date(file.lastModified()))}")
                    }
            }
            if (isUnlockedAndInteractive()) state = state.copy(
                captureChoices = options.takeIf { it.isNotEmpty() },
                message = if (options.isEmpty()) "No previous Studio captures." else ""
            )
        }
    }

    private fun openCapture(id: String) {
        if (state.captureChoices?.none { it.id == id } != false) return
        state = state.copy(captureChoices = null)
        val file = File(captures, id)
        val message = try {
            val mime = mimeTypes[file.extension]
            if (mime == null || !file.isFile || file.canonicalFile.parentFile != captures.canonicalFile)
                "This capture is no longer available."
            else {
                val uri = FileProvider.getUriForFile(this, "$packageName.captures", file)
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                ""
            }
        } catch (_: ActivityNotFoundException) { "No viewer for this capture." }
        catch (_: SecurityException) { "Android restricted access to this capture." }
        catch (_: java.io.IOException) { "This capture is no longer available." }
        catch (_: IllegalArgumentException) { "This capture is no longer available." }
        state = state.copy(message = message)
    }
}
