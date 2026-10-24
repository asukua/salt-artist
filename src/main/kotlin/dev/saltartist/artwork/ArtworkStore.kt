package dev.saltartist.artwork

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.xuncorp.spw.workshop.api.WorkshopApi
import dev.saltartist.PluginLog
import dev.saltartist.am.AppleMusicCatalog
import dev.saltartist.am.AmArtistDetails
import dev.saltartist.am.AmArtistMotion
import dev.saltartist.am.AmArtist
import dev.saltartist.am.atomicCacheWrite
import dev.saltartist.am.artistIdentityKey
import dev.saltartist.am.ArtistLanguagePolicy
import dev.saltartist.am.ArtistIdentityPolicy
import dev.saltartist.am.ArtistMatchCandidate
import dev.saltartist.data.normalizeArtistKey
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import java.io.File
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO

/**
 * 艺人页的大图来源，按优先级尝试：
 *  1. 用户放入 `<插件目录>/artist-photos/<规范化艺人名>.jpg|png|webp` 的高清写真；
 *  2. 曲库内嵌封面（Workshop Library.getCoverById），取该艺人第一首可读歌曲；
 *  3. 回退到 [ArtworkPalette.Fallback] 的纯色渐变，页面仍保持布局稳定。
 *
 * 所有解码都在单线程池里做，避免大封面在 Compose 帧内解码造成卡顿。
 * 状态用 SnapshotStateMap 暴露，加载完成即触发重组。
 */
