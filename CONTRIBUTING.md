# Contributing to MX Pocket

## Access and pull requests

This is a private repository. An owner must invite collaborators; having the URL
does not grant access. Do not make the repository public or publish private forks,
code, logs or screenshots without the owner's approval.

Code, documentation, dependency and workflow changes go through pull requests. Do not
push directly to `main`, merge your own changes automatically, bypass its PR rule
or enable auto-merge. No approval count is required for solo maintenance; a
maintainer still reviews and explicitly merges the PR.

For invited collaborators:

```powershell
gh repo clone misadeks/MX-Pocket
Set-Location MX-Pocket
git switch main
git pull --ff-only
git switch -c feature/short-description
```

If private forks are permitted and preferred, use a private fork with the same
feature-branch/PR workflow. If GitHub does not permit a private fork for your
access level, ask for collaborator access instead of copying code into a public
repository. Keep one focused behavior change per PR.

## Tooling and local checks

Use JDK 17 or 21, Android SDK platform 35, Build Tools 35.0.0 and the committed
Gradle 8.14.4 wrapper. Set `ANDROID_HOME` to your SDK. Android Studio may generate
`local.properties`; it must stay untracked. Do not commit SDK/JDK paths, keys,
APK/AAR outputs or device capture files.

Windows:

```powershell
.\gradlew.bat --no-daemon --console=plain :mx-driver:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :mx-driver:assembleRelease :app:lintDebug :mx-driver:lintDebug
```

Linux/macOS:

```sh
./gradlew --no-daemon --console=plain :mx-driver:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :mx-driver:assembleRelease :app:lintDebug :mx-driver:lintDebug
```

Run the smallest relevant test selector during development; run the full
Android build, tests and lint above before requesting review. Add focused
regression tests for new behavior. Do not weaken tests or silently default on
failed USB/plugin operations to make checks pass.

