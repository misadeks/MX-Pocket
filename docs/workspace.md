# Workspace walkthrough

After [installing the Android app](#install-the-android-app), open
**Home**. The connection button in the top-right corner connects or disconnects
a USB keypad. The adjacent status and message show whether the session is ready
or why it failed. Home previews the selected layout offline and mirrors all nine
LCD keys live when connected. Press keys on the device, not on the phone.
Choose **System**, **Light** or **Dark** in **Settings > Appearance**. The
choice is remembered; System follows your device's current theme.
**Settings > Language** follows the system by default. Choose **English**,
**Français** or **Srpski (latinica)** to override it. Android 13 and newer
also expose this choice in the system's per-app language settings. The Windows
simulator remembers its own choice. Changing language does not rename your
layouts, workflows or key labels, or change exported files.
**Plugins** turns features on or off, **Flows** lets you build and run workflows,
and **Settings** contains USB preferences. Open **Settings → Diagnostics** to
see device details and the session log. On Android, Back returns to Settings.
On Windows, Diagnostics opens in a separate window you can close independently.

On Android and Windows, open **Home → Switch layout** and select a profile to
open its first page, even when disconnected. To get back to the nine-key
launcher, choose **Switch layout → Profile launcher**. The **Home** tab shows
the current keypad; switching tabs does not change its layout. Android system
Back dismisses the open menu, returns from other top-level tabs to Home, or
leaves the Activity from Home. It does not select a keypad profile.

On the physical launcher, press a profile key to open it. **More profiles**
shows the next group if there are more than nine. **Previous** and **Next**
cycle through the launcher and available profile roots. A submenu opens with
its assigned key; a **Back** binding retraces pages within the current profile,
and **Return to root** opens that profile's first page, not the launcher.
Press both page controls together to play Tic Tac Toe (**Page Up** and
**Page Down** in the Windows simulator). Release both, then press either
one to exit. The game is not in the regular launcher or editor.

Turning off a plugin hides its own profile and pages from Home, Profiles and
the action pickers. Its saved layout returns when you re-enable it in
**Plugins**. Custom profiles remain visible, including any keys that will
work again when their plugin is enabled.

On Android, **Profiles** lists saved profiles by name and page count. Tap a
profile, choose a page by name, then tap a key on the keypad drawing to edit
its label, icon, color or actions. **Save key** returns to the same page.
Back retraces the path to the page list and then to Profiles. You can also
drag one key onto another to swap them, or use the **Swap with key N** screen
reader actions. This edits saved bindings, not the live screen: it works
disconnected, runs no keys and does not switch profiles. To switch explicitly,
open the profile’s **Profile settings and rules > Use on keypad**.

In a wide desktop window, the **Profiles** workshop shows a profile library instead of the
swipe strip. **Plugins** lists controls supported by the current host first;
unavailable plugins remain visible with the reason they cannot run. The key
action picker also explains unavailable bindings without changing saved keys.

To give a key a second action, open the key on its page, choose **Hold** under
**Key actions**, pick an action, and save the key. **Tap** and **Hold** are
independent; choose **Unassigned** for Hold and save to remove it. Existing
layouts and factory keys have no Hold. Layout backups include assigned Holds.

On an operational key with Hold assigned, releasing early runs Tap. Keeping
the key down until Android's **Touch & hold delay** runs Hold once instead;
releasing it does not also tap. The clock starts at the original press. A
recognized physical two-key chord consumes both keys. Hold does not grant
permission to launch an app in the background or operate the camera shutter.
Keys without Hold keep their usual response time.

## Windows companion

MX Pocket starts disconnected. **Connect USB keypad** on Home or in Settings
scans for one wired Logitech MX Creative Keypad (`046D:C354`), opens its
vendor HID collections and initializes the page and LCD controls. No driver
installation, elevation or Options+ changes are needed. If discovery finds
no keypad, check the cable. If the device is busy, reconnect or retry later;
the app will not force ownership. Disconnect before switching input modes.

| Profile | Physical keypad | Explicit virtual keypad |
| --- | --- | --- |
| Focus | Local start/pause, reset, duration adjustments and 15/25/50-minute presets. No completion tone. | Same local timer |
| Audio | Volume down/up, mute **toggle**, previous, play/pause and next. Transport depends on a compatible media app. “Sent” means Windows accepted the key events, not that playback changed. | In-memory volume, mute, track and playback. No Windows input. |
| Tic Tac Toe | One- or two-player local game | Same game |

Home is a read-only mirror in USB mode. LCD writes use the same nine rendered
key faces as the mirror. Diagnostics distinguish initialization and image
acknowledgments from visible output. The two physical page keys navigate
available layouts. Ordinary presses act once; a slot with a Hold binding
selects Tap or Hold **on release** (500 ms threshold on Windows). Disconnecting
or changing the layout discards stale input. The two page buttons together open the game; either alone exits it.

Choose **Settings → Connect virtual keypad** for clickable Home keys with no
USB I/O. Phone, Launcher, Studio, Navigation, Teams and OneNote require Android; they
stay listed with reasons. Windows does not read volume levels, track metadata
or seek positions, issue absolute mute/unmute commands, run workflows, apply
foreground rules, or upload user background images. Layouts and workflows can
be edited and backed-up layouts imported without enabling Android actions.
Windows desktop data stays local and is not synchronized with the phone.

Build the [x64 Windows JAR](../CONTRIBUTING.md#tooling-and-local-checks)
with a matching x64 JDK 17/21 (also on ARM64 Windows). Run `java -jar` on the
generated JAR. Running it does not need the Android SDK. The remainder of this
walkthrough uses the Android host.

## Install the Android app

Set `JAVA_HOME` to JDK 17 or 21. In Android SDK Manager install SDK Platform
35, Build Tools 35.0.0 and Platform-Tools. Set `ANDROID_HOME` to the SDK
location or open the project in Android Studio to configure it.

On an Android 9 (API 28) or newer phone, enable USB debugging, connect it to
the computer and accept the debugging prompt. From the repository root run:

```powershell
.\gradlew.bat :app:installDebug
```

On Linux or macOS use `./gradlew :app:installDebug`. Open **MX Pocket** on
the phone. Disconnect the computer, review the connection steps in first-run
setup, then [connect the wired keypad](#connect-a-wired-keypad).

## Choose features on first launch

The first normal launcher open on a fresh installation offers setup, even
if Android already allows some or all access. Opening from a USB attachment,
notification or shortcut does not use that opportunity. Updates do not
restart onboarding.

Tap **Review setup steps** for the USB and session notification checklist.
You can add optional features before reviewing, or tap **Not now** to dismiss
the guide without starting a session or requesting access. Reopen
**Settings > Feature setup** at any time. Reviewing a choice does not enable
it, and removing a choice from the guide does not revoke access. Each step
checks Android's current state; you can finish with steps still pending.

Showing the session notification does not allow reading notifications.
In Android’s **Notification access**, there is one **MX Pocket** entry.
Setup and each feature’s access screen open that same entry, or the list if
Android doesn't support its detail page. Granting access does not turn on
any feature. Choose **Enable music sessions** under Audio, **Enable Teams
call status** under Teams setup, or **Show caller and call controls** under
Settings → Phone calls. Each choice is independent. After upgrading from the
three separate listeners, grant the new entry and enable just the uses you want.
Basic media keys, manual Teams pages and the Phone-permission-only ringing
view do not require notification access.

Studio camera keys, one-press shortcuts and foreground profile switching
have their own app settings as well as Android grants. Reviewing an already
allowed grant doesn't switch on the app feature. A missing keypad, player or
call doesn't require changing Android permissions. See
[deck behavior without special access](android-decks.md).

## Edit layouts without hardware

1. Open Home with no keypad attached. The status is **Not connected**. You can
   browse layouts and see their key faces, but touching a face won't execute it.
2. In Profiles use **New profile** and create *Desk*. Open it and use **New page** to create *Tools*. On Desk's root page edit a key, choose
   *Open page: Tools*, and set its label, icon and color. Put *Back* or *Return
   to root* on Tools. Inspect both pages in Profiles. To exercise the bindings,
   choose **Profile settings and rules > Use on keypad**, connect the wired keypad and press its keys.
3. In Flows duplicate **Keypad check**. Give the copy a name, change a delay
   or add a step, then **Test run** and **Save workflow**. A test executes real
   steps. In Profiles assign the saved workflow to a Tools key. Press that key
   on the connected keypad to see the steps run. **Run history** shows origin,
   result and progress. For confirmation steps open Flows and approve or
   decline on the phone. Cancelling does not undo completed actions.
4. In Plugins enable Audio; optional media access is explained before opening
   Android settings. Start playback in a player. In Profiles enable automatic
   switching and inspect the Audio rule `mediaActive = true`. While the app
   is visible or hosting a real session, a stable match selects Audio after
   a short delay. Supported players expose Play/Pause and title with consent;
   without it the app sends ordinary media keys and does not invent a title
   or playback state. With artwork available on Audio, the unlocked phone's
   layout preview shows one cover across the nine keys; a wired keypad shows
   it on the LCDs. The cover stays on Audio, even during playback;
   a pinned non-music profile keeps its own background.
5. Inspect the selection reason, matched rules and observed values in Profiles.
   Create or change weighted rules for an observed app-owned activity, device,
   local hour/range, media, plugin or workflow state. Pin Desk, change playback
   and verify it stays on Desk. Unpin to resume automatic selection. Disabling
   automatic switching for a profile prevents entering or leaving it by rule.
   Foreground app mappings are optional; see below.
6. Export layouts, duplicate or rename a profile, then import the export.
   The confirmed import restores profiles, bindings, rules, pins and per-profile
   histories as one versioned document. Invalid or oversized input must leave
   the old deck intact. **Restore factory layouts** restores only layouts.
   Workflows, permissions, favorites and capture files are separate; importing a
   workflow key does not import its code, grant access, or run it.

Layout format 2 updated the factory Audio key order; customized pages keep
the old Audio actions. Format 3 also updates an untouched Navigation root
from format 1 or 2. Format 4 removes the stock Focus timer shortcut from
Teams Work without moving other keys. A customized shortcut is preserved.
Older app versions cannot import format 4 exports.

## Connect a wired keypad

1. Plug **one wired MX Creative Keypad** into an Android phone with USB Host
   support. Open **Settings → Diagnostics**, tap **Refresh USB diagnostics**
   and look for `046D:C354`.
   If it's missing, try a data-capable cable or OTG adapter and, if the phone
   cannot supply enough power, a powered hub.
2. Tap **Connect to device** at the top of Home and approve the attached device when Android prompts.
   MX Pocket requires a visible session notification so **Disconnect** stays
   available while another app is open. On Android 13+ allow notification
   posting. If you denied it or blocked the session channel, enable it in the
   app's Android notification settings and tap **Connect to device** again.
3. On the factory Focus page, press **Start**, then use a page button to switch
   profiles. The notification's **Disconnect** ends the session. To connect
   on future attachments, enable **Settings → Connect automatically on USB
   attach**, unplug and reconnect, and choose MX Pocket if Android asks.
   Auto-connect is off by default; Android controls handler selection. If it
   doesn't start, unlock the phone and connect manually.

**Found in diagnostics, but no controls?** Check the grant for this attachment.
An interface claim can still fail because of the phone's USB driver. An
unsupported descriptor, rejected image or missing acknowledgement is an error,
not a successful upload. Note the first error and the phone model/Android
version; don't post unredacted logs, device paths, serials or personal artwork.
No root, firmware update or kernel change is part of setup.

## Show a profile when an app opens

**Automatic profile switching** lets you choose whether app and context rules
can move your keypad at all. Leave it off for manual control, or pin a profile
to pause switching. Teams has a built-in app match: once step 2 below is set
up, opening Teams selects **Teams / Work** without a custom rule. This does
not join a call or press a key.

1. In **Profiles**, open **Studio > Profile settings and rules**. Under **Context rules > Choose app**, tap **Camera** (Samsung Camera on a Galaxy). Open **Audio** and repeat for **YouTube Music**. You can choose a custom profile the same way. Saving a mapping does not enable detection, change key bindings or launch anything.
2. Enable **Automatic profile switching** and **Detect foreground apps**. Tap **Open usage access settings**, select **MX Pocket**, and allow Usage Access. No notification, accessibility or overlay access is needed for detection. Turn detection off here or revoke the Android grant at any time.
3. With the physical keypad connected and the phone unlocked, open one app, then the other. After a stable match the keypad shows that profile’s last page. Opening Camera does **not** take a picture, and opening YouTube Music does **not** start playback. Studio’s optional physical shutter and recording controls require separate setup for the default camera; mapping Camera does not enable them.

The picker creates one ordinary `foregroundApp` rule for the chosen package, with weight **5**: enough to beat the stock Audio playback rule (**3**) by the required two points. Use **Advanced rules** only to inspect signals or change weights. If no app matches, other eligible rules still compete (including playback); if none match, the eligible default wins after debounce. Pins and profile opt-outs still take precedence.

Detection reads local Android activity transitions, not media sessions or screen text. It runs only while MX Pocket is visible or hosting a connected keypad, unlocked, with an eligible mapping. The picker lists launchable apps visible in this Android user/profile. Usage events don’t identify the focused pane in multi-window mode; simultaneous resumed apps, missing events or an observation older than ten minutes give no app match. After granting access, unpinning, or unlocking, reopen the app if Android hasn't reported a fresh resume. Locked playback and authorized hardware media controls work as before. MX Pocket saves your mappings, not an app usage history.

## Open a shortcut while another app is visible

With the phone unlocked and the physical keypad connected, try **Dialer** on
the factory **Phone** profile. By default a background press offers a shortcut
notification: tap it to open the app. Only the latest request is usable, for
one minute on the same connected keypad and binding.

For one-press opening, turn on **Settings → Open apps directly from keypad**
and grant **Display over other apps**. Android gives that setting broader
powers than launching apps; MX Pocket does not draw overlays. A new physical
press can request a Phone shortcut, a saved launcher favorite or the camera
viewfinder, not a capture. If Android suppresses the launch, tap the retry
notification. Turn off the option or revoke the grant to return to notifications.

[Workflow steps](workflows.md) do not inherit this
authority. App/favorite launches and Android Home in workflows wait for approval
on the visible, unlocked phone. For media, camera, navigation and game controls,
see [Android keypad decks](android-decks.md).

## Hardware acceptance (manual, not established by JVM tests)

After [connecting the keypad](#connect-a-wired-keypad), check the following
on your phone. Record Android version, handset model, keypad model and observed
results without identifiers or media metadata.

- Verify discovery, deny/retry/grant and notification consent. Connect one
  046D:C354, read all nine keys and both page controls, release a multi-key chord.
- With automatic connection off, replug and confirm nothing starts until you
  connect. With it on and MX Pocket selected as handler, check for one session
  notification. Disconnect from that notification and reopen the app: it should
  not reconnect until requested or reattached. An unrelated device should not
  start a session.
- Repeat the Desk/Tools workflow above on hardware. Observe all nine 118x118
  regions of the single panel, confirm readability and persistence for 60 seconds.
  Switch pages while an image is transferring; a press against the unacknowledged
  page must not run the replacement action.
- Start playback and confirm the selected media profile, physical Play/Pause,
  corresponding playback-state redraw, then open a nested page. Run the saved
  workflow with a physical key. Compare progress and result on phone and LCD.
- Lock the phone, then turn the screen off. Use the physical media keys without
  waking the screen. On authorized hardware, change tracks and check that
  the Audio title and cover follow the player. Switch profiles, then revoke
  access: private titles and covers should not linger. See the
  [media access and privacy checks](android-decks.md#physical-acceptance-gates-not-established-by-jvm-tests).
- Detach during upload, reconnect and verify saved location and full repaint.
  Background, rotate and lock/unlock the phone. Recheck direct Phone shortcuts,
  notification handoff and revoked permissions. Verify native viewfinder opening,
  optional global navigation and actual player behavior on that handset.
- With MX Pocket in the background, press **Phone: Camera** on a custom page and
  Studio's **Open Camera**, first with direct shortcuts off, then on with special access.
  With access, check that the viewfinder appears without tapping and that no
  capture starts. “If the app did not open, tap to try again” means Android was
  asked to launch; “Tap to open the app” means no direct request was made.
- Save two ordinary launcher favorites. Open the first with a physical key
  while MX Pocket is visible. Without returning to MX Pocket or tapping a
  notification, press the same key and then the second favorite. Check which
  app is visible after each press, separately from any retry notification.
  Revoke special access and repeat: now only tapping the latest notification
  should open its favorite.

The debug `UiPreviewActivity` is a fixture: taps change fixture state or record
UI events only. It cannot substitute for the executable simulator or hardware
checks. Brightness and periodic ownership commands are not sent without a
supported protocol. No notification center, Tasker, Home Assistant or enhanced
accessibility integration is included.