class ArtworkStore(
    private val pluginDir: File?,
    private val log: PluginLog,
    private val catalog: AppleMusicCatalog? = null,
) {
    internal val motionCache = MotionVideoCache(pluginDir, log)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "salt-artist-artwork").apply { isDaemon = true }
    }
    // Slow catalog/profile requests must not hold up local photos and library covers.
    private val appleExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "salt-artist-apple").apply { isDaemon = true }
    }
    private val appleLocalExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "salt-artist-apple-cache").apply { isDaemon = true }
    }
    @Volatile private var closed = false
    private val inFlight = ConcurrentHashMap<String, Boolean>()
    private val requested = AtomicInteger()

    private val coverCache = BoundedArtworkCache<String, Artwork>(128)
    private val heroCoverCache = BoundedArtworkCache<String, Artwork>(4)
    private val customPhotoCache = BoundedArtworkCache<String, Artwork>(4)
    private val localPhotoCache = BoundedArtworkCache<String, Artwork>(4)
    private val localLogoCache = BoundedArtworkCache<String, ImageBitmap?>(8)
    private val appleCache = BoundedArtworkCache<String, AmArt>(4)
    private val appleInFlight = ConcurrentHashMap<String, Boolean>()
    private val appleCompletedAt = ConcurrentHashMap<String, Long>()
    private val appleRequests = ArtistArtworkRequests()
    private val directoryAvatars = ArtistAvatarStore(pluginDir, log, catalog)

    private val photoDir: File? = pluginDir?.let { File(it, "artist-photos") }

    /** Apple Music 侧拿到的头图 + 艺术字；两者都可缺。 */
    data class AmArt(
        val hero: Artwork?, val logo: ImageBitmap?,
        val avatar: ImageBitmap? = null, val details: AmArtistDetails? = null,
        val motion: AmArtistMotion? = null,
        val incompleteImages: Boolean = false,
    ) {
        val retryable: Boolean get() = incompleteImages ||
            (hero == null && logo == null && avatar == null && details == null && motion == null)
    }

    /** Called on a worker thread before saving an imported logo. */
    fun cacheLocalLogo(file: File): Boolean {
        val key = "${file.absolutePath}|${file.lastModified()}|${file.length()}"
        val image = runCatching {
            downscale(checkedImage(readLocalImage(file)), MAX_ART_SIDE).toComposeImageBitmap()
        }.onFailure { log.log("custom logo decode failed: ${log.describe(it)}") }.getOrNull()
        localLogoCache[key] = image
        return image != null
    }

    /** Logos keep their alpha channel and use a separate cache from photographs. */
    fun localLogo(file: File?): ImageBitmap? {
        if (file == null || !file.isFile) return null
        val key = "${file.absolutePath}|${file.lastModified()}|${file.length()}"
        if (localLogoCache.containsKey(key)) return localLogoCache[key]
        if (inFlight.putIfAbsent("logo:$key", true) == null) {
            executor.execute {
                try {
                    cacheLocalLogo(file)
                } finally { inFlight.remove("logo:$key") }
            }
        }
        return localLogoCache[key]
    }

    /**
     * Apple Music 素材：后台联网抓一次（结果落盘缓存），完成后触发重组。
     * 命中内存缓存时直接返回。
     */
    fun appleArt(artistName: String, policy: ArtistLanguagePolicy = ArtistLanguagePolicy(),
                 identity: ArtistIdentityPolicy = ArtistIdentityPolicy(enabled = false)): AmArt? {
        if (closed) return null
        val cat = catalog ?: return null
        // Each queued request captures its own immutable matching policy. A late
        // result cannot become the image selected after a preference change.
        val key = appleArtworkCacheKey(artistName, policy, identity)
        appleCache[key]?.let { hit ->
            if (!hit.retryable || System.currentTimeMillis() - (appleCompletedAt[key] ?: 0L) < EMPTY_RETRY_MS) return hit
        }
        val artistKey = artistIdentityKey(artistName)
        val generation = appleRequests.generation(artistKey)
        val requestKey = "$key|generation=$generation"
        if (appleInFlight.putIfAbsent(requestKey, true) != null) return appleCache[key]

        fun publish(value: AmArt) {
            val published = appleRequests.publish(artistKey, generation) update@{
                if (closed) return@update
                appleCompletedAt[key] = System.currentTimeMillis()
                val previous = appleCache[key]
                appleCache[key] = if (previous == null) value else value.copy(
                    hero = value.hero ?: previous.hero, logo = value.logo ?: previous.logo,
                    avatar = value.avatar ?: previous.avatar, details = value.details ?: previous.details,
                    motion = if (value.hero != null) value.motion else previous.motion,
                )
            }
            if (published && !closed && value.avatar != null) directoryAvatars.warm(artistName, identity)
        }

        fun current() = !closed && appleRequests.generation(artistKey) == generation
        fun mutateCache(action: () -> Unit) {
            appleRequests.publish(artistKey, generation) { if (!closed) action() }
        }
        fun refresh() {
            try {
                if (!current()) return
                val meta = cat.resolve(artistName, policy, identity = identity)
                if (meta == null) {
                    publish(AmArt(null, null))
                    return
                }
                if (current()) publish(readAppleAssets(artistName, identity, meta, network = true, ::mutateCache))
            } catch (t: Throwable) {
                log.log("apple-music failed for \"$artistName\": ${log.describe(t)}")
                publish(AmArt(null, null))
            } finally {
                appleInFlight.remove(requestKey)
            }
        }
        // A cached page never waits behind another artist's profile or image download.
        runCatching { appleLocalExecutor.execute {
            var remoteScheduled = false
            try {
                if (!current()) return@execute
                val start = System.nanoTime()
                val meta = cat.cached(artistName, policy, identity)
                val local = meta?.let { readAppleAssets(artistName, identity, it, network = false, ::mutateCache) }
                if (local != null && current()) {
                    publish(local)
                    log.logOnce("local-art:$key", "apple-music local assets artist=\"$artistName\" ms=${(System.nanoTime() - start) / 1_000_000}")
                    if (!local.retryable && cat.cachedIsFresh(artistName, policy, identity)) return@execute
                }
                if (current()) { appleExecutor.execute(::refresh); remoteScheduled = true }
            } catch (error: Throwable) {
                if (current()) { appleExecutor.execute(::refresh); remoteScheduled = true }
                log.log("apple-music local cache unavailable: ${log.describe(error)}")
            } finally { if (!remoteScheduled) appleInFlight.remove(requestKey) }
        } }.onFailure { appleInFlight.remove(requestKey) }
        return appleCache[key]
    }

    private fun readAppleAssets(name: String, identity: ArtistIdentityPolicy, meta: AmArtist, network: Boolean,
                               mutateCache: (() -> Unit) -> Unit): AmArt {
        val cat = checkNotNull(catalog)
        fun bytes(file: File?, url: String): ByteArray? = if (network) loadImage(file, url, mutateCache) else file
            ?.takeIf { it.isFile && it.length() in 1..MAX_IMAGE_BYTES.toLong() }
            ?.let { runCatching { readLocalImage(it) }.getOrNull() }
        val hero = meta.heroTemplate?.let { template ->
            bytes(cat.heroFile(name, identity), cat.imageUrl(template, meta.heroCrop, 1600, if (meta.heroIsWide) 800 else 1600))
        }?.let { runCatching { decode(it) }.onFailure { mutateCache { cat.heroFile(name, identity)?.delete() } }.getOrNull() }
        val logo = meta.logoTemplate?.let { template ->
            bytes(cat.logoFile(name, identity), cat.imageUrl(template, meta.logoCrop, 1000, 320, "png"))
        }?.let { runCatching { downscale(checkedImage(it), 1000).toComposeImageBitmap() }
            .onFailure { mutateCache { cat.logoFile(name, identity)?.delete() } }.getOrNull() }
        val avatar = meta.avatarTemplate?.let { template ->
            bytes(cat.avatarFile(name, identity), cat.imageUrl(template, meta.avatarCrop, 900, 900))
        }?.let { runCatching { downscale(checkedImage(it), 900).toComposeImageBitmap() }
            .onFailure { mutateCache { cat.avatarFile(name, identity)?.delete() } }.getOrNull() }
        return AmArt(hero, logo, avatar, meta.details, meta.motion,
            incompleteImages = (meta.heroTemplate != null && hero == null) ||
                (meta.logoTemplate != null && logo == null) || (meta.avatarTemplate != null && avatar == null))
    }

    /**
     * 丢掉某个艺人的全部派生缓存（Apple Music 元数据 + 图片、合成图），下次访问会重新抓。
     * 供「刷新头图」用；调用前请自行确认没有自定义写真。
     */
    fun invalidateArtist(artistName: String) {
        val key = normalizeArtistKey(artistName)
        val stem = ArtistIdentityPolicy().artistCacheStem(artistName)
        appleRequests.invalidate(artistIdentityKey(artistName)) {
            appleCache.keys.filter { it.startsWith("$key|$stem--") }.forEach {
                appleCache.remove(it)
                appleCompletedAt.remove(it)
            }
            catalog?.forget(artistName)
            directoryAvatars.invalidate(artistName)
        }
        customPhotoCache.remove(key)
        localPhotoCache.keys.filter { it.startsWith("$key|") }.forEach { localPhotoCache.remove(it) }
    }

    fun identityChanged(artistName: String) {
        appleRequests.invalidate(artistIdentityKey(artistName)) {
            catalog?.identityChanged(artistName)
            directoryAvatars.identityChanged(artistName)
        }
    }

    /** 磁盘优先；没有就下载一次再落盘。 */
    private fun loadImage(file: File?, url: String, mutateCache: (() -> Unit) -> Unit): ByteArray? {
        val cat = catalog ?: return null
        if (file != null && file.isFile && file.length() > 0) {
            return runCatching { readLocalImage(file) }.getOrNull()
        }
        val bytes = runCatching { download(url) }.getOrNull() ?: return null
        if (file != null && bytes.isNotEmpty()) {
            runCatching {
                mutateCache {
                    cat.ensureImageDir()
                    atomicCacheWrite(file, bytes)
                }
            }.onFailure { log.log("apple-music image write failed: ${log.describe(it)}") }
        }
        return bytes
    }

    private fun download(url: String): ByteArray? {
        val conn = URI(url).toURL().openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 15000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) SaltArtist/0.1")
        return try {
            if (conn.responseCode !in 200..299) {
                log.log("apple-music image http ${conn.responseCode} $url")
                null
            } else {
                conn.inputStream.use { it.readNBytes(MAX_IMAGE_BYTES + 1) }.also {
                    require(it.size <= MAX_IMAGE_BYTES) { "image response too large" }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private val photoIndex: Map<String, File> by lazy {
        val dir = photoDir ?: return@lazy emptyMap()
        if (!dir.isDirectory) {
            dir.mkdirs()
            log.log("artist photo directory created: ${dir.absolutePath}")
            return@lazy emptyMap()
        }
        dir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in IMAGE_EXTENSIONS }
            ?.associate { normalizeArtistKey(it.nameWithoutExtension) to it }
            .orEmpty()
            .also { log.log("artist photo index size=${it.size} dir=${dir.absolutePath}") }
    }

    fun photoDirectory(): String? = photoDir?.absolutePath

    /** 用户通过「编辑艺人」指定的单张写真（优先级最高）。 */
    fun localPhoto(artistName: String, file: File): Artwork? {
        // Include the selected file identity: changing an editor photo must not
        // reuse the directory image or a previously selected photo for this artist.
        val key = "${normalizeArtistKey(artistName)}|${file.absolutePath}|${file.lastModified()}"
        localPhotoCache[key]?.let { return it }
        if (inFlight.putIfAbsent("custom:$key", true) == null) {
            executor.execute {
                val artwork = runCatching { decode(readLocalImage(file)) }.getOrNull()
                if (artwork != null) {
                    localPhotoCache[key] = artwork
                } else {
                    log.log("custom photo decode failed: ${file.absolutePath}")
                }
                inFlight.remove("custom:$key")
            }
        }
        return localPhotoCache[key]
    }

    /** 艺人写真：自定义写真 > Apple Music 头图 > 曲库封面。 */
    fun artworkFor(artistName: String, coverTrackId: String?, policy: ArtistLanguagePolicy = ArtistLanguagePolicy(),
                   identity: ArtistIdentityPolicy = ArtistIdentityPolicy(enabled = false)): Artwork? {
        val key = normalizeArtistKey(artistName)
        customPhotoCache[key]?.let { return it }

        val photo = photoIndex[key]
        if (photo != null && inFlight.putIfAbsent("photo:$key", true) == null) {
            executor.execute {
                val artwork = runCatching { decode(readLocalImage(photo)) }.getOrNull()
                if (artwork != null) customPhotoCache[key] = artwork
                else log.log("artist photo decode failed: ${photo.name}")
                inFlight.remove("photo:$key")
            }
        }
        if (photo != null) customPhotoCache[key]?.let { return it }

        // Apple Music 头图单独缓存，绝不被曲库封面占用艺人缓存槽位。
        appleArt(artistName, policy, identity)?.hero?.let { return it }

        val trackId = coverTrackId?.takeIf(String::isNotBlank) ?: return null
        return heroCover(trackId)
    }

    /** Bake blur only for currently visited hero fallbacks, never for the entire cover grid. */
    fun heroCover(trackId: String): Artwork? {
        if (trackId.isBlank()) return null
        heroCoverCache[trackId]?.let { return it }
        val key = "hero-cover:$trackId"
        if (inFlight.putIfAbsent(key, true) == null) runCatching {
            WorkshopApi.library.getCoverById(trackId).whenComplete { bytes, error ->
                if (error != null || bytes == null || bytes.isEmpty()) inFlight.remove(key)
                else runCatching { executor.execute {
                    try { runCatching { decode(bytes, maxSide = 640, blur = true) }.getOrNull()?.let { heroCoverCache[trackId] = it } }
                    finally { inFlight.remove(key) }
                } }.onFailure { inFlight.remove(key) }
            }
        }.onFailure { inFlight.remove(key) }
        return cover(trackId)
    }

    /** 专辑封面。 */
    fun cover(trackId: String, artistKey: String = ""): Artwork? {
        coverCache[trackId]?.let { return it }
        if (requested.get() > MAX_REQUESTS) return null
        if (inFlight.putIfAbsent("cover:$trackId", true) != null) return null
        requested.incrementAndGet()

        runCatching {
            WorkshopApi.library.getCoverById(trackId).whenComplete { bytes, error ->
                if (error != null || bytes == null || bytes.isEmpty()) {
                    inFlight.remove("cover:$trackId")
                    log.log("cover missing id=$trackId error=${log.describe(error)}")
                    return@whenComplete
                }
                executor.execute {
                    val artwork = runCatching { decode(bytes, maxSide = 640, blur = false) }.getOrNull()
                    if (artwork != null) {
                        coverCache[trackId] = artwork
                    }
                    inFlight.remove("cover:$trackId")
                }
            }
        }.onFailure {
            inFlight.remove("cover:$trackId")
            log.log("cover request failed id=$trackId: ${log.describe(it)}")
        }
        return coverCache[trackId]
    }

    fun clear() {
        motionCache.clear()
        coverCache.clear()
        heroCoverCache.clear()
        customPhotoCache.clear()
        localPhotoCache.clear()
        localLogoCache.clear()
        appleRequests.clear { appleCache.clear(); appleCompletedAt.clear() }
        directoryAvatars.clear()
        inFlight.clear()
    }

    fun shutdown() {
        closed = true
        motionCache.close()
        executor.shutdownNow()
        appleExecutor.shutdownNow()
        appleLocalExecutor.shutdownNow()
        directoryAvatars.close()
    }

    /** Small circular portraits only; never starts hero/profile downloads. */
    fun artistAvatar(artistName: String, identity: ArtistIdentityPolicy = ArtistIdentityPolicy(enabled = false)): ImageBitmap? =
        directoryAvatars.avatar(artistName, identity)

    internal fun directoryAvatarRevision(name: String): Pair<Long, Long> = directoryAvatars.revision(name)

    /** Preserve native, user-selected directory covers without creating hero blur caches. */
    fun directoryCover(path: String?): ImageBitmap? {
        val file = path?.let { value -> runCatching {
            if (value.startsWith("file:")) File(URI(value)) else File(value)
        }.getOrNull() }?.takeIf { it.isFile } ?: return null
        val key = "directory:${file.absolutePath}|${file.lastModified()}|${file.length()}"
        coverCache[key]?.let { return it.image }
        if (inFlight.putIfAbsent(key, true) == null) runCatching { executor.execute {
            try {
                runCatching { decode(readLocalImage(file), maxSide = 320, blur = false) }.getOrNull()?.let { coverCache[key] = it }
            } finally { inFlight.remove(key) }
        } }.onFailure { inFlight.remove(key) }
        return null
    }

    /** Explicit UI search runs on its caller's IO worker, separate from image loading. */
    fun searchArtistCandidates(query: String, identity: ArtistIdentityPolicy): List<ArtistMatchCandidate> =
        catalog?.searchCandidates(query, identity).orEmpty()

    private fun readLocalImage(file: File): ByteArray {
        require(file.length() <= MAX_IMAGE_BYTES) { "image file too large" }
        return file.inputStream().use { it.readNBytes(MAX_IMAGE_BYTES + 1) }.also { require(it.size <= MAX_IMAGE_BYTES) }
    }

    private fun checkedImage(bytes: ByteArray): Image {
        require(bytes.size <= MAX_IMAGE_BYTES)
        val image = Image.makeFromEncoded(bytes)
        if (!acceptableImageDimensions(image.width, image.height)) {
            image.close()
            error("image dimensions too large")
        }
        return image
    }

    private fun decode(bytes: ByteArray, maxSide: Int = MAX_ART_SIDE, blur: Boolean = true): Artwork? {
        if (bytes.isEmpty()) return null
        val encoded = checkedImage(bytes)
        // 艺人写真常见 2000–4000px 的原图，后台先缩到页面需要的尺寸。
        // 这里必须把原始不透明位图交给 Compose；任何 alpha 淡出都放在 UI 的
        // 独立过渡层里，否则首屏也会被透明像素和页面底色污染。
        val original = runCatching { paletteThumbnail(bytes) }.getOrNull()
        val skia = downscale(encoded, maxSide)
        val image: ImageBitmap = skia.toComposeImageBitmap()
        val blurred = if (blur) runCatching { buildBlurSteps(skia) }.getOrDefault(emptyList()) else emptyList()
        val palette = original?.let { PaletteExtractor.extract(it) } ?: ArtworkPalette.Fallback
        return Artwork(image, palette, blurred)
    }
    /** 长边超过 [maxSide] 时等比缩到 [maxSide]；否则原样返回。 */
    private fun downscale(source: Image, maxSide: Int): Image {
        val longest = maxOf(source.width, source.height)
        if (longest <= maxSide) return source
        val scale = maxSide.toFloat() / longest
        val w = (source.width * scale).toInt().coerceAtLeast(1)
        val h = (source.height * scale).toInt().coerceAtLeast(1)
        val surface = Surface.makeRasterN32Premul(w, h)
        surface.canvas.drawImageRect(
            source,
            Rect.makeWH(w.toFloat(), h.toFloat()),
            Paint().apply { isAntiAlias = true },
        )
        val snapshot = surface.makeImageSnapshot()
        surface.close()
        source.close()
        return snapshot
    }

    /**
     * 预生成几档模糊位图，替代 UI 里逐帧的 `Modifier.blur`。
     *
     * 原因：renderEffect 每帧重建代价高，而且模糊图层随 item 滚出视口后
     * 桌面端会留下一条被拖糊的残影（见 README「滚动掉帧与释放头图」）。
     * 这里一次性在后台线程把模糊档位烤进位图，UI 只是按滚动进度换图，零额外绘制开销。
     *
     * 档位在最长的边 480px 的缩略图上生成（反正要糊），sigma 随档位递增。
     * 返回的列表长度与 [Artwork.BLUR_STEPS] 一致。
     */
    private fun buildBlurSteps(source: Image): List<ImageBitmap> {
        val longest = maxOf(source.width, source.height)
        if (longest <= 0) return emptyList()
        val scale = (BLUR_SAMPLE_SIDE.toFloat() / longest).coerceAtMost(1f)
        val w = (source.width * scale).toInt().coerceAtLeast(1)
        val h = (source.height * scale).toInt().coerceAtLeast(1)
        return BLUR_SIGMAS.map { sigma ->
            Surface.makeRasterN32Premul(w, h).use { surface ->
                ImageFilter.makeBlur(sigma, sigma, FilterTileMode.CLAMP).use { filter ->
                    Paint().use { paint ->
                        paint.imageFilter = filter
                        surface.canvas.drawImageRect(source, Rect.makeWH(w.toFloat(), h.toFloat()), paint)
                    }
                }
                surface.makeImageSnapshot().toComposeImageBitmap()
            }
        }
    }

    private companion object {
        const val MAX_REQUESTS = 512
        const val EMPTY_RETRY_MS = 60_000L
        const val BLUR_SAMPLE_SIDE = 480

        /** 交给 UI 的写真最长边（超过就等比缩下来）。 */
        const val MAX_ART_SIDE = 1600
        val BLUR_SIGMAS = listOf(2.5f, 5.5f, 9f, 13f, 18f)
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "bmp")
    }
}

data class Artwork(
    val image: ImageBitmap,
    val palette: ArtworkPalette,
    /** 由模糊轻到重排列的预生成档位；为空时 UI 退回原图。 */
    val blurred: List<ImageBitmap> = emptyList(),
) {
    /** 按 0..1 的虚化进度取最接近的一档；0 返回原图。 */
    fun forBlurProgress(progress: Float): ImageBitmap {
        if (blurred.isEmpty() || progress <= 0.02f) return image
        val idx = ((progress.coerceIn(0f, 1f)) * blurred.size).toInt().coerceIn(0, blurred.size - 1)
        return blurred[idx]
    }
}
