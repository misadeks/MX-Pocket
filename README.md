# MX Pocket

MX Pocket lets you control Android by feel. Link a keypad layout to an app
and put actions where your fingers expect them. In supported apps, use physical
keys to move between items, scroll and select without tapping a small button or
making a precise swipe. Use the nine LCD keys to open a favorite app, start a
focus timer or change tracks. Previous and Next switch
layouts. Press them together and release to return to Home and choose a profile,
or hold both to play Tic Tac Toe. To try MX Pocket without hardware, use the
Windows simulator.

On the **Music** layout, press the center key to pause the track.
Switch to **Navigate**: that same key becomes **Select**. With
[Android navigation access](docs/android-decks.md) enabled, you can move focus
between controls in supported apps without swiping the phone.

![Six keypad layouts. Top: the Home profile picker and Navigate. Middle: Music playback and OneNote Draw tools. Bottom: an incoming call with the caller’s picture above their name and Accept and Decline in the bottom corners, beside a game of Tic Tac Toe.](docs/images/audio-and-navigation.png)

*Home lists the available profiles. Navigate groups scrolling and More on its bottom row. Incoming Call shows a fictional caller and both actions from a supported dialer.*

The **wired Logitech MX Creative Keypad** (`046D:C354`) connects directly to
your Android phone over USB-C. MX Pocket's Kotlin driver uses Android's USB
Host API to read key presses and update the labels behind the keys as layouts
change. No root access or companion PC is needed. The Windows companion uses
the standard Windows HID driver for the same wired keypad. The wireless
**Dialpad** sold with it is not supported.

## Quick start

<a name="try-it-on-windows-without-hardware"></a>

**Use MX Pocket on Windows.** From the repository root with `JAVA_HOME` set
to JDK 17 or 21 (x64 on Windows ARM64), run:

```powershell
.\gradlew.bat :desktop-preview:run
```

Choose **Connect USB keypad** to use the physical keys for Focus, Windows
volume and media transport, or Tic Tac Toe. Without a keypad, choose
**Settings → Connect virtual keypad** and click the keys on Home. Virtual
audio never changes Windows media. See [Windows controls and limitations](docs/workspace.md#windows-companion).

<a name="run-it-on-android"></a>

**[Try it on Android](docs/workspace.md#install-the-android-app)** with a
wired keypad. Home mirrors the keys; it never executes their actions. You can
edit profiles before connecting hardware.

<a name="guides"></a>

[Key layouts and permissions](docs/android-decks.md) · [Workflows (paused)](docs/workflows.md) · [Extend MX Pocket](#extending-mx-pocket) · [Contribute](CONTRIBUTING.md)

The **OneNote** profile uses a separate, optional accessibility service to
select drawing tools and change formatting while OneNote is on screen.
Its keypad legends follow the visible toolbar: pens draw a narrow ink sample,
highlighters a broad translucent band, and palette keys show a named swatch.
Black-and-white stripes mean the ink color is unknown. A corner check marks an
observed selection, not a successful key press.

For ordinary mobile calls, turn on **Settings → Phone calls → Show calls on the keypad**.
The authorized **physical keypad** switches when the carrier line rings, even
with the phone locked or its screen off, if USB stays powered. When the default
Phone app supplies both actions, key 7 accepts and key 9 declines. Either
physical page button leaves the layout without affecting the call. Caller
details and controls respect lock-screen restrictions. For caller details or
music session access, choose **MX Pocket** once in Android’s **Notification
access**, then opt in to each feature you want inside the app.
See [carrier call setup and supported controls](docs/android-decks.md#carrier-calls-on-the-keypad).

## Extending MX Pocket

**Build a layout without code.** In **Profiles**, create a page and assign
existing actions to its keys. For example, put an Android settings shortcut
on one key and a link to another page on the next. Choose **Tap** or **Hold**
for each binding, then **Save key**. See the [layout walkthrough](docs/workspace.md#edit-layouts-without-hardware).

**Add a built-in integration.** Implement `PocketPlugin` in Kotlin and register
it in `BuiltInPlugins.create()`. The [OneNote plugin and its registration](app/src/main/kotlin/tel/misa/mxpocket/plugins/BuiltInPlugins.kt)
show how a key press requests a drawing tool through `PluginHost`, without
handling USB. Plugins compile into the Android app; it does not load
third-party plugins at runtime. The [plugin guide](docs/plugins.md) shows how
to bind a plugin to a layout and test it.

**Use the keypad in your own Android app.** Build `:mx-driver` as an Android
library (AAR) to receive key presses and releases and upload key images without
the MX Pocket app or its plugins. The [driver guide](docs/driver.md) shows how
to add the library, obtain USB permission and connect to the wired keypad.

## Architecture

The driver reports which physical key changed; the active layout determines
what it does. On Music, for example, the center key asks Android to play or
pause. The key press does not prove playback changed. When player state is
available, the app uses it to render the key face and sends the image to the
driver. The driver handles images and key events, not tracks or layouts.

On Android, a saved layout's `plugin.key` binding names a plugin by its stable
ID and selects one of its nine slots (0–8). `PluginEngine` delivers presses
and releases to the active plugin using that slot, not the key's physical
position. On a plugin switch or disconnect, the engine cancels the activation's
tasks and sends a release for each held key. A plugin can request an
Android action through `PluginHost`. For the display, it returns nine logical
`KeyTile` values; the app renders the bound tiles as images and the driver
sends them over USB.

- [`:mx-driver`](mx-driver) is a standalone Android library (AAR). It opens
  the wired keypad through Android USB Host with the user's permission. The
  [JVM protocol sources](mx-driver/src/jvmShared/kotlin) handle input reports
  and LCD commands independently of Android.
- [`:app`](app) owns Android actions and permissions. `MainActivity` handles
  visible USB consent; `KeypadService` keeps the connection alive across
  Activity recreation. `PocketSession` routes physical input and LCD updates.
  [Compiled-in plugins](docs/plugins.md) request actions through `PluginHost`,
  not USB. Connecting the keypad does not grant notification or accessibility access.
- [`:shared-ui`](shared-ui/src/commonMain) renders the same Compose screens
  on Android and Windows. Screens receive state and send typed events back to
  the host; the host performs platform actions. On Android Home, the nine key
  tiles mirror the keypad but do not execute its actions.
- [`:desktop-preview`](desktop-preview) is the Windows host. For a wired keypad
  it uses the same protocol sources over native Windows HID, not the Android
  AAR. Its clickable simulator needs no USB and cannot verify physical LCD output.

<a name="hardware-evidence-and-credits"></a>

## Credits

[MIT license](LICENSE). The [driver credits and upstream notices](mx-driver/src/main/assets/mx-pocket-provenance.txt)
trace the protocol to Julian Waller's `node-logitech-mx-creative-console` and
related open-source work. For what requires a real device, see the
[hardware checklist](docs/workspace.md#hardware-acceptance-manual-not-established-by-jvm-tests).
The Gradle wrapper is [Apache-2.0 licensed](LICENSES/Apache-2.0.txt); see [NOTICE](NOTICE).
