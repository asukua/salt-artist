package dev.saltartist.am

import java.net.URI
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

/** Parses only the requested artist's detail header and URL-matched structured data. */
internal object AppleMusicPageParser {
    private val serializedScript = Regex(
        "<script\\b(?=[^>]*\\bid\\s*=\\s*[\"']serialized-server-data[\"'])[^>]*>(.*?)</script>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val linkedDataScript = Regex(
        "<script\\b(?=[^>]*\\btype\\s*=\\s*[\"']application/ld\\+json[\"'])[^>]*>(.*?)</script>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    fun parseHtml(html: String, expectedId: String, sourceUrl: String, now: Long,
                  language: PreferredArtistLanguage = PreferredArtistLanguage.CHINESE,
                  allowOtherLanguages: Boolean = false): AmArtist? {
        val serialized = serializedScript.find(html)?.groupValues?.get(1) ?: return null
        val linked = linkedDataScript.findAll(html).flatMap { match ->
            val parsed = runCatching { AppleMusicJson.read(match.groupValues[1]) }.getOrNull()
            if (parsed is List<*>) parsed.mapNotNull { it.jsonObject() }.asSequence()
            else listOfNotNull(parsed.jsonObject()).asSequence()
        }.toList()
        return parseSerialized(serialized, expectedId, sourceUrl, now, linked, language, allowOtherLanguages)
    }

    fun parseSerialized(
        serialized: String,
        expectedId: String,
        sourceUrl: String,
        now: Long,
        linkedData: List<Map<*, *>> = emptyList(),
        language: PreferredArtistLanguage = PreferredArtistLanguage.CHINESE,
        allowOtherLanguages: Boolean = false,
    ): AmArtist? = runCatching {
        require(expectedId.matches(Regex("[0-9]+")))
        require(artistIdOf(sourceUrl) == expectedId)
        val root = AppleMusicJson.read(serialized).jsonObject() ?: return null
        val entry = root["data"].jsonArray().mapNotNull { it.jsonObject() }.firstOrNull {
            val intent = it.objectAt("intent")
            intent.textAt("\$kind") == "ArtistDetailPageIntent" &&
                descriptorId(intent.objectAt("contentDescriptor")) == expectedId
        } ?: return null
        val page = entry.objectAt("data") ?: return null
        val canonical = page.textAt("canonicalURL")
        if (canonical != null && artistIdOf(canonical) != expectedId) return null
        val headers = page["sections"].jsonArray().mapNotNull { it.jsonObject() }
            .filter { it.textAt("itemKind") == "artistDetailHeader" }
            .flatMap { it["items"].jsonArray().mapNotNull { item -> item.jsonObject() } }
            .filter {
                it.textAt("id") == expectedId && descriptorId(it.objectAt("contentDescriptor")) == expectedId
            }
        // Duplicate headers are ambiguous; never borrow another artist's recommendation.
        val header = headers.singleOrNull() ?: return null
        val schema = linkedData.firstOrNull {
            it.textAt("@type") in setOf("MusicGroup", "Person") && artistIdOf(it.textAt("url")) == expectedId
        }
        val wide = artwork(header["wideArtwork"])
            ?: videoFrame(header.objectAt("wideVideoArtwork"), listOf("motionArtistWide16x9", "motionArtistFullscreen16x9"))
        val square = artwork(header["artwork"]) ?: artwork(header["colorBackdropArtwork"])
            ?: artwork(header["fallbackArtwork"])
        val hero = wide ?: square
        val logo = artwork(header["artistLogo"])
        val avatar = artwork(header["circleArtwork"]) ?: square
        val biographies = listOfNotNull(
            localizedBiography(header.textAt("bio"), language, true),
            localizedBiography(schema.textAt("description"), language, true),
        )
        val biographyValue = biographies.firstOrNull { it.second == language }
            ?: biographies.firstOrNull().takeIf { allowOtherLanguages }
        val biography = biographyValue?.first
        val actualLanguage = biographyValue?.second ?: language
        val genreValues = when (val value = schema?.get("genre")) {
            is String -> listOf(value)
            is List<*> -> value.filterIsInstance<String>()
            else -> emptyList()
        }
        val details = AmArtistDetails(
            biography = biography,
            // Apple's current detail page omits these facts. Only explicit structured
            // fields are accepted if supplied; prose is never mined for life events.
            born = formatDate(schema.textAt("birthDate")),
            formed = formatDate(schema.textAt("foundingDate")),
            origin = localizedPlace(schema?.get("birthPlace"), language) ?: localizedPlace(schema?.get("foundingLocation"), language),
            genres = genreValues.mapNotNull { localizedGenre(it, language)
                ?: if (allowOtherLanguages) localizedGenre(it, PreferredArtistLanguage.ENGLISH) else null }.distinct(),
            sourceUrl = canonical ?: sourceUrl,
            languageCode = if (allowOtherLanguages || language != PreferredArtistLanguage.CHINESE) actualLanguage.code else null,
        ).takeIf { it.hasContent }
        AmArtist(
            artistId = expectedId,
            heroTemplate = hero?.url,
            heroCrop = hero?.crop ?: "bb",
            heroIsWide = wide != null,
            logoTemplate = logo?.url,
            logoCrop = logo?.crop ?: "bb",
            fetchedAt = now,
            details = details,
            avatarTemplate = avatar?.url,
            avatarCrop = avatar?.crop ?: "bb",
            detailsFetchedAt = now,
        )
    }.getOrNull()

