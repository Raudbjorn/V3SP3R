package com.vesper.flipper.data

/** Shared CLI/RPC metadata normalization; absent or malformed values never become guesses. */
internal object FirmwareCatalogMetadata {
    fun target(value: String?): String? = value?.trim()?.lowercase()?.removePrefix("f")
        ?.takeIf { it.matches(Regex("[0-9]+")) }?.let { "f$it" }
    fun api(values: Map<String, String>): String? {
        val combined = values["firmware_api"] ?: values["firmware_api_version"]
        if (combined != null) return combined.trim().takeIf { it.matches(Regex("[0-9]+\\.[0-9]+")) }
        val major = values["firmware_api_major"] ?: values["firmware_api.major"] ?: return null
        val minor = values["firmware_api_minor"] ?: values["firmware_api.minor"] ?: return null
        return "$major.$minor".takeIf { it.matches(Regex("[0-9]+\\.[0-9]+")) }
    }
}
