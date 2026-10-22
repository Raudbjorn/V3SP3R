# Linux USB development bridge

Adapted from [Shiva108/V3SP3R](https://github.com/Shiva108/V3SP3R/tree/3054d3938f373aa5fd35c186e352e1191890dd9c), pinned commit `3054d3938f373aa5fd35c186e352e1191890dd9c` (AGPL-3.0). The termios bridge and Android transport retain the donor's four-byte big-endian length framing around the Flipper RPC byte stream.

On Linux, attach the Flipper and run:

```sh
python3 tools/flipper_desktop_bridge.py --serial /dev/ttyACM0
```

The host requires access to the serial device (commonly the `dialout` group). Close qFlipper and other serial clients first. The server binds only `127.0.0.1:8765`. In an Android **debug** build, open Device and choose **Connect via Desktop Bridge**. The Android emulator reaches host loopback through `10.0.2.2`. This is a developer transport, not a production LAN server. Release builds hide the control and reject the service connection request.

Frames are limited to 1 MiB. Serial writes and incomplete client frames time out after five seconds. Each client opens and primes a fresh serial session. Connection and read failures surface as errors, without mock fallback. Existing BLE and USB remain separate transports.

Validation:

```sh
python3 -m unittest discover -s tools -p 'test_*.py' -v
./gradlew :app:testDebugUnitTest --tests '*DesktopBridgeFramingTest' :app:assembleDebug
```

A pseudo-terminal test covers fragmented/coalesced frames, byte-for-byte round trips, and disconnect cleanup. Physical Flipper firmware, USB permissions, and an Android emulator attached to this host were not available for end-to-end testing; real hardware acceptance remains required. On repeated reconnects, reset the device RPC session if firmware leaves it open.

Integration with T2: `usesRpcOnlyCommandTransport()` includes physical BLE **or** `DESKTOP_BRIDGE` on this branch already. Keep that union when merging the T2 protocol guards; `usesBleCommandTransport()` remains true only for physical BLE for transport telemetry. Neither merge order may remove the desktop guard.
