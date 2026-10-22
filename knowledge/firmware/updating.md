# Firmware updating — flashing, DFU, brick recovery

> How firmware updates actually happen on a Flipper Zero: `.tgz`
> bundles, `/ext/update/` staging, the Updater, DFU recovery,
> family-switching tradeoffs.

## What it is

A Flipper Zero firmware update is a **`.tgz` bundle** (Official /
Momentum / Unleashed / RogueMaster all use the same format) that
contains:

- `firmware.dfu` — the ST32-format image for the Cortex-M4 core.
- `radio.bin` (sometimes) — the STM32WB55 wireless coprocessor binary.
- `resources/` — asset staging that the Updater copies to `/ext/`.
- `update.fuf` (or similar) — the update manifest.

The **on-device Updater** app consumes the bundle: verifies checksums,
flashes the M4 core, optionally reflashes the M0+ radio stack, copies
resources, reboots. If anything goes wrong before the flash actually
completes, the Flipper reboots into the old firmware and the update
just failed. If it goes wrong *during* the flash, DFU recovery over
USB is the escape hatch.

## How it works

### Three routes to flash

**1. qFlipper (recommended for most users):**

1. Connect USB, open qFlipper.
2. Update tab → select the `.tgz` you want (or use the "current release"
   button for Official).
3. qFlipper stages the bundle over USB storage into `/ext/update/`
   and reboots the Flipper into the Updater. Fully automated.

**2. Web installer (Momentum / Unleashed / RogueMaster):**

1. Each fork hosts a WebUSB installer. E.g. <https://momentum-fw.dev/>.
2. Chromium-family browser required (Firefox lacks WebUSB).
3. Same underlying flow — the web page drives USB, stages the `.tgz`,
   triggers the Updater.

**3. Manual `/ext/update/` drop:**

1. Download the firmware archive for your device and **extract it on the
   computer**. Keep the extracted update folder intact, including its
   `update.fuf` manifest and all sibling firmware/resource files.
2. Copy that folder into `SD/update` (`/ext/update/`) with qFlipper's
   storage tab or an SD-card reader. An unopened `.tgz` alone is not an
   on-device update directory; qFlipper's package-install route handles
   archives separately.
3. On the Flipper, open the file browser, enter the copied update folder,
   select its **Update** file (`update.fuf`), and confirm installation.
