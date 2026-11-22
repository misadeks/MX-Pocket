# Use the Android USB driver

[`:mx-driver`](../mx-driver) is an Android library for the wired Logitech MX
Creative Keypad (`046D:C354`). It reads physical key presses and releases and
uploads images. It does not depend on the MX Pocket app, its plugins, or an
Activity. The host app supplies the USB grant, images and connection lifetime.

## Add the library

With the [build prerequisites](../README.md#quick-start) installed, run this
from the repository root:

```powershell
.\gradlew.bat :mx-driver:assembleRelease
```

Copy `mx-driver/build/outputs/aar/mx-driver-release.aar` into the `libs`
directory of your Android app module. The AAR is not a fat archive. In that
module's `build.gradle.kts`, add:

```kotlin
dependencies {
    implementation(files("libs/mx-driver-release.aar"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}
```

Within the MX Pocket build, use `implementation(project(":mx-driver"))` instead
of the file dependency; Gradle then resolves the module's runtime dependencies.
Use Android 9 (API 28) or newer. The library manifest declares USB Host as a
required feature.

## Open a session

Use Android's [USB Host API](https://developer.android.com/develop/connectivity/usb/host)
to enumerate `UsbManager.deviceList`. Filter with `UsbDiagnostics.isKeypad`,
let the user authorize the attached `UsbDevice` with `UsbManager.requestPermission`
if needed, and check `manager.hasPermission(device)` before connecting.
Receiving an attach intent is not itself a grant.

This example uploads one bitmap and closes the session. Call it from a
coroutine in a scope owned by your app. Supply fast callbacks; input, log and
error callbacks can arrive from a USB worker. Post UI updates to the main
thread rather than blocking that worker.

```kotlin
import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import tel.misa.mxpocket.driver.KeyEvent
import tel.misa.mxpocket.driver.MxKeypad

suspend fun showFirstKey(
    manager: UsbManager, device: UsbDevice, bitmap: Bitmap,
    onKey: (KeyEvent) -> Unit, onLog: (String) -> Unit, onFailure: (Throwable) -> Unit,
) {
    val keypad = MxKeypad.connect(manager, device, onKey, onLog, onFailure)
    try {
        keypad.setKeyImage(0, bitmap)
    } finally {
        keypad.close()
    }
}
```

For interactive use, keep the session open while processing input. `setKeyImage`
takes a **zero-based index (0–8)**; input `KeyEvent.controlId` uses **1–9** for
LCD keys and `0x01a1` / `0x01a2` for the page buttons. Check `pressed` and
`synthetic`: a cleanup release is not a new user action. Don't mutate or
recycle the bitmap before its upload finishes. Handle connection and upload
exceptions as well as asynchronous failures; close on detach or cancellation
and recheck the grant on reconnect. Review diagnostics before sharing them.

## What the protocol establishes

- **Discovered from the device:** USB interfaces, endpoints and HID report
  sizes. The driver needs output reports `0x11` (20 bytes) and `0x14` (4095),
  and input reports `0x11` (20) and `0x13` (32). It does not equate HID
  collections with interface numbers. It queries HID++ feature `0x1b04`
  before requesting the two page controls. Unsupported layouts fail rather
  than falling back to guessed endpoints.
- **Taken from upstream research:** LCD feature index 2, display index 1, and
  one 480 × 480 panel. The nine 118 × 118 key regions start at
  `(23 + 158 × column, 6 + 158 × row)` with zero-based rows and columns.
  These values aren't queried from the device.
- **Acknowledged, not visually verified:** The JPEG limit is 65,535 bytes.
  Each padded 4095-byte fragment must receive a protocol reply. A busy reply
  (`0xfb`) retries the whole image within a bounded window; a missing reply
  times out rather than reporting success. The app can paint the whole key
  grid as one 434 × 434 rectangle or send individual keys. Neither an Android
  transfer completion nor a protocol reply proves visible, persistent pixels.
- **Not implemented:** firmware updates, undocumented resets or speculative
  display-ownership and keepalive sequences. Page-button diversion is volatile;
  the driver does not restore another host's configuration.

The [embedded source record](../mx-driver/src/main/assets/mx-pocket-provenance.txt)
explains why the image framing follows one reference where implementations
disagree. The [physical checklist](workspace.md#hardware-acceptance-manual-not-established-by-jvm-tests)
covers the checks that cannot be made in a JVM test.
