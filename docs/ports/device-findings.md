# Typed device actions and findings

Selective AGPL-3.0 port from charlesreid1/M0MA-V3SP3R at 62c189ca54a291ab3b35670b2fa79f236d2331f1. Typed GPIO, NFC/RFID reads, RFID write, IR receive, app listing and system information use constrained templates and require USB. Findings submission, validation history, exact-rule classification and bounded filtered audit queries are available through execute_command; authoring helpers validate a conservative DuckyScript subset and produce diffs without execution.

Findings use a separate Room database `vesper_findings`, with an explicit additive v1→v2 migration preserving rows and adding validation notes. Existing chat/audit schema version remains unchanged. No destructive fallback is added. Full UUIDs avoid truncated-ID collisions; evidence and history are bounded. Classification is a heuristic and does not establish reproducibility.

Integration: merge transport BLE-is-RPC fix before enabling typed hardware commands to close transport-switch races. Apply the security encryption branch's EncryptedDatabaseFactory to the feature-owned DB before production release. Hardware USB command support and upgraded database behavior require device testing; this PR remains draft.

When combining with the generated-schema PR, update its explicit action/argument manifest and run `:app:generateExecuteCommandSchema` followed by `:app:verifyExecuteCommandSchema`. The build intentionally fails on unreviewed schema drift.
