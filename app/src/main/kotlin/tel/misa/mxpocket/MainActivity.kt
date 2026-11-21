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
import android.widget.HorizontalScrollView
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
import tel.misa.mxpocket.plugins.AudioSnapshot
import tel.misa.mxpocket.plugins.BuiltInPlugins
import tel.misa.mxpocket.plugins.KeyTile
import tel.misa.mxpocket.plugins.MediaCommand
import tel.misa.mxpocket.plugins.PhoneApp
import tel.misa.mxpocket.plugins.PluginAction
import tel.misa.mxpocket.plugins.PluginEngine
import tel.misa.mxpocket.plugins.PluginFrame
import tel.misa.mxpocket.plugins.PluginKeyEvent
import tel.misa.mxpocket.plugins.VolumeChange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
    private lateinit var plugins: PluginEngine
    private var preview = false
    private var driver: MxKeypad? = null
    private var deviceName: String? = null
    private var permissionDeviceName: String? = null
    private var connectionJob: Job? = null
    private var paintJob: Job? = null
    private var refreshJob: Job? = null
    private var custom: Bitmap? = null
    private var customVersion = 0
    private data class RenderStamp(
        val pluginId: String,
        val generation: Int,
        val frame: PluginFrame,
        val customVersion: Int,
        val repaintVersion: Int,
    )
    private var repaintVersion = 0
    private var lastPaint: RenderStamp? = null
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
        plugins = PluginEngine(
            BuiltInPlugins.create(getString(R.string.focus), getString(R.string.audio), getString(R.string.phone)),
            lifecycleScope,
            SystemClock::elapsedRealtime,
            readAudio = {
                AudioSnapshot(audio.getStreamVolume(AudioManager.STREAM_MUSIC),
                    audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
            },
            perform = ::performPluginAction,
            invalidateUi = { if (::root.isInitialized) root.post { if (!destroyed) updateDemo() } },
            reportError = { id, error -> if (!destroyed) fail("Plugin $id failed", error) },
        )
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
    }

    override fun onStart() {
        super.onStart()
        resumed = true
        plugins.setEnabled(preview || driver != null)
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
        plugins.close()
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
            plugins.setEnabled(resumed)
            showStatus(getString(R.string.preview_note))
            updateDemo()
        }
        pageTitle = text(getString(R.string.focus), 22)
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabs)
        }, LinearLayout.LayoutParams(-1, -2))
        for (descriptor in plugins.descriptors) {
            button(descriptor.title, parent = tabs, outlined = true) {
                plugins.select(descriptor.id)
                updateDemo()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
            }
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
        plugins.setEnabled(false)
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
                plugins.setEnabled(true)
                showStatus("Protocol initialized. Uploading nine LCD images; check the physical keys.")
                lastPaint = null
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
        if (::plugins.isInitialized) plugins.setEnabled(false)
        paintJob?.cancel()
        paintJob = null
        connectionJob?.cancel()
        connectionJob = null
        driver?.close()
        driver = null
        deviceName = null
        permissionDeviceName = null
        lastPaint = null
        if (::connectButton.isInitialized) connectButton.text = getString(R.string.connect)
        if (!preview && ::status.isInitialized) showStatus(getString(R.string.not_connected))
    }

    private fun handleKey(event: KeyEvent) {
        if (destroyed) return
        appendLog("${if (event.synthetic) "CANCEL" else "KEY"} 0x${event.controlId.toString(16)} " +
            if (event.pressed) "press" else "release")
        if (!resumed) return
        when (event.controlId) {
            0x01a1 -> if (event.pressed && !event.synthetic) changePage(-1)
            0x01a2 -> if (event.pressed && !event.synthetic) changePage(1)
            in 1..9 -> plugins.key(PluginKeyEvent(event.controlId - 1, event.pressed, event.synthetic))
        }
    }

    private fun changePage(delta: Int) {
        plugins.next(delta)
        updateDemo()
    }

    private fun updateDemo(force: Boolean = false) {
        val frame = plugins.render()
        pageTitle.text = plugins.descriptors.single { it.id == plugins.selectedId }.title
        keyButtons.forEachIndexed { index, button -> button.text = frame.keys[index].label }
        val connected = driver ?: return
        if (force) repaintVersion++
        val stamp = RenderStamp(plugins.selectedId, plugins.generation, frame, customVersion, repaintVersion)
        if (stamp == lastPaint) return
        if (paintJob?.isActive == true) return // Coalesce updates; never cancel a USB fragment exchange on a page switch.
        paintJob = lifecycleScope.launch {
            try {
                val previous = lastPaint
                val sameContext = previous != null && previous.pluginId == stamp.pluginId &&
                    previous.generation == stamp.generation && previous.customVersion == stamp.customVersion &&
                    previous.repaintVersion == stamp.repaintVersion
                for (index in 0..8) {
                    if (stamp.generation != plugins.generation || driver !== connected) break
                    if (sameContext && previous?.frame?.keys?.get(index) == frame.keys[index]) continue
                    val bitmap = renderKey(index, frame.keys[index])
                    try { connected.setKeyImage(index, bitmap) } finally { bitmap.recycle() }
                }
                if (stamp.generation == plugins.generation && driver === connected) {
                    lastPaint = stamp
                    showStatus("LCD image updates acknowledged. Verify visibility and physical press/release in the log.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                disconnect()
                fail("LCD upload failed", e)
            } finally {
                if (driver === connected && !destroyed) root.post { updateDemo() }
            }
        }
    }

    private fun renderKey(index: Int, tile: KeyTile): Bitmap {
        val bitmap = Bitmap.createBitmap(118, 118, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(tile.background)
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
        while (paint.measureText(tile.label) > 106 && paint.textSize > 10) paint.textSize -= 1
        canvas.drawText(tile.label, 59f, 66f, paint)
        paint.textSize = 12f
        while (paint.measureText(tile.footer) > 106 && paint.textSize > 8) paint.textSize -= 1
        canvas.drawText(tile.footer, 59f, 104f, paint)
        return bitmap
    }

    private fun performAction(index: Int) {
        if (!preview && driver == null) {
            Snackbar.make(root, "Connect a keypad or select Phone-only preview first", Snackbar.LENGTH_SHORT).show()
            return
        }
        plugins.key(PluginKeyEvent(index, true))
        plugins.key(PluginKeyEvent(index, false))
        appendLog("${if (preview) "PREVIEW" else "ACTION"} plugin=${plugins.selectedId} key=${index + 1}")
        updateDemo()
    }

    private fun performPluginAction(action: PluginAction, scope: CoroutineScope) {
        when (action) {
            is PluginAction.Volume -> {
                val adjustment = when (action.change) {
                    VolumeChange.LOWER -> AudioManager.ADJUST_LOWER
                    VolumeChange.RAISE -> AudioManager.ADJUST_RAISE
                    VolumeChange.MUTE -> AudioManager.ADJUST_MUTE
                    VolumeChange.UNMUTE -> AudioManager.ADJUST_UNMUTE
                }
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, adjustment, AudioManager.FLAG_SHOW_UI)
            }
            is PluginAction.Media -> mediaKey(when (action.command) {
                MediaCommand.PLAY_PAUSE -> AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                MediaCommand.PREVIOUS -> AndroidKeyEvent.KEYCODE_MEDIA_PREVIOUS
                MediaCommand.NEXT -> AndroidKeyEvent.KEYCODE_MEDIA_NEXT
            })
            is PluginAction.OpenApp -> launch(when (action.app) {
                PhoneApp.CAMERA -> Intent("android.media.action.STILL_IMAGE_CAMERA")
                PhoneApp.SETTINGS -> Intent(Settings.ACTION_SETTINGS)
                PhoneApp.DIALER -> Intent(Intent.ACTION_DIAL, Uri.parse("tel:"))
                PhoneApp.WEB -> Intent(Intent.ACTION_VIEW, Uri.parse("https://www.logitech.com/"))
                PhoneApp.ALARMS -> Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS)
            })
            PluginAction.CompletionTone -> scope.launch {
                val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 50)
                try {
                    tone.startTone(ToneGenerator.TONE_PROP_ACK, 500)
                    delay(600)
                } finally {
                    tone.release()
                }
                appendLog("Focus session complete")
            }
            PluginAction.Repaint -> updateDemo(force = true)
            is PluginAction.SelectPlugin -> error("Navigation is handled by PluginEngine")
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
