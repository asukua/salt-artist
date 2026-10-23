package dev.saltartist.am

import dev.saltartist.PluginLog
import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64

/** Optional public-web profile facts. No account, user token, or persisted bearer is used. */
internal object AppleMusicProfileClient {
    private const val MUSIC_ORIGIN = "https://music.apple.com"
    private const val TOKEN_TTL_MS = 60L * 60 * 1000
    private const val TOKEN_RETRY_MS = 5L * 60 * 1000
    private const val TOKEN_EXPIRY_MARGIN_MS = 60_000L
    private const val MAX_RESPONSE_BYTES = 8_000_000
    private const val JWT = "eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    private val tokenLock = Any()
    private var cachedBearer: AppBearer? = null
    private var nextTokenAttemptAt = 0L
    private val defaultHealth = ArtistStorefrontHealth()

    // Avoid an automatically generated toString containing the bearer.
    private class AppBearer(val value: String, val usableUntil: Long)
    private data class HttpResult(val status: Int, val body: String?)

    /** Blocking: use the artwork worker, just like the existing public-page fetch. */
    fun resolve(artistId: String, artistPageHtml: String?, log: PluginLog,
                language: PreferredArtistLanguage = PreferredArtistLanguage.CHINESE,
                allowOtherLanguages: Boolean = false,
                storefronts: List<String> = emptyList(),
                health: ArtistStorefrontHealth = defaultHealth): AmArtistDetails? {
        if (!artistId.matches(Regex("[0-9]+"))) return null
        val regions = profileStorefronts(storefronts, language)
        var bearer = bearerFor(artistId, artistPageHtml, log, regions, health) ?: return null
        var refreshed = false
        var unavailable = false
        val details = resolveProfiles(artistId, language, allowOtherLanguages, regions, health) request@{ endpoint ->
            if (unavailable) return@request null
            var response = get(endpoint, bearer.value, log) ?: run {
                if ("/catalog/us/" in endpoint) health.unavailable("US", regions)
                return@request null
            }
            if (response.status == 401 || response.status == 403) {
                if (refreshed) {
                    rejectBearer(bearer, backoff = true)
                    unavailable = true
                    return@request null
                }
                rejectBearer(bearer, backoff = false)
                // A fresh page discovers the current index after a token rotation.
                val replacement = bearerFor(artistId, null, log, regions, health)
                if (replacement == null) { unavailable = true; return@request null }
                bearer = replacement
                refreshed = true
                response = get(endpoint, bearer.value, log) ?: run {
                    if ("/catalog/us/" in endpoint) health.unavailable("US", regions)
                    return@request null
                }
                if (response.status == 401 || response.status == 403) {
                    rejectBearer(bearer, backoff = true)
                    unavailable = true
                    return@request null
                }
            }
            response.body.takeIf { response.status in 200..299 }
        }
        if (details != null) {
            log.log("apple-music public profile id=$artistId born=${details.born != null} formed=${details.formed != null} origin=${details.origin != null}")
        }
        return details
    }

    /** Region fallback does not change the requested language or accept another artist's facts. */
    internal fun resolveProfiles(artistId: String, language: PreferredArtistLanguage,
                                 allowOtherLanguages: Boolean, storefronts: List<String>,
                                 health: ArtistStorefrontHealth = defaultHealth,
                                 load: (String) -> String?): AmArtistDetails? {
        if (!artistId.matches(Regex("[1-9][0-9]{0,19}"))) return null
        val locale = if (language == PreferredArtistLanguage.CHINESE) "zh-Hans-CN" else language.code
        for (storefront in health.attempts(profileStorefronts(storefronts, language))) {
            val region = storefront.lowercase(java.util.Locale.ROOT)
            val endpoint = "https://amp-api.music.apple.com/v1/catalog/$region/artists/$artistId" +
                "?extend=artistBio,bornOrFormed,origin,editorialArtwork,hero,isGroup,plainEditorialNotes" +
                "&include=artist-biography-canvas&l=$locale&platform=web"
            val body = runCatching { load(endpoint) }.getOrNull() ?: continue
            if (runCatching { AppleMusicJson.read(body).jsonObject() }.getOrNull() == null) {
                health.unavailable(storefront, profileStorefronts(storefronts, language))
                continue
            }
            parseProfile(body, artistId, language, allowOtherLanguages, region)?.let { return it }
        }
        return null
    }

