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

/**
 * The host already ships MediaMP, libmpv, and its native Compose/Skia renderer.
 * Each hero owns a separate silent player; it never borrows PlaybackController.
 * Reflection is resolved only when the visible hero requests motion, after hooks
 * have been installed. No decoder, executable, or host class enters the plugin.
 */
@Composable
internal fun ArtistMotionSurface(
    url: String,
    modifier: Modifier,
    log: PluginLog,
    onFailure: () -> Unit = {},
) {
    // Headless decoding did not verify the shared GPU surface. Do not create a
    // native player until the reported host exits have been diagnosed.
    if (!nativeMotionEnabled()) return
    var failed by remember(url) { mutableStateOf(false) }
    val failureCallback by rememberUpdatedState(onFailure)
    // Returning removes the effects and native surface, so switching windows
    // does not leave a hidden decoder/network stream running in the background.
    if (failed || !LocalWindowInfo.current.isWindowFocused) return

    var session by remember(url) { mutableStateOf<HostMotionSession?>(null) }
    var ready by remember(url) { mutableStateOf(false) }
    val ownership = remember(url) { AtomicReference<HostMotionSession?>(null) }
    fun fail(error: Throwable?) {
        if (failed) return
        failed = true
        log.logOnce("motion-failed-$url", "artist motion unavailable; keeping poster: ${log.describe(error)}")
        failureCallback()
    }

    DisposableEffect(ownership) {
        onDispose {
            ownership.getAndSet(null)?.controller?.let {
                ArtistMotionPlayers.remove(it)
                it.closeAsync()
            }
        }
    }
    LaunchedEffect(url) {
        val generation = ArtistMotionPlayers.generation()
        if (!ArtistMotionPlayers.accepts(generation)) return@LaunchedEffect
        var opened: HostMotionSession? = null
        try {
            withContext(Dispatchers.IO) {
                require(isAppleMotionUrl(url)) { "unsupported artist motion URL" }
                val stream = resolveAppleMotionStream(url)
                ensureActive()
                if (!ArtistMotionPlayers.accepts(generation)) throw CancellationException("motion plugin stopped while opening")
                val candidate = HostMotionSession.create()
                opened = candidate
                ownership.set(candidate)
                if (!ArtistMotionPlayers.add(candidate.controller, generation)) {
                    throw CancellationException("motion plugin stopped while opening")
                }
                candidate.controller.start(stream)
                ensureActive()
            }
            session = opened
        } catch (cancelled: CancellationException) {
            opened?.controller?.let { ArtistMotionPlayers.remove(it); it.closeAsync() }
            throw cancelled
        } catch (error: Throwable) {
            opened?.controller?.let { ArtistMotionPlayers.remove(it); it.closeAsync() }
            ensureActive()
            fail(error)
        }
    }

    val player = session ?: return
    LaunchedEffect(player) {
        // The static poster remains fully visible until Skia has received an
        // actual frame, rather than merely until the HLS metadata is available.
        repeat(100) {
            val available = runCatching { player.controller.hasFrame() }
            if (available.getOrDefault(false)) {
                ready = true
                return@LaunchedEffect
            }
            if (available.isFailure || player.controller.isClosed) {
                fail(available.exceptionOrNull())
                return@LaunchedEffect
            }
            delay(150)
        }
        fail(IllegalStateException("video did not produce a frame within 15 seconds"))
    }
    if (!player.controller.isClosed) {
        // A nonzero preparation alpha lets Skia allocate/render its first frame;
        // alpha=0 can cause the graphics layer to skip all drawing indefinitely.
        HostMotionSurface(player, modifier.graphicsLayer { alpha = if (ready) 1f else 0.001f }) { fail(it) }
    }
}

internal fun nativeMotionEnabled(): Boolean = false

