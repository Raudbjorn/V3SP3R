package com.vesper.flipper.security

import java.io.File

/** Same-directory rename protocol. The old source is retained until the installed copy validates. */
internal class DatabaseConversion(
    private val source: File,
    private val isPlaintext: (File) -> Boolean,
    private val export: (File, File) -> Unit,
    private val validate: (File) -> Unit,
    private val syncDirectory: () -> Unit,
    private val checkpoint: (String) -> Unit = {}
) {
    private val staged = File(source.path + ".encrypting")
    private val original = File(source.path + ".plaintext-backup")

    fun run() {
        // Death between the two renames: restore the known original and retry export.
        if (!source.exists() && original.exists()) {
            check(original.renameTo(source)) { "Cannot restore original database" }
            syncDirectory()
        }
        if (!source.exists()) {
            check(!staged.exists()) { "Orphaned encrypted database requires recovery" }
            return
        }
        if (!isPlaintext(source)) {
            validate(source) // A wrong/lost key must never delete or replace any file.
            remove(staged)
            remove(original)
            syncDirectory()
            return
        }
        check(!original.exists()) { "Ambiguous database recovery state; retaining both copies" }
        remove(staged) // Partial export is disposable only while the original exists.
        export(source, staged) // Must checkpoint/close the source and fsync the new database.
        validate(staged)
        checkpoint("exported")
        check(source.renameTo(original)) { "Cannot preserve original database" }
        syncDirectory()
        checkpoint("original-renamed")
        check(staged.renameTo(source)) { "Cannot install encrypted database" }
        syncDirectory()
        checkpoint("encrypted-installed")
        validate(source)
        remove(original)
        syncDirectory()
        checkpoint("complete")
    }

    private fun remove(file: File) {
        if (file.exists()) check(file.delete()) { "Cannot clean up database migration file" }
        for (suffix in listOf("-wal", "-shm", "-journal")) {
            val sidecar = File(file.path + suffix)
            if (sidecar.exists()) check(sidecar.delete()) { "Cannot remove migration sidecar" }
        }
    }
}
