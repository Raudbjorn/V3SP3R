# Typed device actions and findings

Selective AGPL-3.0 port from charlesreid1/M0MA-V3SP3R at 62c189ca54a291ab3b35670b2fa79f236d2331f1. Typed GPIO, NFC/RFID reads, RFID write, IR receive, app listing and system information use constrained templates in the explicit USB CLI connection mode. Hardware/firmware validation remains pending; normal USB RPC sessions reject these typed wrappers. Findings submission, validation history, exact-rule classification and bounded filtered audit queries are available through execute_command; authoring helpers validate a conservative DuckyScript subset and produce diffs without execution.

Findings use a separate Room database `vesper_findings`, with explicit additive v1→v2→v3 migrations preserving rows and adding validation notes and session ownership. Existing chat/audit schema version remains unchanged. No destructive fallback is added. Full UUIDs avoid truncated-ID collisions; evidence and history are bounded. Classification is a heuristic and does not establish reproducibility.

Typed writes use a dedicated USB-only sink, including during transport switches, and send exactly once without recovery/newline retries. The general transport retains the existing BLE/RPC guards. The feature-owned database now uses EncryptedDatabaseFactory for encrypted storage and preserving plaintext conversion. Hardware USB command support and upgraded database behavior require device testing; this PR remains draft.

When changing an action or argument, update the explicit generator manifest and run `:app:generateExecuteCommandSchema` followed by `:app:verifyExecuteCommandSchema`. The build intentionally fails on unreviewed schema drift.

## Explicit USB connection mode

The Device screen defaults to **USB RPC** for existing screen/file tools. To use
typed CLI actions, disconnect first, enable **USB CLI mode for typed commands**,
then choose **Connect via USB CLI**. If cable attachment already auto-connected
RPC, disconnect that session first; the selector never transitions a live session.
A new serial connection skips RPC priming and RPC baud probes, sends exactly one
read-only `device_info`, and accepts only a bounded response with hardware and
firmware identity fields. Echo, errors, binary data, oversized data, or timeout
leave the connection unavailable and instruct the user to reconnect.

CLI mode centrally refuses all general/RPC transport writes; only the dedicated
typed USB sink can write after the handshake. The mode and port identity are
checked again inside the writer lock, so transport changes cannot redirect a
pending command. Device info auto-refresh is skipped in CLI mode. Reconnect in
RPC mode for file/screen features. Failed firmware replies are errors, and an
echo/prompt alone is an unconfirmed outcome. No side-effecting command retries.

The shared collector retains the screen-routing guard before any send. Protocol
incoming-data handling retains the existing atomic connection epoch, bounded
ingress and single collector. USB mode is captured in the permission request's
immutable ticket and recovered by the queued foreground-start intent; bound
preparation never opens a port. Ticket ownership protects handshake publication
and error cleanup. Reader forwarding/failure handling checks the captured port.

Both typed commands and loader list/close/signal require **explicitly selected
USB CLI plus observed handshake confirmation**. A historical CLI capability alone
does not authorize either sink. This deliberately tightens the earlier loader
path; reconnect and select CLI before using it. RPC remains the default.

Findings and model-accessible audit queries are scoped to the calling chat
session. Finding IDs do not grant access across sessions; validation and
classification reject another session's records. Additive v2→v3 migration
preserves existing rows with an empty legacy scope; those rows are not exposed
to model sessions or reassigned automatically. Export/administrative recovery
of legacy rows requires an explicit owner workflow. The store refuses new
submissions at 500 retained records and caps each validation-history JSON at
64 KiB; it never deletes old evidence to make room. Target filtering uses
Kotlin Unicode case-insensitive matching over this bounded store.

Submission, validation and persisted classification request explicit approval,
even with medium auto-approval enabled. Classification without a finding ID is
read-only. An ID-targeted classification must match that finding's stored type.
