# Command schema contract

Selective port from charlesreid1/M0MA-V3SP3R at 62c189ca54a291ab3b35670b2fa79f236d2331f1 (AGPL-3.0). The generator describes only actions supported by this branch, not donor-only functionality.

Run `./gradlew :app:generateExecuteCommandSchema` after changing the enum or argument specification. `:app:verifyExecuteCommandSchema` checks serialized action coverage and semantic JSON drift; `:app:check` includes verification. When generate and verify are requested together, verification runs first so stale committed data cannot be silently fixed before the check.

The generated document is a description, never an execution or permission surface. Transport and approval policy remain enforced by the application.
