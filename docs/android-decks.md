# Android keypad decks

Select **Connect virtual keypad** or connect an authorized keypad, then select a
profile. Virtual keys run real phone actions. The debug-only UI fixture does not.

| Deck | How to use it | If access is missing |
| --- | --- | --- |
| App Launcher | Choose **Edit favorites**, check apps, then **Done**. Up to seven alphabetical favorites, an edit key and a page key fit on each keypad page. **Next apps** wraps. Real launcher icons appear on both the phone and LCD keys. | No permission prompt. Only exported, currently available `MAIN`/`LAUNCHER` activities in this Android profile are eligible. Removed/deselected targets do not launch. |
| Media Deck (`audio`) | Top: volume down, mute/unmute, volume up. Middle: previous, play/pause, next. Bottom: back 10 seconds, Now playing, forward 10 seconds. The title is a readout, not a shortcut. Open **Plugins → Audio → Media access** on the phone for optional session access. | Without access, the center transport shows a combined play/pause symbol and sends a standard media key. Volume and mute follow Android's local music stream, not a cast device. Unsupported controls are dimmed. |
| Pocket Studio | **Open Studio**, then **Allow camera**. Use the foreground viewfinder or physical keys. Shutter takes a photo; Photo timer cycles off/3/10 seconds. **Record**, **Stop**, **Marker**, center focus, zoom and torch act on this camera only. LCD recording state and elapsed time come from CameraX events, not the Record button. | No camera grant, unavailable hardware, and unsupported use cases do not trigger fallback camera apps. No microphone permission: video is silent. Leaving the Activity stops recording, cancels the shutter timer and releases the camera. |
| Android Navigation | Home opens the system launcher. Back, scrolling, focus movement and activation work in the visible MX Pocket screen. Select **Navigation access** to read the disclosure and optionally enable the service in system settings. | Recents and navigation of other apps require that service. It uses the focused element or scrollable ancestors; when there is no focus it looks for a visible focus/scroll target, with a bounded search. Screens without suitable targets may reject actions. Password elements, Android settings and system permission surfaces are not navigation targets. |

**Saved captures** opens private photos, videos and per-video text markers
(milliseconds from recording start) using a viewer with a single-file read
grant. Recording and capture are never triggered by opening Studio, USB attach,
Activity recreation, a delayed workflow, or a locked-screen shortcut. No
background camera, microphone or storage permission is used. If background
launches are restricted, a fresh authorized physical press for a favorite or
Studio offers a one-shot notification; tapping rechecks binding, connection,
grant, expiry and unlock. Direct shortcuts can instead request **Open Studio**
or the default camera immediately on an unlocked phone, including from custom
pages. For a camera favorite, Android must resolve the default still-camera
handler to the favorite's package; MX Pocket starts that viewfinder, not the
favorite's arbitrary launcher activity. Other favorites always require a tap.
After a direct request the notification is a quiet retry, not proof of launch.

Android requires a notification-listener grant to inspect active media
sessions. It is broad at the OS level. This app uses the component solely as
authorization for `MediaSessionManager`; it does not retrieve, log, or act
on notifications. Spotify and other players use the same Android interface:
there is no Spotify sign-in, SDK, playlist browser or downloaded artwork.
While unlocked on **Audio**, the local cover spans Home's keypad and all nine
LCD regions, paused or playing. A three-pixel black margin around the whole
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
Phone/simulator taps, workflow steps, settings and app launches do not get
this exception. Player sessions can end or drop an action; MX Pocket does not
wake the phone or start a player to compensate.

The navigation service does not subscribe to screen events,
read element text, inject gestures, or record keys. Both grants are optional
and revocable in Android settings.

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

## Physical acceptance gates (not established by JVM tests)

1. With the keypad connected, select at least ten favorites. Verify each icon,
   truncated long label and target on both pages. Change selection, remove an
   app and restart. Press during a page upload: no old label may open a new
   target. Background the app and verify one-shot notifications, grant
   revocation, expiry and lock rejection, including with direct shortcuts on.
2. Start a player, test volume and media keys without listener access; then
   grant access, change tracks and pause from the player. Confirm title and
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
3. In Studio deny, then grant camera. Capture immediate and timed photos, zoom
   both ways, test center focus and torch where supported. Record a silent
   video, mark twice, confirm elapsed/recording LCDs, stop and open the video
   and corresponding timestamp file. Leave/lock during a timer and once during
   recording; no delayed shutter, continuing recording or camera use afterward.
4. Test local navigation with the service off, then enable it manually. In a
   normal scrollable app test Back/Home/Recents, focus, activation and scrolling.
   Confirm locked, unsupported, password and permission screens do not permit
   element actions. Disable the service; cross-app actions stop.
5. Test all nine LCD regions and both physical page keys, stale presses during
   uploads, USB detach/replug and service stop. Verify visible pixels and actual
   Android outcomes, not USB acknowledgement alone.
