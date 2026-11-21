# Android keypad decks

Select **Connect virtual keypad** or connect an authorized keypad, then select a
profile. Virtual keys run real phone actions. The debug-only UI fixture does not.

| Deck | How to use it | If access is missing |
| --- | --- | --- |
| App Launcher | Choose **Edit favorites**, check apps, then **Done**. Up to seven alphabetical favorites, an edit key and a page key fit on each keypad page. **Next apps** wraps. Real launcher icons appear on both the phone and LCD keys. | No permission prompt. Only exported, currently available `MAIN`/`LAUNCHER` activities in this Android profile are eligible. Removed/deselected targets do not launch. |
| Media Deck (`audio`) | Volume, mute, play/pause and skip work with supported players. Press the middle status/title key to learn about optional session access. With access, MX Pocket selects a playing session first, otherwise the first session Android returns. | Without access, MX Pocket sends standard media keys without claiming playback is active. Volume uses the local music stream. With access, unsupported controls report why they cannot run. Remote-device/cast volume is not controlled. |
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
While unlocked, a local cover spans the keypad panel on Home and the nine
visible LCD regions. A paused cover appears only on Audio. Playing artwork
can appear behind another profile, including a pinned one; it does not change
bindings or selection rules. Without usable cover art, the normal key design
(or the user's selected backdrop) remains.

Only embedded bitmaps and bounded, read-granted `content://` images are used.
Titles and cover images are display-only, never written to files or diagnostic
logs. Screen-off or keyguard lock hides both; it does **not** mean the player
is unavailable. One fresh press on the authorized USB keypad can change music
volume, mute or request an advertised transport action without unlocking.
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
   paused/playing, then pin a different profile: its own keys should remain.
   Test locked and screen-off physical Play/Pause, skip, mute and volume; no
   title/cover should remain, and settings/launch keys must not open anything.
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
