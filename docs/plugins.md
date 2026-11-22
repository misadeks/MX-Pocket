# Compiled-in Kotlin plugins

MX Pocket's extension point is a `PocketPlugin` compiled into the Android app.
It is not an external APK/plugin marketplace, downloaded code loader, WebHID
runtime or permission/security sandbox. Contributors add normal reviewed Kotlin
source and register it in `BuiltInPlugins.create()` through a PR.

Workspace identity is keyed by the stable ID in `PocketStyle`, never by the
translated or editable title. Focus is blue, Audio green, Phone violet,
Launcher amber, Studio pink, Navigation teal, Tic-tac-toe (`game.xo`) orange,
Teams indigo and OneNote orchid. **Work** is the root page of `teams`, not a
second plugin. Unknown IDs, including copied/custom profiles, use neutral slate.
These colors identify profiles and their keys on Home; they do not replace status
colors, drawing-tool swatches, user-selected key colors or LCD backgrounds.

Teams and OneNote use icons from the current Android profile's installed
packages or Windows' registered Start apps. Lookup is local and does not
launch either app or enable its integration. If lookup returns no icon, the
shared renderer uses this project's original conversation or spiral-notebook
outline. No Microsoft logo is bundled or downloaded.

## Small contract

The API lives in `app/src/main/kotlin/tel/misa/mxpocket/plugins/PocketPlugin.kt`.
It deliberately uses logical state rather than Bitmaps or USB reports:

| Member | Contract |
| --- | --- |
| `descriptor` | Stable lowercase ID, readable title, declared host capabilities |
| `onActivate(host)` | Acquire activation-scoped resources; launch contextual work in `host.scope` |
| `render(nowMillis)` | Pure, fast rendering of **exactly nine** `KeyTile` values |
| `onKey(event, host)` | LCD key index 0..8, press/release and synthetic release flag |
| `descriptor.chords` / `onChord(chord, host)` | Optional physical LCD pairs, declared at registration; one callback replaces both singles |
| `onDeactivate()` | Synchronous cleanup; scope is already cancelled and host effects disabled |

A `KeyTile` contains a label, footer and ARGB background color. Ordinary keys
keep their key number unless an icon is supplied. The built-in Audio page uses
monochrome player symbols instead. Labels remain available to accessibility
even on icon-only controls. Read-only tiles display state without dispatching input.
Home mirrors the LCDs, including user backgrounds, without dispatching input.
The Android Profiles editor shows saved bindings rather than transient plugin state;
selecting a key edits it without dispatching input. With a physical keypad, Home mirrors
the live LCD images as a display only, rather than a second set of tappable
keys. The independent
driver scales, JPEG-encodes and uploads images. Plugins must not
allocate/recycle host images or import `MxKeypad`/raw USB.

Resolve built-in face text from `KeypadCopy` when rendering, not when registering
the plugin. For a deck command, `displayLabel` is presentation text; dispatch
the command by its ID. Never use translated text as a glyph selector, input
token, or saved binding. Keep app names, track titles and user-authored labels
as supplied, even if they happen to match a built-in English label.

For a plugin with internal navigation, set `PluginFrame.page` to the current
page index (default `0`). A change of page requests a whole-grid redraw even
if the new page happens to reuse most or all of the same tiles.

`PluginEngine` validates nonempty/unique registration, frame size and navigation
targets. Identifiers match `[a-z][a-z0-9.-]{2,63}`. Use an ID such as `weather.local`;
do not reuse or rename a published ID casually. The saved layout determines
page-button navigation; registration alone does not create a layout.

## Register the working example

`plugins/examples/CounterPlugin.kt` is a minimal functional plugin. It displays
nine counters, increments on presses, changes a held key's background color,
shows active elapsed seconds, and clears held state on release/deactivation.
Its ticking coroutine is cancelled by the engine.

Add the import and instance to `BuiltInPlugins.kt`:

Import `tel.misa.mxpocket.plugins.examples.CounterPlugin` and append
`CounterPlugin()` to the list returned by `BuiltInPlugins.create()`.

To exercise it, include a layout whose nine actions are `plugin.key` with
`pluginId=example.counter` and `index=0` through `8`. Select that profile, connect a wired
keypad on Home and press a key. No USB protocol or Activity change is needed. The
example is compiled for tests but **not enabled in the default registry**.

### Bind physical LCD keys 1 + 9

