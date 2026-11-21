# MX Pocket

Native Android USB Host driver and companion app for the Logitech MX Creative
Keypad (046D:C354). Application ID: `tel.misa.mxpocket`. Android 9/API 28 or newer.
This is a source-supported MVP, **not a physically verified Android driver**.

## What is implemented

- `mx-driver`: Kotlin AAR independent of Activities, UI and application actions.
  Android USB Host access, descriptor-based HID report routing, asynchronous
  interrupt IN/OUT using `UsbRequest`, HID control `SET_REPORT` when a HID
  interface has no interrupt OUT, serialized HID++ command/answer exchanges,
  page-button diversion, individual press/release snapshots, Android JPEG
  encoding, and acknowledged/fragmented image uploads to all nine key regions.
- `app`: native Material Android UI with explicit USB permission request, device
  and endpoint diagnostics, connection/cancellation/detach handling, bounded
  event log with user-selected file export, custom image picker, and dynamic
  Focus/Audio/Phone pages. The two physical page buttons switch pages.
- Focus starts/pauses/resets a real monotonic countdown, changes duration, and
  plays a completion tone. Audio changes the music stream volume/mute and
  dispatches media keys to an existing Android media session. Phone opens
  installed camera/settings/dialer/browser/alarm apps. There is no accessibility
  service or dependency on Options+, the official Actions SDK, Node, WebHID,
  root, a custom kernel, or BLE Dialpad.
- Phone-only preview exercises app actions without pretending to connect or
  sending any USB reports.

## Display/protocol caveat

The official specification gives a 480x480 LCD panel and nine LCD buttons.
The primary driver describes **one 480x480 surface with nine visible 118x118
regions**, at `(23 + 158*column, 6 + 158*row)`. It does not describe nine independent
480x480 screens. MX Pocket scales user images to each visible region and overlays
contextual labels.

USB interface numbers, endpoint addresses, report sizes and HID usage pages are
read from the attached device. HID collections are not assumed to correspond
one-to-one to USB interfaces. The report layout must expose output 0x11/20 bytes,
output 0x14/4095 bytes, input 0x11/20 bytes and input 0x13/32 bytes. HID++ 0x1b04 is
queried through the root feature before diverting controls 0x01a1 and 0x01a2.
The LCD VLP feature index 2 and geometry are a **source-supported static profile**,
not a runtime capability query. Unknown/ambiguous descriptor layouts fail
explicitly; they are not silently treated as compatible.

Images follow the primary source's display index 1, 16-bit JPEG byte length,
20-byte first header, 5-byte continuation header and 4095-byte padded HID reports.
The host waits for a protocol answer after **every** fragment. The answer flags
are not required to echo the outbound data-frame flags. Busy 0xfb resends the
whole image within a bounded retry window; other errors and missing
acknowledgements fail explicitly. Timeouts/cancellation close the transport to
avoid misattributing a late answer. Only changed LCD labels are repainted during
the timer; page changes and explicit repaint refresh all nine.

No speculative keepalive, firmware commands, captured startup replay, display
ownership guesses, or undocumented reset are sent. Protocol acceptance in the
log is **not proof of visible or persistent LCD content**.

## Build and test on Windows

Install Android SDK platform 35/build tools and JDK 17 or 21. Set `ANDROID_HOME`
to the SDK directory and `JAVA_HOME` to the JDK, or configure the SDK using your
local Android Studio installation. Open this folder in Android Studio or run:

```powershell
.\gradlew.bat --no-daemon --console=plain :mx-driver:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :mx-driver:assembleRelease :app:lintDebug :mx-driver:lintDebug
```

