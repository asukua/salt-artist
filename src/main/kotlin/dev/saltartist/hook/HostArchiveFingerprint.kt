package dev.saltartist.hook

import java.net.JarURLConnection
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Read archive bytes without loading any Hook target into the JVM. */
internal fun hostArchiveSha256(loader: ClassLoader): String? = runCatching {
    val resource = HookTargets.ARTIST_PAGE_CALLSITE.replace('.', '/') + ".class"
    val url = loader.getResource(resource) ?: return null
    val connection = url.openConnection() as? JarURLConnection ?: return null
    val archiveUrl = connection.jarFileURL
    if (archiveUrl.protocol != "file") return null
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(Path.of(archiveUrl.toURI())).use { input ->
        val buffer = ByteArray(128 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}.getOrNull()
