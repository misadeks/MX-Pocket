package tel.misa.mxpocket.driver

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

object UsbDiagnostics {
    fun describe(manager: UsbManager): String = buildString {
        val devices = manager.deviceList.values.sortedBy { it.deviceName }
        if (devices.isEmpty()) appendLine("No USB devices. Connect a data-capable USB-C cable or OTG adapter.")
        for (device in devices) {
            appendLine("Device ${device.deviceName}")
            appendLine("VID/PID %04X:%04X".format(device.vendorId, device.productId))
            appendLine("Permission: ${manager.hasPermission(device)}")
            appendLine("Manufacturer: ${accessible { device.manufacturerName }}")
            appendLine("Product: ${accessible { device.productName }}")
            appendLine("Version: ${accessible { device.version }}")
            appendLine("Interfaces: ${device.interfaceCount}")
            for (index in 0 until device.interfaceCount) {
                val iface = device.getInterface(index)
                appendLine("  Interface ${iface.id}, alternate ${iface.alternateSetting}: " +
                    "class=${iface.interfaceClass} subclass=${iface.interfaceSubclass} " +
                    "protocol=${iface.interfaceProtocol}, endpoints=${iface.endpointCount}")
                for (e in 0 until iface.endpointCount) {
                    val endpoint = iface.getEndpoint(e)
                    val direction = if (endpoint.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
                    val type = when (endpoint.type) {
                        UsbConstants.USB_ENDPOINT_XFER_INT -> "interrupt"
                        UsbConstants.USB_ENDPOINT_XFER_BULK -> "bulk"
                        UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "control"
                        UsbConstants.USB_ENDPOINT_XFER_ISOC -> "isochronous"
                        else -> "unknown(${endpoint.type})"
                    }
                    appendLine("    Endpoint 0x%02X %s %s maxPacket=%d interval=%d".format(
                        endpoint.address, direction, type, endpoint.maxPacketSize, endpoint.interval))
                }
            }
            appendLine()
        }
    }

    fun isKeypad(device: UsbDevice): Boolean =
        device.vendorId == KeypadProtocol.VENDOR_ID && device.productId == KeypadProtocol.PRODUCT_ID

    private fun accessible(read: () -> String?): String = try {
        read() ?: "(not supplied)"
    } catch (e: SecurityException) {
        "(unavailable without permission: ${e.message})"
    }
}