    /** Search fields belong to one result object, unlike the former +/-400-char window. */
    fun searchArtistLink(json: String, matchesName: (String) -> Boolean): String? = runCatching {
        artistSearchCandidates(json).filter { matchesName(it.name) }.distinctBy { it.id }.singleOrNull()?.url
    }.getOrNull()

    internal fun artistSearchCandidates(json: String): List<ArtistSearchCandidate> =
        artistSearchCandidatesOrNull(json).orEmpty()

    /** Null is an unavailable response, while an empty results list is authoritative. */
    internal fun artistSearchCandidatesOrNull(json: String): List<ArtistSearchCandidate>? = runCatching {
        val results = AppleMusicJson.read(json).jsonObject()?.get("results") as? List<*> ?: return null
        results
            .mapNotNull { it.jsonObject() }
            .mapNotNull { result ->
                if (result.textAt("wrapperType")?.let { it != "artist" } == true) return@mapNotNull null
                val name = result.textAt("artistName")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val link = result.textAt("artistLinkUrl") ?: return@mapNotNull null
                val id = artistIdOf(link) ?: return@mapNotNull null
                val explicitId = result["artistId"]?.toString()
                if (explicitId != null && explicitId != id) return@mapNotNull null
                ArtistSearchCandidate(id, name, link.substringBefore('?'))
            }
    }.getOrNull()

    internal fun lookupArtistNames(json: String, expectedIds: Set<String>): Map<String, Set<String>> = runCatching {
        artistSearchCandidates(json).filter { it.id in expectedIds }
            .groupBy { it.id }.mapValues { (_, items) -> items.map { artistIdentityKey(it.name) }.toSet() }
    }.getOrDefault(emptyMap())

    /** Cheap identity check: only the URL-matched artist header is read. */
    internal fun artistNamesFromHtml(html: String, expectedId: String, sourceUrl: String): Set<String> = runCatching {
        if (artistIdOf(sourceUrl) != expectedId) return emptySet()
        val serialized = serializedScript.find(html)?.groupValues?.get(1) ?: return emptySet()
        val root = AppleMusicJson.read(serialized).jsonObject() ?: return emptySet()
        val entry = root["data"].jsonArray().mapNotNull { it.jsonObject() }.singleOrNull {
            val intent = it.objectAt("intent")
            intent.textAt("\$kind") == "ArtistDetailPageIntent" &&
                descriptorId(intent.objectAt("contentDescriptor")) == expectedId
        } ?: return emptySet()
        val page = entry.objectAt("data") ?: return emptySet()
        val canonical = page.textAt("canonicalURL")
        if (canonical != null && artistIdOf(canonical) != expectedId) return emptySet()
        val header = page["sections"].jsonArray().mapNotNull { it.jsonObject() }
            .filter { it.textAt("itemKind") == "artistDetailHeader" }
            .flatMap { it["items"].jsonArray().mapNotNull { item -> item.jsonObject() } }
            .filter { it.textAt("id") == expectedId && descriptorId(it.objectAt("contentDescriptor")) == expectedId }
            .singleOrNull() ?: return emptySet()
        setOfNotNull(header.textAt("title")?.takeIf { it.isNotBlank() })
    }.getOrDefault(emptySet())

