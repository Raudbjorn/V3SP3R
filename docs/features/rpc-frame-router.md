# RPC screen and command demultiplexing

Selective port from ahuray/V3SP3R at `101dc4fa1cdf21d1b0624d911caf600fc673d58d`.

`setScreenFrameRouting(true)` enables routing for a screen-stream session. `rpcScreenFrames` publishes unsolicited (`command_id = 0`) screen messages; complete solicited frames retain their length prefixes and go to the active command collector. Partial varints/bodies remain buffered until complete. Malformed/overflowing lengths cannot wrap into valid sizes. Reconnection disables routing and discards queued chunks from earlier connection epochs.

Ingress is serialized to preserve notification ordering. Collectors fail explicitly at their byte limits rather than silently retaining unbounded output. Screen notifications never advance command quiet timers. Keep routing enabled after an uncertain stop so unsolicited notifications cannot poison command responses; an acknowledged stop or connection reset restores normal collection.

Tests exercise interleaving, every byte split, solicited screen replies, and malformed lengths. No physical device was available to verify notification timing or large storage transfers.

Binary command responses are bounded at 16 MiB, separately from the 256 KiB per-frame and CLI limits. This permits multi-frame storage reads of catalog apps without truncating verified readback.