    private fun profileStorefronts(regions: List<String>, language: PreferredArtistLanguage): List<String> =
        if (regions.isEmpty()) listOf(when (language) {
            PreferredArtistLanguage.CHINESE -> "CN"
            PreferredArtistLanguage.ENGLISH -> "US"
            PreferredArtistLanguage.JAPANESE -> "JP"
        }) else ArtistIdentityPolicy(storefronts = regions.joinToString(",")).storefrontOrder

    private fun bearerFor(artistId: String, suppliedHtml: String?, log: PluginLog,
                          storefronts: List<String>, health: ArtistStorefrontHealth): AppBearer? =
        synchronized(tokenLock) {
            val now = System.currentTimeMillis()
            cachedBearer?.takeIf { it.usableUntil > now }?.let { return@synchronized it }
            cachedBearer = null
            if (now < nextTokenAttemptAt) return@synchronized null
            // Back off failed discovery so several artists do not repeatedly download the bundle.
            nextTokenAttemptAt = now + TOKEN_RETRY_MS
            val html = suppliedHtml ?: health.attempts(storefronts).firstNotNullOfOrNull { storefront ->
                val response = get("$MUSIC_ORIGIN/${storefront.lowercase(java.util.Locale.ROOT)}/artist/$artistId?l=zh-Hans", null, log)
                if (response == null) health.unavailable(storefront, storefronts)
                response?.takeIf { it.status in 200..299 }?.body
            } ?: return@synchronized null
            val bundleUrl = indexBundleUrl(html) ?: return@synchronized null
            val bundle = get(bundleUrl, null, log)?.takeIf { it.status in 200..299 }?.body
                ?: return@synchronized null
            val value = publicBearerFromBundle(bundle, now) ?: return@synchronized null
            val expiry = expiryMillis(value) ?: return@synchronized null
            AppBearer(value, minOf(now + TOKEN_TTL_MS, expiry - TOKEN_EXPIRY_MARGIN_MS)).also {
                cachedBearer = it
                nextTokenAttemptAt = 0L
            }
        }

    private fun rejectBearer(rejected: AppBearer, backoff: Boolean) = synchronized(tokenLock) {
        // A different caller may already have refreshed the shared value.
        if (cachedBearer === rejected) {
            cachedBearer = null
            nextTokenAttemptAt = if (backoff) System.currentTimeMillis() + TOKEN_RETRY_MS else 0L
        }
    }