    fun artistIdOf(url: String?): String? = runCatching {
        val uri = URI(url ?: return null)
        if (uri.scheme != "https" || !uri.host.equals("music.apple.com", ignoreCase = true) || uri.userInfo != null) return null
        val path = uri.path.split('/')
        if (path.size !in 4..5 || path[0].isNotEmpty() || !path[1].matches(Regex("[A-Za-z]{2}")) || path[2] != "artist") return null
        if (path.any { it == "." || it == ".." } || (path.size == 5 && path[3].isBlank())) return null
        path.last().takeIf { it.matches(Regex("[1-9][0-9]{0,19}")) }
    }.getOrNull()

    private fun descriptorId(descriptor: Map<*, *>?): String? {
        if (descriptor.textAt("kind") != "artist") return null
        val id = descriptor.objectAt("identifiers").textAt("storeAdamID") ?: return null
        val url = descriptor.textAt("url")
        return id.takeIf { url == null || artistIdOf(url) == id }
    }

    private data class ImageTemplate(val url: String, val crop: String)

    private fun artwork(value: Any?): ImageTemplate? {
        val container = value.jsonObject() ?: return null
        val url = container.objectAt("dictionary").textAt("url") ?: return null
        return safeImageUrl(url)?.let { ImageTemplate(it, container.textAt("cropStyle") ?: "bb") }
    }

    private fun videoFrame(value: Map<*, *>?, keys: List<String>): ImageTemplate? {
        val frames = value.objectAt("dictionary") ?: return null
        return keys.firstNotNullOfOrNull { key ->
            val url = frames.objectAt(key).objectAt("previewFrame").textAt("url") ?: return@firstNotNullOfOrNull null
            safeImageUrl(url)?.let { ImageTemplate(it, value.textAt("cropStyle") ?: "bb") }
        }
    }

    private fun safeImageUrl(url: String): String? = runCatching {
        val uri = URI(url.replace("{", "%7B").replace("}", "%7D"))
        url.takeIf { uri.scheme == "https" && (uri.host == "mzstatic.com" || uri.host?.endsWith(".mzstatic.com") == true) }
    }.getOrNull()

    internal fun chineseBiography(value: String?): String? {
        val text = plainText(value)?.takeIf { it.length <= 30_000 } ?: return null
        val han = text.count(::isHan)
        val letters = text.count { it.isLetter() }
        val kana = text.count { it in '\u3040'..'\u30ff' }
        return text.takeIf { han >= 8 && han >= letters * 0.15 && kana <= maxOf(5, han / 12) }
    }

    internal fun localizedBiography(value: String?, requested: PreferredArtistLanguage,
                                    allowOtherLanguages: Boolean): Pair<String, PreferredArtistLanguage>? {
        val text = plainText(value)?.takeIf { it.length in 12..30_000 } ?: return null
        val actual = when {
            text.count { it in '\u3040'..'\u30ff' } >= 5 -> PreferredArtistLanguage.JAPANESE
            chineseBiography(text) != null -> PreferredArtistLanguage.CHINESE
            text.count { it in 'a'..'z' || it in 'A'..'Z' } >= 20 -> PreferredArtistLanguage.ENGLISH
            else -> return null
        }
        return (text to actual).takeIf { allowOtherLanguages || actual == requested }
    }

    internal fun localizedGenre(value: String, language: PreferredArtistLanguage): String? =
        if (language == PreferredArtistLanguage.CHINESE) chineseGenre(value)
        else plainText(value)?.takeIf { it.length <= 64 }

