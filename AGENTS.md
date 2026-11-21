# Agent instructions

## Change workflow

- Read README.md, CONTRIBUTING.md and docs/plugins.md before changing feature
  architecture. All changes after the bootstrap baseline must use a feature
  branch and a pull request. Never push directly to main, bypass branch rules,
  merge, or enable auto-merge without an explicit request.
- Validation is local only. Do not add hosted CI/workflows, automated runs or
  required status checks.
- Work in the existing Kotlin/Android modules and preserve
  `tel.misa.mxpocket`. Avoid unrelated fixes, frameworks and speculative protocol
  changes. Add only abstractions exercised by a real plugin/use case.
- Never flash/program firmware or hardware unless the user explicitly approves
  that specific operation. Ordinary intended LCD/input operations are allowed
  only within the task's device-access scope.
- Never publish personal comments, copied prompts, device serials, local paths,
  logs, secrets, signing keys or private images. Review staged text and binary
  provenance before push. Ignore build outputs and local.properties.

## Code boundaries

- `mx-driver`: decoupled USB Host library; no app/plugin imports. Discover USB
  interfaces, HID report lengths and endpoints from descriptors. Do not equate
  HID collections with interface numbers or use bulkTransfer on interrupt
  endpoints. Keep claims/control transfers/UsbRequest queueing off the main
  thread; close on cancellation/detach and surface failures.
- `app/plugins`: compile-time Kotlin plugins. `PocketPlugin` returns exactly
  nine immutable KeyTiles; `PluginHost` mediates Android actions. Stable IDs are
  unique. Capabilities are API validation, not a sandbox.
- `PluginEngine`: UI-thread-confined activation, coroutine scope ownership,
  held-key cleanup, generation guards and error reporting. Use activation-owned
  scopes; no GlobalScope, raw threads or unowned services in plugins.
- All app-owned screens, dialogs, selectors and keypad presentation **must**
  be Kotlin Compose Multiplatform in `shared-ui/src/commonMain`. Android and
  Windows host adapters provide data and receive typed callbacks; never add
  a native View/XML, Swing, web or platform-only Compose implementation.
  System permissions, camera, document pickers, window hosting and offscreen
  LCD bitmap rendering are platform work, not alternate app UI.
  Use window constraints, not platform identity, for compact and wide layouts.
  Keep key execution separate from editing, resize and preview fixtures.
  Preserve accessible insets, touch targets and selectable diagnostics.
- `MainActivity`: visible USB/notification permission and phone UI.
  `KeypadService` gates the hardware lifetime; `PocketSession` owns input,
  plugin actions and LCDs independently of Activity recreation. Preserve the
  opt-in attach policy, user-image composition and generation checks.
  Page switches must not cancel a partly acknowledged image transaction; let
  the current image complete, then paint only the newest generation.
- Preserve source provenance in
  `mx-driver/src/main/assets/mx-pocket-provenance.txt`. The supported LCD profile
  is one 480x480 panel with nine 118x118 visible regions. Geometry/VLP index are
  source-supported static values, not queried hardware capabilities.
- Missing ACKs, busy responses, cancellation and cleanup must not produce
  success-shaped fallbacks. Do not replay speculative ownership/heartbeat or
  firmware packets from experimental notes.

## Validation

Use the committed Gradle wrapper, JDK 17/21, SDK 35 and Build Tools 35.0.0.
Windows ARM64 full tests need x64 Java for Robolectric. Run focused tests while
iterating, then the contributor check before PR:

```text
gradlew.bat --no-daemon --console=plain :mx-driver:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :mx-driver:assembleRelease :app:lintDebug :mx-driver:lintDebug
```

On Linux/macOS use `./gradlew` with the same arguments. Add a few regression
cases for introduced behavior, especially fragmented report boundaries,
multi-key transitions, stale scopes/events and plugin errors.

Report exactly what passed and what is physically untested. Enumeration,
installation, simulated Android tests and HID++ ACKs do not prove visible LCD
content, persistent ownership, all eleven physical controls or phone power
compatibility. Do not infer full verification from informal feedback.
