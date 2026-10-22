# Phone BLE discovery and GATT

Selective AGPL-3.0 port from charlesreid1/M0MA-V3SP3R at 62c189ca54a291ab3b35670b2fa79f236d2331f1. Commands use the phone adapter and separate connections, independent of Flipper. Scan, enumerate, read, write, and notification collection are bounded. Writes and CCCD subscriptions always enter existing explicit approval even when HIGH auto-approval is enabled. Missing permissions fail without device work. Cancellation propagates and connections close; pending callbacks are cleared; ambiguous characteristic UUIDs fail closed.

Use `ble_scan_targets` (duration seconds, optional command=name filter), `ble_enumerate` (address), `ble_read_char` / `ble_write_char` / `ble_subscribe` (address and full uuid). Write content is hex by default; `hex=false` uses UTF-8. At most 512 bytes per write and 256 scan results/notifications.

Hardware validation required: Android 30/31/33 permission revocation, remote disconnect, descriptor failure, connection cancellation and target read/write/notification. PR remains draft until device checks are recorded.

When combining with the generated-schema PR, update its explicit action/argument manifest and run `:app:generateExecuteCommandSchema` followed by `:app:verifyExecuteCommandSchema`. The build intentionally fails on unreviewed schema drift.
