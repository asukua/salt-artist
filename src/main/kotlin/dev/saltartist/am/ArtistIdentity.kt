package dev.saltartist.am

import dev.saltartist.PluginLog
import dev.saltartist.data.normalizeArtistKey
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Identity recognition affects Apple metadata only, never host library grouping. */
data class ArtistIdentityPolicy(val enabled: Boolean = true, val overrides: String = "") {
    val cacheToken: String by lazy {
        // Bump the revision when registry or matching semantics invalidate old IDs.
        if (!enabled) "strict-v1" else "aliases-v1-" + identityDigest(overrides.trim())
    }
    fun artistCacheKey(name: String): String = normalizeArtistKey(name) +
        "--${identityDigest(artistIdentityKey(name))}--$cacheToken"
}

private fun identityDigest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).take(10).joinToString("") { "%02x".format(it) }

internal data class ArtistSearchCandidate(val id: String, val name: String, val url: String)

/** Case/width/spacing normalization only: punctuation and collaboration names remain distinct. */
internal fun artistIdentityKey(name: String): String = Normalizer.normalize(name.trim(), Normalizer.Form.NFKC)
    .lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

internal class ArtistIdentityResolver(private val get: (String) -> String?, private val log: PluginLog) {
    private data class Hit(val id: String?, val until: Long)
    private val resolutions = ConcurrentHashMap<String, Hit>()
    private val verifiedIds = ConcurrentHashMap<String, Pair<Set<String>, Long>>()

    fun forget(name: String) {
        val suffix = "|${artistIdentityKey(name)}"
        resolutions.keys.removeIf { it.endsWith(suffix) }
    }

    fun resolve(name: String, policy: ArtistIdentityPolicy): String? {
        val key = policy.cacheToken + "|" + artistIdentityKey(name)
        val now = System.currentTimeMillis()
        resolutions[key]?.takeIf { now < it.until }?.let { return it.id }
        val resolved = runCatching { resolveUncached(name, policy) }.onFailure {
            log.log("apple-music identity unavailable: ${it.javaClass.simpleName}")
        }.getOrNull()
        resolutions[key] = Hit(resolved, now + RETRY_MS)
        return resolved
    }

    private fun resolveUncached(name: String, policy: ArtistIdentityPolicy): String? {
        val wanted = artistIdentityKey(name)
        if (wanted.isEmpty()) return null
        if (policy.enabled) {
            val custom = parseOverrides(policy.overrides)
            if (wanted in custom.rejected) return null
            custom.ids[wanted]?.let { id -> return id.takeIf { verifiedNames(it).isNotEmpty() } }
            GROUPS.firstOrNull { wanted in it.names }?.let { group ->
                return group.id.takeIf { verifiedNames(it).any(group.names::contains) }
            }
        }
        val candidates = arrayListOf<ArtistSearchCandidate>()
        for (country in STORES) {
            val url = "https://itunes.apple.com/search?term=${URLEncoder.encode(name, "UTF-8")}" +
                "&entity=musicArtist&attribute=artistTerm&country=$country&limit=8"
            val found = get(url)?.let(AppleMusicPageParser::artistSearchCandidatesOrNull)
            if (found == null) {
                // A failed storefront is unknown, not evidence that it has no
                // conflicting names. Pins and disabled exact mode stay separate.
                if (policy.enabled) return null else continue
            }
            candidates += found
            val matches = candidates.filter { artistIdentityKey(it.name) == wanted }.map { it.id }.distinct()
            // Disabled recognition keeps the previous small exact-name path, but
            // rejects multiple same-name IDs within the returned storefront.
            if (!policy.enabled && matches.isNotEmpty()) return matches.singleOrNull()
            if (matches.size > 1) return null
        }
        val exact = candidates.filter { artistIdentityKey(it.name) == wanted }.map { it.id }.distinct()
        if (exact.isNotEmpty()) return exact.singleOrNull()
        if (!policy.enabled) return null
        val ids = candidates.map { it.id }.distinct()
        if (ids.isEmpty() || ids.size > 3) return null
        // Batch ID lookup keeps request count bounded. Some iTunes artistName
        // fields stay untranslated, so scoped public headers are the final check.
        val names = linkedMapOf<String, MutableSet<String>>()
        for (country in STORES) {
            val found = lookup(ids, country)
            found.forEach { (id, values) -> names.getOrPut(id) { linkedSetOf() }.addAll(values) }
            if (names.filterValues { wanted in it }.size > 1) return null
        }
        val lookupMatches = names.filterValues { wanted in it }.keys
        if (lookupMatches.isNotEmpty()) return lookupMatches.singleOrNull()
        val language = when {
            name.any { it in '\u3040'..'\u30ff' } -> "jp" to "ja-JP"
            name.any { it in '\u3400'..'\u9fff' } -> "cn" to "zh-Hans"
            else -> "us" to "en-US"
        }
        val matches = ids.filter { id ->
            val url = "https://music.apple.com/${language.first}/artist/$id?l=${language.second}"
            get(url)?.let { AppleMusicPageParser.artistNamesFromHtml(it, id, url) }
                .orEmpty().any { artistIdentityKey(it) == wanted }
        }
        return matches.singleOrNull()
    }

    private fun verifiedNames(id: String): Set<String> = synchronized(verifiedIds) {
        val now = System.currentTimeMillis()
        verifiedIds[id]?.takeIf { now < it.second }?.let { return@synchronized it.first }
        for (country in STORES) {
            val names = lookup(listOf(id), country)[id].orEmpty()
            if (names.isNotEmpty()) {
                verifiedIds[id] = names to (now + VERIFIED_MS)
                return@synchronized names
            }
        }
        verifiedIds[id] = emptySet<String>() to (now + RETRY_MS)
        emptySet()
    }

    private fun lookup(ids: List<String>, country: String): Map<String, Set<String>> {
        val url = "https://itunes.apple.com/lookup?id=${ids.joinToString(",")}&country=$country"
        return get(url)?.let { AppleMusicPageParser.lookupArtistNames(it, ids.toSet()) }.orEmpty()
    }

    internal data class Overrides(val ids: Map<String, String>, val rejected: Set<String>)
    companion object {
        private const val RETRY_MS = 15L * 60 * 1000
        private const val VERIFIED_MS = 24L * 60 * 60 * 1000
        private val STORES = listOf("CN", "US", "JP")
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
