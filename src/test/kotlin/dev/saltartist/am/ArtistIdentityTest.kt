package dev.saltartist.am

import dev.saltartist.PluginLog
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArtistIdentityTest {
    private val log = PluginLog(null)
    private val enabled = ArtistIdentityPolicy()
    private val strict = ArtistIdentityPolicy(enabled = false)
    private val fixture: String get() = checkNotNull(javaClass.getResource("/apple-music/artist-cn-sanitized.html")).readText()

    @Test
    fun verifiedBuiltInAliasesShareOneIdValidationAndDoNotUseSearchRanking() {
        val urls = arrayListOf<String>()
        val resolver = ArtistIdentityResolver({ url ->
            urls += url
            if ("/lookup?" in url) result("307078957", "Hatsune Miku") else result("1617064435", "初音未来")
        }, log)
        for (name in listOf("初音ミク", "初音未来", "Hatsune Miku")) assertEquals("307078957", resolver.resolve(name, enabled))
        assertEquals(1, urls.size)
        assertEquals("1617064435", resolver.resolve("初音未来", strict))
        assertEquals(2, urls.size)
    }

    @Test
    fun aBuiltInIdWithTheWrongOfficialNameIsRejected() {
        val resolver = ArtistIdentityResolver({ result("307078957", "Unrelated Artist") }, log)
        assertNull(resolver.resolve("初音未来", enabled))
    }

    @Test
    fun customIdOrAppleUrlOverridesBuiltInAndConflictsFailClosed() {
        val urls = arrayListOf<String>()
        val resolver = ArtistIdentityResolver({ url -> urls += url; result("42", "My Verified Artist") }, log)
        assertEquals("42", resolver.resolve("初音未来", ArtistIdentityPolicy(overrides = "初音未来=https://music.apple.com/us/artist/custom/42")))
        val before = urls.size
        assertNull(resolver.resolve("初音未来", ArtistIdentityPolicy(overrides = "初音未来=42;初音未来=43")))
        assertNull(resolver.resolve("初音未来", ArtistIdentityPolicy(overrides = "初音未来=https://example.com/artist/42")))
        assertEquals(before, urls.size)
        for (url in listOf(
            "https://music.apple.com/us/artist/name/42/999", "https://music.apple.com/us/artist//42",
            "https://music.apple.com/us/artist/../42", "https://music.apple.com/us/artist/%2E%2E/42",
            "https://music.apple.com/us/artist/name/42/", "https://music.apple.com/us/artist/name/0",
            "https://music.apple.com/us/artist/name/00042", "https://music.apple.com/unknown/artist/name/42",
        )) assertTrue("alias" in ArtistIdentityResolver.parseOverrides("Alias=$url").rejected, url)
        val parsed = ArtistIdentityResolver.parseOverrides("AC/DC=42\nAC DC=43;G.E.M.=44;GEM=45")
        assertEquals(4, parsed.ids.size)
        assertTrue(parsed.rejected.isEmpty())
    }

    @Test
    fun enabledRequiresUniqueExactIdentityAcrossStorefrontsWhileDisabledStaysExact() {
        val urls = arrayListOf<String>()
        val resolver = ArtistIdentityResolver({ url ->
            urls += url
            if ("country=US" in url) result("43", "Solo Artist") else result("42", "Solo Artist")
        }, log)
        assertNull(resolver.resolve("Solo Artist", enabled))
        val before = urls.size
        assertNull(resolver.resolve("Solo Artist", enabled))
        assertEquals(before, urls.size, "Ambiguous results should be cached briefly")
        assertEquals("42", resolver.resolve("Solo Artist", strict))
    }

    @Test
    fun unknownStorefrontsCannotConfirmUniquenessButValidEmptyResultsCan() {
        for (unavailable in listOf(null, "{broken", "{\"results\":{}}")) {
            val resolver = ArtistIdentityResolver({ url ->
                when {
                    "country=CN" in url -> result("42", "Solo Artist")
                    "country=US" in url -> unavailable
                    else -> "{\"results\":[]}"
                }
            }, log)
            assertNull(resolver.resolve("Solo Artist", enabled))
            assertEquals("42", resolver.resolve("Solo Artist", strict))
        }
        val available = ArtistIdentityResolver({ url ->
            if ("country=CN" in url) result("42", "Solo Artist") else "{\"results\":[]}"
        }, log)
        assertEquals("42", available.resolve("Solo Artist", enabled))
    }

    @Test
    fun uniqueCompleteNameIsAcceptedButCollaborationAndPunctuationAreNotAliases() {
        val resolver = ArtistIdentityResolver({ result("42", "A & B") }, log)
        assertEquals("42", resolver.resolve("A & B", enabled))
        assertNull(resolver.resolve("A", enabled))
        assertNotEquals(artistIdentityKey("AC/DC"), artistIdentityKey("AC DC"))
        assertNotEquals(artistIdentityKey("G.E.M."), artistIdentityKey("GEM"))
        assertEquals(artistIdentityKey("  HATSUNE   MIKU "), artistIdentityKey("Hatsune Miku"))
    }

    @Test
    fun boundedPublicHeaderVerificationDoesNotGuessWhenTheCandidateSetIsLarger() {
        val urls = arrayListOf<String>()
        val resolver = ArtistIdentityResolver({ url ->
            urls += url
            if ("/search?" in url) results((1..4).map { it.toString() to "Other $it" }) else error("Too many candidates must not trigger lookup or header requests")
        }, log)
        assertNull(resolver.resolve("Unlisted Name", enabled))
        assertEquals(3, urls.size)
    }

    @Test
    fun aScopedOfficialHeaderCanVerifyAnUntranslatedSearchName() {
        val urls = arrayListOf<String>()
        val resolver = ArtistIdentityResolver({ url ->
            urls += url
            when {
                "/search?" in url || "/lookup?" in url -> result("42", "Official Other Language")
                "/artist/42" in url -> page("42").replace("\"title\":\"测试艺人\"", "\"title\":\"Verified Alias\"")
                else -> error("Unexpected request")
            }
        }, log)
        assertEquals("42", resolver.resolve("Verified Alias", enabled))
        assertEquals(1, urls.count { "music.apple.com" in it && "/artist/" in it })
        assertFalse(urls.any { "/assets/" in it || "amp-api" in it || "mzstatic.com" in it })
        assertTrue(AppleMusicPageParser.artistNamesFromHtml(page("99"), "42", "https://music.apple.com/us/artist/42").isEmpty())
        val invalidCanonical = page("42").replace(
            "\"canonicalURL\":\"https://music.apple.com/cn/artist/test/42\"",
            "\"canonicalURL\":\"http://example.com/not-an-artist\"",
        )
        assertTrue(AppleMusicPageParser.artistNamesFromHtml(invalidCanonical, "42", "https://music.apple.com/us/artist/42").isEmpty())
    }

    @Test
    fun metadataImagesAndReloadedCachesAreIsolatedByIdentityPolicy() {
        val directory = Files.createTempDirectory("salt-artist-identity").toFile()
        try {
            val custom = ArtistIdentityPolicy(overrides = "Local Alias=42")
            val changed = ArtistIdentityPolicy(overrides = "Local Alias=43")
            val catalog = AppleMusicCatalog(directory, log, { url ->
                when {
                    "/search?" in url -> result("99", "Local Alias")
                    "/lookup?" in url -> if ("id=43" in url) result("43", "Verified 43") else result("42", "Verified 42")
                    "/artist/43" in url -> page("43")
                    "/artist/42" in url -> page("42")
                    "/artist/99" in url -> page("99")
                    else -> error("Unexpected request")
                }
            }, { _, _, _, _ -> error("Avatar resolution must remain lightweight") })
            assertEquals("99", catalog.resolveAvatar("Local Alias", strict)?.artistId)
            assertEquals("42", catalog.resolveAvatar("Local Alias", custom)?.artistId)
            assertEquals("43", catalog.resolveAvatar("Local Alias", changed)?.artistId)
            val files = listOf(strict, custom, changed).map { policy ->
                catalog.ensureImageDir()
                assertNotNull(catalog.heroFile("Local Alias", policy)).also { it.writeText("art") }
            }
            assertEquals(3, files.toSet().size)
            val reloaded = AppleMusicCatalog(directory, log)
            assertEquals("99", reloaded.cached("Local Alias", identity = strict)?.artistId)
            assertEquals("42", reloaded.cached("Local Alias", identity = custom)?.artistId)
            assertEquals("43", reloaded.cached("Local Alias", identity = changed)?.artistId)
            assertTrue(reloaded.forget("Local Alias"))
            assertTrue(files.none { it.exists() })
            assertNull(reloaded.cached("Local Alias", identity = custom))
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun enabledCacheStemsPreservePunctuationAndDisabledOverridesHaveNoEffect() {
        assertNotEquals(enabled.artistCacheKey("AC/DC"), enabled.artistCacheKey("AC DC"))
        assertNotEquals(enabled.artistCacheKey("G.E.M."), enabled.artistCacheKey("GEM"))
        assertNotEquals(enabled.cacheToken, ArtistIdentityPolicy(overrides = "Name=42").cacheToken)
        assertEquals("strict-v1", ArtistIdentityPolicy(false, "Name=42").cacheToken)
        assertNotEquals(strict.artistCacheKey("AC/DC"), strict.artistCacheKey("AC DC"))
        assertEquals(strict.artistCacheKey("Name"), ArtistIdentityPolicy(false, "Name=42").artistCacheKey("Name"))
        assertFalse(enabled.artistCacheKey("A/B").contains('/'))
    }

    private fun page(id: String) = fixture.replace("\"42\"", "\"$id\"").replace("/42", "/$id")
    private fun result(id: String, name: String) = results(listOf(id to name))
    private fun results(artists: List<Pair<String, String>>) = "{\"results\":[" + artists.joinToString(",") { (id, name) ->
        "{\"wrapperType\":\"artist\",\"artistId\":$id,\"artistName\":\"$name\",\"artistLinkUrl\":\"https://music.apple.com/us/artist/test/$id\"}"
    } + "]}"
}
