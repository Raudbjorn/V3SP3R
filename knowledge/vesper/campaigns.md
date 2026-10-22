# Campaigns — planned functionality

Autonomous campaigns are **not implemented in this standalone corpus
branch or its Android baseline**. There is no Labs → Campaigns screen,
Ralph enable switch, background phase worker, or campaign approval inbox
in that baseline. This document records a proposed workflow; it is not
an operating guide or a claim that its safeguards currently exist.

A separate campaign feature PR may add some of these capabilities.
Check the code and release notes of the version you actually install;
merging this knowledge server does not enable campaign execution.

## Proposed workflow

The design proposes a named assessment with explicit in-scope targets,
exclusions, and five phases: Recon, Research, Enumerate, Exploit
(separately approved active testing), and Report. Proposed state persistence would
support pausing and resuming while linking findings to real audit entries.
These are design goals, not verified behavior in the current baseline.

## Safeguards required before implementation can be used

- Validate target scope in executable code before each operation.
- Require current approval for each action that needs it; an earlier
  phase approval must not authorize later high-risk actions wholesale.
- Define bounded iteration/time budgets and clear pause/stop behavior,
  including what happens to an operation already in flight.
- Persist and reconcile phase state and audit evidence after interruption.
- Distinguish passive observation from actions that transmit, connect,
  modify, emulate, or otherwise affect a target.

No background worker, scope refusal, risk override, notification, report
location, or stop guarantee should be inferred from this proposal.
Use the installed app's existing interactive controls and inspect each
operation under the authorization applicable to that target.

## See also

- [[`vesper/labs.md`](labs.md)](labs.md) — existing Alchemy and Payload Lab surfaces.
- [[`vesper/architecture.md`](architecture.md)](architecture.md) — current command and data flow.
- [`SECURITY.md`](../../SECURITY.md) — reporting vulnerabilities.
