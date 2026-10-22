# Database encryption and recovery

Both Room databases (`vesper_database` and `vesper_devices.db`) use SQLCipher 4.10.0. Each has a random 256-bit passphrase committed synchronously with readback to Android Keystore-backed encrypted preferences. A missing key for an encrypted or interrupted migration is an error; the app does not generate a replacement key or clear the database.

Before Room opens a plaintext database, the converter checkpoints its WAL and switches to DELETE journaling. It exports all tables (including Room identity) to `.encrypting`, preserves `user_version`, compares table row counts, and verifies SQLite integrity and SQLCipher page authentication. The export is synced before any rename.

The original becomes `.plaintext-backup`, then the verified export replaces the database name. Directory entries are synced after each rename. The installed copy must pass validation before the plaintext backup is removed. No destructive Room fallback is enabled, and corruption handlers preserve the files.

On restart:

- Plaintext source plus a partial or invalid staged `.encrypting` export: retain the original source, discard the disposable staged copy, and retry the export.
- Missing source plus the original backup: restore the original and retry.
- Encrypted source plus the original backup: validate the encrypted database before cleanup.
- Wrong key, invalid encrypted database already installed at the source path, orphaned export with no original, or ambiguous originals: stop and preserve files for recovery.

Storage failures can prevent startup until space or the original Keystore access is restored. Never delete the files to work around that error. Migration artifacts and keys are excluded from backup and device transfer. Conversion uses a process lock and a file lock per canonical database path, so a large migration does not block opening the other database. Waiting to acquire either lock is bounded to ten seconds; contention reports a retryable open error without changing data. Keystore preference access shares only a short lock, released before conversion. The app currently uses one Android process and must finish each database’s conversion before opening that database with Room.

Validation includes JVM fault injection at each replacement boundary and Android instrumentation tests for real Room records, v1-to-v2 schema upgrade, device records, WAL blobs, Room identity, wrong keys, and native interruption recovery. The Android tests must run on an emulator/device before releasing this change:

```
./gradlew :app:connectedDebugAndroidTest
```

The implementation preserves logical data. It cannot guarantee physical erasure of old plaintext blocks on flash storage.
