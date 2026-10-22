# `.nfc` file format

> How NFC captures are stored on the Flipper's SD. Tag-type layouts,
> ATQA/SAK/UID/block fields, key encoding.

## What it is

A **`.nfc` file** stores a captured or hand-authored NFC tag: metadata
(UID, ATQA, SAK, protocol), plus block data (for MIFARE), plus per-sector
keys. Plain text. Lives under `/ext/nfc/`.

Format specifics vary slightly by tag type. Three broad layouts matter:

- **ISO14443-3A** (UID-only, no MIFARE Classic).
- **MIFARE Classic** (sectors, keys, per-block data).
- **MIFARE Ultralight / NTAG21x** (pages).

## How it works

### Common header

```
Filetype: Flipper NFC device
Version: 4
```

- **`Version:`** varies by firmware. Version 3 is the historical stable
  format; version 4 uses an updated device representation. Read support
  depends on the exact firmware version and protocol. Do not assume
  older firmware accepts version 4.

### Device / tag identification

```
Device type: Mifare Classic
UID: A1 B2 C3 D4
ATQA: 00 04
SAK: 08
```

- **`Device type`** — one of `NTAG213`, `NTAG215`, `NTAG216`,
  `Mifare Classic`, `Mifare Ultralight`, `EMV`, `Slix`, etc.
- **`UID`** — space-separated hex bytes.
- **`ATQA`** — 2-byte "Answer To Request" (little-endian on some
  parsers; check byte order if writing manually).
- **`SAK`** — 1-byte "Select Acknowledge".

### MIFARE Classic body

```
Mifare Classic type: 1K
Data format version: 2
# Mifare Classic blocks, byte 0-15 hex, per block, sector by sector.
Block 0: A1 B2 C3 D4 08 04 00 62 63 64 65 66 67 68 69 6A
Block 1: 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
Block 2: 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
Block 3: FF FF FF FF FF FF FF 07 80 69 FF FF FF FF FF FF
# ... continue through all sectors
# ...
```

- **`Mifare Classic type`** — `Mini` / `1K` / `4K`.
- **`Block N: ...`** — 16-byte block content, hex-space-separated.
  Sectors 0–31 have four blocks each; their trailer is `Block (4 × sector + 3)`.
  On 4K tags, sectors 32–39 have sixteen blocks each; their trailers are
  blocks 143, 159, …, 255.
- **Sector-trailer bytes** — Key A is bytes **0–5**, access conditions and
  the general-purpose byte occupy **6–9**, and Key B is bytes **10–15**
  (zero-based). The firmware stores and loads keys from these `Block N`
  bytes. There are no separate `Key A sector N` or `Key B sector N` fields.
  Unknown bytes may be `??`; those are missing data, not usable keys.

### MIFARE Ultralight / NTAG body

```
UL version: EV1_MF0UL21
Signature: 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
Mifare version: 00 04 03 01 01 00 0F 03
Counter 0: 000000
Tearing 0: BD
Pages total: 41
Pages read: 41
Page 0: A1 B2 C3 D4
Page 1: E5 F6 07 18
# ... one page per line, 4 bytes each
```

- **`Pages total`** — declared page count for the tag variant.
- **`Pages read`** — how many were successfully read. Locked pages
  come back as `?? ?? ?? ??` on some firmwares.
- **`Page N`** — 4-byte page.
- Metadata (`Signature`, `Counter`, `Tearing`) are Ultralight-EV1
  extensions.

### ISO14443A UID-only body

For non-MIFARE ISO14443A tags where the Flipper only got UID/ATQA/SAK:

```
Filetype: Flipper NFC device
Version: 4
Device type: ISO14443-3A
UID: A1 B2 C3 D4
ATQA: 00 04
SAK: 08
```

No block data. Emulation replays UID + ATQA/SAK responses.

## Capabilities and limits

- **File size** — 1K MIFARE dump is ~5 KB; 4K is ~15 KB. Comfortable.
- **Hand-authoring** works — you can craft a `.nfc` file in a text
  editor to emulate a specific UID + protocol. Useful for testing.
- **UID length** — 4-byte UIDs (single-size), 7-byte UIDs (double-size),
  10-byte UIDs (triple-size) all supported. Length is inferred from
  the `UID:` byte count.
