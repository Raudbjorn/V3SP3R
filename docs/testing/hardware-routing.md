# Hardware routing acceptance checks

Adapted from joylessorchid/V3SP3R at cb60a1818e3793e0f404c212e35f89081e36ba6e.
Unit tests verify command construction and RPC mapping. They do not establish
physical device behavior. No device acceptance checks have been run in the cloud workspace.

## Setup

- Install a debug APK built from the branch being evaluated.
- Record Android version, Flipper firmware/version, transport (BLE or USB), and APK commit.
- Grant Bluetooth permission, unlock the Flipper, connect, and confirm device information loads.
- Use your configured provider/model if invoking commands through chat. Approval prompts remain required by the normal risk policy.

## Checks

| Action | Expected result |
| --- | --- |
| Write a harmless `REM transport test` text file to `/ext/badusb/transport-test.txt` | The file appears in the file browser and reads back exactly; it is not executed. |
| Launch NFC | After required approval, NFC opens on the device. |
| Transmit an existing signal captured from your own test device | After required approval, the Flipper shows transmission and the test receiver responds. |
| Deny a requested approval | No hardware command is sent. |
| Disconnect during a request, reconnect, then request device info | The first request reports failure; the new connection answers without stale data. |
| Connect RPC-only firmware and request an unmapped CLI operation | A clear unsupported-operation error appears; no text command is written into BLE RPC. |

Record pass/fail for each firmware and transport combination. Clean up the test file.

## Failure evidence

Record the exact UI error, command, whether approval was shown, connection state,
and whether the physical device changed state. Capture logs locally when needed:

```sh
adb logcat -d -s FlipperProtocol:* FlipperBleService:* > flipper-transport.log
```

Inspect logs before sharing; they may contain device data. Do not treat an RPC
request being queued or a unit test passing as proof of physical execution.
