# Android keypad decks

Connect an authorized USB keypad at the top of **Home**. To change the active profile,
choose **Profiles > [profile name] > Profile settings and rules > Use on keypad**.
Browsing a saved profile does not switch it, and touching a face on the phone never runs it.

| Deck | How to use it | If access is missing |
| --- | --- | --- |
| App Launcher | Choose **Edit favorites**, check apps, then **Done**. Up to seven alphabetical favorites, an edit key and a page key fit on each keypad page. **Next apps** wraps. Real launcher icons appear on both the phone and LCD keys. | No permission prompt. Only exported, currently available `MAIN`/`LAUNCHER` activities in this Android profile are eligible. Removed/deselected targets do not launch. |
| Media Deck (`audio`) | Top: volume down, mute/unmute, volume up. Middle: previous, play/pause, next. Bottom: back 10 seconds, Now playing, forward 10 seconds. The title is a readout, not a shortcut. Open **Plugins → Audio → Media access** on the phone for optional session access. | Without access, the center transport shows a combined play/pause symbol and sends a standard media key. Volume and mute follow Android's local music stream, not a cast device. An unavailable 10-second seek key shows only the background or cover art. Other unsupported controls stay visible but dimmed. |
| Pocket Studio | **Open Camera** requests the default photo viewfinder; **Open Video** requests video mode. Neither captures. For keypad input choose **Plugins > Studio > Camera controls** on the phone, enable keypad camera controls and enable **MX Pocket navigation** in Android Accessibility. | With the unlocked camera visible, press **Shutter**, **Record** or **Stop** on the physical Studio deck. Unsupported modes, labels or layouts do nothing. Use the camera's own controls instead. MX Pocket never confirms a saved photo or a recording state. |
| Navigation and Accessibility | Use the keys beside **Select** to focus the previous or next item; use the keys above and below to scroll. **Back** goes back, not to the previous focus target. Key 9 opens the **Accessibility** keypad page. | With **System nav off**, Back, focus, select and scroll operate in the visible MX Pocket screen; Home opens the launcher. **Recents** shows **Needs access** and is disabled. With the optional service connected, supported cross-app actions become available. Screens without a suitable focus or scroll target can still reject a press. Password elements, Android settings and system permission surfaces remain excluded from element actions. |
| Tic Tac Toe | Select **1 Player** (play X against the computer) or **2 Players** (take turns on the same keypad). The nine LCD keys become board squares; empty squares show their key numbers. After a win or draw, tap any key to choose a mode again. | No phone touchscreen, game-specific permission or network access is needed during play. An active keypad session is required. |

The factory navigation pad reads left to right: **Back / Scroll up / Home**,
**Prev focus / Select / Next focus**, **Recents / Scroll down / Accessibility**.
Press **Accessibility** to open the keypad page below. Press **Back to Navigation** in the
same bottom-right position to return without sending Android Back. **Access info** (key 5)
still opens the navigation disclosure from MX Pocket. Edited roots and independent copies
keep their saved bindings; no permission or direct-launch opt-in changes on upgrade.

### A TalkBack key and other accessibility shortcuts

Keys 1–3 open **TalkBack**, **Captions** and **Night Light** settings; key 4 runs
the configured **Accessibility shortcut**. To place any of these on a different page, in
**Profiles**, edit a key, choose **Tap**, search for an action below and **Save key**.
Leave **Hold** unassigned: these actions require a fresh physical press on an unlocked phone.

