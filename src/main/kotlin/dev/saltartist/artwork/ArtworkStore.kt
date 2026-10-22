package dev.saltartist.artwork

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.xuncorp.spw.workshop.api.WorkshopApi
import dev.saltartist.PluginLog
import dev.saltartist.am.AppleMusicCatalog
import dev.saltartist.am.AmArtistDetails
import dev.saltartist.am.ArtistLanguagePolicy
import dev.saltartist.am.ArtistIdentityPolicy
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
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "salt-artist-artwork").apply { isDaemon = true }
    }
    // Slow catalog/profile requests must not hold up local photos and library covers.
    private val appleExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "salt-artist-apple").apply { isDaemon = true }
    }
    private val inFlight = ConcurrentHashMap<String, Boolean>()
    private val requested = AtomicInteger()

    private val coverCache = mutableStateMapOf<String, Artwork>()
    private val customPhotoCache = mutableStateMapOf<String, Artwork>()
    private val localPhotoCache = mutableStateMapOf<String, Artwork>()
    private val localLogoCache = mutableStateMapOf<String, ImageBitmap?>()
    private val appleCache = mutableStateMapOf<String, AmArt>()
    private val appleInFlight = ConcurrentHashMap<String, Boolean>()
    private val directoryAvatars = ArtistAvatarStore(pluginDir, log, catalog)

    private val photoDir: File? = pluginDir?.let { File(it, "artist-photos") }

    /** Apple Music 侧拿到的头图 + 艺术字；两者都可缺。 */
    data class AmArt(
        val hero: Artwork?, val logo: ImageBitmap?,
        val avatar: ImageBitmap? = null, val details: AmArtistDetails? = null,
    )

    /** Called on a worker thread before saving an imported logo. */
    fun cacheLocalLogo(file: File): Boolean {
        val key = "${file.absolutePath}|${file.lastModified()}|${file.length()}"
        val image = runCatching {
            downscale(Image.makeFromEncoded(file.readBytes()), MAX_ART_SIDE).toComposeImageBitmap()
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
        val cat = catalog ?: return null
        // Each queued request captures its own immutable matching policy. A late
        // result cannot become the image selected after a preference change.
        val key = appleArtworkCacheKey(artistName, policy, identity)
        appleCache[key]?.let { return it }
        if (appleInFlight.putIfAbsent(key, true) != null) return null

        appleExecutor.execute {
            try {
                val meta = cat.resolve(artistName, policy, identity = identity)
                if (meta == null) {
                    appleCache[key] = AmArt(null, null)
                    return@execute
                }
                val heroBytes = meta.heroTemplate?.let { template ->
                    loadImage(cat.heroFile(artistName, identity), cat.imageUrl(template, meta.heroCrop, 1600, if (meta.heroIsWide) 800 else 1600))
                }
                val logoBytes = meta.logoTemplate?.let { template ->
                    loadImage(cat.logoFile(artistName, identity), cat.imageUrl(template, meta.logoCrop, 1000, 320, "png"))
                }
                val hero = heroBytes?.let { bytes ->
                    runCatching { decode(bytes) }.onFailure {
                        log.log("apple-music hero decode failed: ${log.describe(it)}")
                    }.getOrNull()
                }
                val logo = logoBytes?.let { bytes ->
                    runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
                }
                val avatar = meta.avatarTemplate?.let { template ->
                    loadImage(cat.avatarFile(artistName, identity), cat.imageUrl(template, meta.avatarCrop, 900, 900))
                }?.let { bytes ->
                    runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
                }
                if (hero == null && logo == null) {
                    log.log("apple-music: assets empty for \"$artistName\"")
                }
                appleCache[key] = AmArt(hero, logo, avatar, meta.details)
            } catch (t: Throwable) {
                log.log("apple-music failed for \"$artistName\": ${log.describe(t)}")
                appleCache[key] = AmArt(null, null)
            } finally {
                appleInFlight.remove(key)
            }
        }
        return appleCache[key]
    }

    /**
     * 丢掉某个艺人的全部派生缓存（Apple Music 元数据 + 图片、合成图），下次访问会重新抓。
     * 供「刷新头图」用；调用前请自行确认没有自定义写真。
     */
    fun invalidateArtist(artistName: String) {
        val key = normalizeArtistKey(artistName)
        catalog?.forget(artistName)
        appleCache.keys.filter { it.startsWith("$key|") }.forEach { appleCache.remove(it) }
        directoryAvatars.invalidate(artistName)
        customPhotoCache.remove(key)
        localPhotoCache.keys.filter { it.startsWith("$key|") }.forEach { localPhotoCache.remove(it) }
    }

    /** 磁盘优先；没有就下载一次再落盘。 */
    private fun loadImage(file: File?, url: String): ByteArray? {
        val cat = catalog ?: return null
        if (file != null && file.isFile && file.length() > 1024) {
            return runCatching { file.readBytes() }.getOrNull()
        }
        val bytes = runCatching { download(url) }.getOrNull() ?: return null
        if (file != null && bytes.isNotEmpty()) {
            runCatching {
                cat.ensureImageDir()
                file.writeBytes(bytes)
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
                conn.inputStream.use { it.readBytes() }
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
                val artwork = runCatching { decode(file.readBytes()) }.getOrNull()
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
                val artwork = runCatching { decode(photo.readBytes()) }.getOrNull()
                if (artwork != null) customPhotoCache[key] = artwork
                else log.log("artist photo decode failed: ${photo.name}")
                inFlight.remove("photo:$key")
            }
        }
        if (photo != null) customPhotoCache[key]?.let { return it }

        // Apple Music 头图单独缓存，绝不被曲库封面占用艺人缓存槽位。
        appleArt(artistName, policy, identity)?.hero?.let { return it }

        val trackId = coverTrackId?.takeIf(String::isNotBlank) ?: return null
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
                    val artwork = runCatching { decode(bytes) }.getOrNull()
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
        coverCache.clear()
        customPhotoCache.clear()
        localPhotoCache.clear()
        localLogoCache.clear()
        directoryAvatars.clear()
        inFlight.clear()
    }

    fun shutdown() {
        executor.shutdownNow()
        appleExecutor.shutdownNow()
        directoryAvatars.close()
    }

    /** Small circular portraits only; never starts hero/profile downloads. */
    fun artistAvatar(artistName: String, identity: ArtistIdentityPolicy = ArtistIdentityPolicy(enabled = false)): ImageBitmap? =
        directoryAvatars.avatar(artistName, identity)

    private fun decode(bytes: ByteArray): Artwork? {
        if (bytes.isEmpty()) return null
        val encoded = Image.makeFromEncoded(bytes)
        // 艺人写真常见 2000–4000px 的原图，后台先缩到页面需要的尺寸。
        // 这里必须把原始不透明位图交给 Compose；任何 alpha 淡出都放在 UI 的
        // 独立过渡层里，否则首屏也会被透明像素和页面底色污染。
        val original = runCatching { ImageIO.read(bytes.inputStream()) }.getOrNull()
        val skia = downscale(encoded, MAX_ART_SIDE)
        val image: ImageBitmap = skia.toComposeImageBitmap()
        val blurred = runCatching { buildBlurSteps(skia) }.getOrDefault(emptyList())
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
            val surface = Surface.makeRasterN32Premul(w, h)
            val paint = Paint().apply {
                imageFilter = ImageFilter.makeBlur(sigma, sigma, FilterTileMode.CLAMP)
            }
            surface.canvas.drawImageRect(
                source,
                Rect.makeWH(w.toFloat(), h.toFloat()),
                paint,
            )
            val snapshot: ImageBitmap = surface.makeImageSnapshot().toComposeImageBitmap()
            surface.close()
            snapshot
        }
    }

    private companion object {
        const val MAX_REQUESTS = 512
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
