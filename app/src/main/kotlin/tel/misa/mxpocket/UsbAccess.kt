package tel.misa.mxpocket

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import tel.misa.mxpocket.driver.UsbDiagnostics

@Suppress("DEPRECATION")
internal fun Intent.usbDevice(): UsbDevice? = if (Build.VERSION.SDK_INT >= 33)
    getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java) else getParcelableExtra(UsbManager.EXTRA_DEVICE)

/** Never open a parcelled UsbDevice or infer a USB grant from an intent. */
internal fun UsbManager.keypad(name: String?): UsbDevice? =
    deviceList.values.singleOrNull { UsbDiagnostics.isKeypad(it) }?.takeIf { it.deviceName == name }

internal fun UsbManager.requestKeypadAccess(context: Context, device: UsbDevice, action: String, token: String) {
    val callback = Intent(action).setPackage(context.packageName).putExtra("token", token)
    requestPermission(device, PendingIntent.getBroadcast(context, token.hashCode(), callback,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT))
}

internal class UsbAccess(private val context: Context) {
    private val alias = ComponentName(context, "${context.packageName}.KeypadAttach")
    private val prefs = context.getSharedPreferences("keypad-attach", Context.MODE_PRIVATE)
    var automatic: Boolean
        get() = context.packageManager.getComponentEnabledSetting(alias) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        set(value) {
            context.packageManager.setComponentEnabledSetting(alias, if (value) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }

    private fun attachment(name: String) =
        "${android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, 0)}:$name"

    fun alreadyHandled(name: String) = prefs.getString("handled", null) == attachment(name)
    fun handled(name: String) { prefs.edit().putString("handled", attachment(name)).apply() }
    fun detached(name: String) {
        if (alreadyHandled(name)) prefs.edit().remove("handled").apply()
    }
}
