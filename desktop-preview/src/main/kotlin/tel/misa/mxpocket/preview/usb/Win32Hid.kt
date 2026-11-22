package tel.misa.mxpocket.preview.usb

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Cfgmgr32
import com.sun.jna.platform.win32.Cfgmgr32Util
import com.sun.jna.platform.win32.Guid.GUID
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.SetupApi
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.platform.win32.WinNT.HANDLE
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import java.io.IOException
import tel.misa.mxpocket.driver.KeypadProtocol

// These BOOLEAN functions return one byte, unlike Win32 BOOL (a 32-bit JNA boolean).
internal interface HidApi : StdCallLibrary {
    fun HidD_GetHidGuid(guid: GUID)
    fun HidD_GetAttributes(handle: HANDLE, attributes: Pointer): Byte
    fun HidD_GetPreparsedData(handle: HANDLE, data: PointerByReference): Byte
    fun HidD_FreePreparsedData(data: Pointer): Byte
    fun HidP_GetCaps(data: Pointer, caps: Pointer): Int
    fun HidP_InitializeReportForID(type: Int, id: Byte, data: Pointer, report: Pointer, length: Int): Int
}

internal fun winError(operation: String, code: Int = Native.getLastError()): IOException {
    val reason = when (code) {
        5 -> "access denied; the keypad may already be in use"
        32 -> "sharing violation; the keypad is in use by another application"
        else -> "operation failed"
    }
    return IOException("$operation: $reason (Windows error $code)")
}

internal object Win32Hid : WindowsHid {
    private val hid = Native.load("hid", HidApi::class.java)
    private val k32 = Kernel32.INSTANCE
    private val setup = SetupApi.INSTANCE
    private const val HIDP_SUCCESS = 0x00110000
    private const val VENDOR_PAGE = 0xff43
    private const val SHARE = WinNT.FILE_SHARE_READ or WinNT.FILE_SHARE_WRITE

    // Handle-free ABI probe for packaging tests; does not enumerate anything.
    internal fun classGuid() = GUID().also(hid::HidD_GetHidGuid)

    override fun enumerate(): List<HidCollection> {
        val guid = classGuid()
        val devices = setup.SetupDiGetClassDevs(guid, null, null, SetupApi.DIGCF_PRESENT or SetupApi.DIGCF_DEVICEINTERFACE)
        if (devices == WinBase.INVALID_HANDLE_VALUE) throw winError("List HID interfaces")
        try {
            return buildList {
                var index = 0
                while (true) {
                    val iface = SetupApi.SP_DEVICE_INTERFACE_DATA()
                    if (!setup.SetupDiEnumDeviceInterfaces(devices, null, guid, index++, iface)) {
                        val error = Native.getLastError()
                        if (error == 259) break // ERROR_NO_MORE_ITEMS
                        throw winError("Enumerate HID interface", error)
                    }
                    val size = IntByReference()
                    setup.SetupDiGetDeviceInterfaceDetail(devices, iface, null, 0, size, null)
                    if (Native.getLastError() != 122 || size.value !in 8..65536) throw winError("Size HID interface")
                    val dev = SetupApi.SP_DEVINFO_DATA()
                    val path = Memory(size.value.toLong()).use { detail ->
                        // SP_DEVICE_INTERFACE_DETAIL_DATA_W.cbSize is 8 on x64; DevicePath starts at offset 4.
                        detail.setInt(0, 8)
                        if (!setup.SetupDiGetDeviceInterfaceDetail(devices, iface, detail, size.value, null, dev)) {
                            throw winError("Read HID interface")
                        }
                        detail.getWideString(4)
                    }
                    // Inspect devnode identities, not an undocumented symbolic link layout. Never
                    // even open a metadata handle on another keyboard, receiver, or Bluetooth device.
                    val parent = wiredKeypadParent(dev.DevInst) ?: continue
                    val handle = openHandle(path, 0, 0)
                    try { inspect(parent, path, handle)?.let { add(it) } } finally { k32.CloseHandle(handle) }
                }
            }
        } finally {
            setup.SetupDiDestroyDeviceInfoList(devices)
        }
    }

