# Live screen mirror

Selective port from ahuray/V3SP3R at `101dc4fa1cdf21d1b0624d911caf600fc673d58d`.
Depends on the RPC frame router feature. The Device screen adds an opt-in live mirror beside the existing remote buttons. Native 128×64, 1024-byte vertical-page frames support all four firmware orientations. Invalid frame lengths are ignored.

Turning live screen off freezes the presented frame immediately and requests that firmware stop streaming. Leaving Device requests stop too. Disconnect clears the frame. A missing stop acknowledgement is reported as uncertain, and the router continues separating any late frames from command replies. A failed start can be retried by toggling off/on.

Verification: decoder tests cover page bits, dimensions, malformed lengths and orientation corners. Build and JVM tests do not establish real-device performance. Hardware acceptance requires BLE and USB start/pause/resume, disconnect/reconnect, navigation away, orientation changes, and remote buttons during streaming. Compare portrait and landscape Android layouts and TalkBack labels on device.

Raw CLI commands are rejected while streaming or while a stop remains unacknowledged,
so textual output cannot be lost in the RPC stream parser. An acknowledged pause
restores CLI collection; an uncertain stop requires retrying pause or reconnecting.
RPC commands continue to use the demultiplexed command path while streaming.
ViewModel teardown makes a separate five-second stop attempt so navigation cannot
cancel the only firmware cleanup request with `viewModelScope`.

Streaming is eligible only while the Device lifecycle is STARTED. Home/lock sends a
stop even when the composition is retained; returning foreground resumes an enabled
mirror. Decoding and connection reset share the ingress generation boundary.
