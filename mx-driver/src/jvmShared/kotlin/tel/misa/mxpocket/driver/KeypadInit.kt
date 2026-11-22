package tel.misa.mxpocket.driver

import java.io.IOException
import kotlinx.coroutines.delay

internal suspend fun HidSession.initializeKeypad(setDecoder: (KeyInput) -> Unit, log: (String) -> Unit) {
    delay(100)
    val root = command(0, 0, 0x1b, 0x04)
    if (root.size < 7 || root.u8(4) == 0) throw IOException("Reprogrammable controls feature unavailable")
    val feature = root.u8(4)
    log("Discovered HID++ 0x1b04 index=0x${feature.toString(16)}")
    setDecoder(KeyInput(feature))
    for (id in listOf(0x01a1, 0x01a2)) {
        command(feature, 3, id shr 8, id and 255, 3)
        log("Divert page control 0x${id.toString(16)}: acknowledged")
    }
}
