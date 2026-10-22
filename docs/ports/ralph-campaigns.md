# Experimental scoped campaigns

Selective redesign inspired by charlesreid1/M0MA-V3SP3R at 62c189ca54a291ab3b35670b2fa79f236d2331f1 (AGPL-3.0). WorkManager advances bounded phases of explicitly authored commands. Enable and open the inbox from Settings → Experimental campaigns. Disabled by default. A stop-all switch disables scheduling and cancels work.

The initial supported vocabulary is device/storage information plus scoped directory listing, file reading/writing, and directory creation. Raw CLI, nested runbooks and unbounded autonomous exploitation are rejected. Plans have exact /ext directory scope, at most five phases, twenty steps per phase, five minute execution timeout and 24-hour campaign expiry. Write/create commands always request explicit existing executor approval regardless of auto-approve settings.

The durable inbox displays exact commands, scope, expiry and terminal state. Approval notifications are optional when Android notification permission is absent. Expired or process-lost executor approvals fail closed. Commands are claimed durably before execution; process death leaves an uncertain execution state requiring manual verification rather than replay.

Campaign data uses a separate SQLite schema and explicit additive v1→v2 migration. Existing audit/chat records are untouched. Apply encrypted persistence integration before production release. Hardware validation still required for background execution, process death during side effects, notification permission, approval expiry, and cancellation while a device call is in progress. This is a draft experimental feature, not a production autonomous executor.

When combining with the generated-schema PR, update its explicit action/argument manifest and run `:app:generateExecuteCommandSchema` followed by `:app:verifyExecuteCommandSchema`. The build intentionally fails on unreviewed schema drift.

Kill-switch timing: a command already claimed or sent to the device can finish after stop. Disabled state and durable cancellation prevent subsequent commands from running; verify the device state after stopping an in-flight side effect.
