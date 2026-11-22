# MX Pocket

MX Pocket gives your Android phone nine physical keys with their own labels.
Plug in the **wired Logitech MX Creative Keypad** (`046D:C354`) and put on a
song. The middle key on **Audio** is play/pause. Switch to **Navigate**, and
that same key is **Select**. The wireless **Dialpad** is not supported.

![Six keypad layouts, in reading order: Home profile picker; Navigate with Select between the focus keys; Music with Pause over album art; OneNote Draw with the eraser selected; Incoming Call with a fictional caller, Accept and Decline; and Tic Tac Toe with X across the top row.](docs/images/audio-and-navigation.png)

## Quick start

<a name="run-it-on-android"></a>

### Android

1. [Install MX Pocket](docs/workspace.md#install-the-android-app) on an Android 9 or newer phone with USB Host support. Disconnect the computer and plug the keypad into the phone with a data cable or OTG adapter. No root access is needed.
2. Open **Home → Connect to device** and approve USB access. Allow the session notification on Android 13 or newer. It gives you a **Disconnect** button while another app is open; it does not let MX Pocket read notifications.
3. On the keypad, choose **Focus** and press **Start**. Use the two page buttons to switch layouts. Tap them together to get back to Home; hold them together to start Tic Tac Toe. Pick one or two players, then play on the nine keys. The grid on the phone mirrors the keypad.

Prefer a layout to follow its app? [Link a profile to that app and enable
foreground detection](docs/workspace.md#show-a-profile-when-an-app-opens). Automatic
switching by app needs Android's Usage Access; the page buttons don't.

### Navigation and accessibility on Android

**Navigate** moves focus, selects the focused control, and scrolls within
MX Pocket. To use those keys in another app, unlock the phone and enable
**MX Pocket navigation** in Android Accessibility. **Select** presses the
focused control if the app marks it clickable. **Scroll down** works where
the app exposes a scrollable area. TalkBack, not MX Pocket, supplies speech.

**TalkBack settings** opens Android's Accessibility settings; it does not turn
on the screen reader. On an unlocked Android 12+ phone with navigation access,
a separate key can run [Android's volume-key accessibility shortcut](docs/android-decks.md#a-talkback-key-and-other-accessibility-shortcuts)
if the phone offers it. Choose TalkBack as the only target for a dedicated
TalkBack key; with multiple targets, Android can show a chooser.

**OneNote Draw** uses a separate, optional accessibility service. With OneNote
in the foreground, the keys select pens, highlighters and other tools from its
toolbar. A check mark shows which tool OneNote reports as selected.

<a name="try-it-on-windows-without-hardware"></a>

### Windows companion

From the repository root, set `JAVA_HOME` to JDK 17 or 21 (x64 on Windows
ARM64) and run `.\gradlew.bat :desktop-preview:run`. Choose **Connect USB
keypad** for a wired keypad or **Settings → Connect virtual keypad** to try the
layouts with a mouse. Virtual audio keys do not control Windows playback.
See [Windows controls and limitations](docs/workspace.md#windows-companion).

<a name="guides"></a>

[Android layouts and permissions](docs/android-decks.md) · [Incoming call controls](docs/android-decks.md#carrier-calls-on-the-keypad) · [Workflows (paused)](docs/workflows.md) · [Extend MX Pocket](#extending-mx-pocket) · [Contribute](CONTRIBUTING.md)

## Extending MX Pocket

**Build a layout without code.** In **Profiles**, choose a page and a key.
Assign an action to **Tap** or **Hold**, then choose **Save key**. You can
[edit layouts without a keypad](docs/workspace.md#edit-layouts-without-hardware).

**Add an Android integration.** Implement `PocketPlugin` in Kotlin and register
it in [`BuiltInPlugins.create()`](app/src/main/kotlin/tel/misa/mxpocket/plugins/BuiltInPlugins.kt).
Plugins compile into the app. The [plugin guide](docs/plugins.md) walks through
binding a counter to a physical key and testing it.

**Use the keypad in your own Android app.** The [driver guide](docs/driver.md)
shows how to build the `:mx-driver` AAR, get USB permission, receive key events
and upload key images without the MX Pocket app.

## Architecture

The driver reports which physical key changed; the active layout determines
what it does. For example, the Audio layout asks Android to play or pause.
When the player reports its state, the app uses that state for the key image
rather than assuming the press changed playback.

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
- [`:shared-ui`](shared-ui/src/commonMain) renders the same Compose screens
  on Android and Windows. Screens receive state and send typed events back to
  the host; the host performs platform actions.
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