The optional Counter plugin binds those two keys to **reset all counters**. Declare
an unordered pair using its two **logical plugin indices** in ascending order:

```kotlin
private val reset = KeyChord(0, 8)
override val descriptor = PluginDescriptor("example.counter", "Counter", chords = setOf(reset))

override fun onChord(chord: KeyChord, host: PluginHost) {
    if (chord == reset) counts.fill(0)
}
```

Register Counter as above and use a page where physical LCD keys 1 and 9 have
`plugin.key` bindings to `example.counter` indices `0` and `8`. Other page
positions can bind those slots instead. On a connected keypad, press either
key and then the other while the first is still down. Tapping the keys on the
phone still increments individual counters; phone input never joins a USB pair.
There is no factory shortcut or change to the layout JSON.

- Only an enabled, acknowledged slot with an available, enabled, acknowledged
  partner waits for a chord. Singles without a Hold binding otherwise remain
  immediate. The window is **150 ms** from
  arrival of the first down; both downs must be processed before it expires.
- Without a Hold binding, an early release delivers the ordinary down then up. Timeout commits the
  single down and its eventual release goes to the same slot. A nonmatching
  eligible down commits the older single first, then starts its own decision.
- Pairs only: the first matching second down wins, even when pairs overlap.
  The driver orders simultaneous new downs by LCD number. Both member presses
  and their releases are consumed; neither member can act as a modifier again
  until it has been released and pressed anew. Extra keys are processed normally.
  Repeated reports/down events do not retrigger anything.
- Pending input is discarded on synthetic cleanup, layout edits or activation
  changes, disconnect and lock/screen transitions. Both keys' original
  binding/ACK, session, unlocked state and USB grant must still be valid.
  A redraw of an unrelated single key does not invalidate the pair. Repainting
  either member requires a fresh pair of presses; a late ACK never replays a held key.
  A chord or buffered single can update plugin state and use ordinary host
  capabilities, but does **not** convey direct-launch, capture or workflow
  authority. Keep actions requiring a fresh physical press on unchorded slots.

Layout authors can assign one optional **Hold** independently of the Tap
binding. Chords match the original Tap slots, never the Hold slots. If no
chord wins, Android's system long-press timeout is measured from the original
USB down, not from the end of the chord window. A selected `plugin.key` gets
one down on selection and the matching up on release (immediately for a tap).
Neither deferred choice is a fresh physical-down grant for launches or capture.
The Android Home mirror and editor selection have no keypad input authority.

To author another plugin, copy the example into a named feature package, change
its ID/title and implement its state, frame and input. Add a registry instance
and focused tests, then open a PR.

## Host actions and capabilities

`PluginHost` exposes `nowMillis()`, `invalidate()`, the activation's `scope`,
`audioSnapshot()` and `dispatch(PluginAction)`.

| Capability | Allowed host actions |
| --- | --- |
| `AUDIO` | Read music volume/max; Volume LOWER/RAISE/MUTE/UNMUTE; Media PLAY_PAUSE/PREVIOUS/NEXT |
| `OPEN_APPS` | Open supported PhoneApp CAMERA/SETTINGS/DIALER/WEB/ALARMS/ONENOTE/ACCESSIBILITY; `OpenUri` for external URI schemes such as `onenote:` deep links; `ShareText` (ACTION_SEND text/plain) for share targets such as OneNote's quick note |
| `ACCESSIBILITY` | `AccessibilityClick` taps a visible control by text through the user-enabled, OneNote-scoped `OneNoteAccessService` |
| `NAVIGATION` | Select another registered plugin by ID |
| `TONE` | Play the short completion tone |
| `STUDIO` | Open the default photo/video viewfinder. Only the physical Studio deck may request a matching camera button with separate consent. |
| `SYSTEM_NAVIGATION` | Basic local navigation; global/element actions only through the user-enabled service |
| None | Repaint current frame and invalidate changed state |

For example:

```kotlin
override val descriptor =
    PluginDescriptor("media.custom", "Media", setOf(PluginCapability.AUDIO))

override fun onKey(event: PluginKeyEvent, host: PluginHost) {
    if (!event.pressed || event.synthetic) return
    host.dispatch(PluginAction.Media(MediaCommand.PLAY_PAUSE))
    host.invalidate()
}
```

