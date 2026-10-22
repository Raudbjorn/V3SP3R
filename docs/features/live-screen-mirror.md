# Live screen mirror

Selective port from ahuray/V3SP3R at `101dc4fa1cdf21d1b0624d911caf600fc673d58d`.
Depends on the RPC frame router feature. The Device screen adds an opt-in live mirror beside the existing remote buttons. Native 128×64, 1024-byte vertical-page frames support all four firmware orientations. Invalid frame lengths are ignored.

Turning live screen off freezes the presented frame immediately and requests that firmware stop streaming. Leaving Device requests stop too. Disconnect clears the frame. A missing stop acknowledgement is reported as uncertain, and the router continues separating any late frames from command replies. A failed start can be retried by toggling off/on.

Verification: decoder tests cover page bits, dimensions, malformed lengths and orientation corners. Build and JVM tests do not establish real-device performance. Hardware acceptance requires BLE and USB start/pause/resume, disconnect/reconnect, navigation away, orientation changes, and remote buttons during streaming. Compare portrait and landscape Android layouts and TalkBack labels on device.
