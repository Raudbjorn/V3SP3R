package com.vesper.flipper.data

import com.vesper.flipper.domain.model.FileEntry
import java.io.IOException

/** Read a complete bounded inventory before replacing the UI's previous snapshot. */
internal suspend fun readInstalledAppInventory(
    listDirectory: suspend (String) -> List<FileEntry>
): Map<String, List<String>> {
    val found = linkedMapOf<String, MutableList<String>>()
    val visited = mutableSetOf<String>()
    var entriesRead = 0
    suspend fun scan(path: String, depth: Int) {
        if (depth > 8 || visited.size >= 512) throw IOException("App inventory exceeds directory limit")
        if (!visited.add(path)) throw IOException("Repeated app directory")
        for (entry in listDirectory(path)) {
            if (++entriesRead > 10_000) throw IOException("App inventory exceeds entry limit")
            val parts = entry.path.split('/')
            if (!entry.path.startsWith("$path/") || entry.path.substringAfter("$path/").contains('/') ||
                parts.any { it == "." || it == ".." } || entry.path.contains('\\') ||
                entry.name != entry.path.substringAfterLast('/')) {
                throw IOException("Invalid path in installed app inventory")
            }
            if (entry.isDirectory) scan(entry.path, depth + 1)
            else if (entry.name.endsWith(".fap", ignoreCase = true)) {
                found.getOrPut(entry.name.dropLast(4)) { mutableListOf() }.add(entry.path)
            }
        }
    }
    scan("/ext/apps", 0)
    return found.mapValues { it.value.toList() }
}
