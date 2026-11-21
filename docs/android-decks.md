# Android keypad decks

Select **Connect virtual keypad** or connect an authorized keypad, then select a
profile. Virtual keys run real phone actions. The debug-only UI fixture does not.

| Deck | How to use it | If access is missing |
| --- | --- | --- |
| App Launcher | Choose **Edit favorites**, check apps, then **Done**. Up to seven alphabetical favorites, an edit key and a page key fit on each keypad page. **Next apps** wraps. Real launcher icons appear on both the phone and LCD keys. | No permission prompt. Only exported, currently available `MAIN`/`LAUNCHER` activities in this Android profile are eligible. Removed/deselected targets do not launch. |
| Media Deck (`audio`) | Volume, mute, play/pause and skip work with supported players. Press the middle status/title key to learn about optional session access. With access, MX Pocket selects a playing session first, otherwise the first session Android returns. | Without access, MX Pocket sends standard media keys without claiming playback is active. Volume uses the local music stream. With access, unsupported controls report why they cannot run. Remote-device/cast volume is not controlled. |
| Pocket Studio | **Open Camera** requests the default photo viewfinder; **Open Video** requests video mode. Neither captures. For keypad input choose **Plugins > Studio > Camera controls** on the phone, enable keypad camera controls and enable **MX Pocket navigation** in Android Accessibility. | With the unlocked camera visible, press **Shutter**, **Record** or **Stop** on the physical Studio deck. Unsupported modes, labels or layouts do nothing. Use the camera's own controls instead. MX Pocket never confirms a saved photo or a recording state. |
| Android Navigation | Home opens the system launcher. Back, scrolling, focus movement and activation work in the visible MX Pocket screen. Select **Navigation access** to read the disclosure and optionally enable the service in system settings. | Recents and navigation of other apps require that service. It uses the focused element or scrollable ancestors; when there is no focus it looks for a visible focus/scroll target, with a bounded search. Screens without suitable targets may reject actions. Password elements, Android settings and system permission surfaces are not navigation targets. |

The native camera owns its files, audio, recording lifecycle and permissions.
MX Pocket needs no camera, microphone or storage permission. Files from the old
in-app camera remain accessible under **Camera controls > Open previous Studio
captures** with a single-file read grant. Saved `studio.open` keys open the
native camera; old explicit capture/timer/zoom/focus/torch/marker bindings stay
unavailable, not silently remapped. Only the built-in Studio root page can send
camera clicks, never virtual keys, imported capture actions or workflows.

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
Keypad volume changes read back Android's music-stream level right away.
Phone-side volume notifications refresh the same readout; devices that omit
them fall back to a two-second check.

Only embedded bitmaps and bounded, read-granted `content://` images are used.
Titles and cover images are display-only, never written to files or diagnostic
logs. With Audio selected and media access granted, a connected, USB-authorized
keypad keeps showing new titles and covers while the handset is locked or off.
The app doesn't wake the handset or draw over its lock screen. Without that
keypad, lock hides playback details. Leaving Audio, losing the session or
revoking access clears them. One fresh press on the authorized keypad can change music
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

## Shared setup and access screens

The following Material 3 composables are public in
`tel.misa.mxpocket.shared.ui`. Hosts supply the state; rendering a screen
never requests a permission, persists a choice, or starts an external app.

```kotlin
SetupScreen(state: SetupScreenState, onEvent: (SetupEvent) -> Unit)
DeckAccessScreen(access: DeckAccess, message: String, onOpenSettings: (() -> Unit)?, onDone: () -> Unit)
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
