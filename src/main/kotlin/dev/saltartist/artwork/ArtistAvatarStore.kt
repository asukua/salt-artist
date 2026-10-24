package dev.saltartist.artwork

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import dev.saltartist.PluginLog
import dev.saltartist.am.AppleMusicCatalog
import dev.saltartist.am.ArtistIdentityPolicy
import dev.saltartist.am.artistIdentityKey
import dev.saltartist.am.atomicCacheWrite
import dev.saltartist.data.normalizeArtistKey
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.EncodedImageFormat
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
    private val images = BoundedArtworkCache<String, Result>(512)
    private var clearRevision by mutableStateOf(0L)
    private val revisions = mutableStateMapOf<String, Long>()
    fun revision(name: String): Pair<Long, Long> = clearRevision to (revisions[artistIdentityKey(name)] ?: 0L)
    private val requests = ArtistArtworkRequests()
    private val inFlight = ConcurrentHashMap<String, Boolean>()
    private val localInFlight = ConcurrentHashMap<String, Boolean>()
    private val local = Executors.newSingleThreadExecutor { task -> Thread(task, "salt-artist-avatar-cache").apply { isDaemon = true } }
    private val remote = Executors.newSingleThreadExecutor { task -> Thread(task, "salt-artist-avatar-network").apply { isDaemon = true } }
    @Volatile private var closed = false
    private val activeKeys = ConcurrentHashMap<String, String>()
    private var nextLookupAt = 0L

    fun avatar(name: String, identity: ArtistIdentityPolicy): ImageBitmap? {
        if (closed || catalog == null || name.isBlank()) return null
        val key = artistAvatarCacheKey(name, identity)
        activeKeys[artistIdentityKey(name)] = key
        val hit = images[key]
        if (hit != null && (hit.image != null || hit.retryAt > System.currentTimeMillis())) return hit.image
        requestLocal(name, identity, allowRemote = true)
        return images[key]?.image
    }

    /** A detail-page avatar also satisfies the directory, even during an old negative lookup. */
    fun warm(name: String, identity: ArtistIdentityPolicy) { requestLocal(name, identity, allowRemote = false) }

    private fun requestLocal(name: String, identity: ArtistIdentityPolicy, allowRemote: Boolean) {
        if (closed) return
        val cat = catalog ?: return
        val key = artistAvatarCacheKey(name, identity)
        val generation = requests.generation(artistIdentityKey(name))
        // A detail page can finish while a previous directory miss is still being
        // scheduled. Its warm-up must get one guaranteed pass after that miss.
        val job = "$key|$generation" + if (allowRemote) "" else "|warm"
        if (localInFlight.putIfAbsent(job, true) != null) return
        runCatching {
            local.execute {
                try {
                    if (!isCurrent(name, key, generation)) return@execute
                    cat.cached(name, identity = identity) // Local-only metadata/file migration.
                    val loaded = listOfNotNull(file(key), cat.avatarFile(name, identity)).firstNotNullOfOrNull { source ->
                        source.takeIf { it.isFile && it.length() in 1..MAX_IMAGE_BYTES.toLong() }
                            ?.let { runCatching { source to decode(source.readBytes()) }.getOrNull() }
                    }
                    val source = loaded?.first
                    val cached = loaded?.second
                    if (cached != null) publish(name, key, generation, Result(cached.image)) {
                        if (source != file(key)) persist(key, cached.bytes, checkNotNull(source).lastModified())
                    }
                    val expired = source == null || System.currentTimeMillis() - source.lastModified() >= CACHE_TTL
                    if (allowRemote && (cached == null || expired) && inFlight.putIfAbsent(job, true) == null) {
                        runCatching { remote.execute { fetch(name, key, identity, generation, job, expired && cached != null) } }
                            .onFailure { inFlight.remove(job) }
                    }
                } finally { localInFlight.remove(job) }
            }
        }.onFailure { localInFlight.remove(job) }
    }

    private fun fetch(name: String, key: String, identity: ArtistIdentityPolicy, generation: Long, job: String, refresh: Boolean) {
        try {
            val cat = catalog ?: return
            if (!isCurrent(name, key, generation) || (!refresh && images[key]?.image != null)) return
            // A visible page can contain dozens of artists. Spread first-time lookups
            // instead of sending a burst of searches for the entire collection.
            val pause = nextLookupAt - System.currentTimeMillis()
            if (pause > 0) Thread.sleep(pause)
            if (!isCurrent(name, key, generation) || (!refresh && images[key]?.image != null)) return
            nextLookupAt = System.currentTimeMillis() + 3100L
            val meta = cat.resolveAvatar(name, identity)
            if (!isCurrent(name, key, generation) || (!refresh && images[key]?.image != null)) return
            val bytes = meta?.let { download(cat.imageUrl(it.template, it.crop, 320, 320)) }
            val bitmap = bytes?.let { runCatching { decode(it) }.getOrNull() }
            if (bitmap != null) {
                publish(name, key, generation, Result(bitmap.image)) { persist(key, bitmap.bytes) }
            } else publish(name, key, generation, Result(null, System.currentTimeMillis() + NEGATIVE_TTL))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Throwable) {
            log.log("artist avatar unavailable: ${error.javaClass.simpleName}")
            publish(name, key, generation, Result(null, System.currentTimeMillis() + NEGATIVE_TTL))
        } finally { inFlight.remove(job) }
    }

    private fun isCurrent(name: String, key: String, generation: Long): Boolean =
        !closed && requests.generation(artistIdentityKey(name)) == generation &&
            activeKeys[artistIdentityKey(name)].let { it == null || it == key }

    private fun publish(name: String, key: String, generation: Long, value: Result, save: () -> Unit = {}) {
        requests.publish(artistIdentityKey(name), generation) {
            if (isCurrent(name, key, generation) && (value.image != null || images[key]?.image == null)) {
                save()
                images[key] = value
            }
        }
    }

    private fun file(key: String): File? = pluginDir?.let { File(it, "apple-music/artist-$key-avatar-320.jpg") }
    private fun persist(key: String, bytes: ByteArray?, modified: Long = System.currentTimeMillis()) {
        if (bytes == null) return
        file(key)?.let { destination -> runCatching { atomicCacheWrite(destination, bytes); destination.setLastModified(modified) } }
    }
    private data class Decoded(val image: ImageBitmap, val bytes: ByteArray?)
    private fun decode(bytes: ByteArray): Decoded = Image.makeFromEncoded(bytes).use { source ->
        require(acceptableImageDimensions(source.width, source.height))
        val scale = (320f / maxOf(source.width, source.height)).coerceAtMost(1f)
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        Surface.makeRasterN32Premul(width, height).use { surface ->
            Paint().use { surface.canvas.drawImageRect(source, Rect.makeWH(width.toFloat(), height.toFloat()), it) }
            val snapshot = surface.makeImageSnapshot()
            Decoded(snapshot.toComposeImageBitmap(), snapshot.encodeToData(EncodedImageFormat.JPEG, 90)?.use { it.bytes })
        }
    }

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

    @Synchronized fun invalidate(name: String) {
        val base = ArtistIdentityPolicy().artistCacheStem(name)
        val artist = artistIdentityKey(name)
        requests.invalidate(artist) {
            revisions[artist] = requests.generation(artist)
            images.keys.filter { it.startsWith("$base--") }.forEach { images.remove(it) }
            // Refresh removes strict, enabled and past override configurations alike.
            pluginDir?.resolve("apple-music")?.listFiles()?.filter {
                it.name.startsWith("artist-$base-") && (it.name.endsWith("-avatar-small.jpg") || it.name.endsWith("-avatar-320.jpg"))
            }?.forEach { runCatching { it.delete() } }
            file(normalizeArtistKey(name))?.let { runCatching { it.delete() } } // pre-0.1.5 thumbnail
        }
    }

    /** Changing a binding selects another key; it does not erase already verified disk files. */
    @Synchronized fun identityChanged(name: String) {
        val artist = artistIdentityKey(name)
        requests.invalidate(artist) {
            revisions[artist] = requests.generation(artist)
            activeKeys.remove(artist)
        }
    }

    @Synchronized fun clear() { requests.clear { clearRevision++; revisions.clear(); images.clear() } }

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
