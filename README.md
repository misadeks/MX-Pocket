# MX Pocket

Native Android USB Host driver and companion app for the Logitech MX Creative
Keypad (046D:C354). Application ID: `tel.misa.mxpocket`. Android 9/API 28 or newer.
The app supports saved layouts, context rules, workflows and an executable
virtual keypad. Start with the [workspace walkthrough and acceptance checklist](docs/workspace.md).
Automated tests do not establish physical USB/LCD compatibility.

This repository is private: invited collaborators can work through feature
branches and pull requests. Start with [CONTRIBUTING.md](CONTRIBUTING.md).
There is no hosted CI; validation runs locally.

## What is implemented

- `mx-driver`: Kotlin AAR independent of Activities, UI and application actions.
  Android USB Host access, descriptor-based HID report routing, asynchronous
  interrupt IN/OUT using `UsbRequest`, HID control `SET_REPORT` when a HID
  interface has no interrupt OUT, serialized HID++ command/answer exchanges,
  page-button diversion, individual press/release snapshots, Android JPEG
  encoding, and acknowledged/fragmented image uploads to all nine key regions.
- `app`: native Material Android UI with explicit USB permission request, opt-in
  USB attach handling and a foreground service for an active keypad, device
  and endpoint diagnostics, connection/cancellation/detach handling, bounded
  event log with user-selected file export, custom image picker, and dynamic
  Focus, Audio, Phone, Launcher, Studio and Navigation controls. See the
  [Android deck walkthrough](docs/android-decks.md) and [workflow guide](docs/workflows.md).
- The Material 3 workspace includes Home, Keypad, Plugins, Flows and Settings.
  Profiles, page history, pins, rules and individual key bindings are saved
  locally. Layouts support JSON export/import and restoring factory defaults.
  The workflow editor saves, tests and runs bounded sequences with conditions,
  confirmations, daily schedules, context triggers and execution history.
- Focus starts/pauses/resets a real monotonic countdown, changes duration, and
  plays a completion tone. Audio changes the music stream volume/mute and
  dispatches media keys to an existing Android media session. Phone opens
  installed camera/settings/dialer/browser/alarm apps. Launcher opens saved
  favorites; Audio shows permitted media-session state. Studio opens the
  phone's default photo/video viewfinder. With separate opt-in, physical Studio
  keys can press a supported shutter, record or stop button. Navigation has an optional user-enabled
  accessibility service for supported cross-app actions. No dependency on
  Options+, the official Actions SDK, Node, WebHID, root or BLE Dialpad.
- The virtual keypad accepts presses/releases and models connection loss without
  sending USB reports. It executes real actions, unlike the debug UI fixture.
- Compile-time Kotlin plugin engine with stable registration IDs, exactly nine
  key tiles per frame, physical press/release handling, scoped contextual updates
  and host-mediated Android actions. Focus, Audio and Phone all use this API.
  [Plugin authoring guide](docs/plugins.md) includes a working Counter example.
  No downloaded code, external APK plugin loading or runtime class loading.

## Architecture

```text
app/ui/Workspace + PocketScreen   Compose navigation/editors and keypad Views
  -> MainActivity                 visible USB/notification consent, screen adapter
KeypadService                     foreground notification, detach and disconnect
  -> PocketSession                one plugin/input/LCD owner; Activity optional
       -> deck/DeckRepository     verified layouts, page history, context selection
       -> workflow                stored definitions, progress, restricted host actions
  -> plugins/PluginEngine         selection, key ownership, scopes, errors
       -> PocketPlugin            Focus, Audio, Phone, optional Counter
       -> PluginHost              bounded Android actions + invalidate
  -> mx-driver/MxKeypad            independent reusable USB Host API
       -> HidSession              serialized command/acknowledgement protocol
       -> UsbReportTransport      descriptor-backed USB endpoints and requests
```

Plugins return logical frames; the app composites key labels/custom images and
the driver encodes/fragments JPEGs. A feature does not need to know VID/PID,
report IDs, USB interfaces, packet sizes or firmware details. The `mx-driver`
module never imports plugin/app code.

