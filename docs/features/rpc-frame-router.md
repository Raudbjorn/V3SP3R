# RPC screen and command demultiplexing

Selective port from ahuray/V3SP3R at `101dc4fa1cdf21d1b0624d911caf600fc673d58d`.

`setScreenFrameRouting(true)` enables routing for a screen-stream session. `rpcScreenFrames` publishes unsolicited (`command_id = 0`) screen messages; complete solicited frames retain their length prefixes and go to the active command collector. Partial varints/bodies remain buffered until complete. Malformed/overflowing lengths cannot wrap into valid sizes. Reconnection disables routing and discards queued chunks from earlier connection epochs.

Ingress is serialized to preserve notification ordering. Collectors fail explicitly after 256 KiB rather than silently retaining unbounded output. Screen notifications never advance command quiet timers. Once streaming stops, keeping routing enabled until connection reset prevents late notifications from poisoning command responses.

Tests exercise interleaving, every byte split, solicited screen replies, and malformed lengths. No physical device was available to verify notification timing or large storage transfers.