On Windows ARM64, the APK/AAR and pure Kotlin tests build with an ARM64 JDK, but
Robolectric/Conscrypt do not ship the required Windows ARM64 native test runtime.
Use an **x64 JDK** for the full test command (Windows runs it under emulation).
Set `JAVA_HOME` to your x64 installation (for example Android Studio's JBR):

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --no-daemon --console=plain :mx-driver:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :mx-driver:assembleRelease :app:lintDebug :mx-driver:lintDebug
```

Generated artifacts:

```text
app\build\outputs\apk\debug\app-debug.apk
mx-driver\build\outputs\aar\mx-driver-release.aar
```

The APK uses the local Android debug signing key. The AAR is not a fat archive;
consuming projects also need `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2`.
Tests cover protocol vectors/boundaries, maximum-size fragmentation, explicit
busy/error responses, multi-key input transitions, HID descriptor lengths,
serialized exchanges, cancellation/disconnect/timeout, timer semantics and
Android app startup/preview/lifecycle under Robolectric API 28 and 35.
Robolectric is Android API simulation, not USB host or physical LCD validation.

Library usage after your app has obtained Android USB permission:

```kotlin
import tel.misa.mxpocket.driver.MxKeypad

val keypad = MxKeypad.connect(
    manager, device,
    onEvent = { event -> /* dispatch to your app's state/UI thread */ },
    onLog = { line -> /* retain diagnostics */ },
    onError = { error -> /* surface failure and close your session */ },
)
keypad.setKeyImage(0, bitmap) // LCD indices 0..8; scales and JPEG-encodes off-main
keypad.close() // cancels pending commands and schedules USB resource cleanup off-main
```

The caller owns the coroutine scope and connection lifetime. Input/log/error
callbacks can arrive from a USB worker; they must remain fast and dispatch UI
changes to the main thread. Do not mutate/recycle a bitmap until its upload call
finishes. Close on detach, cancellation or your chosen lifecycle boundary.

## Run on a phone and verify the physical MVP

1. Install the debug APK on an Android USB-host phone. With USB debugging enabled,
   `adb install -r .\app\build\outputs\apk\debug\app-debug.apk` installs the app;
   wireless ADB is useful while the phone's USB-C port hosts the keypad.
2. Connect the keypad with a data-capable USB-C cable/OTG adapter. Use a powered
   hub if the phone cannot supply enough power. Tap **Refresh USB diagnostics**.
   Confirm VID/PID, version, interfaces/endpoints and permission state.
3. Tap **Connect keypad**, accept Android's permission dialog if requested, and
   inspect claim/descriptor/root/diversion results. A failed unforced claim is
   retried with `force=true` to take the selected HID interface from the kernel.
   No interrupt endpoint is passed to `bulkTransfer`; no root or kernel patch
   is attempted. Claim failure is an actionable OEM/kernel compatibility limit.
4. Confirm custom text appears on **all nine physical LCD buttons**, remains
   visible for at least 60 seconds without logo fallback, and changes between
   Focus/Audio/Phone pages. Choose a custom image, return to the app and connect
   again, then verify the image plus labels on every key.
5. Press/release each LCD key and both page buttons. Verify their identities in
   the session log; hold multiple keys and verify independent releases. Use the
   physical controls to change music volume and start/pause the focus timer.
6. Deny permission, retry it, disconnect/reconnect the cable, background the app,
   and return. Verify no stuck keys, stale events or leaked claims. Save the log
   with the actual phone model/Android version and note which checks passed.

**Lifecycle:** this foreground-demo Activity deliberately disconnects when
stopped (including opening other apps/the image picker). Tap Connect on return.
The phone timer continues to use its monotonic deadline while the process lives,
but is not a background service and does not survive process death or recreation.
Page diversion is volatile on the device; unplugging removes the session. This
MVP does not restore a prior host's diversion configuration. Media-key dispatch
requires an active media session and OEM behavior may differ.

## Evidence and limits

Automated validation covers software behavior, not physical USB/LCD behavior.
Detailed LCD visibility/persistence, all control identities, power compatibility
and reconnect validation require the hardware checklist above. Installation
success alone does not establish those results. Firmware flashing is outside
this project's development and testing workflow.

Full source URLs, reviewed revisions, implementation differences, experimental
exclusions and MIT notices are shipped in:

```text
mx-driver\src\main\assets\mx-pocket-provenance.txt
```

That file is embedded in both the AAR and APK. Upstream algorithms are used under
their MIT licenses; source-supported behavior is kept separate from
hardware-confirmed behavior throughout the diagnostic UI and logs.

## License

MX Pocket code is licensed under MIT; see [LICENSE](LICENSE).
Protocol adaptations retain the upstream MIT notices in the embedded provenance
file. The generated Gradle wrapper is Apache-2.0 licensed; see
[NOTICE](NOTICE) and [LICENSES/Apache-2.0.txt](LICENSES/Apache-2.0.txt).
