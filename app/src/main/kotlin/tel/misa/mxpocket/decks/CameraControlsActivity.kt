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
import java.util.Locale
import tel.misa.mxpocket.i18n.AccessCopy
import tel.misa.mxpocket.i18n.AppLocale
import tel.misa.mxpocket.i18n.Copy
import tel.misa.mxpocket.isUnlockedAndInteractive
import tel.misa.mxpocket.shared.ui.CameraControlsEvent
import tel.misa.mxpocket.shared.ui.CameraControlsScreen
import tel.misa.mxpocket.shared.ui.CameraControlsState
import tel.misa.mxpocket.shared.ui.StudioCaptureOption

class CameraControlsActivity : DeckActivity() {
    private enum class Notice(val copy: Copy) {
        SETTINGS_UNAVAILABLE(AccessCopy.AccessibilityUnavailable), SETTINGS_RESTRICTED(AccessCopy.RestrictedSettings),
        NO_CAPTURES(AccessCopy.NoCaptures), UNAVAILABLE(AccessCopy.CaptureUnavailable),
        NO_VIEWER(AccessCopy.CaptureNoViewer), RESTRICTED(AccessCopy.CaptureRestricted)
    }

    private val access by lazy { CameraControlsAccess(this) }
    private val captures by lazy { File(filesDir, "captures") }
    private val mimeTypes = mapOf("jpg" to "image/jpeg", "mp4" to "video/mp4", "txt" to "text/plain")
    private var state by mutableStateOf(CameraControlsState.Available(false, false))
    private var notice by mutableStateOf<Notice?>(null)
    private var listing: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notice = savedInstanceState?.getString("notice")?.let { id -> Notice.entries.find { it.name == id } }
        page({ AccessCopy.StudioControls.text() }) {
            CameraControlsScreen(state.copy(message = notice?.copy?.text().orEmpty()), ::handle)
        }
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
        outState.putString("notice", notice?.name)
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
                notice = try {
                    startActivity(DeckAccessActivity.settings(false))
                    null
                } catch (_: ActivityNotFoundException) { Notice.SETTINGS_UNAVAILABLE }
                catch (_: SecurityException) { Notice.SETTINGS_RESTRICTED }
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
                val dates = DateFormat.getDateTimeInstance(DateFormat.DEFAULT, DateFormat.DEFAULT, Locale.forLanguageTag(AppLocale.current.tag))
                captures.listFiles().orEmpty().filter { it.isFile && it.extension in mimeTypes }
                    .sortedByDescending { it.name }.map { file ->
                        StudioCaptureOption(file.name, "${file.extension.uppercase()}  ${dates.format(Date(file.lastModified()))}")
                    }
            }
            if (isUnlockedAndInteractive()) {
                state = state.copy(captureChoices = options.takeIf { it.isNotEmpty() })
                notice = if (options.isEmpty()) Notice.NO_CAPTURES else null
            }
        }
    }

    private fun openCapture(id: String) {
        val choices = state.captureChoices ?: return
        if (choices.none { it.id == id }) return
        state = state.copy(captureChoices = null)
        val file = File(captures, id)
        notice = try {
            val mime = mimeTypes[file.extension]
            if (mime == null || !file.isFile || file.canonicalFile.parentFile != captures.canonicalFile)
                Notice.UNAVAILABLE
            else {
                val uri = FileProvider.getUriForFile(this, "$packageName.captures", file)
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                null
            }
        } catch (_: ActivityNotFoundException) { Notice.NO_VIEWER }
        catch (_: SecurityException) { Notice.RESTRICTED }
        catch (_: java.io.IOException) { Notice.UNAVAILABLE }
        catch (_: IllegalArgumentException) { Notice.UNAVAILABLE }
    }
}