4. The on-device Updater takes over. Follow the selected firmware's
   [installation instructions](https://github.com/Next-Flip/Momentum-Firmware#install)
   if its release layout differs.

### DFU recovery (STM32 bootloader)

If the M4 firmware image is corrupted, the Updater failed mid-flash, or
the Flipper hangs on boot:

1. Power off the Flipper (hold BACK 10 s, or pop the battery via SD
   compartment on old revs).
2. Hold **LEFT + BACK** during power-on. The M4 comes up in the ST
   system bootloader.
3. USB enumerates as an STM32 DFU device.
4. Use `dfu-util` or ST's DfuSe tool to write a fresh `.dfu` image.

Example:
```
# example — not runnable here
dfu-util -a 0 -s 0x08000000 -D firmware.dfu
```

DFU flashes the M4 core only. The radio coprocessor (`radio.bin`) is
written from *inside* the M4 firmware — so if you DFU an image that
expects radio stack v1.15.0 and the Flipper has v1.12.0, first boot
of the DFU'd firmware will reflash the radio.

### Cross-family flashing

- **Same-family updates and rollbacks:** use a supported bundle and follow
  that release's compatibility guidance; back up settings and SD data.
- **Cross-family via Updater:** supported custom bundles for the same
  hardware can use the staged on-device Updater flow. A family difference
  alone does not require DFU or imply a signature rejection.
- **qFlipper/custom package or web installer:** use the selected firmware's
  documented installation route and package format. Do not assume an
  automatic DFU fallback when a package fails.
- **DFU:** reserve it for recovery or when the documented staged install
  is unavailable. Use a correct image for the hardware; DFU is not a
  compatibility check and an arbitrary image can break the device.

### Radio stack updates

The Cortex-M0+ radio coprocessor runs a proprietary ST binary
("STM32WB Wireless Coprocessor"). It's updated less frequently than
the M4 firmware. When it changes, the `.tgz` includes a `radio.bin`
and the Updater flashes it after the M4 finishes.

A radio-stack update is the highest-risk step because a mid-flash
failure leaves the coprocessor in an unknown state. If BLE stops
working after an update, the radio stack is the first suspect — a
subsequent full flash usually recovers it.

## Capabilities and limits

- **Update integrity:** the Updater checksums the bundle. Corrupt
  `.tgz` = "Update failed, please retry" on boot.
- **No differential updates.** Every flash is a full write. On slow
  SD cards this is several seconds.
- **User data preserved.** `/ext/subghz`, `/ext/nfc`, `/ext/apps`,
  etc. survive updates. Only firmware-owned directories
  (`/ext/dolphin/` under some conditions) get overwritten.
- **DFU cannot flash the radio stack directly.** The radio update
  path is M4-only can-write. Use qFlipper for radio stack fixes.
- **Update size ~1-2 MB.** No practical size concern.

## When to switch families

Each fork's tradeoff:

- **Official** — most conservative, best support, mainstream. Choose
  when you need Flipper-Devices support, are on shared/loaner hardware,
  or don't care about region unlocks.
- **Momentum** ⭐ — richest community feature set, active dev, Vesper's
  primary target. Choose when you want the JS runner, extended NFC
  attacks, worldwide SubGHz. Assume weekly release cadence.
- **Unleashed** — region-unlock focused, more conservative than
  Momentum in scope. Choose if you want unlocks without the app bloat.
- **RogueMaster** — kitchen-sink build. Choose if you want everything
  turned on and are willing to tolerate more churn.

## Common tasks

- **Update to latest of current family:** qFlipper Update tab, one
  click.
- **Switch Official → Momentum:** back up `/ext/`, then use Momentum's
  supported custom-package, web-installer, or extracted-folder Updater
  route. Changing families alone does not require DFU.
- **Return Momentum → Official:** back up data and use a supported
  official update package through the documented updater/install route.
  Use recovery/DFU only if normal installation is unavailable.
- **Recover a soft-brick:** hold LEFT+BACK on power-on → DFU mode →
  `dfu-util` a known-good `firmware.dfu`.
- **Recover a hard-brick:** SWD via a DAP-Link on GPIO pins 10/11/12;
  `openocd` + full flash write. See [`flipper-gpio/pinout.md`](../flipper-gpio/pinout.md).
- **Restage a stuck update:** delete `/ext/update/*.tgz` and retry.

## Gotchas

- **Region-unlock removal happens in firmware.** Flashing Momentum
  *enables* worldwide TX at the firmware layer; nothing in the
  hardware or radio stack does. If someone thinks region unlock is a
  hardware modification — it's not.
- **Web installer requires WebUSB.** Not Safari, not Firefox. Chrome,
  Edge, Brave, Opera work.
- **DFU flashes clobber the radio-stack version pointer.** After DFU,
  first boot may spend an extra 10-20 s reflashing the radio
  coprocessor. This is normal; don't power-cycle mid-boot.
- **`/ext/update/*.tgz` can accumulate** if you interrupt the Updater
  repeatedly. Clean it out to avoid confusion.
- **qFlipper won't downgrade the radio stack** on some releases —
  Flipper-Devices treats radio downgrades as unsafe. If you need one,
  DFU.
- **Battery must be >20%** or the Updater refuses to start. Reasonable
  guardrail; can be bypassed via CLI on some firmwares, don't.

## Legal & safety notes

Choosing a firmware with region unlocks removed enables you to *break*
FCC/ETSI rules; flashing the firmware itself is not illegal. See
[`legal/README.md`](../legal/README.md) for the "capability vs. legality" distinction.

## See also

- [`firmware/families.md`](families.md) — which one to pick.
- [`firmware/momentum.md`](momentum.md) — Momentum-specific install flow details.
- [`firmware/compatibility-profile.md`](compatibility-profile.md) — how Vesper reacts post-update.
- [`flipper-storage/README.md`](../flipper-storage/README.md) — `/ext/update/` layout.
- [`flipper-hardware/README.md`](../flipper-hardware/README.md) — DFU boot procedure.
- [`flipper-gpio/pinout.md`](../flipper-gpio/pinout.md) — SWD pins for hard-brick recovery.

---
*Attribution:* Flipper-Devices Updater docs
(<https://docs.flipper.net/basics/firmware-update>), Momentum
release notes, `dfu-util` documentation. Retrieved 2025-Q3.
Written as of 2026-10-22 against V3SP3R 338e935 and
Momentum-Firmware-CN CN-Clipper 5efbb3f28. Sourced by reading
firmware and app source, not from a run on hardware.
