package com.vesper.flipper.data

import com.vesper.flipper.ble.FlipperFileSystem

/** Both catalog entry points resolve the exact device-compatible binary. */
suspend fun installCatalogApp(fileSystem: FlipperFileSystem, catalog: FlipperAppCatalog, alias: String): String {
    val info = fileSystem.getDeviceInfo().getOrThrow()
    val binary = catalog.download(alias, info)
    val directory = "/ext/apps/${binary.category}"
    val root = fileSystem.listDirectory("/ext/apps").getOrThrow()
    if (root.none { it.name == binary.category && it.isDirectory }) fileSystem.createDirectory(directory).getOrThrow()
    val target = "$directory/${binary.id}.fap"
    installVerifiedApp(object : AppInstallStorage {
        override suspend fun exists(path: String): Boolean =
            fileSystem.listDirectory(path.substringBeforeLast('/')).getOrThrow().any { it.name == path.substringAfterLast('/') }
        override suspend fun write(path: String, bytes: ByteArray) { fileSystem.writeFileBytes(path, bytes).getOrThrow() }
        override suspend fun read(path: String): ByteArray = fileSystem.readFileBytes(path).getOrThrow()
        override suspend fun move(source: String, target: String) { fileSystem.move(source, target).getOrThrow() }
        override suspend fun delete(path: String) { fileSystem.deleteFile(path).getOrThrow() }
    }, target, binary.bytes)
    return "Installed ${binary.name} ${binary.version} to $target; download checksum and uploaded bytes verified"
}
