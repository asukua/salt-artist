package dev.saltartist.artwork

import dev.saltartist.PluginLog
import dev.saltartist.settings.MotionResolution
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

internal data class PreparedMotion(val master: String, val quality: MotionResolution, val location: String,
                                   val remote: String, val cached: Boolean)

/** Bounded, atomic VOD caching. No decoder/GPU object is retained between pages. */
internal class MotionVideoCache(
    pluginDir: File?, private val log: PluginLog,
    private val manifest: (String) -> String = ::loadAppleMotionManifest,
    private val download: (String, File) -> Unit = ::downloadAppleMotionMovie,
) : AutoCloseable {
    private val directory = pluginDir?.resolve("apple-music")
    private val lock = Any()
    private var generation = 0L
    private val closed = AtomicBoolean(false)
    private val streams = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 64
    }
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<File?>>()
    private val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(4),
        { task -> Thread(task, "SaltArtist-motion-cache").apply { isDaemon = true; contextClassLoader = ClassLoader.getPlatformClassLoader() } })

    fun prepare(url: String, quality: MotionResolution): PreparedMotion {
        require(isAppleMotionUrl(url))
        val key = key(url, quality)
        synchronized(lock) {
            file(key, ".mp4")?.takeIf(::validMovie)?.let {
                it.setLastModified(System.currentTimeMillis())
                // libmpv on Windows expects a filesystem path; java.io.File's
                // single-slash file:/C:/ URI is not a playable local URL here.
                return PreparedMotion(url, quality, it.absolutePath, streams[key] ?: readStream(key) ?: url, true)
            }
        }
        return remote(url, quality)
    }

    fun remote(url: String, quality: MotionResolution): PreparedMotion {
        val key = key(url, quality)
        val hit = synchronized(lock) { streams[key] ?: readStream(key)?.also { streams[key] = it } }
        val resolved = hit ?: if (URI(url).path.lowercase().endsWith(".m3u8")) runCatching {
            selectAppleMotionVariant(url, manifest(url), quality) ?: url
        }.getOrDefault(url) else url
        require(isAppleMotionUrl(resolved))
        synchronized(lock) {
            streams[key] = resolved
            file(key, ".stream")?.let { path -> runCatching { path.parentFile.mkdirs(); path.writeText(resolved) } }
        }
        return PreparedMotion(url, quality, resolved, resolved, false)
    }

    /** Called only once the first live frame is displayed, so caching cannot delay first paint. */
    fun warm(source: PreparedMotion): CompletableFuture<File?> {
        if (source.cached || directory == null || closed.get()) return CompletableFuture.completedFuture(null)
        val key = key(source.master, source.quality)
        val result = CompletableFuture<File?>()
        inFlight.putIfAbsent(key, result)?.let { return it }
        val expected = synchronized(lock) { generation }
        runCatching { executor.execute {
            var part: File? = null
            try {
                val movieUrl = if (URI(source.remote).path.lowercase().endsWith(".m3u8"))
                    singleFileMotionMovie(source.remote, manifest(source.remote)) else source.remote
                if (movieUrl == null || closed.get()) { result.complete(null); return@execute }
                val destination = checkNotNull(file(key, ".mp4"))
                destination.parentFile.mkdirs()
                val staging = file(key, ".${UUID.randomUUID()}.part")!!
                part = staging
                download(movieUrl, staging)
                require(validMovie(staging)) { "Incomplete motion movie" }
                synchronized(lock) {
                    if (closed.get() || generation != expected) result.complete(null)
                    else {
                        try { Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                        catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING) }
                        prune(destination)
                        result.complete(destination)
                        log.log("motion video cached quality=${source.quality.label} bytes=${destination.length()}")
                    }
                }
            } catch (error: Throwable) {
                log.logOnce("motion-cache-$key", "motion cache skipped; stream remains available: ${error.javaClass.simpleName}")
                result.complete(null)
            } finally { part?.delete(); inFlight.remove(key, result) }
        } }.onFailure { inFlight.remove(key, result); result.complete(null) }
        return result
    }

    fun invalidate(url: String, quality: MotionResolution) = synchronized(lock) {
        val key = key(url, quality)
        file(key, ".mp4")?.delete()
    }

    fun clear() = synchronized(lock) {
        generation++
        streams.clear()
        directory?.listFiles()?.filter { it.isFile && it.name.startsWith("motion-") }?.forEach { it.delete() }
    }

    private fun readStream(key: String): String? = file(key, ".stream")?.takeIf {
        it.isFile && it.length() <= 8192 && System.currentTimeMillis() - it.lastModified() < 30L * 86400000
    }?.let { runCatching { it.readText().takeIf(::isAppleMotionUrl) }.getOrNull() }

    private fun file(key: String, suffix: String) = directory?.resolve("motion-$key$suffix")
    private fun key(url: String, quality: MotionResolution) = MessageDigest.getInstance("SHA-256")
        .digest("$url\n${quality.name}".toByteArray()).joinToString("") { "%02x".format(it) }

    private fun prune(protected: File) {
        val movies = directory?.listFiles()?.filter { it.name.startsWith("motion-") && it.extension == "mp4" && it.isFile }
            ?.sortedBy { it.lastModified() }.orEmpty().toMutableList()
        var total = movies.sumOf { it.length() }
        while (movies.size > 8 || total > 512L * 1024 * 1024) {
            val oldest = movies.firstOrNull { it != protected } ?: break
            movies.remove(oldest)
            total -= oldest.length()
            oldest.delete()
        }
    }

    override fun close() {
        closed.set(true)
        synchronized(lock) { generation++ }
        executor.shutdownNow()
        inFlight.values.forEach { it.complete(null) }
    }
}

