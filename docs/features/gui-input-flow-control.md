# GUI input and serial flow control

Selective port from ahuray/V3SP3R at `101dc4fa1cdf21d1b0624d911caf600fc673d58d`.

Remote button entrypoints share the command mutex. Short/long/repeat are sent as acknowledged press/action/release sequences, without automatic RPC retry of a non-idempotent input. Uncertain press delivery triggers bounded non-cancellable release cleanup. Explicit successful press remains held until an explicit release. Desktop lock replies accept firmware's empty OK=locked / ERROR=unlocked encoding and status payloads; Back is suppressed immediately after successful unlock so it cannot relock the desktop.

The flow-control characteristic remains big endian. Invalid lengths, negative values and implausible capacities are rejected; no alternate-endianness guess is made. Small control writes use a short bounded capacity wait instead of bypassing known zero credits. This intentionally differs from the donor's blanket small-write bypass.

Tests cover sequence order, lock interpretation, Back behavior and capacity validation. Actual firmware acknowledgement timings, cancellation release and overflow recovery require physical BLE/USB validation.

Release is idempotent and may restart a lost RPC session; press and action remain
non-retrying. Cleanup has an outer five-second deadline. A successful unlock protects
subsequent Back input for three seconds. Explicit unsupported lock probes are cached,
while missing replies and transient busy status do not erase support knowledge.
Unused immediate sender implementations were removed; the shared firmware readiness
diagnostic remains for the transport routing feature.

The non-retry policy reaches the BLE writer: press/action use one write attempt and
one characteristic write type, so a missing GATT acknowledgement cannot cause an
uncertain input packet to be retransmitted. Release retains recovery retries.
Capacity wait duration is chosen from the original RPC payload size before MTU
fragmentation; each fragment still consumes its own byte credits. Bulk transfers
therefore retain the normal wait even at the default 23-byte MTU or on a short tail.
