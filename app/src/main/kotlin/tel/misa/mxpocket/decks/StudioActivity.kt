package tel.misa.mxpocket.decks

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.util.UUID
import java.util.concurrent.ExecutionException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tel.misa.mxpocket.PocketApplication
import tel.misa.mxpocket.isUnlockedAndInteractive

/** The Activity owns every camera resource. No camera or recording is started by service recreation. */
class StudioActivity : DeckActivity() {
    private val session get() = (application as PocketApplication).session
    private val executor by lazy { ContextCompat.getMainExecutor(this) }
    private val captures get() = File(filesDir, "captures")
    private var active = false
    private var epoch = 0
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var photo: ImageCapture? = null
    private var video: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var savingPhoto = false
    private var stopping = false
    private var countdown: Job? = null
    private var markerTimes = mutableListOf<Long>()
    private var latestMillis = 0L
    private var state = StudioState()
    private lateinit var preview: PreviewView
    private lateinit var info: TextView
    private val controls = mutableMapOf<DeckCommand, android.widget.Button>()
    private lateinit var allow: android.widget.Button
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it && active) prepare() else if (!it) update(state.copy(status = "Camera permission is off. Allow camera to start preview."))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = page("Pocket Studio")
        preview = PreviewView(this).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
        column.addView(preview, LinearLayout.LayoutParams(-1, 0, 1.2f))
        val scroll = ScrollView(this)
        column.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(actions)
        info = description(actions, "Camera is off.")
        allow = button(actions, "Allow camera") { permission.launch(Manifest.permission.CAMERA) }
        listOf(listOf(DeckCommand.PHOTO, DeckCommand.RECORD, DeckCommand.STOP),
            listOf(DeckCommand.TIMER, DeckCommand.FOCUS, DeckCommand.TORCH),
            listOf(DeckCommand.ZOOM_OUT, DeckCommand.ZOOM_IN, DeckCommand.MARKER)).forEach { line ->
            val row = LinearLayout(this)
            actions.addView(row)
            for (command in line) controls[command] = button(row, command.label) {
                val result = press(command)
                if (!result.success) update(state.copy(status = result.message))
            }
        }
        button(actions, "Open saved captures") { showCaptures() }
        description(actions, "Video is silent. Captures and marker timestamps stay in MX Pocket until you open or share them.")
        button(actions, "Done") { finish() }
    }

    override fun onResume() {
        super.onResume()
        active = true
        session.attachStudio(this)
        if (permitted()) prepare() else update(state.copy(status = "Allow camera to use the viewfinder. No microphone is requested."))
    }
    override fun onPause() {
        active = false
        epoch++
        countdown?.cancel(); countdown = null
        recording?.stop()
        releaseCamera()
        camera = null; photo = null; video = null
        state = StudioState(timerSeconds = state.timerSeconds)
        session.detachStudio(this)
        super.onPause()
    }
    private fun permitted() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    private fun releaseCamera() {
        camera?.cameraInfo?.zoomState?.removeObservers(this)
        camera?.cameraInfo?.torchState?.removeObservers(this)
        provider?.unbindAll()
    }
    private fun prepare() {
        val token = ++epoch
        val future = ProcessCameraProvider.getInstance(this)
        update(state.copy(status = "Opening camera..."))
        future.addListener({
            if (!active || epoch != token) return@addListener
            try {
                provider = future.get()
                bind(false)
            } catch (_: ExecutionException) { update(state.copy(ready = false, status = "Camera initialization failed.")) }
            catch (_: InterruptedException) { Thread.currentThread().interrupt(); update(state.copy(ready = false, status = "Camera interrupted.")) }
        }, executor)
    }
    /** Preview + one capture use case works on cameras that cannot bind image and video simultaneously. */
    private fun bind(movie: Boolean): Boolean {
        val host = provider ?: return false
        try {
            val selector = if (host.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) CameraSelector.DEFAULT_BACK_CAMERA
                else CameraSelector.DEFAULT_FRONT_CAMERA
            val display = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
            releaseCamera()
            val mode = if (movie) VideoCapture.withOutput(Recorder.Builder().build()).also { video = it; photo = null }
                else ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build().also { photo = it; video = null }
            val bound = host.bindToLifecycle(this, selector, display, mode)
            camera = bound
            val savedZoom = state.zoom
            bound.cameraInfo.zoomState.observe(this) { if (camera === bound && active) update(state.copy(zoom = it.zoomRatio)) }
            bound.cameraInfo.torchState.observe(this) { if (camera === bound && active) update(state.copy(torch = it == androidx.camera.core.TorchState.ON)) }
            if (savedZoom != 1f) reportControl(bound.cameraControl.setZoomRatio(savedZoom), "Zoom")
            update(state.copy(ready = true, hasTorch = bound.cameraInfo.hasFlashUnit(), status = "Ready"))
            return true
        } catch (_: androidx.camera.core.CameraInfoUnavailableException) { /* reported below */ }
        catch (_: IllegalArgumentException) { /* missing camera or unsupported stream combination */ }
        catch (_: IllegalStateException) { /* camera unavailable */ }
        catch (_: SecurityException) { /* permission revoked */ }
        camera = null; photo = null; video = null
        update(state.copy(ready = false, status = "This camera mode is unavailable. Reopen Studio to retry."))
        return false
    }

    private fun update(next: StudioState) {
        state = next
        if (!active) return
        session.updateStudio(this, next)
        info.text = if (next.recording) "REC ${next.elapsed}  |  ${next.markers} markers  |  ${next.status}"
            else if (next.countdown > 0) "Shutter in ${next.countdown}s" else next.status
        allow.visibility = if (permitted()) android.view.View.GONE else android.view.View.VISIBLE
        val visual = DeckState(studio = next)
        controls.forEach { (command, button) ->
            val tile = visual.tile(command)
            button.text = when (command) {
                DeckCommand.TIMER, DeckCommand.ZOOM_IN -> "${tile.label} ${tile.footer}"
                else -> tile.label
            }
            button.isEnabled = next.ready && when (command) {
                DeckCommand.STOP -> recording != null && !stopping
                DeckCommand.MARKER -> next.recording && !stopping
                DeckCommand.RECORD, DeckCommand.PHOTO, DeckCommand.TIMER -> recording == null && !savingPhoto && next.countdown == 0
                DeckCommand.TORCH -> next.hasTorch
                else -> true
            }
        }
    }

    internal fun press(command: DeckCommand): DeckResult {
        val cam = camera
        if (!active || !isUnlockedAndInteractive() || !permitted() || cam == null || !state.ready)
            return unavailable("Open Pocket Studio on the unlocked phone and allow the camera.")
        when (command) {
            DeckCommand.PHOTO, DeckCommand.RECORD, DeckCommand.TIMER ->
                if (recording != null || savingPhoto || countdown != null) return unavailable("Finish the current capture first.")
            else -> Unit
        }
        when (command) {
            DeckCommand.PHOTO -> {
                if (state.timerSeconds == 0) return takePhoto()
                countdown = lifecycleScope.launch {
                    for (left in state.timerSeconds downTo 1) { update(state.copy(countdown = left)); delay(1000) }
                    countdown = null
                    update(state.copy(countdown = 0))
                    val result = takePhoto()
                    if (!result.success) update(state.copy(status = result.message))
                }
            }
            DeckCommand.TIMER -> update(state.copy(timerSeconds = when (state.timerSeconds) { 0 -> 3; 3 -> 10; else -> 0 }))
            DeckCommand.RECORD -> return startRecording()
            DeckCommand.STOP -> {
                val current = recording ?: return unavailable("No recording to stop.")
                if (stopping) return unavailable("Already saving.")
                stopping = true
                current.stop()
                update(state.copy(status = "Saving video..."))
            }
            DeckCommand.MARKER -> {
                if (!state.recording || stopping) return unavailable("Record a video before adding a marker.")
                markerTimes.add(latestMillis)
                update(state.copy(markers = markerTimes.size, status = "Marker at ${state.elapsed}"))
            }
            DeckCommand.ZOOM_IN, DeckCommand.ZOOM_OUT -> {
                val zoom = cam.cameraInfo.zoomState.value ?: return unavailable("Zoom unavailable.")
                val ratio = (state.zoom * if (command == DeckCommand.ZOOM_IN) 1.25f else .8f)
                    .coerceIn(zoom.minZoomRatio, zoom.maxZoomRatio)
                if (ratio == state.zoom) return unavailable("Zoom limit reached.")
                reportControl(cam.cameraControl.setZoomRatio(ratio), "Zoom")
            }
            DeckCommand.TORCH -> {
                if (!state.hasTorch) return unavailable("This camera has no torch.")
                reportControl(cam.cameraControl.enableTorch(!state.torch), "Torch")
            }
            DeckCommand.FOCUS -> {
                val point = preview.meteringPointFactory.createPoint(preview.width * .5f, preview.height * .5f)
                val action = FocusMeteringAction.Builder(point).build()
                if (!cam.cameraInfo.isFocusMeteringSupported(action)) return unavailable("Focus metering is unavailable.")
                val future = cam.cameraControl.startFocusAndMetering(action)
                val token = epoch
                future.addListener({
                    if (active && token == epoch) {
                        val focused = runCatching { future.get().isFocusSuccessful }.getOrDefault(false)
                        update(state.copy(status = if (focused) "Center focused" else "Focus not confirmed"))
                    }
                }, executor)
            }
            else -> return unavailable("Not a Studio control.")
        }
        return ok("Requested ${command.label}.")
    }
    private fun reportControl(future: com.google.common.util.concurrent.ListenableFuture<Void>, label: String) {
        val token = epoch
        future.addListener({
            if (active && token == epoch && runCatching { future.get() }.isFailure)
                update(state.copy(status = "$label was not applied."))
        }, executor)
    }
    private fun newFile(extension: String): File? {
        if (!captures.isDirectory && !captures.mkdirs()) return null
        return File(captures, "${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}.$extension")
    }
    private fun takePhoto(): DeckResult {
        if (!active || !isUnlockedAndInteractive() || !permitted()) return unavailable("Camera is no longer visible.")
        val capture = photo ?: return unavailable("Still camera is not ready.")
        val file = newFile("jpg") ?: return unavailable("Capture storage unavailable.")
        savingPhoto = true
        update(state.copy(status = "Saving photo..."))
        capture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), executor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                savingPhoto = false
                if (active) update(state.copy(status = "Photo saved"))
            }
            override fun onError(error: ImageCaptureException) {
                savingPhoto = false; file.delete()
                if (active) update(state.copy(status = "Photo was not saved. Try again."))
            }
        })
        return ok("Shutter requested.")
    }
    private fun startRecording(): DeckResult {
        val file = newFile("mp4") ?: return unavailable("Capture storage unavailable.")
        if (!bind(true)) { bind(false); return unavailable("Video mode is unavailable.") }
        val capture = video ?: return unavailable("Video is not ready.")
        val marks = mutableListOf<Long>()
        markerTimes = marks; latestMillis = 0; stopping = false
        update(state.copy(status = "Starting video...", elapsedSeconds = 0, markers = 0))
        return try {
            val pending = capture.output.prepareRecording(this, FileOutputOptions.Builder(file).build())
            recording = pending.start(executor) { event ->
                latestMillis = event.recordingStats.recordedDurationNanos / 1_000_000
                when (event) {
                    is VideoRecordEvent.Start -> if (active) update(state.copy(recording = true, status = "Silent video"))
                    is VideoRecordEvent.Status -> if (active && state.elapsedSeconds != latestMillis / 1000)
                        update(state.copy(elapsedSeconds = latestMillis / 1000))
                    is VideoRecordEvent.Finalize -> {
                        recording = null; stopping = false
                        val saved = !event.hasError() && file.length() > 0
                        val markersSaved = saved && (marks.isEmpty() || try {
                            file.resolveSibling("${file.nameWithoutExtension}-markers.txt")
                                .writeText(marks.mapIndexed { i, millis -> "${i + 1}\t${millis} ms" }.joinToString("\n"))
                            true
                        } catch (_: java.io.IOException) { false })
                        if (!saved) file.delete()
                        if (active) {
                            bind(false)
                            update(state.copy(recording = false, status = when {
                                !saved -> "Video was not saved (camera error ${event.error})."
                                !markersSaved -> "Video saved, but timestamps could not be saved."
                                else -> "Video saved"
                            }))
                        }
                    }
                }
            }
            update(state)
            ok("Recording requested.")
        } catch (_: IllegalStateException) { bind(false); unavailable("Recording could not start.") }
        catch (_: SecurityException) { bind(false); unavailable("Camera permission was revoked.") }
    }
    private fun showCaptures() {
        if (recording != null || countdown != null || savingPhoto) { update(state.copy(status = "Finish this capture first.")); return }
        val files = captures.listFiles().orEmpty().filter { it.extension in setOf("jpg", "mp4", "txt") }.sortedByDescending { it.name }
        if (files.isEmpty()) { update(state.copy(status = "No saved captures yet.")); return }
        val dates = java.text.DateFormat.getDateTimeInstance()
        val labels = files.map { file ->
            "${when (file.extension) { "jpg" -> "Photo"; "mp4" -> "Video"; else -> "Markers" }}  ${dates.format(java.util.Date(file.lastModified()))}"
        }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Saved captures").setItems(labels) { _, i ->
            val file = files[i]
            val uri = FileProvider.getUriForFile(this, "$packageName.captures", file)
            try {
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, when (file.extension) {
                    "jpg" -> "image/jpeg"; "mp4" -> "video/mp4"; else -> "text/plain"
                }).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } catch (_: ActivityNotFoundException) { update(state.copy(status = "No viewer for this capture.")) }
        }.setNegativeButton(android.R.string.cancel, null).show()
    }
}