@OptIn(InternalComposeApi::class)
@Composable
private fun HostMotionSurface(session: HostMotionSession, modifier: Modifier, onFailure: (Throwable) -> Unit) {
    // The lease is remembered before the child Surface: forgotten/abandoned
    // children release their native render callbacks/textures before its lease.
    val lease = remember(session.controller) { MotionSurfaceLease(session.controller) }
    if (!lease.attached) return
    val composer = currentComposer
    val failure = runCatching {
        session.surface.invoke(session.provider, session.player, modifier, composer, 0)
    }.exceptionOrNull()
    if (failure != null) SideEffect { onFailure(failure.cause ?: failure) }
}

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
    private var epoch = 0L
    private var active = false

    @Synchronized
    fun begin() {
        epoch++
        players.toList().forEach(MotionPlaybackController::closeAsync)
        players.clear()
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

/** Prefer an ordinary H.264 SDR stream, never Apple's I-frame preview playlist. */
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
            val uri = runCatching { URI(masterUrl).resolve(line).toString() }.getOrNull() ?: continue
            if (!isAppleMotionUrl(uri)) continue
            candidates += Variant(uri, dimensions[0] * dimensions[1], attribute("BANDWIDTH")?.toLongOrNull() ?: Long.MAX_VALUE)
        }
    }
    val usable = candidates.filter { it.pixels in (640L * 360)..(1280L * 720) }.ifEmpty { candidates }
    return usable.minWithOrNull(compareBy<Variant> { it.pixels }.thenBy { it.bandwidth })?.url
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

private class HostMotionSession(
    val player: Any,
    val provider: Any,
    val surface: Method,
    val controller: MotionPlaybackController,
) {
    companion object {
        fun create(): HostMotionSession {
            val loader = HostMotionSession::class.java.classLoader
            val factory = Class.forName("org.openani.mediamp.mpv.MpvMediampPlayerFactory", true, loader)
                .getConstructor().newInstance()
            val create = factory.javaClass.getMethod("create", Any::class.java, CoroutineContext::class.java)
            val player = create.invoke(factory, Unit, EmptyCoroutineContext)
            try {
                val provider = Class.forName(
                    "org.openani.mediamp.mpv.compose.MpvMediampPlayerSurfaceProvider", true, loader,
                ).getConstructor().newInstance()
                val surface = provider.javaClass.methods.single {
                    it.name == "Surface" && it.parameterCount == 4 && it.parameterTypes[0].isInstance(player) &&
                        it.parameterTypes[1] == Modifier::class.java &&
                        it.parameterTypes[2].name == "androidx.compose.runtime.Composer" &&
                        it.parameterTypes[3] == Int::class.javaPrimitiveType
                }
                val handleGetter = player.javaClass.methods.single {
                    it.parameterCount == 0 && !JavaModifier.isStatic(it.modifiers) &&
                        it.returnType.name.startsWith("org.openani.mediamp.mpv.") &&
                        it.returnType.methods.any(::isMotionCommandMethod)
                }
                val handle = handleGetter.invoke(player)
                val command = handle.javaClass.methods.single(::isMotionCommandMethod)
                // The host renderer keeps the latest successfully rendered Skia
                // image. Discover fields by type, never by obfuscated field name.
                val rendererField = player.javaClass.declaredFields.single {
                    it.type.declaredFields.any { field -> field.type.name == "org.jetbrains.skia.Image" }
                }.apply { isAccessible = true }
                val renderer = checkNotNull(rendererField.get(player)) { "host has no supported video renderer" }
                val imageField = renderer.javaClass.declaredFields.single {
                    it.type.name == "org.jetbrains.skia.Image"
                }.apply { isAccessible = true }
                val backend = object : MotionBackend {
                    override fun command(arguments: List<String>): Boolean =
                        command.invoke(handle, arguments.toTypedArray() as Any) as Boolean
                    override fun hasFrame(): Boolean = imageField.get(renderer) != null
                    override fun close() { (player as AutoCloseable).close() }
                }
                return HostMotionSession(player, provider, surface, MotionPlaybackController(backend))
            } catch (error: Throwable) {
                runCatching { (player as AutoCloseable).close() }
                throw error
            }
        }
    }
}

internal fun isMotionCommandMethod(method: Method): Boolean =
    !JavaModifier.isStatic(method.modifiers) && method.returnType == Boolean::class.javaPrimitiveType &&
        method.parameterTypes.contentEquals(arrayOf(Array<String>::class.java))
