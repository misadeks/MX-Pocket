# MX Pocket

MX Pocket lets you control Android by feel. Link a keypad layout to an app
and put actions where your fingers expect them. In supported apps, use physical
keys to move between items, scroll and select without tapping a small button or
making a precise swipe. Assign a workflow to one of the nine LCD keys, open a
favorite app, start a focus timer or change tracks. Previous and Next switch
layouts. Press them together and release to return to Home and choose a profile,
or hold both to play Tic Tac Toe. To try MX Pocket without hardware, use the
Windows simulator.

When you join a Microsoft Teams call on the connected Android phone, the keypad
switches to a meeting layout. Mute stays in the same spot, with keys for
captions, raising your hand and opening meeting chat where Teams supports those
actions. When the call ends, the keypad returns to your previous layout unless
you've selected another.

On the **Music** layout, press the center key to pause the track.
Switch to **Navigation and Accessibility**: that same key now says **Select**, and the key below
it says **Scroll down**. The bottom-right key opens **Accessibility**; press it again
to return to navigation. With [Android navigation access](docs/android-decks.md)
enabled, you can keep reading in a supported app without swiping the screen.

![Two illustrated nine-key keypads. On Music, the center key shows Pause and the key below reads “Druga Strana”. On Navigation, those keys become Select and Scroll down.](docs/images/audio-and-navigation.png)

*LCD layouts on an illustrated keypad.*

The **wired Logitech MX Creative Keypad** (`046D:C354`) connects directly to
your Android phone over USB-C. MX Pocket's Kotlin driver uses Android's USB
Host API to read key presses and update the labels behind the keys as layouts
change. You can use the driver without the app. No root access or companion PC
is needed. The Windows companion uses the standard Windows HID driver for the
same wired keypad. The wireless **Dialpad** sold with it is not supported.

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
**[Reuse the Kotlin driver](docs/driver.md)** in your own Android app.

<a name="guides"></a>

[Key layouts and permissions](docs/android-decks.md) · [Workflows](docs/workflows.md) · [Write a plugin](docs/plugins.md) · [Contribute](CONTRIBUTING.md)

The **Teams** profile opens the Android client on this phone. Choose **Call
tools** for its manual meeting page, or **Teams setup** for optional local call
status and switching. A Teams call notice is not proof you joined. Key 8 and
Teams setup distinguish a notice from a confirmed call. In Android’s
**Notification access**, choose **MX Pocket** once. Music sessions, Teams
call notices and optional Phone caller details each need their own in-app
opt-in, including after upgrading from separate listeners. The Teams mic
key still needs its separate accessibility grant.
See [Teams call controls and client limits](docs/android-decks.md#teams-on-this-phone).

The **OneNote** profile uses a separate, optional accessibility service to
select drawing tools and change formatting while OneNote is on screen.
Its keypad legends follow the visible toolbar: pens draw a narrow ink sample,
highlighters a broad translucent band, and palette keys show a named swatch.
Black-and-white stripes mean the ink color is unknown. A corner check marks an
observed selection, not a successful key press.

For ordinary mobile calls, **Settings → Phone calls** can temporarily show the
caller on the **physical keypad**. Either page key leaves without affecting the
call. See [carrier call setup and supported controls](docs/android-decks.md#carrier-calls-on-the-keypad).

<a name="hardware-evidence-and-credits"></a>

## Credits

[MIT license](LICENSE). The [driver credits and upstream notices](mx-driver/src/main/assets/mx-pocket-provenance.txt)
trace the protocol to Julian Waller's `node-logitech-mx-creative-console` and
related open-source work. For what requires a real device, see the
[hardware checklist](docs/workspace.md#hardware-acceptance-manual-not-established-by-jvm-tests).
The Gradle wrapper is [Apache-2.0 licensed](LICENSES/Apache-2.0.txt); see [NOTICE](NOTICE).
