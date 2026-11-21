# MX Pocket

MX Pocket turns the **wired Logitech MX Creative Keypad** into a control surface
for Android. Start a focus timer, change tracks, open a favorite app, or assign
a workflow to one of nine LCD keys. Two physical page buttons switch what the
keys do. You can also use the app with on-screen keys, or reuse its Kotlin USB
driver without the app.

The hardware target is the USB **Keypad** (`046D:C354`), not the wireless
**Dialpad** sold with it. Android talks to the keypad directly through USB
Host; it doesn't need root or Logi Options+. The Windows simulator does not
drive hardware.

## Quick start

Clone the repository and open a terminal in its root. Set `JAVA_HOME` to a
JDK 17 or 21. Commands below use the included Gradle wrapper in PowerShell;
for Android builds on Linux/macOS, substitute
`./gradlew` for `.\gradlew.bat`.

### Try it on Windows without hardware

```powershell
.\gradlew.bat :desktop-preview:run
```

No Android SDK, phone or keypad is needed. On Windows ARM64, use an **x64
JDK** for the desktop runtime. On **Home**, select **Focus** and click **Start** to see the
countdown. Select **Audio** to try simulated media keys, or play **Tic Tac Toe**
using the nine keys as board squares. **Keypad** edits and saves layouts
locally using the same editor as Android. The simulator doesn't change Windows
volume, open phone apps, or run Android workflows.

### Run it on Android

Install Android SDK Platform 35, Build Tools 35.0.0 and Platform-Tools using
SDK Manager. Set `ANDROID_HOME` to that SDK or open the project in Android
Studio to configure it. On an Android 9 (API 28) or newer phone, enable USB
debugging, connect it to your computer and accept its debugging prompt.
Install the debug app, then open **MX Pocket** on the phone:

```powershell
.\gradlew.bat :app:installDebug
```

- **Without the keypad:** Choose **Skip and try virtual keypad** on first
  launch, or **Home → Connect virtual keypad** later. On **Focus**, tap
  **Start**; the key becomes **Pause**. Unlike the Windows demo, these keys
  run real Android actions. The timer needs no USB or special access.
- **With the keypad:** Disconnect the computer and attach one keypad to a
  USB-host-capable phone using a data cable or OTG adapter. If first-run
  setup is open, choose **Use a USB keypad** and finish setup. On **Home**, tap
  **Connect keypad**. Allow access to this USB device and allow the session
  notification so you can disconnect from outside the app. Home lists the
  nine key labels under **Keypad shows:**. Press **Start** on the physical
  Focus keypad, not on the phone, then try a page button. If it doesn't
  connect, see [USB setup and troubleshooting](docs/workspace.md#connect-a-wired-keypad).

Notification *access* for player titles and artwork is separate from permission
to *show* the session notification. It is optional, as are Usage Access for
app-based profile switching, accessibility for cross-app navigation or supported
camera buttons, and **Display over other apps** for one-press background app
shortcuts. Leave these off unless you want the corresponding feature.
A separate optional OneNote accessibility service lets keypad keys select draw
tools while OneNote is on screen.

## Guides

| I want to… | Start here |
| --- | --- |
| Set up profiles, pages and automatic switching | [Android workspace walkthrough](docs/workspace.md) |
| Use media, launcher, camera, navigation or game keys | [Android keypad decks and their permissions](docs/android-decks.md) |
| Assign several actions to one key | [Build and test a workflow](docs/workflows.md) |
| Use the USB driver in another Android app | [Build the Kotlin AAR and connect a keypad](docs/driver.md) |
| Add a deck in Kotlin | [Compiled-in plugins and the Counter example](docs/plugins.md) |
| Build, test or contribute | [Contributor guide](CONTRIBUTING.md) |

## Hardware evidence and credits

The driver treats the screen as **one 480 × 480 panel with nine visible
118 × 118 regions**. This layout comes from the upstream protocol research.
An acknowledged upload means the keypad answered, not that the picture appeared
or stayed visible. Software tests cannot establish phone power compatibility,
all eleven physical controls or reconnect behavior; use the
[hardware checklist](docs/workspace.md#hardware-acceptance-manual-not-established-by-jvm-tests).
The driver doesn't replay experimental ownership or keepalive packets or flash
firmware.

MX Pocket is [MIT licensed](LICENSE). Its protocol code draws on Julian
Waller's `node-logitech-mx-creative-console`, with cross-checks from
`logilinux` and `mx-creative-console-webhid` and SDK examples from
`logilinux-sdk`. The [provenance file](mx-driver/src/main/assets/mx-pocket-provenance.txt)
records the sources, revisions, differences and upstream MIT notices; it ships
in the APK and AAR. The generated Gradle wrapper is
[Apache-2.0 licensed](LICENSES/Apache-2.0.txt); see [NOTICE](NOTICE).
