package tel.misa.mxpocket.setup

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import java.util.UUID
import tel.misa.mxpocket.isUnlockedAndInteractive
import tel.misa.mxpocket.keypad
import tel.misa.mxpocket.requestKeypadAccess
import tel.misa.mxpocket.ui.WorkspaceTheme

/** Consent only: this Activity never attaches a live screen to the session or opens a device. */
class SetupActivity : AppCompatActivity() {
    private val choices by lazy { SetupChoices(this) }
    private val access by lazy { SetupAccess(this, choices) }
    private var selected by mutableStateOf(emptySet<Feature>())
    private var reviewing by mutableStateOf(false)
    private var steps by mutableStateOf(emptyList<SetupStep>())
    private var message by mutableStateOf("")
    private var usbName: String? = null
    private var usbToken: String? = null
    private val usbReply get() = "$packageName.SETUP_USB_PERMISSION"

    private val notices = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        afterRequest(Manifest.permission.POST_NOTIFICATIONS, it)
    }
    private fun afterRequest(permission: String, granted: Boolean) {
        choices.markAsked(permission)
        message = if (!granted) "Permission not granted. You can finish without it." else ""
        refresh()
    }

    private val changes = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == usbReply) {
                if (usbToken == null || usbToken != intent.getStringExtra("token")) return
                val device = access.usb.keypad(usbName)
                message = if (device != null && access.usb.hasPermission(device)) ""
                    else "USB access was not granted or the keypad was removed. You can try again."
                usbToken = null
                usbName = null
            }
            refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Feature setup"
        selected = savedInstanceState?.getStringArrayList("selected")?.let(SetupChoices::decode)
            ?: choices.load(access::existingFeatures)
        reviewing = savedInstanceState?.getBoolean("review") == true
        message = savedInstanceState?.getString("message").orEmpty()
        usbName = savedInstanceState?.getString("usbName")
        usbToken = savedInstanceState?.getString("usbToken")
        ContextCompat.registerReceiver(this, changes, IntentFilter(usbReply).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        setContent {
            WorkspaceTheme {
                SetupScreen(selected, reviewing, steps, message, intent.getBooleanExtra("firstRun", false),
                    select = { feature -> selected = if (feature in selected) selected - feature else selected + feature },
                    review = {
                        choices.save(selected)
                        if (selected.isEmpty()) finish() else {
                            reviewing = true
                            refresh()
                        }
                    },
                    edit = { reviewing = false; message = "" },
                    act = ::perform, done = ::finish,
                    virtual = { setResult(TRY_VIRTUAL); finish() })
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList("selected", ArrayList(selected.map { it.name }))
        outState.putBoolean("review", reviewing)
        outState.putString("message", message)
        outState.putString("usbName", usbName)
        outState.putString("usbToken", usbToken)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        unregisterReceiver(changes)
        super.onDestroy()
    }

    private fun refresh() {
        if (reviewing) steps = access.steps(selected)
    }

    private fun perform(action: SetupAction) {
        refresh()
        if (!reviewing || steps.none { it.action == action } || !isUnlockedAndInteractive()) return
        message = ""
        try {
            when (action) {
                SetupAction.AUTO -> access.attach.automatic = true
                SetupAction.DIRECT -> access.shortcuts.enabled = true
                SetupAction.POST -> notices.launch(Manifest.permission.POST_NOTIFICATIONS)
                SetupAction.USB -> {
                    val device = access.keypads().singleOrNull() ?: return refresh()
                    val token = UUID.randomUUID().toString()
                    usbName = device.deviceName
                    usbToken = token
                    access.usb.requestKeypadAccess(this, device, usbReply, token)
                }
                else -> startActivity(access.settings(action))
            }
        } catch (_: ActivityNotFoundException) {
            message = "This Android settings page is unavailable. You can leave this step for later."
        } catch (_: SecurityException) {
            message = "Android restricted access. Leave this step off and continue."
        } catch (_: IllegalArgumentException) {
            message = "This option is no longer available. Check the device and try again."
        }
        refresh()
    }

    companion object {
        internal const val TRY_VIRTUAL = RESULT_FIRST_USER
        internal fun intent(context: Context, firstRun: Boolean) =
            Intent(context, SetupActivity::class.java).putExtra("firstRun", firstRun)
    }
}
