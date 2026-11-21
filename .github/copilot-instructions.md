# Copilot repository guidance

Use [AGENTS.md](../AGENTS.md) as the authoritative technical and workflow guide,
and [CONTRIBUTING.md](../CONTRIBUTING.md) for PR checks.

Keep `mx-driver` independent of the app. New app features use the compile-time
`PocketPlugin`/`PluginHost` API documented in `docs/plugins.md`; no raw USB in
plugins. Preserve cancellation, activation generation and nine-key frame
invariants. All changes use PRs; never push to main or merge automatically.

Do not flash firmware, publish device/private conversation data, or claim
physical USB/LCD verification based solely on automated tests.
