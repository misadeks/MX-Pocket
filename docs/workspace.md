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
and **Settings** contains connection preferences and diagnostics.

**Profiles** switches and edits profiles. Swipe the endless profile carousel
(or tap a card) to put that profile on the keypad; the cards beside the
centered one are slightly blurred. The profile picker below it always shows
the same profile: choosing one there also puts it on the keypad. Choosing a
page only opens it for editing. **Swap keys** opens the keypad with the same
key faces as the LCDs (live for the page on the keypad): drag a key onto
another to exchange them, or tap a key to edit its label, icon, color or
action. Nothing there runs an action; screen readers offer **Swap with key N**
actions instead of dragging. In a narrow window, Back returns from the key
editor; a wider window keeps the key details beside the picker.

To give a key a second action, open it from **Profiles > Swap keys**, choose **Hold** under
**Key actions**, pick an action, and save the key. **Tap** and **Hold** are
independent; choose **Unassigned** for Hold and save to remove it. Existing
layouts and factory keys have no Hold. Layout backups include assigned Holds.

On an operational key with Hold assigned, releasing early runs Tap. Keeping
the key down until Android's **Touch & hold delay** runs Hold once instead;
releasing it does not also tap. The clock starts at the original press. A
recognized physical two-key chord consumes both keys. Hold does not grant
permission to launch an app in the background or operate the camera shutter.
Keys without Hold keep their usual response time.

The Windows simulator uses the same workspace and starts with the virtual
keypad connected. It can save its own layouts, but has no USB connection,
image upload, or Android permission and foreground-app detection controls.
The walkthrough below uses the Android host.

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
the phone. Disconnect the computer, choose **Use a USB keypad** in
first-run setup, finish setup and [connect the wired keypad](#connect-a-wired-keypad).

## Choose features on first launch

Choose the features you want help setting up, or tap **Not now** to dismiss
the guide without starting a session or requesting access. Reopen
**Settings > Feature setup** at any time. Reviewing a choice does not enable
it, and removing a choice from the guide does not revoke access. Each step
checks Android's current state; you can finish with steps still pending.

Optional **notification access** is for richer player controls and artwork,
not for posting the session notification. Basic media keys still work without
it. Physical Studio camera controls, one-press app opening and cross-app
navigation each have their own steps. If Android restricts access, leave that
option off; see [deck behavior without special access](android-decks.md).

## Edit layouts without hardware

1. Open Home with no keypad attached. The status is **Not connected**. You can
   browse layouts and see their key faces, but touching a face won't execute it.
2. In Profiles use **Manage profile > New profile** and create *Desk*. Under
   **Manage page** create *Tools*. On Desk's root page edit a key, choose
   *Open page: Tools*, and set its label, icon and color. Put *Back* or *Return
   to root* on Tools. Inspect both pages in Profiles. To exercise the bindings,
   connect the wired keypad and press its keys.
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
   support. In **Settings → Refresh USB diagnostics**, look for `046D:C354`.
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

1. In **Profiles**, pick **Studio** in the **Profile** picker. Under **Context rules > Choose app**, tap **Camera** (Samsung Camera on a Galaxy). Pick **Audio** and repeat for **YouTube Music**. You can choose a custom profile the same way. Saving a mapping does not enable detection, change key bindings or launch anything.
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
