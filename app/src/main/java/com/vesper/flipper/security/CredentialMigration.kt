package com.vesper.flipper.security

/** Called under the owner's mutex. Never remove the recoverable source before verification. */
object CredentialMigration {
    suspend fun read(
        legacy: String?, readSecure: () -> String?,
        writeSecure: (String) -> Boolean, removeLegacy: suspend () -> Unit
    ): String? {
        val secured = readSecure()
        if (legacy == null) return secured
        // A previous save may have completed before plaintext cleanup was interrupted.
        val selected = secured ?: legacy
        check(writeSecure(selected) && readSecure() == selected) { "Unable to securely store credential" }
        removeLegacy()
        return selected
    }

    suspend fun write(
        value: String, readSecure: () -> String?,
        writeSecure: (String) -> Boolean, removeLegacy: suspend () -> Unit
    ) {
        check(writeSecure(value) && readSecure() == value) { "Unable to securely store credential" }
        removeLegacy()
    }
}
