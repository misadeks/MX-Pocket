# Compiled-in Kotlin plugins

MX Pocket's extension point is a `PocketPlugin` compiled into the Android app.
It is not an external APK/plugin marketplace, downloaded code loader, WebHID
runtime or permission/security sandbox. Contributors add normal reviewed Kotlin
source and register it in `BuiltInPlugins.create()` through a PR.

## Small contract

The API lives in `app/src/main/kotlin/tel/misa/mxpocket/plugins/PocketPlugin.kt`.
It deliberately uses logical state rather than Bitmaps or USB reports:

| Member | Contract |
| --- | --- |
| `descriptor` | Stable lowercase ID, readable title, declared host capabilities |
| `onActivate(host)` | Acquire activation-scoped resources; launch contextual work in `host.scope` |
| `render(nowMillis)` | Pure, fast rendering of **exactly nine** `KeyTile` values |
| `onKey(event, host)` | LCD key index 0..8, press/release and synthetic release flag |
| `onDeactivate()` | Synchronous cleanup; scope is already cancelled and host effects disabled |

A `KeyTile` contains a label, footer and ARGB background color. The app draws
the key number, label and footer and overlays any user-selected background image.
The independent driver scales, JPEG-encodes and uploads it. Plugins must not
allocate/recycle host images or import `MxKeypad`/raw USB.

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
`pluginId=example.counter` and `index=0` through `8`. Select that profile and **Connect
virtual keypad** on Home. No USB protocol or Activity change is needed. The
example is compiled for tests but **not enabled in the default registry**.

To author another plugin, copy the example into a named feature package, change
its ID/title and implement its state, frame and input. Add a registry instance
and focused tests, then open a PR.

## Host actions and capabilities

`PluginHost` exposes `nowMillis()`, `invalidate()`, the activation's `scope`,
`audioSnapshot()` and `dispatch(PluginAction)`.

| Capability | Allowed host actions |
| --- | --- |
| `AUDIO` | Read music volume/max; Volume LOWER/RAISE/MUTE/UNMUTE; Media PLAY_PAUSE/PREVIOUS/NEXT |
| `OPEN_APPS` | Open supported PhoneApp CAMERA/SETTINGS/DIALER/WEB/ALARMS |
| `NAVIGATION` | Select another registered plugin by ID |
| `TONE` | Play the short completion tone |
| `STUDIO` | Foreground Pocket Studio capture and camera controls on an explicit user press |
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
camera and navigation state to the shell and profile editor; `DeckState.tile()`
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

Selection change, disconnect, leaving the foreground with the virtual keypad, or engine close:

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
phone. Only the built-in Phone page, with direct shortcuts explicitly enabled and
Android special access granted, can request an immediate launch on a fresh press.
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
frame. Reselect/reconnect/preview activation can retry. Cancellation is not
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
