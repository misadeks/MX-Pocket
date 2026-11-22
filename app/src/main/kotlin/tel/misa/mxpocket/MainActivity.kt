package tel.misa.mxpocket

import tel.misa.mxpocket.i18n.CallCopy
import tel.misa.mxpocket.ui.DeviceCopy as D
import tel.misa.mxpocket.i18n.WorkspaceCopy as W
import tel.misa.mxpocket.i18n.displayName
import tel.misa.mxpocket.i18n.displayPageName
import tel.misa.mxpocket.i18n.displaySelectionReason

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
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
import tel.misa.mxpocket.ui.LanguagePreference
import tel.misa.mxpocket.deck.DeckLimits
import java.io.ByteArrayOutputStream

class MainActivity : AppCompatActivity(), PocketSession.Screen {
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(LanguagePreference.activityContext(newBase))

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
        if (!direct.enabled) session.message(D.DirectOff.text())
        render()
    }

    private val permissionAction get() = "$packageName.USB_PERMISSION"
    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != permissionAction || usbToken == null || intent.getStringExtra("token") != usbToken) return
            usbToken = null
            // The callback is an immutable wake-up, not evidence of permission.
            val device = usb.keypad(target)
            if (device == null || !usb.hasPermission(device)) cancelAttempt(D.Denied.text())
            else advance()
            refreshDiagnostics()
        }
    }
    private val attachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val name = intent.usbDevice()?.deviceName ?: return
            if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED && !usb.deviceList.containsKey(name)) {
                access.detached(name)
                if (target == name) cancelAttempt(D.Detached.text())
            }
            refreshDiagnostics()
        }
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        waitingForNotifications = false
        if (target != null) {
            if (granted) advance() else cancelAttempt(D.AllowNotifications.text())
        }
    }
    private val callStatePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) session.message(CallCopy.PhonePermissionDenied.text())
        session.refreshPhoneCall()
        render()
    }
    private val imagePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            try {
                val image = withContext(Dispatchers.IO) {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
                    require(bounds.outWidth > 0 && bounds.outHeight > 0) { D.Decode.text() }
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 960) sample *= 2
                    val options = BitmapFactory.Options().apply { inSampleSize = sample }
                    val decoded = contentResolver.openInputStream(uri).use {
                        BitmapFactory.decodeStream(it, null, options)
                    } ?: error(D.Decode.text())
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
                session.fail(D.LoadImage.text(), e)
            }
        }
    }
    private val logSaver = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) lifecycleScope.launch {
            try {
                val text = diagnostics + "\n" + session.logText()
                withContext(Dispatchers.IO) {
                    val output = contentResolver.openOutputStream(uri) ?: error(D.LogDestination.text())
                    output.bufferedWriter().use { it.write(text) }
                }

                session.message(D.SavedLog.text())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                session.fail(D.SaveLog.text(), e)
            }
        }
    }

    private val layoutSaver = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val text = session.deck.exportJson()
                    val out = contentResolver.openOutputStream(uri, "wt") ?: error(D.Destination.text())
                    out.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
                }
                session.message(W.Exported.text())
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { session.fail(D.Export.text(),e) }
        }
    }
    private val layoutPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            try {
                val imported = withContext(Dispatchers.IO) {
                    val stream = contentResolver.openInputStream(uri) ?: error(D.OpenDocument.text())
                    val text = stream.use {
                        val bytes = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val n = it.read(buffer)
                            if (n < 0) break
                            require(bytes.size() + n <= DeckLimits.MAX_BYTES) { D.TooLarge.text() }
                            bytes.write(buffer,0,n)
                        }
                        Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString()
                    }
                    session.deck.importJson(text)
                }
                imported.fold({ session.message(W.Imported.text()) }, { session.fail(D.Import.text(), it) })
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { session.fail(D.Import.text(),e) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LanguagePreference.sync(this)
        tab = savedInstanceState?.getString("tab")?.let { name -> WorkspaceTab.entries.find { it.name == name } } ?: WorkspaceTab.HOME
        shortcutDisclosure = savedInstanceState?.getBoolean("shortcutDisclosure") == true
        setContent {
            PocketTheme(ThemePreference.current(this)) {
                presentation?.let { state ->
                    BackHandler(tab != WorkspaceTab.HOME) { showTab(WorkspaceTab.HOME) }
                    val icons by session.integrationIcons.collectAsState()
                    CompositionLocalProvider(LocalIntegrationIcons provides remember(icons) { icons.mapValues { it.value.asImageBitmap() } }) {
                        AndroidWorkspace(tab, ::showTab, session, state, ::handleScreenEvent,
                            usageGranted = usageGranted, onUsageSettings = ::openUsageSettings,
                            onExport = { layoutSaver.launch("mx-pocket-layouts.json") },
                            onImport = { layoutPicker.launch(arrayOf("application/json","text/*","application/octet-stream")) })
                    }
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
        savedInstanceState?.getString("target")?.let { name ->
            target = name
            fromAttach = savedInstanceState.getBoolean("fromAttach")
            usbToken = savedInstanceState.getString("usbToken")
            waitingForNotifications = savedInstanceState.getBoolean("notificationPrompt")
        }
        if (savedInstanceState == null) welcomeLauncher(intent)
    }

    private fun welcomeLauncher(intent: Intent) {
        if (target == null && SetupChoices(this).takeLauncherWelcome(intent)) openSetup(firstRun = true)
    }

    private fun openSetup(firstRun: Boolean) {
        startActivity(SetupActivity.intent(this, firstRun))
    }

    private fun openUsageSettings(): String? =
        try {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            null
        } catch (_: ActivityNotFoundException) {
            D.UsageSettings.text()
        } catch (_: SecurityException) {
            D.RestrictedSettings.text()
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
        welcomeLauncher(intent)
    }

    private fun readAttach(intent: Intent) {
        if (intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED || !access.automatic) return
        val reported = intent.usbDevice()?.takeIf(UsbDiagnostics::isKeypad) ?: return
        val device = usb.keypad(reported.deviceName) ?: return
        // A fast replug can deliver the new Activity intent before the previous detach/error callback.
        session.request?.let {
            if (it.name == device.deviceName) return
            session.disconnect(D.PreviousDetached.text())
        }
        target?.let {
            if (it == device.deviceName) return
            cancelAttempt(D.PreviousDetached.text())
        }
        if (access.alreadyHandled(device.deviceName)) return
        if (!usb.hasPermission(device)) {
            session.message(D.PermissionRequired.text())
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
            session.message(if (candidates.isEmpty()) D.Missing.text()
                else D.Multiple.text())
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
        waitingForNotifications = false
        session.message(message)
    }

    private fun advance() {
        val name = target ?: return
        if (!resumed) return
        if (session.request != null) { target = null; usbToken = null; return }
        if (fromAttach && !access.automatic) { cancelAttempt(getString(R.string.not_connected)); return }
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            cancelAttempt(D.UnlockConnect.text()); return
        }
        val device = usb.keypad(name) ?: run { cancelAttempt(D.Gone.text()); return }
        if (waitingForNotifications) return
        if (!usb.hasPermission(device)) {
            if (fromAttach) { cancelAttempt(D.NeedUsb.text()); return }
            if (usbToken != null) return
            val token = UUID.randomUUID().toString()
            usbToken = token
            session.message(D.Waiting.text())
            try {
                usb.requestKeypadAccess(this, device, permissionAction, token)
            } catch (e: RuntimeException) {
                cancelAttempt(D.RequestFailed.text(e.message))
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
            cancelAttempt(D.NeedNotifications.text())
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
            session.fail(D.ServiceDenied.text(), e)
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
            session.message(D.SpecialUnavailable.text())
        } catch (_: SecurityException) {
            session.message(D.SpecialRestricted.text())
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
                session.message(D.RevokeOverlay.text())
            } else shortcutDisclosure = true
            is PocketScreenEvent.DesktopInput -> return
            is PocketScreenEvent.SelectPlugin -> session.openProfile(event.id)
            is PocketScreenEvent.ChangePage -> session.changePage(event.delta)
            PocketScreenEvent.Repaint -> session.repaint()
            PocketScreenEvent.ChooseImage -> imagePicker.launch(arrayOf("image/*"))
            PocketScreenEvent.RefreshDiagnostics -> refreshDiagnostics()
            PocketScreenEvent.SaveLog -> logSaver.launch("mx-pocket-diagnostics.txt")
            PocketScreenEvent.FeatureSetup -> openSetup(firstRun = false)
            is PocketScreenEvent.DefaultProfile -> session.deck.setDefault(event.id).onFailure { session.fail(D.ChangeDefault.text(), it) }
            is PocketScreenEvent.EnablePlugin -> session.setPluginEnabled(event.id, event.enabled)
            is PocketScreenEvent.PluginSettings -> session.pluginSettings(event.id)?.second?.invoke()
            is PocketScreenEvent.SetTheme -> ThemePreference.set(this, event.mode)
            is PocketScreenEvent.SetLanguage -> {
                LanguagePreference.set(this, event.language)
                session.languageChanged()
                if (Build.VERSION.SDK_INT < 33) recreate()
            }
            is PocketScreenEvent.SetPhoneCalls -> {
                session.phoneAccess.enabled = event.enabled && session.phoneAccess.supported
                session.refreshPhoneCall()
            }
            is PocketScreenEvent.SetCallerDetails -> {
                session.phoneAccess.details = event.enabled && Build.VERSION.SDK_INT >= 34
                session.refreshPhoneCall()
            }
            PocketScreenEvent.AllowPhoneState -> if (resumed && session.phoneAccess.enabled && isUnlockedAndInteractive())
                callStatePermission.launch(Manifest.permission.READ_PHONE_STATE)
            PocketScreenEvent.CallerNotificationAccess -> if (Build.VERSION.SDK_INT >= 34 && resumed &&
                session.phoneAccess.enabled && session.phoneAccess.details && isUnlockedAndInteractive()) try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, session.phoneAccess.listener.flattenToString()))
            } catch (_: ActivityNotFoundException) {
                session.message(CallCopy.OpenAccessSettings.text())
            } catch (_: SecurityException) {
                session.message(CallCopy.RestrictedAccess.text())
            }
        }
        render()
    }

    override fun render() {
        val deck = session.deck.operatingState.value
        val carrierCall = deck.activeProfileId == tel.misa.mxpocket.deck.PHONE_CALL_VIEW
        val profile = deck.activeProfile.displayName()
        val page = deck.activeProfile.displayPageName(deck.activePage)
        val state = PocketScreenState(
            status = session.status,
            pluginTitle = if (carrierCall) CallCopy.ViewTitle.text() else if (page == profile) profile else "$profile / $page",
            tiles = session.homeTiles(),
            profiles = deck.navigationProfiles.map { ProfileChoice(it.id, it.displayName()) },
            activeId = deck.activeProfileId, reason = if (carrierCall) CallCopy.ViewReason.text() else displaySelectionReason(deck.selectionReason),
            connectionLabel = session.connectionLabel,
            connectionActive = target != null || session.request != null,
            usbReady = session.ready, activePageId = deck.activePage.id,
            diagnostics = diagnostics, log = session.logText(60), automatic = access.automatic, directShortcuts = direct.enabled,
            themeMode = ThemePreference.current(this),
            language = LanguagePreference.choice(this),
            phoneCalls = session.phoneAccess.let {
                PhoneCallSettings(it.supported, it.enabled, it.phoneGranted, Build.VERSION.SDK_INT >= 34, it.details,
                    it.notificationGranted, session.phoneCallBlocker)
            },
        )
        presentation = state
    }

    override fun openApp(app: PhoneApp): Boolean {
        if (!resumed || !isUnlockedAndInteractive()) return false
        launchPhoneApp(app)?.let(session::message)
        return true
    }

    override fun openUri(uri: String): Boolean {
        if (!resumed || !isUnlockedAndInteractive()) return false
        launchShortcut(PocketSession.Target.Uri(uri))?.let(session::message)
        return true
    }

    override fun shareText(text: String): Boolean {
        if (!resumed || !isUnlockedAndInteractive()) return false
        launchShortcut(PocketSession.Target.Share(text))?.let(session::message)
        return true
    }

    override fun deckAction(command: DeckCommand, component: String?): DeckResult {
        if (!resumed || !isUnlockedAndInteractive()) return unavailable(D.OpenUnlocked.text())
        val intent = when (command) {
            DeckCommand.TEAMS_OPEN -> teamsLauncher() ?: return unavailable(D.NoTeams.text())
            DeckCommand.TEAMS_ACCESS -> Intent(this, TeamsAccessActivity::class.java)
            DeckCommand.OPEN -> session.favoriteApps.intentFor(component) ?: return unavailable(D.NoFavorite.text())
            DeckCommand.EDIT -> Intent(this, FavoritesActivity::class.java)
            DeckCommand.STUDIO, DeckCommand.VIDEO -> viewfinder(if (command == DeckCommand.STUDIO) CameraMode.PHOTO else CameraMode.VIDEO)
                ?: return unavailable(D.NoCamera.text())
            DeckCommand.CAMERA_ACCESS -> Intent(this, CameraControlsActivity::class.java)
            DeckCommand.MEDIA_ACCESS, DeckCommand.NAV_ACCESS -> DeckAccessActivity.intent(this, command)
            DeckCommand.HOME -> Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            DeckCommand.BACK -> { onBackPressedDispatcher.onBackPressed(); return ok(D.Back.text()) }
            DeckCommand.UP, DeckCommand.DOWN, DeckCommand.FORWARD, DeckCommand.REVERSE, DeckCommand.ACTIVATE ->
                return navigateVisibleWorkspace(command)
            DeckCommand.RECENTS -> return unavailable(D.RecentAccess.text())
            else -> return unavailable(D.NotScreenAction.text())
        }

        return try { startActivity(intent); ok(D.Opened.text(command.displayLabel)) }
        catch (_: ActivityNotFoundException) { unavailable(D.NoHandler.text()) }
        catch (_: SecurityException) { unavailable(D.ActionRestricted.text()) }
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
        if (!hasWindowFocus() || navigating || shortcutDisclosure) return unavailable(D.FocusWorkspace.text())
        val code = when (command) {
            DeckCommand.UP -> KeyEvent.KEYCODE_PAGE_UP
            DeckCommand.DOWN -> KeyEvent.KEYCODE_PAGE_DOWN
            DeckCommand.REVERSE, DeckCommand.FORWARD -> KeyEvent.KEYCODE_TAB
            DeckCommand.ACTIVATE -> KeyEvent.KEYCODE_ENTER
            else -> return unavailable(D.UnsupportedNavigation.text())
        }
        val meta = if (command == DeckCommand.REVERSE) KeyEvent.META_SHIFT_ON else 0
        val now = android.os.SystemClock.uptimeMillis()
        navigating = true
        return try {
            fun send(action: Int) = window.decorView.dispatchKeyEvent(KeyEvent(now, now, action, code, 0, meta))
            val down = send(KeyEvent.ACTION_DOWN)
            val up = send(KeyEvent.ACTION_UP)
            if (down || up) ok(D.Requested.text(command.displayLabel)) else unavailable(D.NoControl.text())
        } finally { navigating = false }
    }
}