- **Version drift** — Check the target firmware's NFC loader and supported
  protocol before exporting. A date or family name alone does not prove
  support for a particular file version.
- **DESFire dumps** capture UID, ATQA, SAK, ATS. No file-selection
  content beyond that (auth required).
- **`Data format version:`** on MIFARE Classic bodies — bumped when
  the block layout changes. Version 2 is current.

## Worked example — MIFARE Classic 1K dump

```
Filetype: Flipper NFC device
Version: 4
Device type: Mifare Classic
UID: A1 B2 C3 D4
ATQA: 00 04
SAK: 08
Mifare Classic type: 1K
Data format version: 2
Block 0: A1 B2 C3 D4 08 04 00 62 63 64 65 66 67 68 69 6A
Block 1: 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
Block 2: 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
Block 3: FF FF FF FF FF FF FF 07 80 69 FF FF FF FF FF FF
Block 4: 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
Block 5: 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
Block 6: 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
Block 7: A0 A1 A2 A3 A4 A5 FF 07 80 69 B0 B1 B2 B3 B4 B5
# ... blocks 8-63 continue similarly
# ...
```

## Common tasks

- **Inspect a `.nfc` file:** `storage read /ext/nfc/mycard.nfc`
  (see [`flipper-cli/README.md`](../flipper-cli/README.md)), or open in any text editor.
- **Emulate:** `nfc emulate /ext/nfc/mycard.nfc` from CLI, or
  Emulate button in the app.
- **Hand-author a UID emulator:** create a minimal `.nfc` with only
  header + `UID:` / `ATQA:` / `SAK:`. Great for testing what a reader
  accepts.
- **Extract keys from a saved dump:** locate each sector's trailer `Block N`.
  Read Key A from bytes 0–5 and Key B from bytes 10–15. For example,
  `Block 7` above contains `A0 A1 A2 A3 A4 A5` and `B0 B1 B2 B3 B4 B5`.
  Skip keys containing `??`. For an authorized tag, fully known keys can
  be added as twelve hex digits per line to `mf_classic_dict_user.nfc`.
- **Convert between v3 and v4:** use firmware that can load the source
  format, then save through its NFC app. Preserve a backup. Changing only
  the header does not convert protocol data or required fields; verify
  that the saved result loads correctly on the target firmware.

## Gotchas

- **`SAK: 08` = MIFARE Classic 1K.** `SAK: 18` = 4K. `SAK: 00` =
  MIFARE Ultralight. `SAK: 20` = ISO14443-4 (DESFire etc.). Getting
  this wrong makes emulation fail with confusing "reader rejected"
  behavior.
- **Sector-trailer keys are canonical.** Change the appropriate six bytes
  in the trailer block when editing a saved key. Adding separate key
  fields does not update the keys loaded by the firmware. Preserve the
  access-condition bytes; malformed access bits can make a card unusable.
- **UID uniqueness bit** — for 7-byte UIDs on MIFARE Ultralight,
  byte 0 must be `04` (NXP manufacturer prefix). Emulating with
  another prefix produces cards some readers accept but others
  reject.
- **Magic-card writes require the `.nfc` file's byte 0 of Block 0** to
  equal the desired UID. Some Gen2 magic cards lock this after first
  write.

## Legal & safety notes

Cloning access-control credentials without authorization is illegal
in most jurisdictions. The file format itself is neutral; what you
capture into it is where the tripwires live. See
[`legal/README.md`](../legal/README.md).

## See also

- [`nfc/README.md`](README.md) — protocols, hardware, attacks.
- [`flipper-storage/README.md`](../flipper-storage/README.md) — `/ext/nfc/`, `/ext/nfc/assets/` layout.
- [`flipper-cli/README.md`](../flipper-cli/README.md) — `nfc emulate`, `nfc detect`.

---
*Attribution:* Flipper-Devices NFC file format
(<https://docs.flipper.net/file-formats/nfc>), Momentum
`applications/main/nfc/` sources. Retrieved 2025-Q3.

Serializer reference: [official MIFARE Classic loader and saver](https://github.com/flipperdevices/flipperzero-firmware/blob/dev/lib/nfc/protocols/mf_classic/mf_classic.c).
