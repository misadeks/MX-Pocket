# Contributing to MX Pocket

## Access and pull requests

This is a private repository. An owner must invite collaborators; having the URL
does not grant access. Do not make the repository public or publish private forks,
code, logs or screenshots without the owner's approval.

The initial MVP is the bootstrap baseline on `main`. All subsequent code,
documentation, dependency and workflow changes go through pull requests. Do not
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

On Windows ARM64 use an x64 JDK for Robolectric; its native runtime and
Conscrypt do not support Windows ARM64. Pure Kotlin tests and Android compilation
can use ARM64 Java. Follow [README](README.md) for installation and USB checks.

Run the smallest relevant test selector during development; run the complete
command above before requesting review. Add focused regression tests for new
behavior and preserve existing tests. Do not weaken tests or silently default on
failed USB/plugin operations to make checks pass.

## Boundaries and evidence

- `mx-driver` owns USB descriptors, interface claims, reports, JPEG encoding,
  HID++ exchanges and physical input decoding. It must remain usable without
  the app, plugin engine or Android Activity.
- `app/.../plugins` owns logical features. Use the host actions and nine-key
  frame API instead of importing the driver, accessing raw USB or duplicating
  Android action dispatch. See [plugin authoring](docs/plugins.md).
- `MainActivity` owns permission/lifecycle, Android action execution, UI,
  custom-image composition and driver connections. USB traffic stays off-main.
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