private const val MAX_MOVIE_BYTES = 128L * 1024 * 1024
private fun validMovie(file: File): Boolean = runCatching {
    if (!file.isFile || file.length() !in 32..MAX_MOVIE_BYTES) return false
    val header = file.inputStream().use { it.readNBytes(8) }
    header.size == 8 && String(header, 4, 4, Charsets.US_ASCII) in setOf("ftyp", "styp")
}.getOrDefault(false)

/** Apple's short artist VODs use one fragmented MP4 with byte ranges. Never cache DRM/live playlists. */
internal fun singleFileMotionMovie(url: String, playlist: String): String? {
    if (!playlist.trimStart().startsWith("#EXTM3U") || !playlist.contains("#EXT-X-ENDLIST")) return null
    if (playlist.lineSequence().map(String::trim).any { it.startsWith("#EXT-X-KEY:") && !it.contains("METHOD=NONE") }) return null
    val resources = playlist.lineSequence().map(String::trim).mapNotNull { line ->
        when {
            line.startsWith("#EXT-X-MAP:") -> Regex("URI=\"([^\"]+)\"").find(line)?.groupValues?.get(1)
            line.isNotEmpty() && !line.startsWith('#') -> line
            else -> null
        }
    }.map { runCatching { URI(url).resolve(it).toString() }.getOrDefault("") }.toSet()
    return resources.singleOrNull()?.takeIf { isAppleMotionUrl(it) && URI(it).path.lowercase().endsWith(".mp4") }
}

internal fun loadAppleMotionManifest(url: String): String {
    val connection = motionConnection(url)
    return try {
        check(connection.responseCode == 200)
        val bytes = connection.inputStream.use { it.readNBytes(262145) }
        require(bytes.size <= 262144)
        bytes.toString(Charsets.UTF_8).also { require(it.trimStart().startsWith("#EXTM3U")) }
    } finally { connection.disconnect() }
}

private fun motionConnection(url: String): HttpURLConnection {
    require(isAppleMotionUrl(url))
    return (URI(url).toURL().openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = false; connectTimeout = 5000; readTimeout = 15000
        setRequestProperty("User-Agent", "SaltArtist/0.2.2"); setRequestProperty("Accept-Encoding", "identity")
    }
}

private fun downloadAppleMotionMovie(url: String, destination: File) {
    val connection = motionConnection(url)
    try {
        check(connection.responseCode == 200)
        require(connection.contentLengthLong <= MAX_MOVIE_BYTES)
        connection.inputStream.use { input -> destination.outputStream().use { output ->
            val buffer = ByteArray(65536)
            var total = 0L
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val count = input.read(buffer)
                if (count < 0) break
                total += count; require(total <= MAX_MOVIE_BYTES)
                output.write(buffer, 0, count)
            }
            if (connection.contentLengthLong > 0) require(total == connection.contentLengthLong)
        } }
    } finally { connection.disconnect() }
}
