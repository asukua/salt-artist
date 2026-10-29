package dev.saltartist.hook

import java.net.URLClassLoader
import java.nio.file.Files
import java.security.MessageDigest
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HostArchiveFingerprintTest {
    @Test
    fun hashesTheArchiveContainingTheNavigationResourceWithoutLoadingIt() {
        val jar = Files.createTempFile("host fingerprint ", ".jar")
        try {
            JarOutputStream(Files.newOutputStream(jar)).use { output ->
                output.putNextEntry(JarEntry(HookTargets.ARTIST_PAGE_CALLSITE.replace('.', '/') + ".class"))
                output.write(byteArrayOf(1, 2, 3))
                output.closeEntry()
            }
            val expected = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            URLClassLoader(arrayOf(jar.toUri().toURL()), null).use { loader ->
                assertEquals(expected, hostArchiveSha256(loader))
            }
            URLClassLoader(emptyArray(), null).use { loader -> assertNull(hostArchiveSha256(loader)) }
        } finally {
            Files.deleteIfExists(jar)
        }
    }
}
