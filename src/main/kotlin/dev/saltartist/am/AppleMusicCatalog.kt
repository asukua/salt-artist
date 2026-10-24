package dev.saltartist.am

import dev.saltartist.PluginLog
import dev.saltartist.data.normalizeArtistKey
import dev.saltartist.artwork.ArtistArtworkRequests
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/** Public artist metadata, independently cached for each requested language. */
class AppleMusicCatalog(
    private val pluginDir: File?,
    private val log: PluginLog,
    private val pageLoader: ((String) -> String?)? = null,
    private val profileLoader: ((String, String?, PluginLog, PreferredArtistLanguage) -> AmArtistDetails?)? = null,
) {
    private val metaFile = pluginDir?.let { File(it, "apple-music-cache.tsv") }
    private val imageDir = pluginDir?.let { File(it, "apple-music") }
    private val memory = ConcurrentHashMap<String, AmArtist>()
    private data class AvatarHit(val metadata: AvatarMetadata?, val checkedAt: Long)
    private val avatars = ConcurrentHashMap<String, AvatarHit>()
    private val storefrontHealth = ArtistStorefrontHealth()
    private val identities = ArtistIdentityResolver(::httpGet, log, storefrontHealth)
    private val migratedScopes = HashSet<String>()
    private val requests = ArtistArtworkRequests()
    @Volatile private var loaded = false

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        val file = metaFile
        if (file?.isFile == true) runCatching {
            file.forEachLine(Charsets.UTF_8) { line ->
                AppleMusicCache.decode(line)?.let { (key, artist) ->
                    // v2 was Chinese-only. Keep it while adding independent language records.
                    val explicitLanguage = PreferredArtistLanguage.entries.firstOrNull { key.endsWith("|${it.code}") }
                    val language = explicitLanguage ?: PreferredArtistLanguage.CHINESE
                    // The preceding scope is opaque: it can include an identity
                    // namespace. Old name-only records remain isolated until the
                    // raw artist name is matched into a new scoped record.
                    val base = explicitLanguage?.let { key.removeSuffix("|${it.code}") } ?: key
                    val details = artist.details
                    memory[cacheKey(base, language)] = artist.copy(
                        details = details?.copy(languageCode = details.languageCode ?: language.code),
                    )
                }
            }
        }.onFailure { log.log("apple-music cache load failed: ${log.describe(it)}") }
        loaded = true
        log.log("apple-music cache entries=${memory.size}")
    }

    @Synchronized
    fun saveCache() {
        val file = metaFile ?: return
        runCatching {
            pluginDir?.mkdirs()
            atomicCacheWrite(file, memory.toSortedMap().entries.joinToString("\n", postfix = "\n") {
                (key, artist) -> AppleMusicCache.encode(key, artist)
            }.toByteArray(Charsets.UTF_8))
        }.onFailure { log.log("apple-music cache write failed: ${log.describe(it)}") }
    }

    fun cached(artistName: String, policy: ArtistLanguagePolicy = ArtistLanguagePolicy(),
               identity: ArtistIdentityPolicy = STRICT_IDENTITY): AmArtist? {
        return selectLanguageRecord(cachedRecords(artistName, identity), policy)
    }

    fun cachedIsFresh(artistName: String, policy: ArtistLanguagePolicy, identity: ArtistIdentityPolicy): Boolean =
        freshRecords(cachedRecords(artistName, identity), policy, System.currentTimeMillis(), CACHE_TTL_MS)

    /** No network: retain legacy data only when its policy or explicit ID proves the same identity. */
    @Synchronized private fun cachedRecords(name: String, identity: ArtistIdentityPolicy): Map<PreferredArtistLanguage, AmArtist> {
        ensureLoaded()
        if (identity.rejects(name)) return emptyMap()
        val base = identity.artistCacheKey(name)
        if (!migratedScopes.add(base)) return records(base)
        val exact = identity.legacyArtistCacheKey(name)
        val manual = identity.manualId(name)
        val stem = identity.artistCacheStem(name) + "--"
        val candidates = memory.entries.mapNotNull { (key, artist) ->
            val language = PreferredArtistLanguage.entries.firstOrNull { key.endsWith("|${it.code}") } ?: return@mapNotNull null
            val oldBase = key.removeSuffix("|${language.code}")
            val scoped = oldBase.startsWith(stem) && oldBase.removePrefix(stem).matches(Regex("(?:aliases|strict)-v2-[a-f0-9]{20}"))
            if (oldBase != base && scoped && (if (manual != null) artist.artistId == manual else oldBase == exact))
                Triple(oldBase, language, artist) else null
        }.sortedByDescending { maxOf(it.third.fetchedAt, it.third.detailsFetchedAt) }
        var changed = false
        for ((oldBase, language, old) in candidates) {
            val key = cacheKey(base, language)
            val current = memory[key] ?: old.also { memory[key] = it; changed = true }
            if (current.artistId != old.artistId) continue
            fun copy(suffix: String, same: Boolean = true) {
                if (!same) return
                val source = imageDir?.resolve(oldBase + suffix) ?: return
                val target = imageDir.resolve(base + suffix)
                if (source.isFile && !target.isFile) runCatching { atomicCacheWrite(target, source.readBytes()); target.setLastModified(source.lastModified()) }
            }
            copy("-hero.jpg", current.heroTemplate == old.heroTemplate && current.heroCrop == old.heroCrop && current.heroIsWide == old.heroIsWide)
            copy("-logo.png", current.logoTemplate == old.logoTemplate && current.logoCrop == old.logoCrop)
            copy("-avatar.jpg", current.avatarTemplate == old.avatarTemplate && current.avatarCrop == old.avatarCrop)
            val thumbnail = avatarThumbnailFile(name, identity)
            if (thumbnail != null && !thumbnail.isFile && current.avatarTemplate == old.avatarTemplate && current.avatarCrop == old.avatarCrop) {
                listOf("artist-$oldBase-avatar-320.jpg", "artist-$oldBase-${oldBase.substringAfterLast("--")}-avatar-320.jpg")
                    .mapNotNull { imageDir?.resolve(it) }.firstOrNull { it.isFile }?.let {
                        runCatching { atomicCacheWrite(thumbnail, it.readBytes()); thumbnail.setLastModified(it.lastModified()) }
                    }
            }
        }
        if (changed) saveCache()
        return records(base)
    }

    private fun freshRecords(before: Map<PreferredArtistLanguage, AmArtist>, policy: ArtistLanguagePolicy, now: Long, maxAgeMs: Long): Boolean {
        val selected = selectLanguageRecord(before, policy)
        val languages = listOf(policy.preferredLanguage) +
            if (policy.showOtherLanguages) PreferredArtistLanguage.entries.filter { it != policy.preferredLanguage } else emptyList()
        return before[policy.preferredLanguage]?.let { AppleMusicCache.isFresh(it, now, maxAgeMs) } == true &&
            ((selected?.details?.biography != null && AppleMusicCache.isFresh(selected, now, maxAgeMs)) ||
                languages.all { before[it]?.let { item -> AppleMusicCache.isFresh(item, now, maxAgeMs) } == true })
    }

    /** Blocking manual search: callers display candidates and persist only an explicit choice. */
    fun searchCandidates(query: String, identity: ArtistIdentityPolicy = STRICT_IDENTITY): List<ArtistMatchCandidate> =
        identities.searchCandidates(query, identity)

    fun forget(artistName: String): Boolean {
        ensureLoaded()
        val base = normalizeArtistKey(artistName)
        val stem = STRICT_IDENTITY.artistCacheStem(artistName)
        var removed = false
        requests.invalidate(artistIdentityKey(artistName)) {
            synchronized(this) {
                memory.keys.filter { it.startsWith("$base|") || it.startsWith("$stem--") }.forEach {
                    if (memory.remove(it) != null) removed = true
                }
                avatars.keys.filter { it == base || it.startsWith("$stem--") }.forEach {
                    if (avatars.remove(it) != null) removed = true
                }
                migratedScopes.removeIf { it.startsWith("$stem--") }
                identities.forget(artistName)
                imageDir?.listFiles()?.filter { file ->
                    (file.name.startsWith("$stem--") || file.name.startsWith("artist-$stem--")) &&
                        listOf("-hero.jpg", "-logo.png", "-avatar.jpg", "-avatar-320.jpg").any(file.name::endsWith)
                }?.forEach {
                    if (it.isFile) { removed = true; it.delete() }
                }
            }
        }
        if (removed) saveCache()
        return removed
    }

    internal fun identityChanged(name: String) { requests.invalidate(artistIdentityKey(name)) { } }

    /** Blocking: the caller supplies the worker and includes policy in its own snapshot key. */
    fun resolve(artistName: String, policy: ArtistLanguagePolicy = ArtistLanguagePolicy(),
                maxAgeMs: Long = CACHE_TTL_MS, identity: ArtistIdentityPolicy = STRICT_IDENTITY): AmArtist? {
        ensureLoaded()
        val base = identity.artistCacheKey(artistName)
        val generation = requests.generation(artistIdentityKey(artistName))
        val before = cachedRecords(artistName, identity)
        val previous = selectLanguageRecord(before, policy)
        val now = System.currentTimeMillis()
        val languages = listOf(policy.preferredLanguage) +
            if (policy.showOtherLanguages) PreferredArtistLanguage.entries.filter { it != policy.preferredLanguage } else emptyList()
        if (freshRecords(before, policy, now, maxAgeMs)) return previous
        val artistId = before.values.firstOrNull()?.artistId ?: avatars[base]?.metadata?.artistId
            ?: identities.resolve(artistName, identity) ?: return previous
        for (language in languages) {
            val hit = memory[cacheKey(base, language)]
            if (hit == null || !AppleMusicCache.isFresh(hit, now, maxAgeMs)) {
                loadLanguage(artistName, base, artistId, language, now, identity, generation)
            }
            val selected = selectLanguageRecord(records(base), policy)
            if (selected?.details?.biography != null && AppleMusicCache.isFresh(selected, now, maxAgeMs)) break
        }
        val selected = selectLanguageRecord(records(base), policy) ?: previous
        requests.publish(artistIdentityKey(artistName), generation) {
            if (selected != null) invalidateChangedImages(artistName, previous, selected, identity)
        }
        saveCache()
        return selected
    }

    /** Compatibility with the former positional maximum-age argument. */
    fun resolve(artistName: String, maxAgeMs: Long, identity: ArtistIdentityPolicy = STRICT_IDENTITY): AmArtist? =
        resolve(artistName, ArtistLanguagePolicy(), maxAgeMs, identity)

    private fun loadLanguage(name: String, base: String, artistId: String,
                             language: PreferredArtistLanguage, now: Long, identity: ArtistIdentityPolicy, generation: Long) {
        val pages = arrayListOf<AmArtist>()
        var firstHtml: String? = null
        for (country in storefrontHealth.attempts(identity.storefrontOrder)) {
            val store = country.lowercase(java.util.Locale.ROOT)
            val link = "https://music.apple.com/$store/artist/$artistId?l=${language.code}"
            val html = httpGet(link, identity.storefrontOrder) ?: continue
            val parsed = AppleMusicPageParser.parseHtml(html, artistId, link, now, language, true) ?: continue
            if (firstHtml == null) firstHtml = html
            val details = parsed.details
            val actual = PreferredArtistLanguage.entries.firstOrNull { it.code == details?.languageCode } ?: language
            if (details?.biography != null && actual != language) {
                // A localized URL can still contain an English biography. Cache that
                // fact under its actual language, never as a successful Chinese bio.
                val foreignKey = cacheKey(base, actual)
                requests.publish(artistIdentityKey(name), generation) {
                    memory.compute(foreignKey) { _, existing ->
                        // An incidental translation fallback is usable as a last resort,
                        // but must not prevent fetching that language's native page.
                        if (existing?.details?.biography == null) parsed.copy(detailsFetchedAt = 0L) else existing
                    }
                }
                pages += parsed.copy(details = details.copy(biography = null, languageCode = language.code).takeIf { it.hasContent })
            } else pages += parsed
            if (pages.last().details?.biography != null) break
        }
        if (pages.isEmpty()) return
        val profile = if (profileLoader != null) profileLoader.invoke(artistId, firstHtml, log, language)
            else AppleMusicProfileClient.resolve(artistId, firstHtml, log, language, allowOtherLanguages = true,
                storefronts = identity.storefrontOrder, health = storefrontHealth)
        requests.publish(artistIdentityKey(name), generation) {
            val old = memory[cacheKey(base, language)]
            memory[cacheKey(base, language)] = mergePages(pages, old, now, profile).let { merged ->
                val incidentalBio = old?.takeIf { it.detailsFetchedAt == 0L }?.details?.takeIf { !it.biography.isNullOrBlank() }
                val details = if (merged.details?.biography == null && incidentalBio != null) {
                    (merged.details ?: AmArtistDetails()).copy(
                        biography = incidentalBio.biography, sourceUrl = incidentalBio.sourceUrl,
                    )
                } else merged.details
                merged.copy(details = details?.copy(languageCode = language.code))
            }
            log.log("apple-music resolved \"$name\" id=$artistId language=${language.code} biography=${memory[cacheKey(base, language)]?.details?.biography != null}")
        }
    }

    /** List-only metadata: no profile API, public-app bundle, hero image, or biography load. */
    fun resolveAvatar(artistName: String, identity: ArtistIdentityPolicy = STRICT_IDENTITY): AvatarMetadata? {
        ensureLoaded()
        val base = identity.artistCacheKey(artistName)
        val generation = requests.generation(artistIdentityKey(artistName))
        cachedRecords(artistName, identity).values.firstNotNullOfOrNull { artist ->
            artist.avatarTemplate?.let { AvatarMetadata(artist.artistId, it, artist.avatarCrop) }
        }?.let { return it }
        val now = System.currentTimeMillis()
        avatars[base]?.takeIf { now - it.checkedAt < AVATAR_RETRY_MS }?.let { return it.metadata }
        val id = records(base).values.firstOrNull()?.artistId
            ?: identities.resolve(artistName, identity)
        if (id == null) { requests.publish(artistIdentityKey(artistName), generation) { avatars[base] = AvatarHit(null, now) }; return null }
        val metadata = storefrontHealth.attempts(identity.storefrontOrder).firstNotNullOfOrNull { country ->
            val link = "https://music.apple.com/${country.lowercase(java.util.Locale.ROOT)}/artist/$id?l=zh-Hans"
            val parsed = httpGet(link, identity.storefrontOrder)?.let { AppleMusicPageParser.parseHtml(it, id, link, now) }
            parsed?.avatarTemplate?.let { AvatarMetadata(id, it, parsed.avatarCrop) }
        }
        val accepted = requests.publish(artistIdentityKey(artistName), generation) {
            avatars[base] = AvatarHit(metadata, now)
            if (metadata != null) {
                // Persist only the verified avatar metadata. A later artist-page resolve
                // still sees detailsFetchedAt=0 and performs its own language/profile work.
                val key = cacheKey(base, PreferredArtistLanguage.CHINESE)
                memory.compute(key) { _, old ->
                    old?.copy(avatarTemplate = metadata.template, avatarCrop = metadata.crop)
                        ?: AmArtist(id, null, "bb", false, null, "bb", now,
                            avatarTemplate = metadata.template, avatarCrop = metadata.crop)
                }
            }
        }
        if (accepted && metadata != null) saveCache()
        return metadata.takeIf { accepted }
    }

    private fun records(base: String): Map<PreferredArtistLanguage, AmArtist> =
        PreferredArtistLanguage.entries.mapNotNull { language -> memory[cacheKey(base, language)]?.let { language to it } }.toMap()

    fun imageUrl(template: String, crop: String, width: Int, height: Int, format: String = "jpg"): String =
        template.replace("{w}", width.toString()).replace("{h}", height.toString()).replace("{c}", crop).replace("{f}", format)
    fun heroFile(artistName: String, identity: ArtistIdentityPolicy = STRICT_IDENTITY): File? = imageFile(artistName, "-hero.jpg", identity)
    fun logoFile(artistName: String, identity: ArtistIdentityPolicy = STRICT_IDENTITY): File? = imageFile(artistName, "-logo.png", identity)
    fun avatarFile(artistName: String, identity: ArtistIdentityPolicy = STRICT_IDENTITY): File? = imageFile(artistName, "-avatar.jpg", identity)
    internal fun avatarThumbnailFile(artistName: String, identity: ArtistIdentityPolicy): File? =
        imageDir?.resolve("artist-${identity.artistCacheKey(artistName)}-avatar-320.jpg")
    private fun imageFile(artistName: String, suffix: String, identity: ArtistIdentityPolicy) =
        imageDir?.let { File(it, identity.artistCacheKey(artistName) + suffix) }
    fun ensureImageDir(): File? = imageDir?.also { if (!it.isDirectory) it.mkdirs() }

    internal fun invalidateChangedImages(artistName: String, old: AmArtist?, current: AmArtist,
                                         identity: ArtistIdentityPolicy = STRICT_IDENTITY) {
        if (old == null) { listOf(heroFile(artistName, identity), logoFile(artistName, identity), avatarFile(artistName, identity)).forEach { it?.delete() }; return }
        if (old.heroTemplate != current.heroTemplate || old.heroCrop != current.heroCrop || old.heroIsWide != current.heroIsWide) heroFile(artistName, identity)?.delete()
        if (old.logoTemplate != current.logoTemplate || old.logoCrop != current.logoCrop) logoFile(artistName, identity)?.delete()
        if (old.avatarTemplate != current.avatarTemplate || old.avatarCrop != current.avatarCrop) {
            avatarFile(artistName, identity)?.delete()
            avatarThumbnailFile(artistName, identity)?.delete()
        }
    }

    private fun httpGet(url: String): String? = httpGet(url, null)

    private fun httpGet(url: String, regions: List<String>?): String? {
        if (pageLoader != null) return pageLoader.invoke(url)
        return runCatching {
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            val catalogSearch = url.startsWith("https://itunes.apple.com/")
            connection.connectTimeout = if (catalogSearch) 3500 else 8000
            connection.readTimeout = if (catalogSearch) 6000 else 12000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept-Language", when {
                "l=en-US" in url -> "en-US,en;q=0.9"
                "l=ja-JP" in url -> "ja-JP,ja;q=0.9"
                else -> "zh-CN,zh;q=0.9"
            })
            try {
                if (connection.responseCode !in 200..299) return null
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } finally { connection.disconnect() }
        }.onFailure {
            if (regions != null && url.startsWith("https://music.apple.com/us/")) storefrontHealth.unavailable("US", regions)
            log.log("apple-music http failed $url: ${it.javaClass.simpleName}")
        }.getOrNull()
    }

    companion object {
        private val STRICT_IDENTITY = ArtistIdentityPolicy(enabled = false)
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
        private const val CACHE_TTL_MS = 30L * 24 * 60 * 60 * 1000
        private const val AVATAR_RETRY_MS = 60_000L
        private fun cacheKey(base: String, language: PreferredArtistLanguage) = "$base|${language.code}"

        internal fun selectLanguageRecord(records: Map<PreferredArtistLanguage, AmArtist>, policy: ArtistLanguagePolicy): AmArtist? {
            val preferred = records[policy.preferredLanguage]
            fun allowed(artist: AmArtist): AmArtist {
                if (policy.showOtherLanguages || policy.preferredLanguage != PreferredArtistLanguage.CHINESE) return artist
                val details = artist.details ?: return artist
                val filtered = details.copy(
                    origin = AppleMusicPageParser.localizedPlace(details.origin, PreferredArtistLanguage.CHINESE),
                    genres = details.genres.mapNotNull(AppleMusicPageParser::chineseGenre).distinct(),
                )
                return artist.copy(details = filtered.takeIf { it.hasContent })
            }
            if (preferred?.details?.biography != null) return allowed(preferred)
            val others = PreferredArtistLanguage.entries.filter { it != policy.preferredLanguage }.mapNotNull { records[it] }
            if (policy.showOtherLanguages) others.firstOrNull { it.details?.biography != null }?.let { return it }
            if (preferred?.details?.hasContent == true) return allowed(preferred)
            if (policy.showOtherLanguages) others.firstOrNull { it.details?.hasContent == true }?.let { return it }
            return (preferred ?: records.values.firstOrNull())?.copy(details = null)
        }

        internal fun mergePages(pages: List<AmArtist>, previous: AmArtist?, now: Long,
                                profile: AmArtistDetails? = null): AmArtist {
            val first = pages.first()
            require(pages.all { it.artistId == first.artistId } && (previous == null || previous.artistId == first.artistId))
            val verifiedPrevious = previous?.takeIf { it.detailsFetchedAt > 0L }
            val currentVisual = pages.firstOrNull { it.motion != null }
                ?: pages.firstOrNull { it.heroTemplate != null && it.heroIsWide }
                ?: pages.firstOrNull { it.heroTemplate != null }
            val visual = currentVisual ?: verifiedPrevious ?: first
            val profiles = listOfNotNull(profile) + pages.mapNotNull { it.details }
            val bio = profiles.firstOrNull { it.biography != null }
            val details = AmArtistDetails(
                biography = bio?.biography,
                born = profiles.firstNotNullOfOrNull { it.born }, formed = profiles.firstNotNullOfOrNull { it.formed },
                origin = profiles.firstNotNullOfOrNull { it.origin },
                genres = profiles.firstOrNull { it.genres.isNotEmpty() }?.genres.orEmpty(),
                sourceUrl = bio?.sourceUrl ?: profiles.firstOrNull()?.sourceUrl,
                languageCode = bio?.languageCode ?: profiles.firstNotNullOfOrNull { it.languageCode },
            ).takeIf { it.hasContent }
            val logo = pages.firstOrNull { it.logoTemplate != null } ?: verifiedPrevious
            val avatar = pages.firstOrNull { it.avatarTemplate != null } ?: verifiedPrevious
            return visual.copy(artistId = first.artistId, fetchedAt = now, details = details, detailsFetchedAt = now,
                motion = currentVisual?.motion, motionCheckedAt = currentVisual?.motionCheckedAt ?: first.motionCheckedAt,
                logoTemplate = logo?.logoTemplate, logoCrop = logo?.logoCrop ?: "bb",
                avatarTemplate = avatar?.avatarTemplate, avatarCrop = avatar?.avatarCrop ?: "bb")
        }
    }
}
