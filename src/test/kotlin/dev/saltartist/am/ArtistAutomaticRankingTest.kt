package dev.saltartist.am

import dev.saltartist.PluginLog
import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArtistAutomaticRankingTest {
    private val log = PluginLog(null)
    private val policy = ArtistIdentityPolicy(storefronts = "US,JP,CN")
    private fun artist(id: String, name: String) =
        """{"wrapperType":"artist","artistId":$id,"artistName":"$name","artistLinkUrl":"https://music.apple.com/us/artist/test/$id"}"""
    private fun results(vararg entries: String) = "{\"results\":[" + entries.joinToString(",") + "]}"
    private fun song(artistId: String, trackId: Int) =
        """{"wrapperType":"track","kind":"song","artistId":$artistId,"trackId":$trackId}"""
    private val motion: String get() = checkNotNull(javaClass.getResource("/apple-music/artist-motion-sanitized.json")).readText()
    private fun html(id: String, animated: Boolean): String {
        var json = motion.replace("/42", "/$id").replace("\"42\"", "\"$id\"")
        if (!animated) json = json.replace("\"video\":", "\"ignoredVideo\":")
        return "<script id=\"serialized-server-data\">$json</script>"
    }

    @Test fun matchingSongsChooseThePopularSameNameCandidateEvenWhenListedSecond() {
        val calls = mutableListOf<String>()
        val resolver = ArtistIdentityResolver({ url ->
            calls += url
            when {
                "entity=musicArtist" in url -> results(artist("41", "Adele"), artist("42", "Adele"), artist("43", "Adele Tribute"))
                "entity=song" in url -> results(*(1..18).map { song("42", it) }.toTypedArray(), song("41", 100), song("43", 101))
                "/artist/" in url -> html(if ("/42" in url) "42" else "41", false)
                else -> null
            }
        }, log)
        assertEquals("42", resolver.resolve("Adele", policy))
        assertEquals(2, calls.count { "entity=song" in it })
        assertFalse(calls.any { "/artist/43" in it })
        val count = calls.size
        assertEquals("42", resolver.resolve("Adele", policy))
        assertEquals(count, calls.size)
    }

    @Test fun dynamicArtworkBreaksANameAndSongEvidenceTie() {
        val resolver = ArtistIdentityResolver({ url ->
            when {
                "entity=musicArtist" in url -> results(artist("41", "Twin"), artist("42", "Twin"))
                "entity=song" in url -> results(song("41", 1), song("42", 2))
                url.endsWith("/42") -> html("42", true)
                url.endsWith("/41") -> html("41", false)
                else -> null
            }
        }, log)
        assertEquals("42", resolver.resolve("Twin", policy))
    }

    @Test fun unavailableSignalsStillPickARelevantCandidateAutomatically() {
        val calls = mutableListOf<String>()
        val resolver = ArtistIdentityResolver({ url ->
            calls += url
            if ("entity=musicArtist" in url) results(artist("41", "Twin"), artist("42", "Twin")) else null
        }, log)
        assertEquals("41", resolver.resolve("Twin", policy))
        assertTrue(calls.count { "/artist/" in it } <= 2)
        assertFalse(calls.any { "amp-api" in it })
    }

    @Test fun knownAliasesAndKanaVariantsAreSearchedWithoutMergingLibraryNames() {
        val resolver = ArtistIdentityResolver({ url ->
            if ("entity=musicArtist" in url && "Mao" in url) results(artist("42", "Mao Buyi")) else "{\"results\":[]}"
        }, log)
        assertEquals("42", resolver.resolve("毛不易", policy))
        assertTrue(artistNamesMatch("あいみょん", "Aimyon"))
        assertFalse(artistNamesMatch("A", "A & B"))
        assertFalse(artistNamesMatch("AC/DC", "AC DC"))
    }

    @Test fun extendedKnownIdAliasesAreVerifiedBeforeUse() {
        val calls = mutableListOf<String>()
        val resolver = ArtistIdentityResolver({ url ->
            calls += url
            if ("/lookup?" in url) results(artist("300117743", "Jay Chou")) else "{\"results\":[]}"
        }, log)
        assertEquals("300117743", resolver.resolve("Zhou Jielun", policy))
        assertTrue(calls.all { "/lookup?" in it })
    }

    @Test fun aliasSearchCanCompeteWithAnExactNameImpersonator() {
        val resolver = ArtistIdentityResolver({ url ->
            when {
                "entity=musicArtist" in url && "Mao" in url -> results(artist("42", "Mao Buyi"))
                "entity=musicArtist" in url -> results(artist("41", "毛不易"))
                "entity=song" in url -> results(*(1..10).map { song("42", it) }.toTypedArray())
                else -> null
            }
        }, log)
        assertEquals("42", resolver.resolve("毛不易", policy))
    }

    @Test fun strictModeKeepsItsOriginalNoAliasAndSameRegionAmbiguityBehavior() {
        val resolver = ArtistIdentityResolver({ url ->
            if ("entity=musicArtist" in url) results(artist("41", "Twin"), artist("42", "Twin"))
            else error("strict ambiguity must not invoke popularity lookups")
        }, log)
        assertNull(resolver.resolve("Twin", policy.copy(enabled = false)))
    }

    @Test fun userBindingDoesNotRequireTheOfficialNameToMatch() {
        val resolver = ArtistIdentityResolver({ url ->
            if ("/lookup?" in url) results(artist("42", "Different Official Name")) else error("manual binding must win")
        }, log)
        assertEquals("42", resolver.resolve("Local Name", policy.copy(overrides = "Local Name=42")))
    }

    @Test fun songSignalsRejectOtherArtistsDuplicatesAndNonSongs() {
        val input = results(song("42", 1), song("42", 1), song("43", 2),
            """{"wrapperType":"track","kind":"music-video","artistId":42,"trackId":3}""",
            """{"wrapperType":"track","kind":"song","artistId":42,"trackId":0}""")
        assertEquals(mapOf("42" to setOf("1")), AppleMusicPageParser.songArtistSignals(input, setOf("42")))
        assertNull(AppleMusicPageParser.songArtistSignals("{broken", setOf("42")))
    }

    @Test fun automaticCacheNamespaceChangesWithoutInvalidatingManualOrStrictBindings() {
        val automatic = ArtistIdentityPolicy()
        assertTrue(automatic.artistCacheToken("Artist").startsWith("aliases-v4-"))
        val manual = automatic.copy(overrides = "Artist=42")
        assertTrue(manual.artistCacheToken("Artist").startsWith("aliases-v3-"))
        assertTrue(automatic.copy(enabled = false).artistCacheToken("Artist").startsWith("strict-v3-"))
        assertNotEquals(automatic.artistCacheKey("Artist"), manual.artistCacheKey("Artist"))
    }

    @Test fun liveAppleCatalogSmokeTestWhenExplicitlyEnabled() {
        if (System.getenv("SALT_ARTIST_LIVE_TEST") != "1") return
        val resolver = ArtistIdentityResolver({ url ->
            runCatching {
                val c = URI(url).toURL().openConnection() as HttpURLConnection
                c.connectTimeout = 5000; c.readTimeout = 8000
                c.setRequestProperty("User-Agent", "Mozilla/5.0")
                try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
            }.getOrNull()
        }, log)
        assertEquals("262836961", resolver.resolve("Adele", ArtistIdentityPolicy(storefronts = "US,GB")))
    }
}
