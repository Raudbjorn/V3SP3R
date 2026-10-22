# Device acceptance and regression checks

The JVM suite is runnable with `./gradlew testDebugUnitTest`. It covers command parsing, risk/approval behavior, RPC mappings, diagnostics, and file-system fallbacks. Run it alongside the focused regression suites in the transport and catalog feature PRs.

Build the app and opt-in device test with:

```sh
./gradlew assembleDebug assembleDebugAndroidTest
```

The device acceptance test uses the real application services. It does not call an AI provider or transmit saved RF signals. On an Android phone with the required Bluetooth permissions granted, and a paired, unlocked Flipper that you own or are authorized to test:

```sh
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.vesper.flipper.diagnostics.DeviceAcceptanceTest \
  -Pandroid.testInstrumentationRunnerArguments.flipperAddress=AA:BB:CC:DD:EE:FF
```

Without `flipperAddress` the test is skipped. Replace the example address with your device. The test connects, reads device information and the app directory, disconnects, reconnects, and repeats the reads with bounded deadlines.

For an additional transfer/readback check, explicitly add:

```sh
-Pandroid.testInstrumentationRunnerArguments.writeReadback=true
```

This writes a uniquely named `/ext/vesper-acceptance-<UUID>.bin` file and compares every byte read back. It never overwrites an existing user file. The scratch file remains for inspection and can be deleted manually. Run with the Flipper charged and its SD card mounted. Record Android version, firmware version, transport, and the test output when reporting results.

This harness derives from [ckumar1's pinned maintenance revision](https://github.com/ckumar1/V3SP3R/tree/261824dfe8e178e4b2cc000867c796299b20b15c). Building the test APK or passing JVM tests does not establish physical-device acceptance.

The opt-in transfer contains every byte value across 8 KiB, including zero and high-bit bytes, so readback checks binary fidelity rather than only text. Device-info checks reject blank, unknown, or placeholder firmware versions.
