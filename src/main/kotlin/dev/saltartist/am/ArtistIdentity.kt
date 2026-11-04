package dev.saltartist.am

import dev.saltartist.PluginLog
import dev.saltartist.data.normalizeArtistKey
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Identity recognition affects Apple metadata only, never host library grouping. */
data class ArtistIdentityPolicy(
    val enabled: Boolean = true,
    val overrides: String = "",
    val storefronts: String = DEFAULT_STOREFRONTS,
) {
    val storefrontOrder: List<String> by lazy { normalizedStorefronts(storefronts) }
    val cacheToken: String by lazy {
        // Bump the revision when registry or matching semantics invalidate old IDs.
        val regions = storefrontOrder.joinToString(",")
        if (!enabled) "strict-v2-" + identityDigest(regions)
        else "aliases-v2-" + identityDigest(overrides.trim() + "\n" + regions)
    }
    private val parsedOverrides by lazy { ArtistIdentityResolver.parseOverrides(overrides) }

    /** Only this artist's effective binding participates in its persistent cache. */
    fun artistCacheToken(name: String): String {
        val binding = when {
            !enabled -> "strict"
            artistIdentityKey(name) in parsedOverrides.rejected -> "rejected"
            else -> parsedOverrides.ids[artistIdentityKey(name)] ?: "automatic"
        }
        // Only automatic aliases need a fresh namespace for the new ranking.
        // Explicit IDs and strict-mode results retain their existing cache.
        val prefix = if (!enabled) "strict-v3-" else if (binding == "automatic") "aliases-v4-" else "aliases-v3-"
        return prefix + identityDigest(binding + "\n" + storefrontOrder.joinToString(","))
    }
    internal fun manualId(name: String): String? = if (enabled) parsedOverrides.ids[artistIdentityKey(name)] else null
    internal fun rejects(name: String): Boolean = enabled && artistIdentityKey(name) in parsedOverrides.rejected
    internal fun artistCacheStem(name: String): String = normalizeArtistKey(name) + "--${identityDigest(artistIdentityKey(name))}"
    internal fun legacyArtistCacheKey(name: String): String = artistCacheStem(name) + "--$cacheToken"
    fun artistCacheKey(name: String): String = artistCacheStem(name) + "--${artistCacheToken(name)}"

    companion object {
        const val DEFAULT_STOREFRONTS = "US,JP,CN"
        private val ISO_COUNTRIES = Locale.getISOCountries().toSet()
        private fun normalizedStorefronts(value: String): List<String> = value
            .split(Regex("[,;，\\s]+"))
            .map { it.trim().uppercase(Locale.ROOT) }
            .filter { it.length == 2 && it in ISO_COUNTRIES }
            .distinct().take(8)
            .ifEmpty { DEFAULT_STOREFRONTS.split(',') }
    }
}

/** Explicit manual search results; returning a candidate does not bind an artist. */
data class ArtistMatchCandidate(val id: String, val name: String, val url: String, val avatarUrl: String? = null)

private fun identityDigest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).take(10).joinToString("") { "%02x".format(it) }

internal data class ArtistSearchCandidate(val id: String, val name: String, val url: String)

/** Case/width/spacing normalization only: punctuation and collaboration names remain distinct. */
internal fun artistIdentityKey(name: String): String = Normalizer.normalize(name.trim(), Normalizer.Form.NFKC)
    .lowercase(Locale.ROOT)
    .filterNot { it == '\u200b' || it == '\ufeff' || it == '\u2060' }
    .map { if (it.isWhitespace() || Character.isSpaceChar(it)) ' ' else it }.joinToString("")
    .trim().replace(Regex(" +"), " ")

/** Shared transient transport health; artist availability and language stay independent. */
internal class ArtistStorefrontHealth(private val now: () -> Long = System::currentTimeMillis) {
    @Volatile private var unavailableUsUntil = 0L

    fun unavailable(country: String, configured: List<String>) {
        if (country == "US" && configured == DEFAULT) unavailableUsUntil = now() + 5L * 60 * 1000
    }

    fun attempts(configured: List<String>): Sequence<String> = sequence {
        val tried = HashSet<String>()
        while (true) {
            val current = if (configured == DEFAULT && now() < unavailableUsUntil) listOf("CN", "JP") else configured
            val next = current.firstOrNull { it !in tried } ?: break
            tried += next
            yield(next)
        }
    }

    private companion object { val DEFAULT = ArtistIdentityPolicy.DEFAULT_STOREFRONTS.split(',') }
}