On Windows ARM64 use an x64 JDK for Robolectric; its native runtime and
Conscrypt do not support Windows ARM64. Pure Kotlin tests and Android compilation
can use ARM64 Java. See [Android installation](docs/workspace.md#install-the-android-app)
and the [hardware checklist](docs/workspace.md#hardware-acceptance-manual-not-established-by-jvm-tests) for USB checks.

The Android debug APK is `app/build/outputs/apk/debug/app-debug.apk`, signed
with the local debug key. For a reusable Android library, see the
[AAR guide](docs/driver.md). To test and package the standalone Windows
companion, run with an x64 JDK:

```powershell
.\gradlew.bat :shared-ui:desktopTest :desktop-preview:test :desktop-preview:packageUberJarForCurrentOS
```

Run `java -jar` with the path to the generated JAR in
`desktop-preview/build/compose/jars/`. Use a matching x64 JDK on Windows
ARM64. Running the packaged JAR does not require the Android SDK. It includes
Skiko and JNA native runtime support; native ARM64 Java is not a supported
Windows distribution. See [the Windows guide](docs/workspace.md#windows-companion).

## Phone UI layout and safe preview

Android and Windows render `PocketScreenState` using `PocketWorkspace` in the
[shared UI sources](shared-ui/src/commonMain). Taps emit typed `PocketScreenEvent`
values. Use `PocketTheme` for appearance and Compose semantics for UI tests.
Rendering does not activate plugins or execute actions. Keep the safe-area
insets, 48dp control/84dp key minimum heights, selectable diagnostics and live
status announcements. The [Windows host](desktop-preview) can run the Focus
timer, Tic Tac Toe and wired media controls, but does not call Android services.

The shared grid uses `shared-ui/src/commonMain/artwork/mx-keypad-housing.svg`,
exported from the project's `draw_keypad_hero.py` illustration without its LCD
content. Gradle rasterizes that SVG for both Compose targets; do not maintain
a separate Android drawable or desktop casing. Each of its nine openings shows
the complete corresponding 118 × 118 key image, including any artwork already
composited by the LCD renderer. Editor and focus outlines belong on the bezel.

`MainActivity` remains the live state/event adapter: it owns visible consent and
hands hardware sessions to `KeypadService`/`PocketSession`. Styling the phone
must not require changing USB reports, the plugin API or keypad LCD composition.

For a deterministic, **non-operational** screen preview, choose the debug variant
in Android Studio and run `tel.misa.mxpocket.ui.UiPreviewActivity` with an
explicit-Activity run configuration on an emulator. Alternatively, after
installing a debug build on an emulator:

```powershell
adb -e shell am start -n tel.misa.mxpocket/.ui.UiPreviewActivity --es scene disconnected
adb -e shell am start -n tel.misa.mxpocket/.ui.UiPreviewActivity --es scene audio
```

Scene IDs include `disconnected`, `focus`, `audio` and `phone`. Profile controls change
fixtures; keys are read-only and the connection button only displays a UI event. This host does not
construct the live Activity/engine, register USB receivers, activate plugins,
query USB/audio state or launch phone actions. It and its fixture data/manifest
entry exist only in the debug build, not release builds. Use the standalone
Windows simulator to exercise on-screen keys; Android accepts keypad input only from USB.

Use emulator light/dark mode, font scaling and screen sizes for visual checks.
Run `:app:testDebugUnitTest --tests "tel.misa.mxpocket.ui.*"` for render/event and
preview isolation tests, then the full local contributor command before a PR.
Preview fixtures are not evidence of a USB connection or hardware behavior.

## Boundaries and evidence

- `mx-driver` owns USB descriptors, interface claims, reports, JPEG encoding,
  HID++ exchanges and physical input decoding. It must remain usable without
  the app, plugin engine or Android Activity.
- The [app plugins](app/src/main/kotlin/tel/misa/mxpocket/plugins) own logical
  features. Use the host actions and nine-key
  frame API instead of importing the driver, accessing raw USB or duplicating
  Android action dispatch. See [plugin authoring](docs/plugins.md).
- `MainActivity` owns visible consent and phone UI. `KeypadService` owns the
  foreground hardware lifetime; `PocketSession` centralizes driver I/O, plugin
  actions and custom-image composition independent of any Activity. USB traffic stays off-main.
- Protocol edits require source-supported evidence and a boundary regression
  test. Preserve embedded license notices. Experimental notes/captured packets
  are not verified protocol facts.
- JVM/Robolectric tests cannot demonstrate phone-side kernel claims, visible
  LCDs, USB power compatibility or physical reconnect behavior. Record the
  hardware checklist items you actually exercised, or explicitly say untested.
- **Never flash or program firmware/hardware without explicit approval of that
  specific operation.** Normal keypad LCD/input commands are not firmware
  flashing. Do not substitute root, kernel edits or BLE work for USB testing.

Sanitize evidence before adding it to a PR: remove device serials, USB device
paths, usernames, private paths, exported logs, personal images, credentials and
conversation quotations. Prefer a small redacted descriptor table and exact
reproduction steps. Technical documentation is not a transcript.

## Submit a PR

Review the entire diff, verify the license/source notices for new code, and run
the checks. Then commit and push your feature branch:

```powershell
git add <specific-files>
git diff --cached --check
git diff --cached
git commit -m "Describe the focused change"
git push -u origin feature/short-description
gh pr create --repo misadeks/MX-Pocket --base main --title "Describe the change" --body-file <your-description-file>
```

Use the PR template, explain behavior and tradeoffs, list actual commands/results,
and distinguish automated from physical testing. There is no hosted CI. Do not
add workflows, replacement automation or required status checks as part of an
unrelated contribution.

## Merge policy

There are no hosted CI workflows or required automated status checks. Build,
tests and lint are local contributor checks.

GitHub main protection requires pull requests, including for administrators,
with zero required approvals and no status checks. Force pushes and branch
deletion are disabled. These server-side settings are separate from repository
files. Administrators should inspect Settings > Branches after changing account
plans or repository visibility; documentation does not enforce the rule.

After inspecting the PR and its local validation evidence, a maintainer may
explicitly merge it. Do not bypass the PR policy for a broken build: submit a
focused fix PR.

## Shared application UI

Make UI changes once in `shared-ui/src/commonMain`. Both entry points use the
production composables; do not add Swing/AWT widgets, native Android layouts,
platform-only Compose screens, or wrappers around an alternate toolkit.
Keep host permissions, camera/document intents, service lifetimes and LCD pixel
generation outside presentation. Pass typed display data and callbacks.

### Localized copy

Add app-owned text to the nearest `*Copy.kt` catalog in `shared-ui` (or
`DeviceCopy` for Android-only messages). A `Copy` contains English, French
and Serbian Latin together; call `.text()` when presenting it, not when
serializing a layout or registering a command. Use `{0}`, `{1}`, etc. for
arguments; pass user text as arguments, never as templates. Use `PluralCopy`
for counts (one/few/other). For system-facing Android strings update
`res/values`, `res/values-fr` and `res/values-b+sr+Latn` together.

Translate factory names only at display time, using their stable identity.
Never translate IDs, intent actions, accessibility matchers, external app
labels, artist or track metadata, or user-authored names and commands.
Exercise compact and wide constraints, keyboard input and screen-reader actions.
Run `gradlew.bat checkSharedUi` (`./gradlew checkSharedUi` on Unix); builds also
run the guard. `testSharedUiBoundary` checks sample violations and permitted
platform glue. Update those samples if a new legitimate platform API is needed.
