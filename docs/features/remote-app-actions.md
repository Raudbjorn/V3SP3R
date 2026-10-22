# Remote input and loader actions

Selective port from bob-reis/V3SP3R at `c11fe9beb1cb9b0ff5f40fa48d911273cb089fc8`.

The command model/tool schema supports `input_send` (`key`, optional `press_type`), `loader_list`, `loader_close`, and `loader_signal` (`signal_id`, optional `content`). Existing `launch_app` uses the RPC application bridge where available, quotes names containing spaces, and preserves empty arguments so an interactive app is not forced into headless RPC mode.

Input uses GUI RPC and never retries an uncertain result through CLI. Input and signals require high-risk approval because the foreground action can transmit or modify data; close requires approval because it interrupts the foreground app. List is read-only. This intentionally preserves a stronger safety boundary than the donor's low-risk input rule.

Loader list/close/signal have no proven generic RPC equivalent and require both an established USB serial connection and confirmed CLI capability. BLE RPC receives an explicit unsupported result. Firmware CLI errors remain errors rather than a claimed success.

Tests cover risk policy, invalid input, uncertain input without duplicate CLI fallback, loader argument rejection and unsupported transport. Physical firmware testing is still required for USB loader commands and GUI response timing.

Loader operations use exactly one CRLF-terminated command through a USB-only sink.
Echo-only or missing output is reported as an unconfirmed outcome, with no newline,
variant, session-recovery, or BLE fallback retry. Dispatch requires confirmed
CLI-only capability; USB port identity is checked before each write chunk. The
checked-in execute-command JSON schema includes all four actions and arguments.

Merge #20 first: its acknowledged GUI sequence and bounded release cleanup cover
the shared input implementation used by this feature. When merging typed USB
operations, keep their explicit CLI session-mode check in `sendUsbDataOnly` and the
screen router's lower collector guard before both USB and generic write sinks.