The phone layout/theme and safe debug-only UI fixtures are described in
[Contributing: phone UI](CONTRIBUTING.md#phone-ui-layout-and-safe-preview).
This is separate from **Connect virtual keypad**, which executes phone actions.

The registry is `BuiltInPlugins.create()`. Saved profiles reference stable
actions rather than registry positions. See [plugin authoring](docs/plugins.md).

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
avoid misattributing a late answer. A new album cover and its nine labeled tiles
normally travel as one 434x434 JPEG rectangle, at (23,6) in the panel. It uses
the same image command and fragment acknowledgements as an individual key; there
is no separate display commit. A cover too large for the 65535-byte limit at
the supported qualities falls back to separate keys. Old artwork in the gaps
is erased before falling back if needed. Sparse changes such as a volume label
only update changed keys. Separate updates can briefly show parts of both frames;
the app checks current pixels after each image and continues around the panel.
When a physical key needs rewriting,
the app reuses the JPEG if the same rendered bitmap is still cached. The cache
is per connection and capped at 2 MiB including retained bitmap pixels. It never
skips an upload or its ACKs. For music-cover tiles, the encoder keeps the
ordinary JPEG when it already fits one report. Otherwise it tries a lower
quality and uses it only if that saves at least one report, without resizing
the key or its labels. Explicit repaint and reconnect send the full layout.
The log records JPEG preparation on misses and upload time through the last ACK
on each completed key; neither measures when the pixels became visible. Input
from a superseded or not-yet-acknowledged page is dropped.

No speculative keepalive, firmware commands, captured startup replay, display
ownership guesses, or undocumented reset are sent. Protocol acceptance in the
log is **not proof of visible or persistent LCD content**.

## Quickstart and local validation

Invited collaborators can clone using Git or the GitHub CLI:

```powershell
gh repo clone misadeks/MX-Pocket
Set-Location MX-Pocket
git switch -c feature/your-change
```

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

On Linux/macOS use `./gradlew` with the same task arguments. The Gradle wrapper
downloads Gradle 8.14.4; Android SDK dependencies must already be installed.
Use SDK Manager to install `platforms;android-35` and `build-tools;35.0.0`.
Do not commit `local.properties` or personal SDK/JDK paths.

The APK uses the local Android debug signing key. The AAR is not a fat archive;
consuming projects also need `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2`.
Tests cover protocol vectors/boundaries, maximum-size fragmentation, explicit
busy/error responses, multi-key input transitions, HID descriptor lengths,
serialized exchanges, cancellation/disconnect/timeout, timer semantics,
plugin registration/capabilities/activation cleanup/stale callbacks, and
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
4. Confirm text appears on **all nine physical LCD buttons** and remains
   visible for at least 60 seconds without logo fallback. On factory layouts,
   press Next from Focus: verify Audio, Phone, Launcher, Studio, Navigation,
   then Focus again. Press Previous to wrap back to Navigation. On saved
   layouts, single-page profiles cycle in workspace order; in a multi-page
   profile the buttons navigate its pages, with Previous retracing page history
   first. Choose a custom image and verify it appears with the labels on every
   key without reconnecting.
5. Press/release each LCD key and both page buttons. Verify their identities in
   the session log; hold multiple keys and verify independent releases. Use the
   physical controls to change music volume and start/pause the focus timer.
6. Deny permission, retry it, disconnect/reconnect the cable, and run the
   background/attach checks below. Verify no stuck keys or stale events. Save the log
   with the actual phone model/Android version and note which checks passed.

### Background sessions and automatic connection

**Connect keypad** always works independently of the auto-connect setting.
Allow USB access and session notifications (including the Android 13+ notification
permission). MX Pocket requires the notification so **Disconnect** is accessible
away from the app. If notifications were denied or the session channel is blocked,
enable them in Android app settings and tap **Connect keypad** again.

To opt in, enable **Connect automatically when this keypad is attached**, then
unplug and reconnect **one** MX Creative Keypad. Choose MX Pocket in Android's USB
handler dialog; select the default/always option if offered and desired. The OS
may open the phone UI on attach. Android controls defaults, USB permission,
lock-screen behavior and delivery after force-stop; open/unlock the app and use
manual Connect if attach is not delivered. Turning the option off unregisters
the attach handler; it does not end the current session. No boot receiver, USB
polling while detached or silent service restart is used.

**Opening apps while another app is visible:** to use a single physical keypress,
select **Open apps directly from keypad**, read the explanation and grant
**Display over other apps** to MX Pocket in Android settings. This optional
special access is broader than launching apps: it also permits drawing over
other apps. MX Pocket creates no overlay windows. A fresh physical press on the
active, authorized keypad can open a built-in Phone shortcut, **Phone: Camera**
on any saved layout, Studio's **Open Camera** / **Open Video**, or a saved favorite. Each new press can
request the same or a different favorite without returning to MX Pocket. The
service rechecks the selected launcher component; for Android's resolved default
still camera it requests the viewfinder instead. Opening a shortcut does not
take a photo or start recording. If you enabled direct shortcuts before they
included ordinary favorites, enable the option again to opt in to this scope.
Turn it off in MX Pocket or revoke special access in Settings.

With the option off or special access denied/unavailable, press a
shortcut and tap its **Open Camera**, **Open Dialer**, etc. notification. No second keypad
press or visit to the MX Pocket screen is needed. Allow the **Keypad app shortcuts**
notification channel; Android controls whether it appears as a banner or in the
shade. Only the latest request is usable, for one minute on the same connected
keypad and binding. Direct launches require an unlocked, interactive phone.
If the phone was locked, unlock normally and press again. In the
foreground the same key still launches the app immediately. Android does not
confirm that an external app appeared; a quiet tap-to-retry shortcut remains
available if an OEM suppresses an otherwise permitted direct launch.

USB access and a foreground service don't grant [background activity launch
privileges](https://developer.android.com/guide/components/activities/background-starts).
Android's [companion device pairing](https://developer.android.com/develop/connectivity/bluetooth/companion-device-pairing)
scans Bluetooth or Wi-Fi, not USB. On Android 13–16 the self-managed association
alternative requires `REQUEST_COMPANION_SELF_MANAGED`, a platform
[signature/privileged permission](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/core/res/AndroidManifest.xml)
that [the companion service enforces](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/services/companion/java/com/android/server/companion/utils/PermissionsUtils.java).
It is not a user-grantable switch for this APK. The optional direct mode instead
uses Android's documented user-granted `SYSTEM_ALERT_WINDOW` exception
([AOSP BAL check](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/services/core/java/com/android/server/wm/BackgroundActivityStartController.java)).

**Physical acceptance checklist** (not proven by JVM tests):

1. With opt-in off, replug and verify the app does not connect until tapped.
   Deny USB permission, retry, then check there is one session notification.
2. Start Focus, press Home and use the physical page buttons and Audio controls.
   Verify timer/LCD labels update, volume changes, the image picker and rotation
   leave the connection intact, and returning shows the same page.
3. With direct shortcuts off and another app in front, press Camera on the Phone page. The current app
   should remain visible until you tap **Open Camera**; the tap should open the
   camera without another press. Repeat with Dialer and verify the dialer, not
   the previous camera request, opens. Try a tap after unplugging or after a
   minute: it must not launch. Enable direct shortcuts as described above and
   repeat: one Camera press should open the camera. Revoke special access and
   repeat: it must require a tap again. Verify a shortcut never unlocks the phone.
   Media controls need an active Android media session.
4. With auto-connect enabled and Android's handler selected, replug. Verify
   one session; stop via notification. Reopening the app must not reconnect the
   same attachment. Replug again, then detach during an upload: the notification
   should disappear and another manual or permitted attach should work. Try an
   unrelated USB device; it must not start a keypad session.

Profiles, page histories, pins, context rules, favorites, workflow definitions
and bounded execution history persist after process death. The active USB
connection, running timer, global background image and in-flight run do not.
Android may kill the service; reconnect manually or reattach. Preview never
starts a service and pauses its plugin when the screen leaves the foreground.
Switching plugins or disconnecting still cancels the old activation and releases
held keys. A Focus completion on another page is deferred until Focus is active.
Page diversion is volatile; the app does not restore a prior host's configuration.

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

## Troubleshooting

| Symptom | Next check |
| --- | --- |
| No keypad in diagnostics | USB-host support, data-capable cable, OTG adapter and power; look for 046D:C354 |
| Permission denied | Tap Connect and accept Android's device permission; reattach if the permission was lost |
| Interface claim fails | Inspect unforced/forced claim results; OEM/kernel HID conflicts may prevent userspace access |
| Unsupported descriptor | Provide a small redacted interface/report table; do not guess interface numbers or replay packets |
| Missing ACK or rejected image | Save the first relevant redacted error; connection closes rather than claiming success |
| Media key says Unavailable or has no effect | Start a player. With optional media access, only the transport actions offered by its Android session are enabled. Without access, Android routes standard media keys; the app cannot confirm playback. Volume controls the phone's music stream, not a cast device. |
| Title or cover missing on the locked keypad | Select Audio, check optional media-session access and that the USB keypad is connected and authorized. The keypad can follow the selected player while the phone stays locked or off; without authorized hardware, locked playback details are hidden. Not every player supplies local artwork. See the [media checks](docs/android-decks.md#physical-acceptance-gates-not-established-by-jvm-tests). |
| Plugin error | Error is attributed to its ID; its tasks stop. Reconnect/re-enter preview or reselect the plugin to retry |
| Windows ARM64 Robolectric failure | Run local app tests with an x64 JDK, not ARM64 Java |

No firmware update, root or kernel change is part of troubleshooting. Do not
upload raw device logs or private images; follow the issue template's redaction
instructions.

## License

MX Pocket code is licensed under MIT; see [LICENSE](LICENSE).
Protocol adaptations retain the upstream MIT notices in the embedded provenance
file. The generated Gradle wrapper is Apache-2.0 licensed; see
[NOTICE](NOTICE) and [LICENSES/Apache-2.0.txt](LICENSES/Apache-2.0.txt).
