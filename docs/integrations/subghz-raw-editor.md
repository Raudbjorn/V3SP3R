# SubGHz RAW import and editor

L2 (I4), adapted from [Shiva108/V3SP3R](https://github.com/Shiva108/V3SP3R/tree/3054d3938f373aa5fd35c186e352e1191890dd9c), pinned commit `3054d3938f373aa5fd35c186e352e1191890dd9c` (AGPL-3.0).

Open **Alchemy Lab → Import RAW file** (Android document picker), or paste a Flipper RAW file. Import replaces the current local project only after validation succeeds. A generated RAW blueprint can also open in the RF editor. An imported layer keeps every signed pulse duration; waveform resampling uses elapsed time rather than treating each pulse as one pixel.

Select a layer to edit its signed microsecond timings, then **Apply pulse timings**. Layers support enable/disable, duplication, ordering, and repeats. Bit/encoding edits explicitly replace raw timings with synthesized bits; the UI names this consequence. **Export → Export .sub file** writes through Android's document picker without a device. **Save to Flipper** asks for confirmation before replacing the named `/ext/subghz/alchemy_*.sub` file. Saving does not transmit RF.

The parser rejects malformed tokens, zero/overflow durations, duplicate headers, missing or unsupported metadata, and custom radio presets (their register data cannot be preserved). Limits: 1 MiB input, 16,384 pulses, 10 seconds per pulse, 5 minutes total imported duration, 32 editor layers, and 100,000 synthesized pulses. Errors leave the previous project unchanged. Import, frequency editing, and export share the repository's 280–930 MHz support range. Export also enforces the importer's pulse-count, total-duration, and byte limits after repeat expansion, so every accepted export can be reopened. Only the five standard metadata headers are accepted, each bounded to 128 characters. Standard modulation presets, frequency, and pulse timings round-trip through export. Physical RF transmission and device write acceptance require hardware validation.

## UI contract

This extension keeps Alchemy's existing Compose Material 3 controls, `VesperAccent`/`VesperOrange` theme colors, typography, and card spacing. It adds bounded file/paste input, visible import/export busy states, retained paste text after errors, accessible named actions, and a save confirmation. File imports offer cancellation and a 15-second deadline; paste remains usable while a stalled provider closes, and late file results cannot overwrite a newer paste import. A new file request remains disabled until the previous provider read exits, avoiding unbounded blocked readers. The waveform is a local visualization; the donor's simulated two-second “playing” control is deliberately omitted.

## Validation

`./gradlew :app:assembleDebug :app:testDebugUnitTest --max-workers=2 --no-daemon`

Tests cover exact multiline pulse round trips, malformed/zero/overflow/large inputs, long-tail waveform sampling, edited/disabled layers, and bounded repeat expansion. This PR includes minimal repairs needed to compile the pre-existing unit tests: JUnit/Mockito dependencies, current CommandExecutor constructor arguments, and `Nothing`-returning assertion branches. Other baseline test behavior is outside this feature.

## Signed level durations

Consecutive durations with the same sign are preserved exactly, including across `RAW_Data` records. They describe consecutive intervals at the same level; rejecting them would change the accepted source format. The [Flipper firmware RAW worker](https://github.com/flipperdevices/flipperzero-firmware/blob/66613d2b46e4aa533aeaf8181d2a1e6566616a4e/lib/subghz/subghz_file_encoder_worker.c#L48) parses each integer and maps its sign to the level independently; it does not impose alternating signs. A regression test covers preservation and export/reimport of this sequence.

Frequency editing keeps a local text draft, including empty or temporarily invalid text. **Apply frequency** commits a supported value. Pending text explicitly shows that export and Save to Flipper still use the last applied frequency; importing or selecting a preset resets the draft to that project frequency.