    internal fun localizedPlace(value: Any?, language: PreferredArtistLanguage): String? {
        if (language == PreferredArtistLanguage.CHINESE) return chinesePlace(value)
        val text = if (value is String) value else value.jsonObject().textAt("name")
        return plainText(text)?.takeIf { it.length <= 160 }
    }

    private fun isHan(char: Char): Boolean = char in '\u3400'..'\u9fff' || char in '\uf900'..'\ufaff'

    private fun chinesePlace(value: Any?): String? {
        val text = when (value) {
            is String -> value
            is Map<*, *> -> value.textAt("name")
            else -> null
        }
        return plainText(text)?.takeIf { it.length <= 160 && it.any(::isHan) }
    }

    internal fun chineseGenre(value: String): String? {
        val text = plainText(value)?.takeIf { it.length <= 64 } ?: return null
        return GENRES[text.lowercase(Locale.ROOT)] ?: text.takeIf { it.any(::isHan) }
    }

    internal fun formatDate(value: String?): String? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (text.matches(Regex("[12][0-9]{3}"))) return "${text}年"
        if (text.matches(Regex("[12][0-9]{3}-[0-9]{2}"))) {
            return runCatching { YearMonth.parse(text).let { "${it.year}年${it.monthValue}月" } }.getOrNull()
        }
        val formats = listOf(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("MMMM d, uuuu", Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("MMM d, uuuu", Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT),
        )
        for (format in formats) {
            val date = runCatching { LocalDate.parse(text, format) }.getOrNull() ?: continue
            return "${date.year}年${date.monthValue}月${date.dayOfMonth}日"
        }
        val chinese = Regex("([12][0-9]{3})年(?:(1[0-2]|[1-9])月(?:([12][0-9]|3[01]|[1-9])日)?)?")
            .matchEntire(text) ?: return null
        val year = chinese.groupValues[1].toInt()
        val month = chinese.groupValues[2].toIntOrNull()
        val day = chinese.groupValues[3].toIntOrNull()
        return runCatching {
            if (day != null) LocalDate.of(year, requireNotNull(month), day)
            else if (month != null) YearMonth.of(year, month)
            text
        }.getOrNull()
    }

    private fun plainText(value: String?): String? = value?.let {
        it.replace(Regex("(?i)<br\\s*/?>|</p\\s*>"), "\n")
            .replace(Regex("<[^>]*>"), "")
            .replace("&amp;", "&").replace("&quot;", "\"")
            .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&nbsp;", " ").replace('\u00a0', ' ')
            .replace(Regex("[ \\t]+"), " ").replace(Regex("\\n{3,}"), "\n\n")
            .trim().takeIf { text -> text.isNotEmpty() }
    }

    private val GENRES = mapOf(
        "pop" to "流行", "mandopop" to "国语流行", "cantopop/hk-pop" to "粤语流行",
        "hip-hop/rap" to "嘻哈/说唱", "hip hop/rap" to "嘻哈/说唱", "rap" to "说唱",
        "r&b/soul" to "节奏布鲁斯/灵魂乐", "rock" to "摇滚", "alternative" to "另类音乐",
        "electronic" to "电子音乐", "dance" to "舞曲", "jazz" to "爵士", "blues" to "蓝调",
        "classical" to "古典", "country" to "乡村", "folk" to "民谣", "indie rock" to "独立摇滚",
        "singer/songwriter" to "唱作人", "soundtrack" to "原声音乐", "soundtracks" to "原声音乐",
        "j-pop" to "日本流行", "k-pop" to "韩国流行", "j-rock" to "日本摇滚", "anime" to "动漫",
        "vocal" to "声乐", "instrumental" to "器乐", "metal" to "金属", "punk" to "朋克",
        "new age" to "新世纪音乐", "world" to "世界音乐", "latin" to "拉丁音乐", "reggae" to "雷鬼",
        "children's music" to "儿童音乐", "christian & gospel" to "基督教与福音", "enka" to "演歌",
    )
}
