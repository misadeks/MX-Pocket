# Workspace walkthrough

Home operates the current nine keys. Keypad edits them without executing
anything. Plugins controls availability and optional Android access. Flows
edits and runs workflows. Settings contains USB consent preferences, repaint,
the session-only image picker and diagnostic log export.

## Choose features on first launch

Choose the features you want help setting up, or tap **Skip and try virtual
keypad** to use the on-screen keys without hardware or permissions. Reopen
**Settings > Feature setup** at any time. Reviewing a choice does not enable
it, and removing a choice from the guide does not revoke access. Each step
checks Android's current state; you can finish with steps still pending.

For physical keys, authorize the **attached keypad** and allow MX Pocket to
show its session notification. Start the session with **Connect keypad** on
Home. Automatic connection is separate: enable plug-in handling, reconnect
the keypad and select MX Pocket if Android offers a handler choice.
Optional **notification access** is for richer player controls and artwork,
not for posting the session notification. Basic media keys still work without
it. Physical Studio camera controls, one-press app opening and cross-app navigation each
have their own steps. If Android restricts access, leave that option off.

## Run without hardware

1. On Home choose **Connect virtual keypad** and press **Start** on Focus.
   The first tile changes to **Pause** and the countdown advances. No USB
   permission or foreground service is needed. Disconnecting the virtual
   keypad disables input; reconnecting restores the selected page.
2. In Keypad use **Manage profile > New profile** and create *Desk*. Under
   **Manage page** create *Tools*. On Desk's root page edit a key, choose
   *Open page: Tools*, and set its label, icon and color. Put *Back* or *Return
   to root* on Tools. Choose **Use this profile**, then navigate on Home.
3. In Flows duplicate **Keypad check**. Give the copy a name, change a delay
   or add a step, then **Test run** and **Save workflow**. A test executes real
   steps. In Keypad assign the saved workflow to a Tools key. Press that key
   on Home and watch its running/terminal state. **Run history** shows origin,
   result and progress. For confirmation steps open Flows and approve or
   decline on the phone. Cancelling does not undo completed actions.
4. In Plugins enable Audio; optional media access is explained before opening
   Android settings. Start playback in a player. In Keypad enable automatic
   switching and inspect the Audio rule `mediaActive = true`. While the app
   is visible or hosting a real session, a stable match selects Audio after
   debounce. Supported players expose Play/Pause and title with consent;
   without it the app sends ordinary media keys and does not invent a title
   or playback state. On Audio, an unlocked phone and the keypad show one inset
   cover across the nine keys. The cover stays on Audio, even during playback;
   a pinned non-music profile keeps its own background.
5. Inspect the selection reason, matched rules and observed values in Keypad.
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

## Show a profile when an app opens

1. In **Keypad**, pick **Studio** in the **Profile** picker. Under **Context rules > Choose app**, tap **Camera** (Samsung Camera on a Galaxy). Pick **Audio** and repeat for **YouTube Music**. You can choose a custom profile the same way. Saving a mapping does not enable detection, change key bindings or launch anything.
2. Enable **Automatic profile switching** and **Detect foreground apps**. Tap **Open usage access settings**, select **MX Pocket**, and allow Usage Access. No notification, accessibility or overlay access is needed for detection. Turn detection off here or revoke the Android grant at any time.
3. With the physical keypad connected and the phone unlocked, open one app, then the other. After a stable match the keypad shows that profile’s last page. Opening Camera does **not** take a picture, and opening YouTube Music does **not** start playback. Studio’s optional physical shutter and recording controls require separate setup for the default camera; mapping Camera does not enable them.

The picker creates one ordinary `foregroundApp` rule for the chosen package, with weight **5**: enough to beat the stock Audio playback rule (**3**) by the required two points. Use **Advanced rules** only to inspect signals or change weights. If no app matches, other eligible rules still compete (including playback); if none match, the eligible default wins after debounce. Pins and profile opt-outs still take precedence.

Detection reads local Android activity transitions, not media sessions or screen text. It runs only while MX Pocket is visible or hosting a connected keypad, unlocked, with an eligible mapping. The picker lists launchable apps visible in this Android user/profile. Usage events don’t identify the focused pane in multi-window mode; simultaneous resumed apps, missing events or an observation older than ten minutes give no app match. After granting access, unpinning, or unlocking, reopen the app if Android hasn't reported a fresh resume. Locked playback and authorized hardware media controls work as before. MX Pocket saves your mappings, not an app usage history.

## Integrated controls

| Profile | What to exercise | Platform boundary |
| --- | --- | --- |
| Phone | Select the factory **Phone** profile, connect an authorized physical keypad, opt in to **Open apps directly from keypad** in Settings and grant the explained Android special access. With the phone unlocked, background MX Pocket and press **Dialer**. | This legacy Phone shortcut can open immediately from one fresh physical press. Without the opt-in/grant it offers a notification instead. Virtual input and workflow steps never inherit this permission. |
| Launcher | Edit favorites, choose installed apps, open one and page through additional favorites. Assign a favorite directly in a custom layout. | With direct shortcuts enabled and special access granted, a fresh physical press requests the exact saved launcher component or the resolved default camera's viewfinder. Removed/non-favorite components cannot launch. |
| Audio | Start a player, adjust volume, skip and toggle playback. Observe the title and transport state. | Global metadata requires optional Android media-session access. Support for individual controls depends on the player. No notification inbox or message access features. |
| Studio | Open the phone camera in photo or video mode. Optionally enable **Camera controls** and the Android navigation accessibility service to request Shutter, Record or Stop using the physical Studio deck. | Only a matching button in the visible default camera can respond. The camera owns media and reports its own state. No camera grant to MX Pocket is needed. |
| Navigation | Try Back, Home, focus movement, activation and scrolling; inspect the optional navigation-access explanation. | System Recents/cross-app operations need explicit service activation. Apps and protected system screens may refuse actions; no gesture recording or scanning mode. |
| Custom | Put different media, page, profile, favorite and workflow actions on adjacent keys. Disable one plugin and verify its assigned controls no longer run. | An import is configuration, not new code. Optional Android grants remain controlled by the OS. |

In Flows, app/favorite launches and Android Home wait for separate approval on
the visible, unlocked phone. A delay, schedule or physical workflow trigger
does not turn these steps into a one-press Phone shortcut. Workflows cannot
start recording, enable access or drive the navigation service.

## Hardware acceptance (manual, not established by JVM tests)

Use the [USB and foreground-service checklist](../README.md#background-sessions-and-automatic-connection)
on a separately authorized host phone and keypad. Record Android version, handset,
keypad model and outcomes without identifiers or media metadata.

- Verify discovery, deny/retry/grant and notification consent. Connect one
  046D:C354, read all nine keys and both page controls, release a multi-key chord.
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
