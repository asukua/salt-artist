package dev.saltartist.artwork

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import kotlinx.coroutines.isActive
import androidx.compose.ui.platform.LocalWindowInfo
import dev.saltartist.PluginLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.lang.reflect.Method
import java.lang.reflect.Modifier as JavaModifier
import java.net.URI
import java.net.HttpURLConnection
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** CPU frames are drawn as ordinary raster images; no host video Surface or GPU handles. */
@Composable
internal fun ArtistMotionSurface(url: String, modifier: Modifier, log: PluginLog,
                                frameRate: Int = 30, blurProgress: Float = 0f, onFailure: () -> Unit = {}) {
    var failed by remember(url) { mutableStateOf(false) }
    if (failed || !LocalWindowInfo.current.isWindowFocused) return
    val worker = remember(url, frameRate) { SoftwareMotionWorker() }
    val frames = remember(worker) { OwnedMotionFrame<org.jetbrains.skia.Image>() }
    var revision by remember(worker) { mutableStateOf(0L) }
    DisposableEffect(worker) {
        ArtistMotionPlayers.addSoftware(worker)
        onDispose {
            worker.close()
            ArtistMotionPlayers.removeSoftware(worker)
            frames.close()
        }
    }
    LaunchedEffect(worker, frameRate) {
        try {
            withContext(Dispatchers.IO) {
                require(isAppleMotionUrl(url))
                worker.open(resolveAppleMotionStream(url))
                val started = System.nanoTime()
                var received = false
                while (isActive && !worker.isClosed) {
                    val frameStarted = System.nanoTime()
                    val frame = worker.read()
                    if (frame != null) {
                        val image = org.jetbrains.skia.Image.makeRaster(
                            org.jetbrains.skia.ImageInfo(frame.width(), frame.height(),
                                org.jetbrains.skia.ColorType.BGRA_8888, org.jetbrains.skia.ColorAlphaType.OPAQUE),
                            frame.bgra(), frame.width() * 4)
                        if (frames.replace(image)) { received = true; revision++ }
                    } else if (!received && System.nanoTime() - started > 15_000_000_000L) {
                        error("video produced no CPU frames")
                    }
                    delay(motionFrameDelayMs(frameStarted, System.nanoTime(), frameRate))
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Throwable) {
            failed = true
            log.logOnce("motion-cpu-$url", "CPU motion failed; keeping poster: ${log.describe(error)}")
            onFailure()
        } finally { worker.close() }
    }
    androidx.compose.foundation.Canvas(modifier) {
        revision // Repaint when a new owned CPU frame is published.
        frames.useFrame { image ->
            val scale = maxOf(size.width / image.width, size.height / image.height)
            val width = image.width * scale
            val height = image.height * scale
            drawIntoCanvas { canvas ->
                val sigma = blurProgress.coerceIn(0f, 1f) * 18f * maxOf(size.width, size.height) / 480f
                // Ordinary Skia filtering of our owned raster frame, never a video
                // Surface or a Compose RenderEffect that can outlive a scrolling item.
                val filter = if (sigma > 0.05f) org.jetbrains.skia.ImageFilter.makeBlur(sigma, sigma, org.jetbrains.skia.FilterTileMode.CLAMP) else null
                val paint = org.jetbrains.skia.Paint()
                try {
                    paint.imageFilter = filter
                    canvas.nativeCanvas.drawImageRect(image,
                        org.jetbrains.skia.Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
                        org.jetbrains.skia.Rect.makeLTRB((size.width-width)/2, 0f, (size.width+width)/2, height),
                        org.jetbrains.skia.SamplingMode.LINEAR, paint, true)
                } finally { paint.close(); filter?.close() }
            }
        }
    }
}

/** Account for decode/copy time rather than adding a fixed sleep after each frame. */
internal fun motionFrameDelayMs(started: Long, finished: Long, fps: Int): Long {
    val budget = 1_000_000_000L / fps.coerceIn(15, 30)
    return ((budget - (finished - started).coerceAtLeast(0) + 999_999) / 1_000_000).coerceAtLeast(1)
}

// Kept as a policy boundary: this now enables only CPU drawing, never the old native Surface.
internal fun nativeMotionEnabled(): Boolean = true

internal class SoftwareMotionWorker : AutoCloseable {
    private val stopped = AtomicBoolean(false)
    private val decoder = AtomicReference<SoftwareVideoFrames?>(null)
    val isClosed: Boolean get() = stopped.get()
    fun open(url: String) {
        if (stopped.get()) return
        ensureHostMpvLoaded()
        if (stopped.get()) return
        val opened = SoftwareVideoFrames(url)
        decoder.set(opened)
        if (stopped.get()) decoder.getAndSet(null)?.close()
    }
    fun read(): SoftwareVideoFrames.Frame? = if (stopped.get()) null else decoder.get()?.read()
    override fun close() {
        stopped.set(true)
        decoder.getAndSet(null)?.let { opened -> motionCleanup.execute { opened.close() } }
    }
}

@Synchronized
private fun ensureHostMpvLoaded() {
    if (hostMpvLoaded) return
    // Let the existing host loader extract its bundled libraries. No Surface is created.
    val factory = Class.forName("org.openani.mediamp.mpv.MpvMediampPlayerFactory")
        .getConstructor().newInstance()
    val player = factory.javaClass.getMethod("create", Any::class.java, CoroutineContext::class.java)
        .invoke(factory, Unit, EmptyCoroutineContext)
    (player as AutoCloseable).close()
    hostMpvLoaded = true
}
private var hostMpvLoaded = false

/** This boundary is also exercised without loading any host/native classes. */
internal interface MotionBackend : AutoCloseable {
    fun command(arguments: List<String>): Boolean
    fun hasFrame(): Boolean
}

internal class MotionPlaybackController(private val backend: MotionBackend) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private var closedForComposition by mutableStateOf(false)
    private val surfaceLock = java.lang.Object()
    private var surfaceLeases = 0
    private var started = false
    val isClosed: Boolean get() = closedForComposition

    fun attachSurface(): Boolean = synchronized(surfaceLock) {
        if (closed.get()) false else { surfaceLeases++; true }
    }

    fun detachSurface() = synchronized(surfaceLock) {
        check(surfaceLeases > 0)
        surfaceLeases--
        surfaceLock.notifyAll()
    }

    @Synchronized
    fun start(url: String) {
        check(!closed.get() && !started) { "motion player is not available" }
        try {
            // Fail closed if any audio guard is rejected. These all precede
            // loadfile, so even a stream containing audio cannot open a device.
            for ((property, value) in listOf(
                "aid" to "no",
                "mute" to "yes",
                "volume" to "0",
                "loop-file" to "inf",
                "keep-open" to "no",
                "pause" to "no",
                "force-window" to "no",
                "sub-visibility" to "no",
                "audio-file-auto" to "no",
                "sub-auto" to "no",
                "osd-level" to "0",
            )) {
                check(!closed.get()) { "motion player closed while opening" }
                check(backend.command(listOf("set", property, value))) { "motion option rejected: $property" }
            }
            // The embedded host disables user configuration during construction
            // and does not expose ytdl. Only the verified Apple CDN URI is opened.
            backend.command(listOf("set", "cache", "yes"))
            backend.command(listOf("set", "demuxer-max-bytes", "16777216"))
            backend.command(listOf("set", "demuxer-max-back-bytes", "16777216"))
            backend.command(listOf("set", "demuxer-seekable-cache", "yes"))
            backend.command(listOf("set", "network-timeout", "8"))
            backend.command(listOf("set", "hls-bitrate", "1500000"))
            backend.command(listOf("set", "vd-lavc-threads", "2"))
            check(!closed.get()) { "motion player closed while opening" }
            check(backend.command(listOf("loadfile", url, "replace"))) { "motion stream could not be opened" }
            started = true
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    @Synchronized
    fun hasFrame(): Boolean = !closed.get() && backend.hasFrame()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        closedForComposition = true
        release()
    }

    fun closeAsync() {
        if (!closed.compareAndSet(false, true)) return
        closedForComposition = true
        motionCleanup.execute { release() }
    }

    private fun release() {
        synchronized(this) { runCatching { backend.command(listOf("stop")) } }
        // closeAll can run while the overlay is still composed. Setting the
        // observable closed state removes its Surface; stop ends streaming now,
        // while handle destruction waits for child disposal to finish safely.
        // Never hold this controller's monitor while waiting: UI hasFrame()
        // must return so the main thread can recompose and release its lease.
        synchronized(surfaceLock) {
            while (surfaceLeases > 0) surfaceLock.wait()
        }
        synchronized(this) { runCatching { backend.close() } }
    }
}

private class MotionSurfaceLease(private val controller: MotionPlaybackController) : RememberObserver {
    val attached = controller.attachSurface()
    private val released = AtomicBoolean(false)
    override fun onRemembered() = Unit
    override fun onForgotten() = release()
    override fun onAbandoned() = release()
    private fun release() {
        if (attached && released.compareAndSet(false, true)) controller.detachSurface()
    }
}

/** Also called from plugin shutdown if a host composition outlives its hooks. */
internal object ArtistMotionPlayers {
    private val players = mutableSetOf<MotionPlaybackController>()
    private val software = mutableSetOf<SoftwareMotionWorker>()
    @Synchronized fun addSoftware(worker: SoftwareMotionWorker): Boolean {
        if (!active) { worker.close(); return false }; return software.add(worker)
    }
    @Synchronized fun removeSoftware(worker: SoftwareMotionWorker) { software.remove(worker) }
    private var epoch = 0L
    private var active = false

    @Synchronized
    fun begin() {
        epoch++
        players.toList().forEach(MotionPlaybackController::closeAsync)
        players.clear()
        software.toList().forEach { it.close() }
        software.clear()
        active = true
    }

    @Synchronized
    fun generation(): Long = epoch

    @Synchronized
    fun accepts(generation: Long): Boolean = active && generation == epoch

    @Synchronized
    fun add(player: MotionPlaybackController, generation: Long = epoch): Boolean =
        active && generation == epoch && players.add(player)

    @Synchronized
    fun remove(player: MotionPlaybackController) { players.remove(player) }

    @Synchronized
    fun closeAll() {
        active = false
        epoch++
        players.toList().forEach(MotionPlaybackController::closeAsync)
        players.clear()
        software.toList().forEach { it.close() }
        software.clear()
    }
}

internal fun isAppleMotionUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    val host = uri.host?.lowercase() ?: return@runCatching false
    uri.scheme.equals("https", ignoreCase = true) && uri.userInfo == null &&
        (uri.port == -1 || uri.port == 443) &&
        (host == "itunes.apple.com" || host.endsWith(".itunes.apple.com") ||
            host == "mzstatic.com" || host.endsWith(".mzstatic.com")) &&
        uri.path.orEmpty().lowercase().let { it.endsWith(".m3u8") || it.endsWith(".mp4") || it.endsWith(".mov") }
}.getOrDefault(false)

private val motionCleanup = ThreadPoolExecutor(
    0, 1, 10, TimeUnit.SECONDS, LinkedBlockingQueue(),
    { task -> Thread(task, "SaltArtist-motion-cleanup").apply {
        isDaemon = true
        contextClassLoader = ClassLoader.getPlatformClassLoader()
    } },
)

/** Highest full-HD H.264 SDR stream; preview playlists and larger frames stay excluded. */
internal fun selectAppleMotionVariant(masterUrl: String, playlist: String): String? {
    data class Variant(val url: String, val pixels: Long, val bandwidth: Long)
    val candidates = mutableListOf<Variant>()
    var pending: String? = null
    for (raw in playlist.lineSequence()) {
        val line = raw.trim()
        if (line.startsWith("#EXT-X-STREAM-INF:")) {
            pending = line.substringAfter(':')
        } else if (line.isNotEmpty() && !line.startsWith('#')) {
            val attributes = pending ?: continue
            pending = null
            fun attribute(name: String): String? = Regex("(?:^|,)" + Regex.escape(name) + "=(\"[^\"]*\"|[^,]*)")
                .find(attributes)?.groupValues?.get(1)?.trim('"')
            val codec = attribute("CODECS").orEmpty().lowercase()
            val range = attribute("VIDEO-RANGE").orEmpty()
            if (!codec.split(',').any { it.trim().startsWith("avc1") } || (range.isNotEmpty() && range != "SDR")) continue
            val dimensions = attribute("RESOLUTION")?.split('x')?.mapNotNull(String::toLongOrNull)
            if (dimensions?.size != 2 || dimensions.any { it <= 0 || it > 8192 }) continue
            if (dimensions[0] > SoftwareVideoFrames.MAX_WIDTH || dimensions[1] > SoftwareVideoFrames.MAX_HEIGHT) continue
            val uri = runCatching { URI(masterUrl).resolve(line).toString() }.getOrNull() ?: continue
            if (!isAppleMotionUrl(uri)) continue
            candidates += Variant(uri, dimensions[0] * dimensions[1],
                attribute("BANDWIDTH")?.toLongOrNull()?.coerceAtLeast(0) ?: 0)
        }
    }
    // The old smallest-first policy enlarged 480x270 video across a desktop hero.
    // Resolution wins; for equal dimensions use the less compressed rendition.
    return candidates.maxWithOrNull(compareBy<Variant> { it.pixels }.thenBy { it.bandwidth })?.url
}

private fun resolveAppleMotionStream(url: String): String {
    if (!URI(url).path.lowercase().endsWith(".m3u8")) return url
    // Only a small master manifest is inspected. Media segments and buffering
    // remain in the independent native decoder and disappear when it is closed.
    return runCatching {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 SaltArtist/0.1.7")
        connection.setRequestProperty("Accept-Encoding", "identity")
        try {
            check(connection.responseCode == 200)
            val bytes = connection.inputStream.use { it.readNBytes(262_145) }
            check(bytes.size <= 262_144)
            val text = bytes.toString(Charsets.UTF_8)
            check(text.trimStart().startsWith("#EXTM3U"))
            selectAppleMotionVariant(url, text) ?: url
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(url)
}

internal fun isMotionCommandMethod(method: Method): Boolean =
    !JavaModifier.isStatic(method.modifiers) && method.returnType == Boolean::class.javaPrimitiveType &&
        method.parameterTypes.contentEquals(arrayOf(Array<String>::class.java))
