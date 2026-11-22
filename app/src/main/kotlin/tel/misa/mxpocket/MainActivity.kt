package tel.misa.mxpocket

import android.Manifest
import android.app.KeyguardManager
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
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tel.misa.mxpocket.driver.UsbDiagnostics
import tel.misa.mxpocket.plugins.PhoneApp
import tel.misa.mxpocket.setup.SetupActivity
import tel.misa.mxpocket.setup.SetupChoices
import tel.misa.mxpocket.decks.*
import tel.misa.mxpocket.shared.ui.*
import tel.misa.mxpocket.ui.AndroidWorkspace
import tel.misa.mxpocket.ui.ThemePreference
import tel.misa.mxpocket.deck.DeckLimits
import tel.misa.mxpocket.deck.DeckState
import tel.misa.mxpocket.plugins.PluginFrame
import java.io.ByteArrayOutputStream

class MainActivity : AppCompatActivity(), PocketSession.Screen {
    private val session get() = (application as PocketApplication).session
    private val usb by lazy { getSystemService(UsbManager::class.java) }
    private val access by lazy { UsbAccess(this) }
    private val direct by lazy { DirectShortcuts(this) }
    private var diagnostics = ""
    private var tab by mutableStateOf(WorkspaceTab.HOME)
    private var presentation by mutableStateOf<PocketScreenState?>(null)
    private var shortcutDisclosure by mutableStateOf(false)
    private var navigating = false
    private var refreshJob: Job? = null
    private var resumed = false
    private var usageGranted by mutableStateOf(false)
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