- **TalkBack settings** opens Android **Accessibility**. Choose **TalkBack → Use TalkBack** to turn it on or off yourself. MX Pocket never flips the switch or approves a prompt.
- **Run Android accessibility shortcut** requests the phone’s configured **volume-key** accessibility shortcut. For a dedicated TalkBack key, go to **Accessibility → TalkBack → TalkBack shortcut** and select TalkBack alone for that shortcut. Multiple targets can show a chooser. The keypad action requires Android 12+, **MX Pocket navigation** access and a shortcut advertised by Android. If unavailable, use **TalkBack settings** instead. [Google’s TalkBack guide](https://support.google.com/accessibility/android/answer/6007100) also explains how to use the shortcut without the keypad.
- **Caption preferences** opens Android’s caption controls for apps that support captions, not Live Caption or Teams meeting captions. **Night Light settings** opens the display tint controls where available. On phones without a dedicated panel these keys open Accessibility or Display, then the main Settings screen if needed.

The three settings keys need no navigation service. From another app, tap the keypad notification within one minute; even with direct shortcuts enabled, Settings never opens in the background without that tap. These keys do not run in workflows or on Windows.

The native camera owns its files, audio, recording lifecycle and permissions.
MX Pocket needs no camera, microphone or storage permission. Files from the old
in-app camera remain accessible under **Camera controls > Open previous Studio
captures** with a single-file read grant. Saved `studio.open` keys open the
native camera; old explicit capture/timer/zoom/focus/torch/marker bindings stay
unavailable, not silently remapped. Only the built-in Studio root page can send
camera clicks, never imported capture actions or workflows.

The camera-control service reads the active window only on demand. It requires
the resolved camera package and a unique visible, enabled button with an exact
mode-specific accessibility description. English labels are supported; unknown
labels, multiple controls and permission screens are not. A button request is
not proof of capture. To revoke just camera controls, turn off the Studio
option; navigation can remain enabled.

If background
launches are restricted, a fresh authorized physical press for a favorite or
Studio offers a one-shot notification; tapping rechecks binding, connection,
grant, expiry and unlock. Direct shortcuts can instead request **Open Camera** / **Open Video**,
the default camera, or a saved favorite on an unlocked phone, including from
custom pages. For a favorite in the resolved default still-camera package,
MX Pocket requests the verified viewfinder. For other favorites it requests
only the exact selected, still-exported `MAIN`/`LAUNCHER` component.
After a direct request the notification is a quiet retry, not proof of launch.

For richer player controls and artwork, choose **Enable music sessions** in
Audio setup and allow **MX Pocket** in Android’s **Notification access**.
This is a broad system grant shared with optional Teams notices and dialer
details. With only Music enabled, MX Pocket uses the component solely to
authorize `MediaSessionManager`: it does not inspect posted or removed
notifications or query active notifications. After upgrading from separate
listeners, grant the new entry and turn on each use you want; old grants and
preferences do not opt you in. Spotify and other players use the same Android interface:
there is no Spotify sign-in, SDK, playlist browser or downloaded artwork.
While unlocked on **Audio**, the local cover spans the layout preview on Home
and all nine LCD regions on a connected keypad, paused or playing. With hardware
connected, Home mirrors the live keys as a display only; opening a page under **Profiles**
shows its saved bindings rather than the live playback state. A three-pixel black margin around the whole
cover provides bezel clearance. Each label uses black or white against its own part of the picture.
Other profiles keep their normal keys even during playback. Without usable
art, the keys keep a neutral background (or the user's selected backdrop).
A temporary read failure is retried with increasing delays, without changing tracks.
If one player announces the next track before providing its cover, the new title
updates immediately. The last authorized cover can remain for up to 650 ms; if
no replacement arrives, the normal background returns. A new Audio page can
wait that same brief interval before its first physical upload. Losing access
or switching players does not extend the old cover's lifetime.
The bar beneath mute shows Android's local music-stream level. It empties when
muted, fills at maximum and is omitted if the maximum is unknown. TalkBack
announces the precise level and mute state. Keypad changes read the level back right away.
Phone-side volume notifications refresh the same readout; devices that omit
them fall back to a two-second check.

Transport targets a playing session first, otherwise Android's first session.
Ten-second seek is available only while unlocked and only when this session
reports an absolute position and supports seeking. Each press projects the
reported position using playback speed and Android's monotonic timestamp,
then clamps the target to zero and to the duration when known. The title and
status update from session events; there is no ticking position display.

Only embedded bitmaps and bounded, read-granted `content://` images are used.
Titles and cover images are display-only, never written to files or diagnostic
logs. With Audio selected and media access granted, a connected, USB-authorized
keypad keeps showing new titles and covers while the handset is locked or off.
The app doesn't wake the handset or draw over its lock screen. Without that
keypad, lock hides playback details. Leaving Audio, losing the session or
revoking access clears them. One fresh press on the authorized keypad can change music
volume, mute, play/pause or skip without unlocking. Seeking stays disabled.
Workflow steps, settings and app launches do not get
this exception. Player sessions can end or drop an action; MX Pocket does not
wake the phone or start a player to compensate.

The navigation service does not subscribe to screen events,
read element text, inject gestures, or record keys. Both grants are optional
and revocable in Android settings.

## Carrier calls on the keypad

In **Settings → Phone calls**, turn on **Show calls on the keypad** and grant
the optional Android Phone permission (`READ_PHONE_STATE`). On Android 12+
this reads ringing, off-hook or idle for active mobile subscriptions. It does
not read the call log, contacts, microphone or phone number. With the Phone
plugin enabled, an authorized USB keypad, the phone unlocked, automatic
switching on and no manual pin, a call temporarily replaces the current
layout. “Dialing or active” does **not** mean the other person answered.

Either page key leaves immediately, without accepting or declining. You can
also choose a layout in the app. Updates for that call won't pull you back;
only an observed idle state rearms the next switch. If you stay on the call
view, the previous eligible layout returns when the call ends. No call view
is saved. There is no phone popup or Android overlay.

On Android 14+, **Show caller and call controls** is a second, independent
opt-in. If not already granted, allow **MX Pocket** in Android’s **Notification
access**. Basic ringing does not need this grant. Android's grant can expose notifications from other apps.
MX Pocket checks the publisher and Android user before reading the default
Phone app's [CallStyle](https://developer.android.com/reference/android/app/Notification.CallStyle)
fields. It uses the display label (name or number) and an embedded bitmap
if supplied; it doesn't resolve caller URIs, download a photo, look up a
contact, or store caller data. Home and the desktop simulator do not display
the caller's identity or photo.

**Key 7 Accept** and **key 9 Decline** appear only while ringing, for a single
matching Phone notification with two distinct Phone-owned actions. A press
must match the acknowledged LCD face and a fresh check of the notification
and carrier state. There is no Hang up key. Use the system Phone app if the
notification is absent, ambiguous, filtered, unsupported or lacks controls.
Without usable caller details the keypad says **Private / unknown**. Android
12–13 supports the state-only view; Android 9–11 needs the system Phone app.
Lock, disconnect, or loss of Phone permission withdraws the view; loss of
notification access removes caller details and actions. Both options default
off. No dialer-role change is required.

## Teams on this phone

Select **Teams / Work**. **Open Teams** opens the launchable Teams app in this
Android profile; it does not follow a meeting link or join a call. In the
background, press the physical key, then tap the shortcut notification.
Previous focus, Select and Next focus use the existing Navigation controls.
The bottom-left key is unassigned. **Call tools** opens the call page without
claiming a call is active. My chat and Meeting link are unassigned. Existing
layouts are preserved: editing or deleting a Teams page isn't undone on upgrade.

To show **Work** when Teams opens, enable **Teams call status**, **Automatic profile switching** and
**Detect foreground apps**, and grant Android **Usage Access** to MX Pocket.
Teams needs no app mapping. Opening the app never selects **In call** or
presses a key. Pins, profile opt-outs and explicit app mappings still win;
manually changing pages takes precedence while Teams remains in the foreground.

**Teams call status** starts off. Turn it on in **Plugins → Teams → Teams
setup**. This setting does not grant Android permissions.
With an authorized wired keypad, unlocked phone and
Android 14+, notification access for **MX Pocket** allows read-only
status. It is a broad Android permission: the adapter first checks the posting
app and Android user, then checks Teams notification categories and call types.
It does not read messages, names or notification action labels. Turning off
Teams in the app leaves Music and caller details alone; revoking the shared
Android grant stops all three uses.

**Read key 8 on Call tools, or the explanation at the top of Teams setup:**

| Readout | What it means |
| --- | --- |
| **No signal / Check filters** | No eligible Teams notice is visible. This is not a “No call” result. See the filter check below. |
| **Call notice / Type unknown** | Teams posted a call-category notification without a supported call type. Use the controls in Teams. Incoming and screening notices also never switch pages. |
| **Call notice / Show controls** | Teams reported an ongoing call. Open the meeting on this phone and show its controls; the notice alone does not prove you joined. |
| **Call notice / This profile** | Multiple Android profiles are present. Only a notice posted in MX Pocket's own profile is observed. No window inspection, auto-switch or mic control is permitted. |
| **In call / This phone** | A typed ongoing Teams notice and the joined toolbar were both observed on this phone. A hidden or unsupported mic still cannot be operated. |

If access is already on but Teams shows **No signal**, open **Notification
access** in Teams setup → **MX Pocket**. Where Android offers filter
controls, make sure Teams isn't excluded and review **Notifications** (alerting),
**Silent** and **Conversations**. Android's listener delivery classes are *not*
the Teams call type. MX Pocket does not reset your choices. Some phones hide the controls; an
update or re-enabling access is not a guaranteed reset. If no supported notice
arrives, use Teams directly.

For automatic entry on a **single-profile** phone, also allow accessibility
access to **MX Pocket Teams call controls**. Entry requires **both** one
[typed ongoing CallStyle](https://developer.android.com/develop/ui/compose/notifications/call-style)
from Teams in the same Android user and the visible joined meeting controls.
The foreground check looks for the English **Mic** and **Hang up** buttons
together in the same control group, as named in
[Microsoft's Android TalkBack guide](https://support.microsoft.com/en-us/accessibility/teams/use-a-screen-reader-to-join-a-meeting-in-microsoft-teams).
The Mic need not be actionable to recognize this pair. Hang up is never pressed.
Once confirmed, the same notification can maintain
the page with Teams in the background; hidden controls cannot be pressed.
No presence, media activity, chat content or caller identity is used. No
call data or automatic page selection is written to a backup.

Keep the factory center mic key as a Tap with no Hold binding. It shows
**Mute mic** or **Unmute mic** only when the visible Mic button explicitly
reports an unmuted or muted state. A fresh physical press
requests one click and waits for Teams to report the result. Otherwise it
says **Mic unavailable**. Use Teams directly for captions, hand, chat and
call volume; MX Pocket does not substitute media volume or system mute.
Leaving the call restores the previous page unless you navigated elsewhere.
Pinning a profile prevents automatic entry. Locking the phone, losing either
service or revoking access drops live controls. Windows is a layout simulator,
not a Teams remote.

**Compatibility:** Work and manual page selection need no special access.
Automatic detection and mic dispatch are tested with synthetic Android
notifications, accessibility buttons and keypad input, **not a live Teams
client**. There is no verified client/version combination yet. Missing or
ambiguous controls, another language, an unrecognized notification or an
administrator restriction can limit the readout. With multiple profiles,
call notices are read-only. A real, user-controlled call and a physical
LCD/TalkBack check are still required before claiming support for a client.
Verify that ringing/pre-join does not switch the page, showing the joined
toolbar changes key 8, one center press changes the mic in Teams, and leaving
restores the previous page. No meeting content, screenshots or logs are needed.

## Profile and workflow bindings

Persist `DeckCommand.id`, never an ordinal. Only `launcher.open` takes a
parameter: `component` is the exact flattened component of a current favorite.
Unknown actions, unexpected parameters and stale favorites fail closed.
Use `DeckCommand.workflowSafe` to restrict workflow steps to volume/transport.
Navigation, captures, permission setup and launching need an immediate user
press; dispatch must not carry that authority into asynchronous work. The Flows
screen separately supports opening a current favorite or Android Home after
foreground confirmation; it does not use the keypad's launch authority.
`PocketSession.runDeckAction(id, params)` returns `DeckResult(success, message)`.
An accepted transport or capture request is not proof playback or capture
succeeded. `PocketSession.deckState` supplies the confirmed live state,
independent of the selected page.

## Shared setup and access screens

The following Material 3 composables are public in
`tel.misa.mxpocket.shared.ui`. Hosts supply the state; rendering a screen
never requests a permission, persists a choice, or starts an external app.

```kotlin
SetupScreen(state: SetupScreenState, onEvent: (SetupEvent) -> Unit)
DeckAccessScreen(access: DeckAccess, message: String, onOpenSettings: (() -> Unit)?, onDone: () -> Unit,
    listenerGranted: Boolean = false, musicEnabled: Boolean = false, onToggleMusic: (() -> Unit)? = null)
CameraControlsScreen(state: CameraControlsState, onEvent: (CameraControlsEvent) -> Unit)
FavoritesScreen(state: FavoritesState, onSetSelected: (String, Boolean) -> Unit, onDone: () -> Unit,
    loadIcon: (suspend (String) -> ImageBitmap?)? = null)
PhoneActionResultScreen(message: String, onDone: () -> Unit)
DirectShortcutConsentDialog(hasOverlayAccess: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit)
```

Wrap the screens in `PocketTheme`. In setup, route Back from review to
`SetupEvent.Edit`; cancelling the chooser does not save its pending edits.
On Windows, supply `SetupOption.unavailableReason` for unavailable features
and a host-specific introduction. A null settings callback,
`CameraControlsState.Unavailable`, or `FavoritesState.Unavailable(reason)`
explains a missing capability without offering a pretend control.

The shortcut dialog's confirm callback must perform **only the action on its
button**. Recheck the current grant before enabling; opening settings,
dismissing the dialog or returning from settings is not consent to enable.
The notification tap still consumes a one-shot request, not a target supplied
by the screen. Only its failure path needs the result screen.

## Physical acceptance gates (not established by JVM tests)

1. With the keypad connected, select at least ten favorites. Verify each icon,
   truncated long label and target on both pages. Change selection, remove an
   app and restart. Press during a page upload: no old label may open a new
   target. Background the app and verify one-shot notifications, grant
   revocation, expiry and lock rejection, including with direct shortcuts on.
2. Start a player, test volume and media keys without listener access; then
   grant the shared entry and enable music sessions, change tracks and pause from the player. Confirm title and
   playback state follow on the LCD. Try Spotify and a second player without
   skip. Change the cover without changing the title if possible. Check Audio
   paused/playing, including several Play presses: the cover should not flash
   green. Skip tracks on the handset, including a track without a cover,
   then leave and reopen Audio: check for a temporary full gray panel before a
   ready cover and for a definite neutral fallback when art stays missing.
   Pin a different profile: its keys should have no album art. Compare
   rapid physical and handset volume changes against the on-key readout.
   Test locked and screen-off physical Play/Pause, skip, mute and volume;
   change tracks and verify the new title and cover appear on the keypad.
   Switch away from Audio or revoke access and check the private details clear.
   Settings/launch keys must not open anything while locked.
   Unlock, end the session, remove media access and test missing art. Check all
   nine regions redraw on each change. No title or URI should appear in logs.
3. Choose Studio and open photo and video modes. Confirm the default native
   viewfinder appears without a capture. Check disabled access, another app,
   lock, and detach. Physical compatibility depends on the camera build and
   language; fixture clicks do not verify Samsung Camera or a saved file.
4. Test local navigation with the service off, then enable it manually. In a
   normal scrollable app test Back/Home/Recents, focus, activation and scrolling.
   Confirm locked, unsupported, password and permission screens do not permit
   element actions. Disable the service; cross-app actions stop.
5. Test all nine LCD regions and both physical page keys, stale presses during
   uploads, USB detach/replug and service stop. Verify visible pixels and actual
   Android outcomes, not USB acknowledgement alone.
