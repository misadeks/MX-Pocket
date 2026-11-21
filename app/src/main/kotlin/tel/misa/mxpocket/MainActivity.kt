package tel.misa.mxpocket

import android.Manifest
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tel.misa.mxpocket.driver.UsbDiagnostics
import tel.misa.mxpocket.plugins.PhoneApp
import tel.misa.mxpocket.ui.PocketScreen
import tel.misa.mxpocket.ui.PocketScreenEvent
import tel.misa.mxpocket.ui.PocketScreenState
import tel.misa.mxpocket.ui.*
import tel.misa.mxpocket.deck.DeckLimits
import java.io.ByteArrayOutputStream

class MainActivity : AppCompatActivity(), PocketSession.Screen {
    private val session get() = (application as PocketApplication).session
    private val usb by lazy { getSystemService(UsbManager::class.java) }
    private val access by lazy { UsbAccess(this) }
    private val direct by lazy { DirectShortcuts(this) }
    private lateinit var screen: PocketScreen
    private var diagnostics = ""
    private var tab by mutableStateOf(WorkspaceTab.HOME)
    private var presentation by mutableStateOf<PocketScreenState?>(null)
    private lateinit var workspace: ComposeView
    private var refreshJob: Job? = null
    private var resumed = false
    private var target: String? = null
    private var fromAttach = false
    private var usbToken: String? = null
    private var waitingForNotifications = false
    private val specialAccess = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        direct.enabled = Settings.canDrawOverlays(this)
        if (!direct.enabled) session.message("Direct shortcuts are off. Tap a keypad shortcut notification instead.")
        render()
    }

    private val permissionAction get() = "$packageName.USB_PERMISSION"
    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != permissionAction || usbToken == null || intent.getStringExtra("token") != usbToken) return
            usbToken = null
            // The callback is an immutable wake-up, not evidence of permission.
            val device = usb.keypad(target)
            if (device == null || !usb.hasPermission(device)) cancelAttempt("USB permission denied or keypad removed. Tap Connect to retry.")
            else advance()
            refreshDiagnostics()
        }
    }
    private val attachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val name = intent.usbDevice()?.deviceName ?: return
            if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED && !usb.deviceList.containsKey(name)) {
                access.detached(name)
                if (target == name) cancelAttempt("Keypad detached.")
            }
            refreshDiagnostics()
        }
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        waitingForNotifications = false
        if (target != null) {
            if (granted) advance() else cancelAttempt("Allow notifications in Android settings for a visible keypad session, then tap Connect.")
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
                        if (decoded.width != 118 || decoded.height != 118) decoded.recycle()
                    }
                }
                session.setImage(image)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                session.fail("Cannot load image", e)
            }
        }
    }
    private val logSaver = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) lifecycleScope.launch {
            try {
                val text = diagnostics + "\n" + session.logText()
                withContext(Dispatchers.IO) {
                    val output = contentResolver.openOutputStream(uri) ?: error("Cannot open log destination")
                    output.bufferedWriter().use { it.write(text) }
                }

                private val layoutSaver = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
                    if (uri != null) lifecycleScope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                val text = session.deck.exportJson()
                                val out = contentResolver.openOutputStream(uri, "wt") ?: error("Cannot open destination")
                                out.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
                            }
                            session.message("Layouts exported.")
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { session.fail("Cannot export layouts",e) }
                    }
                }
                private val layoutPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) lifecycleScope.launch {
                        try {
                            val json = withContext(Dispatchers.IO) {
                                val stream = contentResolver.openInputStream(uri) ?: error("Cannot open document")
                                stream.use {
                                    val bytes = ByteArrayOutputStream()
                                    val buffer = ByteArray(8192)
                                    while (true) {
                                        val n = it.read(buffer)
                                        if (n < 0) break
                                        require(bytes.size() + n <= DeckLimits.MAX_BYTES) { "Layout file too large" }
                                        bytes.write(buffer,0,n)
                                    }
                                    Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString()
                                }
                            }
                            AlertDialog.Builder(this@MainActivity).setTitle("Replace saved layouts?")
                                .setMessage("Import replaces profiles, page history, and context rules. Workflows and Android permissions are unchanged.")
                                .setNegativeButton(android.R.string.cancel,null).setPositiveButton("Import") { _,_ ->
                                    session.deck.importJson(json).fold({ session.message("Layouts imported.") },
                                        { session.fail("Cannot import layouts", it) })
                                }.show()
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { session.fail("Cannot import layouts",e) }
                    }
                }
                Snackbar.make(screen.view, "Diagnostic log saved", Snackbar.LENGTH_SHORT).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                session.fail("Cannot save log", e)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tab = savedInstanceState?.getString("tab")?.let { name -> WorkspaceTab.entries.find { it.name == name } } ?: WorkspaceTab.HOME
        screen = PocketScreen(this, session.plugins.descriptors, ::handleScreenEvent, live = true)
        workspace = ComposeView(this).apply {
            id = R.id.workspace_panel
            setContent {
                WorkspaceTheme {
                    WorkspacePanel(tab,session,presentation,::handleScreenEvent,
                        onExport = { layoutSaver.launch("mx-pocket-layouts.json") },
                        onImport = { layoutPicker.launch(arrayOf("application/json","text/*","application/octet-stream")) })
                }
            }
        }
        val navigation = ComposeView(this).apply {
            id = R.id.workspace_navigation
            setContent { WorkspaceTheme { WorkspaceNav(tab,::showTab) } }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(screen.view,LinearLayout.LayoutParams(-1,0,1f))
            addView(workspace,LinearLayout.LayoutParams(-1,0,1f))
            addView(navigation,LinearLayout.LayoutParams(-1,-2))
        })
        showTab(tab)
        ContextCompat.registerReceiver(this, permissionReceiver, IntentFilter(permissionAction), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, attachReceiver, IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        if (savedInstanceState == null && intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0) readAttach(intent)
        if (savedInstanceState != null) {
            target = savedInstanceState.getString("target")
            fromAttach = savedInstanceState.getBoolean("fromAttach")
            usbToken = savedInstanceState.getString("usbToken")
            waitingForNotifications = savedInstanceState.getBoolean("notificationPrompt")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("target", target)
        outState.putBoolean("fromAttach", fromAttach)
        outState.putString("usbToken", usbToken)
        outState.putBoolean("notificationPrompt", waitingForNotifications)
        outState.putString("tab",tab.name)
    }

    private fun showTab(value: WorkspaceTab) {
        tab = value
        screen.view.visibility = if (value == WorkspaceTab.HOME) View.VISIBLE else View.GONE
        workspace.visibility = if (value == WorkspaceTab.HOME) View.GONE else View.VISIBLE
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readAttach(intent)
        advance()
    }

    private fun readAttach(intent: Intent) {
        if (intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED || !access.automatic) return
        val reported = intent.usbDevice()?.takeIf(UsbDiagnostics::isKeypad) ?: return
        val device = usb.keypad(reported.deviceName) ?: return
        // A fast replug can deliver the new Activity intent before the previous detach/error callback.
        session.request?.let {
            if (it.name == device.deviceName) return
            session.disconnect("Previous keypad detached.")
        }
        target?.let {
            if (it == device.deviceName) return
            cancelAttempt("Previous keypad detached.")
        }
        if (access.alreadyHandled(device.deviceName)) return
        if (!usb.hasPermission(device)) {
            session.message("Android USB permission is required. Tap Connect.")
            return
        }
        target = device.deviceName
        fromAttach = true
        access.handled(device.deviceName)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        session.attach(this)
        refreshDiagnostics()
        advance()
    }

    override fun onPause() {
        resumed = false
        session.detach(this)
        super.onPause()
    }

    override fun onDestroy() {
        unregisterReceiver(permissionReceiver)
        unregisterReceiver(attachReceiver)
        super.onDestroy()
    }

    private fun requestConnect() {
        val candidates = usb.deviceList.values.filter(UsbDiagnostics::isKeypad)
        if (candidates.size != 1) {
            session.message(if (candidates.isEmpty()) "No MX Creative Keypad (046D:C354). Check cable and USB host support."
                else "Multiple keypads connected. Connect only one for this demo.")
            refreshDiagnostics()
            return
        }
        target = candidates.single().deviceName
        fromAttach = false
        advance()
    }

    private fun cancelAttempt(message: String) {
        target = null
        usbToken = null
        fromAttach = false
        session.message(message)
    }

    private fun advance() {
        val name = target ?: return
        if (!resumed || waitingForNotifications) return
        if (session.request != null) { target = null; usbToken = null; return }
        if (fromAttach && !access.automatic) { cancelAttempt(getString(R.string.not_connected)); return }
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            cancelAttempt("Unlock the phone and tap Connect."); return
        }
        val device = usb.keypad(name) ?: run { cancelAttempt("Keypad no longer available. Check the cable and tap Connect."); return }
        if (!usb.hasPermission(device)) {
            if (fromAttach) { cancelAttempt("USB permission is required. Tap Connect."); return }
            if (usbToken != null) return
            val token = UUID.randomUUID().toString()
            usbToken = token
            val callback = Intent(permissionAction).setPackage(packageName).putExtra("token", token)
            val permission = PendingIntent.getBroadcast(this, token.hashCode(), callback,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
            session.message("Waiting for Android USB permission...")
            try {
                usb.requestPermission(device, permission)
            } catch (e: RuntimeException) {
                cancelAttempt("Could not request USB permission: ${e.message}. Tap Connect to retry.")
            }
            return
        }
        usbToken = null
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            waitingForNotifications = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        if (!KeypadService.notificationsVisible(this)) {
            cancelAttempt("Enable keypad session notifications in Android settings and tap Connect.")
            return
        }
        target = null
        access.handled(name)
        val ticket = session.reserve(device) ?: return
        try {
            // This is a resumed Activity. Start normally and promote before opening USB; a
            // detach/cancel before delivery can then stop without owing Android an FGS promotion.
            startService(KeypadService.command(this, ticket))
        } catch (e: RuntimeException) {
            session.disconnect()
            session.fail("Android did not allow the keypad foreground service", e)
        }
    }

    private fun handleScreenEvent(event: PocketScreenEvent) {
        when (event) {
            PocketScreenEvent.ToggleConnection -> if (target != null) cancelAttempt(getString(R.string.not_connected))
                else if (session.request != null) session.disconnect() else requestConnect()
            is PocketScreenEvent.SetAutomatic -> {
                access.automatic = event.enabled
                if (!event.enabled && fromAttach && target != null) cancelAttempt(getString(R.string.not_connected))
            }
            is PocketScreenEvent.SetDirectShortcuts -> if (!event.enabled) {
                direct.enabled = false
                session.message("Direct shortcuts are off. You can revoke Display over other apps in Android settings.")
            } else {
                val hasAccess = Settings.canDrawOverlays(this)
                AlertDialog.Builder(this).setTitle(R.string.direct_shortcuts).setMessage(R.string.direct_shortcuts_rationale)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(if (hasAccess) R.string.enable_shortcuts else R.string.open_settings) { _, _ ->
                        if (hasAccess) { direct.enabled = true; render() }
                        else try {
                            specialAccess.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                        } catch (_: ActivityNotFoundException) {
                            session.message("Special access is unavailable. Use keypad shortcut notifications instead.")
                        } catch (_: SecurityException) {
                            session.message("Android restricted special access. Use keypad shortcut notifications instead.")
                        }
                    }.show()
            }
            PocketScreenEvent.EnterPreview -> {
                target = null
                usbToken = null
                session.startPreview()
            }
            PocketScreenEvent.StopPreview -> session.disconnect("Virtual keypad disconnected. Reconnect to resume this layout.")
            is PocketScreenEvent.SelectPlugin -> session.selectProfile(event.id)
            is PocketScreenEvent.TapKey -> if (!session.phoneKey(event.index))
                Snackbar.make(screen.view, "Connect a keypad or select Phone-only preview first", Snackbar.LENGTH_SHORT).show()
            is PocketScreenEvent.ChangePage -> session.changePage(event.delta)
            PocketScreenEvent.Repaint -> session.repaint()
            PocketScreenEvent.ChooseImage -> imagePicker.launch(arrayOf("image/*"))
            PocketScreenEvent.RefreshDiagnostics -> refreshDiagnostics()
            PocketScreenEvent.SaveLog -> logSaver.launch("mx-pocket-diagnostics.txt")
        }
        render()
    }

    override fun render() {
        val deck = session.deck.state.value
        val frame = session.frame()
        val state = PocketScreenState(
            status = session.status,
            pluginTitle = if (deck.activePage.name == deck.activeProfile.name) deck.activeProfile.name
                else "${deck.activeProfile.name} / ${deck.activePage.name}",
            frame = frame, profiles = deck.profiles.map { it.id to it.name },
            activeId = deck.activeProfileId, reason = session.deck.reason.value,
            simulating = session.preview, canRunKeys = session.ready,
            rendered = frame.keys.mapIndexed(session::keyImage),
            connectionActive = target != null || session.request != null,
            diagnostics = diagnostics, log = session.logText(60), automatic = access.automatic, directShortcuts = direct.enabled,
        )
        presentation = state
        screen.render(state)
    }

    override fun openApp(app: PhoneApp): Boolean {
        if (!resumed || !isUnlockedAndInteractive()) return false
        launchPhoneApp(app)?.let(session::message)
        return true
    }

    private fun refreshDiagnostics() {
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            diagnostics = withContext(Dispatchers.IO) { UsbDiagnostics.describe(usb) }
            render()
        }
    }
}
