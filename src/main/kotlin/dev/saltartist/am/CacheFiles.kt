package dev.saltartist.am

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** Readers see either the complete old cache or the complete new cache. */
internal fun atomicCacheWrite(file: File, bytes: ByteArray) {
    val parent = file.absoluteFile.parentFile.toPath()
    Files.createDirectories(parent)
    val temporary = Files.createTempFile(parent, ".salt-art-", ".tmp")
    try {
        Files.write(temporary, bytes)
        try { Files.move(temporary, file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING) }
        catch (_: AtomicMoveNotSupportedException) { Files.move(temporary, file.toPath(), REPLACE_EXISTING) }
    } finally { Files.deleteIfExists(temporary) }
}
