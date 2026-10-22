package com.vesper.flipper.data

import com.vesper.flipper.ble.FlipperFileSystem

/** Both catalog entry points resolve the exact device-compatible binary. */
suspend fun installCatalogApp(fileSystem: FlipperFileSystem, catalog: FlipperAppCatalog, alias: String): String {
    val info = fileSystem.getDeviceInfo().getOrThrow()
    val binary = catalog.download(alias, info)
    val directory = "/ext/apps/${binary.category}"
    val root = fileSystem.listDirectory("/ext/apps").getOrThrow()
    if (root.none { it.name == binary.category && it.isDirectory }) {
        val created = fileSystem.createDirectory(directory)
        if (created.isFailure) {
            // Another installer may have provisioned the same category while we downloaded.
            val current = fileSystem.listDirectory("/ext/apps").getOrThrow()
            if (current.none { it.name == binary.category && it.isDirectory }) created.getOrThrow()
        }
    }
    val target = "$directory/${binary.id}.fap"
    val existing = fileSystem.listDirectory(directory).getOrThrow()
    check(existing.none { it.name == "${binary.id}.fap" }) { "Already present at $target; existing app was preserved" }
    fileSystem.writeFileBytes(target, binary.bytes).getOrThrow()
    return "Installed ${binary.name} ${binary.version} to $target"
}
