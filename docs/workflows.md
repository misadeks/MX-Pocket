# Workflows

The Workflows screen builds an ordered sequence from local actions. Nothing runs
when the editor opens. **Test run** executes the current draft for real without
saving it. **Save workflow** validates and stores it on this phone. Duplicate
makes a new editable copy with a separate ID; it does not copy a schedule.

## Try the keypad check

1. In Workflows, edit **Keypad check**. Expand its first step. The two branches
   put “Connected” or “Virtual keypad” on key 1 according to actual device state.
2. Duplicate the workflow, change the wait to five seconds, then save. Use
   **Move up** / **Move down** to change the order inside a sequence or branch.
3. Test it without USB: the virtual branch runs, the phone reports its current
   step, a short tone plays, and key 1 changes to “Ready”. Try **Cancel run**
   during the wait. Cancel stops later steps; it does not undo earlier actions.
4. In the integrated profile editor, assign the saved workflow to another key.
   Tap the assigned virtual key to run it through the same host. With an
   authorized keypad connected, press that physical key and compare its run
   indicator with the phone. Inspect **Run history** for origin, step and outcome.
   The real LCD, physical presses and phone power behavior require a hardware check.

## Triggers, conditions and effects

Manual runs and test runs are always available. Physical-only and virtual-only
triggers filter which assigned key can start a run. Daily schedules use the local
clock and Android WorkManager, not an exact alarm; Android may defer them. Missed
days are not replayed. Connection/disconnection and app-context triggers listen
for transitions while the app’s session is alive, not initial snapshots. A run
does not retrigger itself through its own context changes. One run owns the host
at a time. A second attempted run reports busy rather than interrupting it.

**If / else** compares values (equals/not equals). Use a variable, plugin state
key, connection state, app context, boolean or the preceding action’s outcome.
Missing state is unknown, not a match. **On error → Continue** lets a later
branch handle an unavailable action or request; history still records the failure.
Child workflows share their caller's variables, cancellation and deadline.
Action availability and field choices come from the same app host as the keypad.
Navigation and application launch are not privileged automation: they require
the installed app capabilities and applicable user confirmation. A workflow
does not grant permissions.

Web links open only after confirmation in the foreground editor. Notification
steps require a usable notification channel/permission; speech needs an Android
TTS engine. Keep the Workflows screen open when a run needs confirmation or open
it from the keypad's waiting status; unattended runs cannot approve themselves.

## Endpoints and limits

Approve each exact HTTPS address under **Workflows → Endpoints** before adding a
request step. Choose GET or empty-body POST. No templates, request credentials,
query strings, redirects, response parsing, downloads or script execution.
GET may retry transport/5xx failures at most twice. POST requires confirmation
and is never replayed on a missing response. Revoking an endpoint cancels the
current run and prevents future requests to it. Do not put secrets in workflow
names, labels or endpoint addresses. History stores outcomes, not payloads,
response bodies, URLs or raw exception messages.

The local, atomic `workflow-library.json` has schema version `1`. Unknown versions
are not overwritten. Limits: 50 workflows, 32 endpoints, 64 expanded steps,
four branch/call levels, 120 seconds per wait and 180 seconds per run. Recursion
is rejected before side effects. Run history retains the last 30 outcomes.
Notification-listener events and external integrations are not part of this system.