                session.message("Diagnostic log saved")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                session.fail("Cannot save log", e)
            }
        }
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
                val imported = withContext(Dispatchers.IO) {
                    val stream = contentResolver.openInputStream(uri) ?: error("Cannot open document")
                    val text = stream.use {
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
                    session.deck.importJson(text)
                }
                imported.fold({ session.message("Layouts imported.") }, { session.fail("Cannot import layouts", it) })
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { session.fail("Cannot import layouts",e) }
        }
    }

    private val setup = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == SetupActivity.TRY_VIRTUAL && target == null && session.request == null) {
            showTab(WorkspaceTab.HOME)
            if (!session.preview) session.startPreview()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tab = savedInstanceState?.getString("tab")?.let { name -> WorkspaceTab.entries.find { it.name == name } } ?: WorkspaceTab.HOME
        shortcutDisclosure = savedInstanceState?.getBoolean("shortcutDisclosure") == true
        setContent {
            PocketTheme(ThemePreference.current(this)) {
                presentation?.let { state ->
                    BackHandler(tab != WorkspaceTab.HOME) { showTab(WorkspaceTab.HOME) }
                    AndroidWorkspace(tab, ::showTab, session, state, ::handleScreenEvent,
                        usageGranted = usageGranted, onUsageSettings = ::openUsageSettings,
                        onExport = { layoutSaver.launch("mx-pocket-layouts.json") },
                        onImport = { layoutPicker.launch(arrayOf("application/json","text/*","application/octet-stream")) })
                }
                if (shortcutDisclosure) DirectShortcutConsentDialog(Settings.canDrawOverlays(this),
                    onConfirm = ::confirmDirectShortcuts, onDismiss = { shortcutDisclosure = false })
            }
        }
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
        if (savedInstanceState == null && target == null && session.request == null && !session.preview &&
            SetupChoices(this).takeLauncherWelcome(intent)) openSetup(firstRun = true)
    }

    private fun openSetup(firstRun: Boolean) {
        setup.launch(SetupActivity.intent(this, firstRun))
    }

    private fun openUsageSettings(): String? =
        try {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            null
        } catch (_: ActivityNotFoundException) {
            "Usage access settings are unavailable on this phone."
        } catch (_: SecurityException) {
            "Android restricted access to these settings."
        }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("target", target)
        outState.putBoolean("fromAttach", fromAttach)
        outState.putString("usbToken", usbToken)
        outState.putBoolean("notificationPrompt", waitingForNotifications)
        outState.putString("tab",tab.name)
        outState.putBoolean("shortcutDisclosure", shortcutDisclosure)
    }

    private fun showTab(value: WorkspaceTab) {
        tab = value
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
        usageGranted = hasUsageAccess()
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
            session.message("Waiting for Android USB permission...")
            try {
                usb.requestKeypadAccess(this, device, permissionAction, token)
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

    private fun confirmDirectShortcuts() {
        shortcutDisclosure = false
        if (Settings.canDrawOverlays(this)) {
            direct.enabled = true
            render()
        } else try {
            specialAccess.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } catch (_: ActivityNotFoundException) {
            session.message("Special access is unavailable. Use keypad shortcut notifications instead.")
        } catch (_: SecurityException) {
            session.message("Android restricted special access. Use keypad shortcut notifications instead.")
        }
    }

    internal fun handleScreenEvent(event: PocketScreenEvent) {
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
            } else shortcutDisclosure = true
            PocketScreenEvent.EnterPreview -> {
                target = null
                usbToken = null
                session.startPreview()
            }
            PocketScreenEvent.StopPreview -> session.disconnect("Virtual keypad disconnected. Reconnect to resume this layout.")
            is PocketScreenEvent.SelectPlugin -> session.selectProfile(event.id)
            is PocketScreenEvent.VirtualKey -> if (resumed && !navigating && tab == WorkspaceTab.HOME &&
                event.grid != null && event.grid == presentation?.virtualGrid) synchronized(session.deck) {
                if (event.grid == homeGrid(session.deck.state.value, session.frame()))
                    session.phoneKey(event.index, hold = event.hold)
            }
            is PocketScreenEvent.ChangePage -> session.changePage(event.delta)
            PocketScreenEvent.Repaint -> session.repaint()
            PocketScreenEvent.ChooseImage -> imagePicker.launch(arrayOf("image/*"))
            PocketScreenEvent.RefreshDiagnostics -> refreshDiagnostics()
            PocketScreenEvent.SaveLog -> logSaver.launch("mx-pocket-diagnostics.txt")
            PocketScreenEvent.FeatureSetup -> openSetup(firstRun = false)
            is PocketScreenEvent.DefaultProfile -> session.deck.setDefault(event.id).onFailure { session.fail("Cannot change default", it) }
            is PocketScreenEvent.EnablePlugin -> session.setPluginEnabled(event.id, event.enabled)
            is PocketScreenEvent.PluginSettings -> session.pluginSettings(event.id)?.second?.invoke()
            is PocketScreenEvent.SetTheme -> ThemePreference.set(this, event.mode)
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
            tiles = frame.keys.mapIndexed { i, key -> key.toGridTile(session.ready, session.keyImage(i, key).asImageBitmap()) },
            profiles = deck.profiles.map { ProfileChoice(it.id, it.name) },
            activeId = deck.activeProfileId, reason = session.deck.reason.value,
            simulating = session.preview, panelArt = session.panelArt?.asImageBitmap(),
            connectionLabel = session.connectionLabel,
            connectionActive = target != null || session.request != null,
            usbReady = session.ready && !session.preview, activePageId = deck.activePage.id, hardwareSummary = !session.preview,
            virtualGrid = homeGrid(deck, frame),
            diagnostics = diagnostics, log = session.logText(60), automatic = access.automatic, directShortcuts = direct.enabled,
            themeMode = ThemePreference.current(this),
        )
        presentation = state
    }

    private fun homeGrid(deck: DeckState, frame: PluginFrame): VirtualGrid? {
        if (!session.preview || !session.ready || session.request != null || target != null) return null
        val holds = if (deck.activePage.keys.any { it.holdAction != null }) session.frame(hold = true) else null
        return virtualGrid(session.inputGeneration, deck, frame, holds)
    }

    override fun openApp(app: PhoneApp): Boolean {
        if (!resumed || !isUnlockedAndInteractive()) return false
        launchPhoneApp(app)?.let(session::message)
        return true
    }

    override fun deckAction(command: DeckCommand, component: String?): DeckResult {
        if (!resumed || !isUnlockedAndInteractive()) return unavailable("Open MX Pocket on the unlocked phone.")
        val intent = when (command) {
            DeckCommand.TEAMS_OPEN -> teamsLauncher() ?: return unavailable("Teams is not installed in this profile.")
            DeckCommand.TEAMS_ACCESS -> Intent(this, TeamsAccessActivity::class.java)
            DeckCommand.OPEN -> session.favoriteApps.intentFor(component) ?: return unavailable("Favorite no longer available.")
            DeckCommand.EDIT -> Intent(this, FavoritesActivity::class.java)
            DeckCommand.STUDIO, DeckCommand.VIDEO -> viewfinder(if (command == DeckCommand.STUDIO) CameraMode.PHOTO else CameraMode.VIDEO)
                ?: return unavailable("No default camera viewfinder for this mode.")
            DeckCommand.CAMERA_ACCESS -> Intent(this, CameraControlsActivity::class.java)
            DeckCommand.MEDIA_ACCESS, DeckCommand.NAV_ACCESS -> DeckAccessActivity.intent(this, command)
            DeckCommand.HOME -> Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            DeckCommand.BACK -> { onBackPressedDispatcher.onBackPressed(); return ok("Back in MX Pocket.") }
            DeckCommand.UP, DeckCommand.DOWN, DeckCommand.FORWARD, DeckCommand.REVERSE, DeckCommand.ACTIVATE ->
                return navigateVisibleWorkspace(command)
            DeckCommand.RECENTS -> return unavailable("Recent apps needs navigation access. Choose Navigation access to learn more.")
            else -> return unavailable("Not a visible screen action.")
        }

        return try { startActivity(intent); ok("Opened ${command.label}.") }
        catch (_: ActivityNotFoundException) { unavailable("No app handles this action.") }
        catch (_: SecurityException) { unavailable("Android restricted this action.") }
    }

    private fun refreshDiagnostics() {
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            val (text, devices) = withContext(Dispatchers.IO) {
                UsbDiagnostics.describe(usb) to usb.deviceList.values.map {
                    "%04X-%04X".format(java.util.Locale.ROOT,it.vendorId,it.productId)
                }.toSet()
            }
            diagnostics = text
            session.observeUsb(devices)
            render()
        }
    }

    /** Only our focused activity receives these keys; never send input to a permission dialog or another app. */
    private fun navigateVisibleWorkspace(command: DeckCommand): DeckResult {
        if (!hasWindowFocus() || navigating || shortcutDisclosure) return unavailable("Focus the workspace first.")
        val code = when (command) {
            DeckCommand.UP -> KeyEvent.KEYCODE_PAGE_UP
            DeckCommand.DOWN -> KeyEvent.KEYCODE_PAGE_DOWN
            DeckCommand.REVERSE, DeckCommand.FORWARD -> KeyEvent.KEYCODE_TAB
            DeckCommand.ACTIVATE -> KeyEvent.KEYCODE_ENTER
            else -> return unavailable("Unsupported navigation command.")
        }
        val meta = if (command == DeckCommand.REVERSE) KeyEvent.META_SHIFT_ON else 0
        val now = android.os.SystemClock.uptimeMillis()
        navigating = true
        return try {
            fun send(action: Int) = window.decorView.dispatchKeyEvent(KeyEvent(now, now, action, code, 0, meta))
            val down = send(KeyEvent.ACTION_DOWN)
            val up = send(KeyEvent.ACTION_UP)
            if (down || up) ok("Requested ${command.label}.") else unavailable("No matching workspace control.")
        } finally { navigating = false }
    }
}
