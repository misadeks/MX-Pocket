package tel.misa.mxpocket.setup

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import java.io.IOException
import java.util.UUID
import tel.misa.mxpocket.i18n.AccessCopy
import tel.misa.mxpocket.i18n.AppLocale
import tel.misa.mxpocket.i18n.Copy
import tel.misa.mxpocket.isUnlockedAndInteractive
import tel.misa.mxpocket.decks.observeTeamsProfiles
import tel.misa.mxpocket.keypad
import tel.misa.mxpocket.requestKeypadAccess
import tel.misa.mxpocket.shared.ui.SetupAction
import tel.misa.mxpocket.shared.ui.SetupEvent
import tel.misa.mxpocket.shared.ui.SetupOption
import tel.misa.mxpocket.shared.ui.SetupFeature as Feature
import tel.misa.mxpocket.shared.ui.SetupScreen
import tel.misa.mxpocket.shared.ui.SetupScreenState
import tel.misa.mxpocket.shared.ui.SetupStep
import tel.misa.mxpocket.shared.ui.PocketTheme

/** Consent only: this Activity never attaches a live screen to the session or opens a device. */
class SetupActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) =
        super.attachBaseContext(tel.misa.mxpocket.ui.LanguagePreference.activityContext(newBase))

    private enum class Notice(val copy: Copy) {
        PERMISSION(AccessCopy.PermissionDenied), USB(AccessCopy.UsbDenied), SETTINGS(AccessCopy.SetupSettingsUnavailable),
        RESTRICTED(AccessCopy.SetupRestricted), UNAVAILABLE(AccessCopy.SetupOptionUnavailable),
        LAYOUT(AccessCopy.SettingChangeFailed), STORAGE(AccessCopy.SettingSaveFailed)
    }

    private val choices by lazy { SetupChoices(this) }
    private val access by lazy { SetupAccess(this, choices) }
    private var selected by mutableStateOf(emptySet<Feature>())
    private var options by mutableStateOf(emptyList<SetupOption>())
    private lateinit var profiles: BroadcastReceiver
    private var reviewing by mutableStateOf(false)
    private var steps by mutableStateOf(emptyList<SetupStep>())
    private var notice by mutableStateOf<Notice?>(null)
    private var usbName: String? = null
    private var usbToken: String? = null
    private val usbReply get() = "$packageName.SETUP_USB_PERMISSION"

    private val notices = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        afterRequest(Manifest.permission.POST_NOTIFICATIONS, it)
    }
    private fun afterRequest(permission: String, granted: Boolean) {
        choices.markAsked(permission)
        notice = if (!granted) Notice.PERMISSION else null
        refresh()
    }

    private val changes = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == usbReply) {
                if (usbToken == null || usbToken != intent.getStringExtra("token")) return
                val device = access.usb.keypad(usbName)
                notice = if (device != null && access.usb.hasPermission(device)) null else Notice.USB
                usbToken = null
                usbName = null
            } else if (usbName != null && access.usb.keypad(usbName) == null) {
                usbName = null
                usbToken = null
            }
            refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        options = access.options()
        profiles = observeTeamsProfiles(::refresh)
        val firstRun = intent.getBooleanExtra("firstRun", false)
        selected = (savedInstanceState?.getStringArrayList("selected")?.let(SetupChoices::decode)
            ?: if (firstRun) emptySet() else choices.load(access::existingFeatures))
            .intersect(options.filter { it.unavailableReason == null }.map { it.feature }.toSet())
        reviewing = savedInstanceState?.getBoolean("review") == true
        notice = savedInstanceState?.getString("notice")?.let { id -> Notice.entries.find { it.name == id } }
        usbName = savedInstanceState?.getString("usbName")
        usbToken = savedInstanceState?.getString("usbToken")
        ContextCompat.registerReceiver(this, changes, IntentFilter(usbReply).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        setContent {
            PocketTheme(tel.misa.mxpocket.ui.ThemePreference.current(this)) {
                LaunchedEffect(AppLocale.current) {
                    title = AccessCopy.FeatureSetup.text()
                    refresh()
                }
                BackHandler(enabled = reviewing) { handle(SetupEvent.Edit) }
                SetupScreen(SetupScreenState(selected, reviewing, steps, notice?.copy?.text().orEmpty(), firstRun, options), ::handle)
            }
        }
    }

    private fun handle(event: SetupEvent) {
        when (event) {
            is SetupEvent.Select -> selected = if (event.feature in selected) selected - event.feature else selected + event.feature
            SetupEvent.Review -> {
                choices.save(selected)
                reviewing = true
                refresh()
            }
            SetupEvent.Edit -> { reviewing = false; notice = null }
            is SetupEvent.Act -> perform(event.action)
            SetupEvent.Cancel, SetupEvent.Finish -> finish()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList("selected", ArrayList(selected.map { it.name }))
        outState.putBoolean("review", reviewing)
        outState.putString("notice", notice?.name)
        outState.putString("usbName", usbName)
        outState.putString("usbToken", usbToken)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        unregisterReceiver(profiles)
        unregisterReceiver(changes)
        super.onDestroy()
    }

    private fun refresh() {
        options = access.options()
        if (reviewing) steps = access.steps(selected, usbName)
    }

    private fun perform(action: SetupAction) {
        refresh()
        if (!reviewing || steps.none { it.action == action } || !isUnlockedAndInteractive()) return
        notice = null
        try {
            when (action) {
                SetupAction.POST -> notices.launch(Manifest.permission.POST_NOTIFICATIONS)
                SetupAction.USB -> {
                    val device = access.keypads().singleOrNull() ?: return refresh()
                    val token = UUID.randomUUID().toString()
                    if (access.usb.hasPermission(device)) return refresh()
                    access.usb.requestKeypadAccess(this, device, usbReply, token)
                    usbName = device.deviceName
                    usbToken = token
                }
                else -> access.perform(action)
            }
        } catch (_: ActivityNotFoundException) {
            notice = Notice.SETTINGS
        } catch (_: SecurityException) {
            notice = Notice.RESTRICTED
        } catch (_: IllegalArgumentException) {
            notice = Notice.UNAVAILABLE
        } catch (_: IllegalStateException) {
            notice = Notice.LAYOUT
        } catch (_: IOException) {
            notice = Notice.STORAGE
        }
        refresh()
    }

    companion object {
        internal fun intent(context: Context, firstRun: Boolean) =
            Intent(context, SetupActivity::class.java).putExtra("firstRun", firstRun)
    }
}
