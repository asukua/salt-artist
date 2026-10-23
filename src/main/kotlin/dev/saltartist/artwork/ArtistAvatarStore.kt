package dev.saltartist.artwork

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import dev.saltartist.PluginLog
import dev.saltartist.am.AppleMusicCatalog
import dev.saltartist.am.ArtistIdentityPolicy
import dev.saltartist.data.normalizeArtistKey
import org.jetbrains.skia.Image
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** On-demand directory thumbnails. Local cache work never queues behind catalog lookups. */
internal class ArtistAvatarStore(
    private val pluginDir: File?,
    private val log: PluginLog,
    private val catalog: AppleMusicCatalog?,
) : AutoCloseable {
    private data class Result(val image: ImageBitmap?, val retryAt: Long = 0L)
    private val images = mutableStateMapOf<String, Result>()
    private val inFlight = ConcurrentHashMap<String, Boolean>()
    private val local = Executors.newSingleThreadExecutor { task -> Thread(task, "salt-artist-avatar-cache").apply { isDaemon = true } }
    private val remote = Executors.newSingleThreadExecutor { task -> Thread(task, "salt-artist-avatar-network").apply { isDaemon = true } }
    @Volatile private var closed = false
    @Volatile private var activeIdentityToken: String? = null
    private var nextLookupAt = 0L

    fun avatar(name: String, identity: ArtistIdentityPolicy): ImageBitmap? {
        if (closed || catalog == null || name.isBlank()) return null
        activeIdentityToken = identity.cacheToken
        val key = artistAvatarCacheKey(name, identity)
        val hit = images[key]
        if (hit != null && (hit.image != null || hit.retryAt > System.currentTimeMillis())) return hit.image
        if (inFlight.putIfAbsent(key, true) != null) return null
        runCatching {
            local.execute {
                if (!isCurrent(identity)) {
                    inFlight.remove(key)
                    return@execute
                }
                val cachedFile = file(key)
                val cached = cachedFile?.takeIf {
                    it.isFile && it.length() <= MAX_IMAGE_BYTES && System.currentTimeMillis() - it.lastModified() < CACHE_TTL
                }?.let { runCatching { decode(it.readBytes()) }.getOrNull() }
                if (cached != null) {
                    publish(key, Result(cached))
                    inFlight.remove(key)
                } else {
                    runCatching { remote.execute { fetch(name, key, identity) } }.onFailure { inFlight.remove(key) }
                }
            }
        }.onFailure { inFlight.remove(key) }
        return images[key]?.image
    }

    private fun fetch(name: String, key: String, identity: ArtistIdentityPolicy) {
        try {
            val cat = catalog ?: return
            if (!isCurrent(identity)) return
            // A visible page can contain dozens of artists. Spread first-time lookups
            // instead of sending a burst of searches for the entire collection.
            val pause = nextLookupAt - System.currentTimeMillis()
            if (pause > 0) Thread.sleep(pause)
            if (!isCurrent(identity)) return
            nextLookupAt = System.currentTimeMillis() + 3100L
            val meta = cat.resolveAvatar(name, identity)
            if (!isCurrent(identity)) return
            val bytes = meta?.let { download(cat.imageUrl(it.template, it.crop, 320, 320)) }
            val bitmap = bytes?.let { runCatching { decode(it) }.getOrNull() }
            if (bitmap != null) {
                file(key)?.let { destination ->
                    runCatching { destination.parentFile.mkdirs(); destination.writeBytes(bytes) }
                }
                publish(key, Result(bitmap))
            } else publish(key, Result(null, System.currentTimeMillis() + NEGATIVE_TTL))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Throwable) {
            log.log("artist avatar unavailable: ${error.javaClass.simpleName}")
            publish(key, Result(null, System.currentTimeMillis() + NEGATIVE_TTL))
        } finally { inFlight.remove(key) }
    }

    private fun isCurrent(identity: ArtistIdentityPolicy): Boolean =
        !closed && activeIdentityToken == identity.cacheToken

    private fun publish(key: String, value: Result) {
        if (closed) return
        images[key] = value
        if (images.size > 128) images.keys.firstOrNull { it != key }?.let(images::remove)
    }

    private fun file(key: String): File? = pluginDir?.let { File(it, "apple-music/artist-$key-avatar-320.jpg") }
    private fun decode(bytes: ByteArray): ImageBitmap = Image.makeFromEncoded(bytes).toComposeImageBitmap()

    private fun download(url: String): ByteArray? {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 6000
        connection.readTimeout = 8000
        connection.setRequestProperty("User-Agent", "SaltArtist/0.1")
        return try {
            if (connection.responseCode !in 200..299) null
            else connection.inputStream.use { stream ->
                stream.readNBytes(MAX_IMAGE_BYTES + 1).takeIf { it.isNotEmpty() && it.size <= MAX_IMAGE_BYTES }
            }
        } finally { connection.disconnect() }
    }

    fun invalidate(name: String) {
        val base = normalizeArtistKey(name)
        images.keys.filter { it.startsWith("$base-") }.forEach { images.remove(it) }
        // Refresh removes strict, enabled and past override configurations alike.
        pluginDir?.resolve("apple-music")?.listFiles()?.filter {
            it.name.startsWith("artist-$base-") && (it.name.endsWith("-avatar-small.jpg") || it.name.endsWith("-avatar-320.jpg"))
        }?.forEach { runCatching { it.delete() } }
        file(base)?.let { runCatching { it.delete() } } // pre-0.1.5 thumbnail
    }

    fun clear() { images.clear() }

    override fun close() {
        closed = true
        local.shutdownNow()
        remote.shutdownNow()
    }

    private companion object {
        const val MAX_IMAGE_BYTES = 2_000_000
        const val CACHE_TTL = 30L * 24 * 60 * 60 * 1000
        const val NEGATIVE_TTL = 10L * 60 * 1000
    }
}