internal class ArtistIdentityResolver(private val get: (String) -> String?, private val log: PluginLog,
                                      private val health: ArtistStorefrontHealth = ArtistStorefrontHealth()) {
    private data class Hit(val id: String?, val until: Long)
    private val resolutions = ConcurrentHashMap<String, Hit>()
    private val verifiedIds = ConcurrentHashMap<String, Pair<Set<String>, Long>>()
    private val searches = ConcurrentHashMap<String, Pair<List<ArtistSearchCandidate>, Long>>()

    fun forget(name: String) {
        val suffix = "|${artistIdentityKey(name)}"
        resolutions.keys.removeIf { it.endsWith(suffix) }
        searches.keys.removeIf { it.endsWith(suffix) }
    }

    fun resolve(name: String, policy: ArtistIdentityPolicy): String? {
        val key = policy.artistCacheToken(name) + "|" + artistIdentityKey(name)
        val now = System.currentTimeMillis()
        resolutions[key]?.takeIf { now < it.until }?.let { return it.id }
        val resolved = runCatching { resolveUncached(name, policy) }.onFailure {
            log.log("apple-music identity unavailable: ${it.javaClass.simpleName}")
        }.getOrNull()
        resolutions[key] = Hit(resolved, now + if (resolved == null) NEGATIVE_MS else RETRY_MS)
        return resolved
    }

    private fun resolveUncached(name: String, policy: ArtistIdentityPolicy): String? {
        val wanted = artistIdentityKey(name)
        if (wanted.isEmpty()) return null
        if (policy.enabled) {
            val custom = parseOverrides(policy.overrides)
            if (wanted in custom.rejected) return null
            custom.ids[wanted]?.let { id -> return id.takeIf { verifiedNames(it, policy).isNotEmpty() } }
            GROUPS.firstOrNull { wanted in it.names }?.let { group ->
                return group.id.takeIf { verifiedNames(it, policy, group.names).any(group.names::contains) }
            }
            // Variants in the extended table may reuse an already verified ID,
            // but an unrelated lookup name can never validate that mapping.
            ArtistAliasRegistry.groupsFor(name).firstOrNull { it.id != null }?.let { group ->
                val id = checkNotNull(group.id)
                if (verifiedNames(id, policy, group.names).any(group.names::contains)) return id
            }
        }
        val candidates = arrayListOf<ArtistSearchCandidate>()
        val ranked = linkedMapOf<String, CandidateSignals>()
        fun search(term: String, regions: Sequence<String>) {
            for (country in regions) {
                val url = "https://itunes.apple.com/search?term=${URLEncoder.encode(term, "UTF-8")}" +
                    "&entity=musicArtist&attribute=artistTerm&country=$country&limit=8"
                val found = fetch(url)?.let(AppleMusicPageParser::artistSearchCandidatesOrNull)
                if (found == null) {
                    health.unavailable(country, policy.storefrontOrder)
                    continue
                }
                candidates += found
                found.forEachIndexed { index, candidate ->
                    val matches = if (policy.enabled) artistNamesMatch(name, candidate.name)
                        else artistIdentityKey(candidate.name) == wanted
                    if (matches) ranked.getOrPut(candidate.id) { CandidateSignals(candidate) }
                        .recordSearch(country, index)
                }
                // Strict mode preserves its existing first-region exact behavior.
                if (!policy.enabled && ranked.isNotEmpty()) return
            }
        }
        search(name, health.attempts(policy.storefrontOrder))
        if (policy.enabled) {
            // Variants discover candidates, but names still have to match a known
            // group or the controlled kana comparison; limit extra requests.
            artistSearchTerms(name, true).drop(1).take(2).forEach { term ->
                search(term, health.attempts(policy.storefrontOrder).take(2))
            }
        }
        if (!policy.enabled) return ranked.keys.singleOrNull()
        if (ranked.size == 1) return ranked.keys.single()
        if (ranked.size > 1) return choosePopularCandidate(name, ranked.values.toList(), policy)
        if (!policy.enabled) return null
        val ids = candidates.map { it.id }.distinct()
        if (ids.isEmpty() || ids.size > 3) return null
        // A localized official name may only occur in lookup or the artist header.
        val names = linkedMapOf<String, MutableSet<String>>()
        for (country in health.attempts(policy.storefrontOrder)) {
            val found = lookup(ids, country, policy.storefrontOrder)
            found.forEach { (id, values) -> names.getOrPut(id) { linkedSetOf() }.addAll(values) }
        }
        val lookupMatches = names.filterValues { wanted in it }.keys
        if (lookupMatches.isNotEmpty()) {
            val eligible = candidates.filter { it.id in lookupMatches }.distinctBy { it.id }
                .map { CandidateSignals(it) }
            return if (eligible.size == 1) eligible.single().candidate.id else choosePopularCandidate(name, eligible, policy)
        }
        val language = when {
            name.any { it in '\u3040'..'\u30ff' } -> "ja-JP"
            name.any { it in '\u3400'..'\u9fff' } -> "zh-Hans"
            else -> "en-US"
        }
        val matches = ids.filter { id ->
            health.attempts(policy.storefrontOrder).any { country ->
                val url = "https://music.apple.com/${country.lowercase(Locale.ROOT)}/artist/$id?l=$language"
                fetch(url)?.let { AppleMusicPageParser.artistNamesFromHtml(it, id, url) }
                    .orEmpty().any { artistIdentityKey(it) == wanted }
            }
        }
        val eligible = candidates.filter { it.id in matches }.distinctBy { it.id }.map { CandidateSignals(it) }
        return when (eligible.size) {
            0 -> null
            1 -> eligible.single().candidate.id
            else -> choosePopularCandidate(name, eligible, policy)
        }
    }

    /** Signals are catalog evidence, not follower or play counts. */
    private fun choosePopularCandidate(name: String, candidates: List<CandidateSignals>, policy: ArtistIdentityPolicy): String? {
        if (candidates.isEmpty()) return null
        val ids = candidates.map { it.candidate.id }.toSet()
        // At most two valid storefront responses; unavailable responses are not zero popularity.
        var successful = 0
        for (country in health.attempts(policy.storefrontOrder)) {
            if (successful >= 2) break
            val url = "https://itunes.apple.com/search?term=${URLEncoder.encode(name, "UTF-8")}" +
                "&entity=song&country=$country&limit=50"
            val response = fetch(url)?.let { AppleMusicPageParser.songArtistSignals(it, ids) }
            if (response == null) { health.unavailable(country, policy.storefrontOrder); continue }
            successful++
            candidates.forEach { candidate -> candidate.songIds += response[candidate.candidate.id].orEmpty() }
        }
        // Motion is a weak tie-breaker, not proof of popularity or identity.
        // Inspect at most two plausible leaders and avoid expensive page fetches
        // when song evidence already outweighs the maximum motion bonus.
        val leaders = candidates.sortedWith(compareByDescending<CandidateSignals> { it.baseScore }.thenBy { it.order })
        if (leaders.size > 1 && leaders[0].baseScore - leaders[1].baseScore <= MOTION_BONUS) {
            leaders.take(2).forEach { signal ->
                val url = signal.candidate.url
                val page = fetch(url)?.let { AppleMusicPageParser.parseHtml(it, signal.candidate.id, url, 0L) }
                signal.motion = page?.motion != null
            }
        }
        val winner = candidates.maxWithOrNull(compareBy<CandidateSignals> { it.score }.thenBy { -it.order }) ?: return null
        log.log("apple-music automatic choice candidates=${candidates.size} songs=${winner.songIds.size} motion=${winner.motion} regions=${winner.countries.size}")
        return winner.candidate.id
    }

    private class CandidateSignals(val candidate: ArtistSearchCandidate) {
        val countries = linkedSetOf<String>()
        val songIds = linkedSetOf<String>()
        var motion = false
        var order = Int.MAX_VALUE
        fun recordSearch(country: String, index: Int) {
            countries += country
            order = minOf(order, index)
        }
        val baseScore: Int get() = songIds.size.coerceAtMost(12) * 3 + countries.size.coerceAtMost(3) * 2
        val score: Int get() = baseScore + if (motion) MOTION_BONUS else 0
    }

    /** Blocking, bounded search for a user to inspect; no automatic binding or artwork fetch. */
    fun searchCandidates(query: String, policy: ArtistIdentityPolicy): List<ArtistMatchCandidate> {
        val name = query.trim()
        if (name.isEmpty() || name.length > 2048) return emptyList()
        val key = policy.artistCacheToken(name) + "|" + artistIdentityKey(name)
        val now = System.currentTimeMillis()
        searches[key]?.takeIf { now < it.second }?.let { (items, _) ->
            return manualCandidates(items, name)
        }
        val explicitId = name.takeIf { it.matches(Regex("[1-9][0-9]{0,19}")) }
            ?: AppleMusicPageParser.artistIdOf(name)
        if (explicitId != null) {
            for (country in health.attempts(policy.storefrontOrder)) {
                val url = "https://itunes.apple.com/lookup?id=$explicitId&country=$country"
                val response = fetch(url)?.let(AppleMusicPageParser::artistSearchCandidatesOrNull)
                if (response == null) health.unavailable(country, policy.storefrontOrder)
                val found = response.orEmpty().filter { it.id == explicitId }
                if (found.isNotEmpty()) {
                    searches[key] = found to (now + RETRY_MS)
                    return manualCandidates(found, name)
                }
            }
            searches[key] = emptyList<ArtistSearchCandidate>() to (now + NEGATIVE_MS)
            return emptyList()
        }
        if (name.startsWith("http", ignoreCase = true) || name.all(Char::isDigit)) return emptyList()
        if (name.length > 200) return emptyList()
        val candidates = arrayListOf<ArtistSearchCandidate>()
        for (country in health.attempts(policy.storefrontOrder)) {
            val url = "https://itunes.apple.com/search?term=${URLEncoder.encode(name, "UTF-8")}" +
                "&entity=musicArtist&attribute=artistTerm&country=$country&limit=8"
            val found = fetch(url)?.let(AppleMusicPageParser::artistSearchCandidatesOrNull)
            if (found == null) health.unavailable(country, policy.storefrontOrder) else candidates += found
        }
        searches[key] = candidates to (now + if (candidates.isEmpty()) NEGATIVE_MS else RETRY_MS)
        return manualCandidates(candidates, name)
    }

    private fun manualCandidates(items: List<ArtistSearchCandidate>, query: String): List<ArtistMatchCandidate> =
        items.sortedBy { if (artistIdentityKey(it.name) == artistIdentityKey(query)) 0 else 1 }
            .distinctBy { it.id }.take(30).map { ArtistMatchCandidate(it.id, it.name, it.url) }

    private fun fetch(url: String): String? = runCatching { get(url) }.getOrNull()

    private fun verifiedNames(id: String, policy: ArtistIdentityPolicy,
                              expectedNames: Set<String>? = null): Set<String> = synchronized(verifiedIds) {
        val key = policy.storefrontOrder.joinToString(",") + "|" + id
        val now = System.currentTimeMillis()
        verifiedIds[key]?.takeIf { now < it.second }?.let { cached ->
            if (expectedNames == null || cached.first.isEmpty() || cached.first.any(expectedNames::contains)) return@synchronized cached.first
        }
        val collected = linkedSetOf<String>()
        for (country in health.attempts(policy.storefrontOrder)) {
            val names = lookup(listOf(id), country, policy.storefrontOrder)[id].orEmpty()
            if (names.isNotEmpty()) {
                collected += names
                if (expectedNames == null || names.any(expectedNames::contains)) {
                    verifiedIds[key] = collected to (now + VERIFIED_MS)
                    return@synchronized collected
                }
            }
        }
        verifiedIds[key] = collected to (now + if (collected.isEmpty()) NEGATIVE_MS else VERIFIED_MS)
        collected
    }

    private fun lookup(ids: List<String>, country: String, configured: List<String>): Map<String, Set<String>> {
        val url = "https://itunes.apple.com/lookup?id=${ids.joinToString(",")}&country=$country"
        val response = fetch(url)?.let(AppleMusicPageParser::artistSearchCandidatesOrNull)
        if (response == null) health.unavailable(country, configured)
        return response.orEmpty().filter { it.id in ids }.groupBy { it.id }
            .mapValues { (_, items) -> items.map { artistIdentityKey(it.name) }.toSet() }
    }

    internal data class Overrides(val ids: Map<String, String>, val rejected: Set<String>)
    companion object {
        private const val MOTION_BONUS = 6
        private const val RETRY_MS = 15L * 60 * 1000
        private const val NEGATIVE_MS = 60_000L
        private const val VERIFIED_MS = 24L * 60 * 60 * 1000
        private data class Group(val id: String, val names: Set<String>)
        // Public Apple CN/US/JP/TW pages were checked on 2026-10-23. Every
        // group's ID is revalidated by lookup before it can supply metadata.
        private val GROUPS = listOf(
            "307078957" to listOf("初音ミク", "初音未来", "初音未來", "Hatsune Miku"),
            "300117743" to listOf("周杰伦", "周杰倫", "Jay Chou"),
            "137938148" to listOf("陈奕迅", "陳奕迅", "Eason Chan"),
            "216635866" to listOf("林俊杰", "林俊傑", "JJ Lin"),
            "41760704" to listOf("王菲", "Faye Wong"),
            "425208570" to listOf("邓紫棋", "鄧紫棋", "G.E.M."),
            // Official header 567072 carries the current motion artwork. Search
            // also returns a different artist named Gorillaz (1529389870).
            "567072" to listOf("Gorillaz"),
        ).map { (id, names) -> Group(id, names.map(::artistIdentityKey).toSet()) }

        internal fun parseOverrides(text: String): Overrides {
            val ids = linkedMapOf<String, String>()
            val rejected = linkedSetOf<String>()
            text.split(Regex("[;\\r\\n]+")).map(String::trim).filter(String::isNotEmpty).forEach { line ->
                val alias = artistIdentityKey(line.substringBefore('='))
                val target = line.substringAfter('=', "").trim()
                val id = target.takeIf { it.matches(Regex("[1-9][0-9]{0,19}")) }
                    ?: AppleMusicPageParser.artistIdOf(target)
                if (alias.isEmpty() || id == null || alias in rejected || (ids[alias] != null && ids[alias] != id)) {
                    ids.remove(alias); rejected += alias
                } else ids[alias] = id
            }
            return Overrides(ids, rejected)
        }
    }
}