    /** Only the public Apple index asset can supply the MusicKit developerToken value. */
    internal fun indexBundleUrl(html: String): String? {
        val scripts = Regex("<script\\b[^>]*\\bsrc\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
        return scripts.findAll(html).firstNotNullOfOrNull { match ->
            runCatching {
                val uri = URI(MUSIC_ORIGIN).resolve(match.groupValues[1])
                uri.toString().takeIf {
                    uri.scheme == "https" && uri.host == "music.apple.com" && uri.userInfo == null &&
                        uri.path.matches(Regex("/assets/index(?:[~.-][A-Za-z0-9_.-]+)?\\.js"))
                }
            }.getOrNull()
        }
    }

    internal fun publicBearerFromBundle(bundle: String, now: Long): String? {
        val references = Regex("[\"']?developerToken[\"']?\\s*:\\s*([A-Za-z_$][A-Za-z0-9_$]*)")
        for (reference in references.findAll(bundle)) {
            val variable = Regex.escape(reference.groupValues[1])
            val assignment = Regex("(?<![A-Za-z0-9_$])$variable\\s*=\\s*[\"']($JWT)[\"']")
            for (match in assignment.findAll(bundle)) {
                val token = match.groupValues[1]
                if ((expiryMillis(token) ?: 0L) > now + TOKEN_EXPIRY_MARGIN_MS) return token
            }
        }
        return null
    }

    private fun expiryMillis(token: String): Long? = runCatching {
        val payload = String(Base64.getUrlDecoder().decode(token.split('.')[1]), Charsets.UTF_8)
        val seconds = (AppleMusicJson.read(payload).jsonObject()?.get("exp") as? Number)?.toLong()
            ?: return null
        Math.multiplyExact(seconds, 1000L).takeIf { it > 0L }
    }.getOrNull()

    /** API facts are scoped to one matching artist; biography still comes from the public page. */
    internal fun parseProfile(json: String, expectedId: String,
                              language: PreferredArtistLanguage = PreferredArtistLanguage.CHINESE,
                              allowOtherLanguages: Boolean = false,
                              storefront: String? = null): AmArtistDetails? = runCatching {
        if (!expectedId.matches(Regex("[0-9]+"))) return null
        val artists = AppleMusicJson.read(json).jsonObject()?.get("data").jsonArray()
            .mapNotNull { it.jsonObject() }
            .filter { it.textAt("id") == expectedId && it.textAt("type") == "artists" }
        val artist = artists.singleOrNull() ?: return null
        val attributes = artist.objectAt("attributes") ?: return null
        val sourceUrl = attributes.textAt("url")
        if (sourceUrl != null && AppleMusicPageParser.artistIdOf(sourceUrl) != expectedId) return null
        val date = AppleMusicPageParser.formatDate(attributes.textAt("bornOrFormed"))
        val isGroup = attributes["isGroup"] as? Boolean
        AmArtistDetails(
            born = date.takeIf { isGroup == false },
            formed = date.takeIf { isGroup == true },
            origin = AppleMusicPageParser.localizedPlace(attributes.textAt("origin"),
                if (allowOtherLanguages) PreferredArtistLanguage.ENGLISH else language),
            genres = attributes["genreNames"].jsonArray().filterIsInstance<String>()
                .mapNotNull { AppleMusicPageParser.localizedGenre(it, language)
                    ?: if (allowOtherLanguages) AppleMusicPageParser.localizedGenre(it, PreferredArtistLanguage.ENGLISH) else null }.distinct(),
            sourceUrl = sourceUrl ?: "$MUSIC_ORIGIN/${storefront ?: when (language) { PreferredArtistLanguage.CHINESE -> "cn"; PreferredArtistLanguage.ENGLISH -> "us"; PreferredArtistLanguage.JAPANESE -> "jp" }}/artist/$expectedId?l=${language.code}",
            languageCode = language.code,
        ).takeIf { it.hasContent }
    }.getOrNull()

    private fun get(url: String, bearer: String?, log: PluginLog): HttpResult? = runCatching {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 8000
        connection.readTimeout = 12000
        // Public /artist/id pages redirect to their canonical slug. API bearer
        // headers must never be forwarded through a redirect.
        connection.instanceFollowRedirects = bearer == null
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Accept-Language", when {
            "l=en-US" in url -> "en-US,en;q=0.9"
            "l=ja-JP" in url -> "ja-JP,ja;q=0.9"
            else -> "zh-CN,zh;q=0.9"
        })
        connection.setRequestProperty("Origin", MUSIC_ORIGIN)
        connection.setRequestProperty("Referer", "$MUSIC_ORIGIN/")
        if (bearer != null) connection.setRequestProperty("Authorization", "Bearer $bearer")
        try {
            val status = connection.responseCode
            val body = if (status in 200..299) {
                val bytes = connection.inputStream.use { it.readNBytes(MAX_RESPONSE_BYTES + 1) }
                if (bytes.size > MAX_RESPONSE_BYTES) return null
                String(bytes, Charsets.UTF_8)
            } else null
            HttpResult(status, body)
        } finally {
            connection.disconnect()
        }
    }.onFailure {
        // Log the class only: request headers and response bodies may contain bearer material.
        log.log("apple-music public profile request failed: ${it.javaClass.simpleName}")
    }.getOrNull()
}
