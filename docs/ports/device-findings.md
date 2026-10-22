# Typed device actions and findings

Selective AGPL-3.0 port from charlesreid1/M0MA-V3SP3R at 62c189ca54a291ab3b35670b2fa79f236d2331f1. Typed GPIO, NFC/RFID reads, RFID write, IR receive, app listing and system information use constrained templates in the explicit USB CLI connection mode. Hardware/firmware validation remains pending; normal USB RPC sessions reject these typed wrappers. Findings submission, validation history, exact-rule classification and bounded filtered audit queries are available through execute_command; authoring helpers validate a conservative DuckyScript subset and produce diffs without execution.

Findings use a separate Room database `vesper_findings`, with an explicit additive v1→v2 migration preserving rows and adding validation notes. Existing chat/audit schema version remains unchanged. No destructive fallback is added. Full UUIDs avoid truncated-ID collisions; evidence and history are bounded. Classification is a heuristic and does not establish reproducibility.

Typed writes use a dedicated USB-only sink, including during transport switches, and send exactly once without recovery/newline retries. The broader legacy execute_cli transport is addressed separately by T2. Apply the security encryption branch's EncryptedDatabaseFactory to the feature-owned DB before production release. Hardware USB command support and upgraded database behavior require device testing; this PR remains draft.

When combining with the generated-schema PR, update its explicit action/argument manifest and run `:app:generateExecuteCommandSchema` followed by `:app:verifyExecuteCommandSchema`. The build intentionally fails on unreviewed schema drift.

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

When merging S1 (`codex/live-screen-mirror`), preserve its
`if (screenFrameRouting) return ""` guard at the start of
`collectRawCliResponseAttempt`, before either USB-only or general transport writes.
That shared collector guard also protects this typed sink during active mirroring
or an uncertain stream stop; do not resolve the signature merge by dropping it.

When combining S2, consolidate the incoming-data generation check with S2's
atomic `connectionEpoch`; preserve the stale queued-payload rejection instead of
maintaining independent counters. Fresh USB mode selection always resets protocol
readiness. Connection ownership tokens also reject stale handshake publication,
reader data, and reader failures after disconnect or a competing connection.