    private fun wiredKeypadParent(devInst: Int): String? {
        var node = devInst
        repeat(8) {
            val parent = IntByReference()
            if (Cfgmgr32.INSTANCE.CM_Get_Parent(parent, node, 0) != 0) return null
            node = parent.value
            val id = Cfgmgr32Util.CM_Get_Device_ID(node)
            if (isKeypadParent(id)) return id.uppercase()
            // At a physical USB device with a different identity, stop; never group by hub.
            if (id.startsWith("USB\\", true) && !id.substringBeforeLast('\\').contains("&MI_", true)) return null
        }
        return null
    }

    internal fun isKeypadParent(id: String) = id.startsWith("USB\\VID_046D&PID_C354\\", true)

    private fun inspect(parent: String, path: String, handle: HANDLE): HidCollection? {
        Memory(12).use { attrs -> // HIDD_ATTRIBUTES (DWORD, 3 USHORTs, trailing alignment)
            attrs.setInt(0, 12)
            if (hid.HidD_GetAttributes(handle, attrs).toInt() == 0) throw winError("Read HID attributes")
            if (attrs.u16(4) != KeypadProtocol.VENDOR_ID || attrs.u16(6) != KeypadProtocol.PRODUCT_ID) return null
        }
        val data = PointerByReference()
        if (hid.HidD_GetPreparsedData(handle, data).toInt() == 0) throw winError("Read HID capabilities")
        try {
            Memory(64).use { caps -> // HIDP_CAPS is 32 USHORTs, including its reserved array.
                if (hid.HidP_GetCaps(data.value, caps) != HIDP_SUCCESS) throw IOException("Invalid HID capabilities")
                if (caps.u16(2) != VENDOR_PAGE) return null
                val route = Route.entries.singleOrNull { it.usage == caps.u16(0) } ?: return null
                val collection = HidCollection(parent, path, route, caps.u16(4), caps.u16(6))
                collection.validate()
                // HIDP_CAPS alone gives maximum lengths, not proof this report ID exists.
                fun verifyId(type: Int, size: Int) = Memory(size.toLong()).use { buffer ->
                    if (hid.HidP_InitializeReportForID(type, route.id.toByte(), data.value, buffer, size) != HIDP_SUCCESS) {
                        throw IOException("Unsupported ${route.name} report descriptor")
                    }
                }
                if (collection.inputBytes > 0) verifyId(0, collection.inputBytes)
                if (route.writes) verifyId(1, collection.outputBytes)
                return collection
            }
        } finally {
            hid.HidD_FreePreparsedData(data.value)
        }
    }

    override fun open(collection: HidCollection): HidPort {
        var access = if (collection.inputBytes > 0) WinNT.GENERIC_READ else 0
        if (collection.route.writes) access = access or WinNT.GENERIC_WRITE
        val handle = openHandle(collection.path, access, WinNT.FILE_FLAG_OVERLAPPED)
        try {
            val actual = inspect(collection.usbParent, collection.path, handle)
            if (actual?.route != collection.route || actual.inputBytes != collection.inputBytes ||
                actual.outputBytes != collection.outputBytes) throw IOException("Keypad collection changed; reconnect and rescan")
            return OverlappedHidPort(actual, handle)
        } catch (e: Throwable) {
            k32.CloseHandle(handle)
            throw e
        }
    }

    private fun openHandle(path: String, access: Int, flags: Int): HANDLE {
        val handle = k32.CreateFile(path, access, SHARE, null, WinNT.OPEN_EXISTING, flags, null)
        if (handle == WinBase.INVALID_HANDLE_VALUE) throw winError("Open keypad collection")
        return handle
    }

    private fun Pointer.u16(offset: Long) = getShort(offset).toInt() and 0xffff
}
