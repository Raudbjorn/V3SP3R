# RPC screen and command demultiplexing

Selective port from ahuray/V3SP3R at `101dc4fa1cdf21d1b0624d911caf600fc673d58d`.

`setScreenFrameRouting(true)` enables routing for a screen-stream session. `rpcScreenFrames` publishes unsolicited (`command_id = 0`) screen messages; complete solicited frames retain their length prefixes and go to the active command collector. Partial varints/bodies remain buffered until complete. Malformed/overflowing lengths cannot wrap into valid sizes. Reconnection disables routing and discards queued chunks from earlier connection epochs.

Ingress is serialized to preserve notification ordering. Collectors fail explicitly at their byte limits rather than silently retaining unbounded output. While routing is enabled, screen notifications never advance command quiet timers. Keep routing enabled after an uncertain stop so unsolicited notifications cannot poison command responses; an acknowledged stop or connection reset restores normal collection.

Tests exercise interleaving, every byte split, solicited screen replies, and malformed lengths. No physical device was available to verify notification timing or large storage transfers.

Binary command responses are bounded at 16 MiB, separately from the 256 KiB per-frame and CLI limits. This permits multi-frame storage reads of catalog apps without truncating verified readback.

Ingress retains at most 64 notifications of at most 64 KiB each. Overload or corrupt
recovery beyond 2 KiB fails the current generation and commands return an explicit
error until connection reset. Clean fragmented frames retain their 256 KiB allowance.
Reset, routing-mode transitions, and parser mutations share one lock. Collector
failures become result-based command errors; CLI capacity counts UTF-8 input bytes.
A confirmed RPC session stop restores normal CLI collection as well as stopping routing.

Verified binary readback accepts an optional expected size through overloads of
`readFileBytes` and `readFileBinary`; existing one-argument APIs remain available.
The explicit hint accepts 0–10 MiB and requires complete RPC storage output of that
exact length, with no legacy/text CLI fallback on failure. The allowance is four
seconds plus the expected bytes at 8 KiB/second, capped at 25 minutes (a 10 MiB
read receives 21 minutes 24 seconds). This is a conservative bounded policy, not a
hardware throughput guarantee; caller cancellation remains effective.

A single binary collector spans the write and all response continuations, preserving
fragmented envelopes over quiet gaps. One deadline covers the operation; an unfinished
`has_next` response fails instead of returning partial bytes. The 16 MiB aggregate
cap still applies. Queue acquisition remains bounded independently of transfer time,
using the acquisition-only mutex prerequisite shared with PR29.

For the catalog installer integration, pass `binary.bytes.size.toLong()` to
`fileSystem.readFileBytes(path, expectedSizeBytes = ...)` for both temporary and final
readback via its shared `AppInstallStorage.read` adapter. The integration patch is
recorded separately because the catalog adapter is introduced by PR6.
