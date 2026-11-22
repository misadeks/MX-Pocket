package tel.misa.mxpocket.preview.usb

import java.io.Closeable
import java.io.IOException
import tel.misa.mxpocket.driver.KeypadProtocol

/** Windows exposes top-level HID collections, not Android USB interfaces. The vendor page FF43
 * and three usage-to-report mappings below are reported in the Windows reference:
 * https://github.com/hcooper-idealbuilders/mx-creative-controller/blob/221c52f3514a25671a08fa19e723e49c44119543/docs/protocol.md#hid-collections
 * Win32Hid verifies the usage and report ID with HidP on each handle, never using ColNN or
 * copying that reference's experimental startup/ownership packets. */
internal enum class Route(val usage: Int, val id: Int) {
    COMMAND(0x1a02, 0x11), KEYS(0x1a08, 0x13), LCD(0x1a10, 0x14);

    val wireBytes: Int get() = KeypadProtocol.reportSizes.getValue(id)
    val writes: Boolean get() = this != KEYS
}

internal class HidCollection(
    val usbParent: String,
    val path: String,
    val route: Route,
    val inputBytes: Int,
    val outputBytes: Int,
) {
    // ReadFile/WriteFile take the collection's maximum buffer size INCLUDING the ID.
    // This differs from WebHID-style APIs which take the ID as a separate argument.
    val inputWireBytes = if (route == Route.LCD) inputBytes else route.wireBytes

    fun validate() {
        if (inputBytes !in 0..65535 || outputBytes !in 0..65535 ||
            (route != Route.LCD && inputBytes < route.wireBytes) ||
            (route == Route.LCD && inputBytes in 1..5) ||
            (route.writes && outputBytes < route.wireBytes)) {
            throw IOException("Unsupported HID report sizes for ${route.name} collection")
        }
    }

    fun decode(bytes: ByteArray): ByteArray? {
        if (inputBytes == 0 || bytes.size != inputBytes || (bytes[0].toInt() and 255) != route.id) return null
        return if (inputWireBytes == inputBytes) bytes else bytes.copyOf(inputWireBytes)
    }

    fun encode(report: ByteArray): ByteArray {
        check(route.writes && report.size == route.wireBytes && (report[0].toInt() and 255) == route.id)
        return if (outputBytes == report.size) report else report.copyOf(outputBytes)
    }
}

internal interface HidPort : Closeable {
    val collection: HidCollection
    fun read(): ByteArray
    fun write(bytes: ByteArray)
    /** close cancels outstanding native operations; resources survive until the kernel is done. */
    suspend fun awaitClosed()
}

internal interface WindowsHid {
    fun enumerate(): List<HidCollection>
    fun open(collection: HidCollection): HidPort
}

internal fun requireWindowsRuntime(os: String, arch: String) {
    if (!os.startsWith("Windows", ignoreCase = true)) throw UnsupportedOperationException("USB keypad requires Windows")
    if (arch.lowercase() !in setOf("amd64", "x86_64")) {
        throw UnsupportedOperationException("USB keypad requires an x64 JVM (also on Windows ARM64); current JVM: $arch")
    }
}
