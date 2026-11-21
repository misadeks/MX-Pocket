package tel.misa.mxpocket

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent as AndroidKeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import tel.misa.mxpocket.driver.KeyEvent
import tel.misa.mxpocket.driver.MxKeypad
import tel.misa.mxpocket.driver.UsbDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private val usb by lazy { getSystemService(UsbManager::class.java) }
    private val audio by lazy { getSystemService(AudioManager::class.java) }
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var diagnostics: TextView
    private lateinit var logView: TextView
    private lateinit var pageTitle: TextView
    private lateinit var connectButton: MaterialButton
    private val keyButtons = mutableListOf<MaterialButton>()
    private val logs = ArrayDeque<String>()
    private val focus = FocusTimer()
    private var page = 0
    private var preview = false
    private var driver: MxKeypad? = null
    private var deviceName: String? = null
    private var permissionDeviceName: String? = null
    private var connectionJob: Job? = null
    private var paintJob: Job? = null
    private var refreshJob: Job? = null
    private var custom: Bitmap? = null
    private var customVersion = 0
    private var lastPaint = emptyList<String>()
    private var resumed = false
    private var destroyed = false
    private var connectionGeneration = 0

    private val permissionAction get() = "$packageName.USB_PERMISSION"
    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != permissionAction) return
            val device = intent.usbDevice() ?: return
            if (device.deviceName != permissionDeviceName) return
            permissionDeviceName = null
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) &&
                usb.hasPermission(device) && usb.deviceList.containsKey(device.deviceName)) {
                appendLog("Android USB permission granted")
                if (resumed) connect(device) else showStatus("Permission granted. Tap Connect when you return.")
            } else {
                showStatus("USB permission denied. Tap Connect to request again.")
                appendLog("USB permission denied for ${device.deviceName}")
            }
            refreshDiagnostics()
        }
    }
    private val attachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.usbDevice()
            if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED && device != null &&
                device.deviceName == deviceName) {
                disconnect()
                showStatus("Keypad detached. Reconnect the cable and tap Connect.")
                appendLog("Detach: ${device.deviceName}")
            }
            if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) appendLog("USB attached; refresh and connect")
            refreshDiagnostics()
        }
    }
    private val imagePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            try {
                val image = withContext(Dispatchers.IO) {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
                    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Image could not be decoded" }
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 960) sample *= 2
                    val options = BitmapFactory.Options().apply { inSampleSize = sample }
                    val decoded = contentResolver.openInputStream(uri).use {
                        BitmapFactory.decodeStream(it, null, options)
                    } ?: error("Image could not be decoded")
                    try {
                        Bitmap.createScaledBitmap(decoded, 118, 118, true)
                    } finally {
                        // createScaledBitmap may return the source when it already has the requested size.
                        if (decoded.width != 118 || decoded.height != 118) decoded.recycle()
                    }
                }
                paintJob?.cancelAndJoin()
                custom?.recycle()
                custom = image
                customVersion++
                appendLog("Custom image loaded; labels will be overlaid on all nine keys")
                updateDemo(force = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("Cannot load image", e)
            }
        }
    }
    private val logSaver = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) lifecycleScope.launch {
            try {
                val text = diagnostics.text.toString() + "\n" + logs.joinToString("\n")
                withContext(Dispatchers.IO) {
                    val output = contentResolver.openOutputStream(uri) ?: error("Cannot open log destination")
                    output.bufferedWriter().use { it.write(text) }
                }
                Snackbar.make(root, "Diagnostic log saved", Snackbar.LENGTH_SHORT).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("Cannot save log", e)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        ContextCompat.registerReceiver(this, permissionReceiver, IntentFilter(permissionAction),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, attachReceiver, IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }, ContextCompat.RECEIVER_EXPORTED)
        appendLog("MX Pocket 0.1.0 / Android ${Build.VERSION.RELEASE} API ${Build.VERSION.SDK_INT}")
        appendLog("Protocol derived from MIT sources; USB and LCD operation not yet physically verified")
        refreshDiagnostics()
        updateDemo()
        lifecycleScope.launch {
            while (true) {
                val finished = focus.tick(SystemClock.elapsedRealtime())
                if (finished) {
                    val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 50)
                    try {
                        tone.startTone(ToneGenerator.TONE_PROP_ACK, 500)
                        delay(600)
                    } finally {
                        tone.release()
                    }
                    appendLog("Focus session complete")
                }
                if (resumed) updateDemo()
                delay(1000)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        resumed = true
        refreshDiagnostics()
    }

    override fun onStop() {
        resumed = false
        disconnect()
        if (!preview) showStatus("USB session paused while app is in background. Tap Connect to resume.")
        super.onStop()
    }

    override fun onDestroy() {
        destroyed = true
        disconnect()
        unregisterReceiver(permissionReceiver)
        unregisterReceiver(attachReceiver)
        custom?.recycle()
        super.onDestroy()
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply { isFillViewport = true }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(24))
        }
        scroll.addView(root)
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        scroll.requestApplyInsets()
        text(getString(R.string.app_name), 28)
        text(getString(R.string.subtitle), 16)
        status = text(getString(R.string.not_connected), 16).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        connectButton = button(getString(R.string.connect)) {
            if (driver != null || connectionJob?.isActive == true) disconnect() else requestConnect()
        }
        button(getString(R.string.preview), outlined = true) {
            disconnect()
            preview = true
            showStatus(getString(R.string.preview_note))
            updateDemo()
        }
        pageTitle = text(getString(R.string.focus), 22)
        val tabs = row()
        listOf(R.string.focus, R.string.audio, R.string.phone).forEachIndexed { index, label ->
            button(getString(label), parent = tabs, outlined = true) { page = index; updateDemo(force = true) }
        }
        repeat(3) { r ->
            val line = row()
            repeat(3) { c ->
                val index = r * 3 + c
                keyButtons += button("", parent = line, outlined = true) { performAction(index) }.apply {
                    minHeight = dp(84)
                    maxLines = 3
                }
            }
        }
        val arrows = row()
        button(getString(R.string.previous), parent = arrows, outlined = true) { changePage(-1) }
        button(getString(R.string.next), parent = arrows, outlined = true) { changePage(1) }
        button(getString(R.string.repaint), outlined = true) { updateDemo(force = true) }
        button(getString(R.string.import_image), outlined = true) { imagePicker.launch(arrayOf("image/*")) }
        text(getString(R.string.profile_note), 14)
        text(getString(R.string.diagnostics), 22)
        button(getString(R.string.refresh), outlined = true) { refreshDiagnostics() }
        diagnostics = text("", 14).apply { setTextIsSelectable(true); typeface = android.graphics.Typeface.MONOSPACE }
        button(getString(R.string.export_log), outlined = true) { logSaver.launch("mx-pocket-diagnostics.txt") }
        text(getString(R.string.log), 22)
        logView = text("", 12).apply { setTextIsSelectable(true); typeface = android.graphics.Typeface.MONOSPACE }
    }

    private fun row(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        root.addView(this, LinearLayout.LayoutParams(-1, -2))
    }

    private fun text(value: String, size: Int): TextView = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setPadding(0, dp(if (size >= 22) 20 else 8), 0, dp(8))
        root.addView(this, LinearLayout.LayoutParams(-1, -2))
    }

    private fun button(
        label: String,
        parent: LinearLayout = root,
        outlined: Boolean = false,
        action: () -> Unit,
    ): MaterialButton = MaterialButton(this, null, if (outlined)
        com.google.android.material.R.attr.materialButtonOutlinedStyle
        else com.google.android.material.R.attr.materialButtonStyle).apply {
        text = label
        minHeight = dp(48)
        isAllCaps = false
        textSize = 14f
        setOnClickListener { action() }
        val params = if (parent === root) LinearLayout.LayoutParams(-1, -2)
            else LinearLayout.LayoutParams(0, -2, 1f)
        params.setMargins(dp(4), dp(4), dp(4), dp(4))
        parent.addView(this, params)
    }

    private fun requestConnect() {
        preview = false
        val candidates = usb.deviceList.values.filter(UsbDiagnostics::isKeypad)
        if (candidates.size != 1) {
            showStatus(if (candidates.isEmpty()) "No MX Creative Keypad (046D:C354). Check cable and USB host support."
                else "Multiple keypads connected. Connect only one for this demo.")
            refreshDiagnostics()
            return
        }
        val device = candidates.single()
        if (usb.hasPermission(device)) connect(device)
        else {
            permissionDeviceName = device.deviceName
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val permission = PendingIntent.getBroadcast(this, 0,
                Intent(permissionAction).setPackage(packageName), flags)
            usb.requestPermission(device, permission)
            showStatus("Waiting for Android USB permission...")
        }
    }

    private fun connect(device: UsbDevice) {
        if (connectionJob?.isActive == true || driver != null) return
        val generation = ++connectionGeneration
        deviceName = device.deviceName
        showStatus("Claiming descriptor-backed HID interfaces and initializing keypad...")
        connectButton.text = getString(R.string.disconnect)
        connectionJob = lifecycleScope.launch {
            try {
                val connected = MxKeypad.connect(usb, device,
                    onEvent = { event -> runOnUiThread {
                        if (generation == connectionGeneration) handleKey(event)
                    } },
                    onLog = { line -> runOnUiThread { appendLog("session $generation: $line") } },
                    onError = { error -> runOnUiThread {
                        if (generation == connectionGeneration) {
                            disconnect()
                            fail("USB connection failed", error)
                        }
                    } })
                if (generation != connectionGeneration || !resumed) {
                    connected.close()
                    return@launch
                }
                driver = connected
                showStatus("Protocol initialized. Uploading nine LCD images; check the physical keys.")
                lastPaint = emptyList()
                updateDemo(force = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                disconnect()
                fail("Cannot initialize keypad", e)
            }
        }
    }

    private fun disconnect() {
        connectionGeneration++
        paintJob?.cancel()
        paintJob = null
        connectionJob?.cancel()
        connectionJob = null
        driver?.close()
        driver = null
        deviceName = null
        permissionDeviceName = null
        lastPaint = emptyList()
        if (::connectButton.isInitialized) connectButton.text = getString(R.string.connect)
        if (!preview && ::status.isInitialized) showStatus(getString(R.string.not_connected))
    }

    private fun handleKey(event: KeyEvent) {
        if (destroyed) return
        appendLog("${if (event.synthetic) "CANCEL" else "KEY"} 0x${event.controlId.toString(16)} " +
            if (event.pressed) "press" else "release")
        if (!event.pressed || !resumed) return
        when (event.controlId) {
            0x01a1 -> changePage(-1)
            0x01a2 -> changePage(1)
            in 1..9 -> performAction(event.controlId - 1)
        }
    }

    private fun changePage(delta: Int) {
        page = (page + delta + 3) % 3
        updateDemo(force = true)
    }

    private fun labels(): List<String> = when (page) {
        0 -> listOf(if (focus.running) "Pause" else "Start", focus.label(SystemClock.elapsedRealtime()), "Reset",
            "-5 min", "${focus.durationMillis / 60_000} min", "+5 min", "15 min", "25 min", "50 min")
        1 -> listOf("Volume -", "Play/Pause", "Volume +", "Previous", "Audio", "Next",
            "Mute", "${audio.getStreamVolume(AudioManager.STREAM_MUSIC)} / ${audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}", "Unmute")
        else -> listOf("Camera", "Settings", "Dialer", "Web", "Phone", "Alarm", "Focus", "Audio", "Repaint")
    }

    private fun updateDemo(force: Boolean = false) {
        val labels = labels()
        pageTitle.text = listOf(getString(R.string.focus), getString(R.string.audio), getString(R.string.phone))[page]
        keyButtons.forEachIndexed { index, button -> button.text = labels[index] }
        val connected = driver ?: return
        val renderPage = page
        val signature = labels + listOf("page=$page", "image=$customVersion")
        if (!force && signature == lastPaint) return
        if (paintJob?.isActive == true) return // Next tick paints the latest state; never build an unbounded frame queue.
        paintJob = lifecycleScope.launch {
            try {
                val sameContext = lastPaint.size == signature.size && lastPaint.takeLast(2) == signature.takeLast(2)
                for (index in 0..8) {
                    if (!force && sameContext && lastPaint[index] == signature[index]) continue
                    val bitmap = renderKey(index, labels[index], renderPage)
                    try { connected.setKeyImage(index, bitmap) } finally { bitmap.recycle() }
                }
                lastPaint = signature
                showStatus("Nine LCD image uploads acknowledged. Verify visibility and physical press/release in the log.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                disconnect()
                fail("LCD upload failed", e)
            }
        }
    }

    private fun renderKey(index: Int, label: String, renderPage: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(118, 118, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(listOf(Color.rgb(25, 67, 117), Color.rgb(39, 71, 64), Color.rgb(73, 52, 95))[renderPage])
        custom?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER }
        if (custom != null) {
            paint.color = 0xc0000000.toInt()
            canvas.drawRect(0f, 36f, 118f, 91f, paint)
            paint.color = Color.WHITE
        }
        paint.textSize = 14f
        canvas.drawText("${index + 1}", 59f, 23f, paint)
        paint.textSize = 18f
        while (paint.measureText(label) > 106 && paint.textSize > 10) paint.textSize -= 1
        canvas.drawText(label, 59f, 66f, paint)
        paint.textSize = 12f
        canvas.drawText(listOf("FOCUS", "AUDIO", "PHONE")[renderPage], 59f, 104f, paint)
        return bitmap
    }

    private fun performAction(index: Int) {
        if (!preview && driver == null) {
            Snackbar.make(root, "Connect a keypad or select Phone-only preview first", Snackbar.LENGTH_SHORT).show()
            return
        }
        try {
            when (page) {
                0 -> when (index) {
                    0 -> focus.toggle(SystemClock.elapsedRealtime())
                    2 -> focus.reset()
                    3 -> focus.adjust(-5)
                    5 -> focus.adjust(5)
                    6 -> focus.select(15)
                    7 -> focus.select(25)
                    8 -> focus.select(50)
                }
                1 -> when (index) {
                    0 -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                    1 -> mediaKey(AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                    2 -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                    3 -> mediaKey(AndroidKeyEvent.KEYCODE_MEDIA_PREVIOUS)
                    5 -> mediaKey(AndroidKeyEvent.KEYCODE_MEDIA_NEXT)
                    6 -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                    8 -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
                }
                2 -> when (index) {
                    0 -> launch(Intent("android.media.action.STILL_IMAGE_CAMERA"))
                    1 -> launch(Intent(Settings.ACTION_SETTINGS))
                    2 -> launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:")))
                    3 -> launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.logitech.com/")))
                    5 -> launch(Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS))
                    6 -> page = 0
                    7 -> page = 1
                }
            }
            appendLog("${if (preview) "PREVIEW" else "ACTION"} page=$page key=${index + 1}")
            updateDemo(force = true)
        } catch (e: SecurityException) {
            fail("Android rejected this control action", e)
        }
    }

    private fun mediaKey(code: Int) {
        audio.dispatchMediaKeyEvent(AndroidKeyEvent(AndroidKeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(AndroidKeyEvent(AndroidKeyEvent.ACTION_UP, code))
        Snackbar.make(root, "Media command sent; an active media session is required", Snackbar.LENGTH_SHORT).show()
    }

    private fun launch(intent: Intent) {
        try { startActivity(intent) } catch (e: ActivityNotFoundException) { fail("No app can handle this action", e) }
    }

    private fun refreshDiagnostics() {
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            diagnostics.text = withContext(Dispatchers.IO) { UsbDiagnostics.describe(usb) }
        }
    }

    private fun fail(message: String, error: Throwable) {
        appendLog("ERROR $message: ${error.message}")
        showStatus("$message: ${error.message}. Check diagnostics, reconnect and try again.")
        Snackbar.make(root, message, Snackbar.LENGTH_LONG).show()
    }

    private fun appendLog(line: String) {
        if (destroyed) return
        logs.addLast("${SystemClock.elapsedRealtime()} $line")
        while (logs.size > 250) logs.removeFirst()
        logView.text = logs.takeLast(60).joinToString("\n")
    }

    private fun showStatus(message: String) { status.text = message }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    @Suppress("DEPRECATION")
    private fun Intent.usbDevice(): UsbDevice? = if (Build.VERSION.SDK_INT >= 33)
        getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java) else getParcelableExtra(UsbManager.EXTRA_DEVICE)
}