Capabilities validate the supported host API; they do not grant Android
permissions or isolate trusted compiled-in code. New Android privileges or
actions require an explicit host/API/manifest change and review, not direct
Context/USB access from a plugin. The adapter owns Intent handling, media keys,
audio stream behavior and tone resource cleanup in one place.

For the Android decks use `PluginAction.Deck(DeckCommand, component?)`.
Each command declares its fixed namespace and belongs to one capability.
`PocketSession.deckState` exposes immutable, observable selection, media,
camera-control opt-in and navigation access to the shell and profile editor; `DeckState.tile()`
uses the same data for saved bindings and built-in pages. `KeyTile.iconComponent`
names an app icon, not a path or URI. `inputToken` identifies the logical binding
when state or a favorites page changes. See [deck usage](android-decks.md) for
action persistence, authority, media access and device checks.

## Lifecycle, concurrency and input ownership

The engine and callbacks are confined to the UI dispatcher. Use `host.scope` for
activation work. For blocking external work, use `withContext(Dispatchers.IO)`
inside that scope, then publish state/invalidate back on the owner dispatcher.
Do not use `GlobalScope`, unowned threads, an application-wide static scope or a
background service in a plugin.

Selection change, disconnect, or engine close:

1. Invalidates the old activation generation and disables its host effects.
2. Cancels its child scope.
3. Sends synthetic releases for keys it owns.
4. Calls `onDeactivate()`.

The coroutine cancellation signal does not synchronously join arbitrary plugin
work. Cooperative cancellation and `finally` remain the plugin author's
responsibility. Even if a coroutine ignores cancellation, its old host cannot
dispatch actions or invalidate the next plugin. Do not mutate shared/global
state from a cancelled task. `onDeactivate()` must not block, throw or attempt
new actions; release listeners/resources there or in coroutine `finally`.

The engine suppresses duplicate presses and releases not owned by the current
activation. Physical page buttons are reserved for layout navigation and are
not delivered as plugin LCD keys. A release from a key held on the departing page
must not be interpreted as input on its replacement.

Plugin state objects remain in memory across switches; activation-scoped tasks
do not. The Focus timer retains its monotonic deadline, but completion alerts
wait until Focus is selected again. A physical session keeps its active plugin
and LCD painter across Activity recreation or backgrounding; the app's
connected-device foreground service, not the plugin, owns that lifetime.
Transient plugin state may be lost on process death; saved layouts and workflow
definitions are independent of the activation. A background `OpenApp` dispatched
synchronously for a real LCD key press offers a one-tap notification for that
app. The tap requires a current request, USB grant and plugin page on an unlocked
phone. With direct shortcuts explicitly enabled and Android special access
granted, a fresh physical press can request a legacy Phone shortcut, a saved
favorite's launcher activity, or a camera viewfinder (Phone: Camera, Pocket
Studio, or the default camera favorite). Opening a viewfinder does not authorize capture.
Plugin timers, releases and synthetic events cannot issue a background shortcut
or dismiss the lock screen.

USB upload is not a plugin task. On a switch the app lets the one in-flight JPEG
transaction finish so it does not corrupt the report stream, skips any remaining
old-generation keys, then uploads the latest frame. Invalidations are coalesced
instead of queuing unbounded redraws. Disconnect still cancels the connection.
ACK acceptance is distinct from physical LCD visibility.

## Errors and validation

Synchronous callback/render failures and asynchronous scoped-task failures are
attributed to the plugin ID, surfaced in the app's log/status, and stop its
activation. The UI explicitly renders `Plugin error`, not a fabricated success
frame. Reselecting or reconnecting can retry. Cancellation is not
silently converted into successful work.

Tests:

```powershell
.\gradlew.bat --no-daemon --console=plain :app:testDebugUnitTest --tests "tel.misa.mxpocket.plugins.*"
```

Add cases for your feature's state transitions, physical press/release semantics,
rendered frame, capabilities, cancellation and cleanup. `PluginEngineTest`
exercises stale callbacks, synthetic releases, failure attribution and the
Counter example. `BuiltInPluginsTest` verifies migrated Focus/Audio/Phone
controls. Activity tests cover Android API 28 and 35.

Before review, run the full **local** contributor build/test/lint command. On
hardware verify frame visibility on all nine regions, input identities,
selection updates, detach/reconnect and no stale effects after changing plugins.
Local JVM/Robolectric tests do not prove those physical properties.
