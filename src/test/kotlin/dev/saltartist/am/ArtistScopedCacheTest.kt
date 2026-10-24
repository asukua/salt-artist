package dev.saltartist.am

import dev.saltartist.PluginLog
import dev.saltartist.artwork.appleArtworkCacheKey
import dev.saltartist.artwork.artistAvatarCacheKey
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ArtistScopedCacheTest {
    private val log = PluginLog(null)
    private val policy = ArtistIdentityPolicy(overrides = "Local Artist=42")
    private fun record(id: String = "42"): AmArtist {
        val now = System.currentTimeMillis()
        return AmArtist(id, "https://example.mzstatic.com/hero/{w}x{h}.{f}", "bb", true,
            "https://example.mzstatic.com/logo/{w}x{h}.{f}", "bb", now,
            avatarTemplate = "https://example.mzstatic.com/avatar/{w}x{h}.{f}",
            details = AmArtistDetails(biography = "这是一段用于本地缓存测试的原创中文简介。", languageCode = "zh-Hans"),
            detailsFetchedAt = now, motionCheckedAt = now)
    }

    @Test fun editingAnotherBindingDoesNotInvalidateMetadataOrEitherArtworkCache() {
        val changed = policy.copy(overrides = "Other Artist=99; Local Artist=https://music.apple.com/us/artist/local/42")
        assertEquals(policy.artistCacheKey("Local Artist"), changed.artistCacheKey("Local Artist"))
        assertEquals(artistAvatarCacheKey("Local Artist", policy), artistAvatarCacheKey("Local Artist", changed))
        assertEquals(appleArtworkCacheKey("Local Artist", ArtistLanguagePolicy(), policy),
            appleArtworkCacheKey("Local Artist", ArtistLanguagePolicy(), changed))
        assertEquals(ArtistIdentityPolicy().artistCacheKey("Unbound"), changed.artistCacheKey("Unbound"))
        assertNotEquals(policy.artistCacheKey("Local Artist"), policy.copy(overrides = "Local Artist=43").artistCacheKey("Local Artist"))
        assertNotEquals(policy.artistCacheKey("Local Artist"), policy.copy(enabled = false).artistCacheKey("Local Artist"))
        assertNotEquals(policy.artistCacheKey("Local Artist"), policy.copy(storefronts = "JP,CN").artistCacheKey("Local Artist"))
    }

    @Test fun aVerifiedManualLegacyMatchAndItsFilesMigrateWithoutNetworkAndSurviveRestart() {
        val dir = Files.createTempDirectory("salt-manual-cache").toFile()
        try {
            val oldPolicy = policy.copy(overrides = "Local Artist=42;Old Other=88")
            val oldBase = oldPolicy.legacyArtistCacheKey("Local Artist")
            dir.resolve("apple-music-cache.tsv").writeText(AppleMusicCache.encode("$oldBase|zh-Hans", record()) + "\n")
            val images = dir.resolve("apple-music").apply { mkdirs() }
            for (suffix in listOf("-hero.jpg", "-logo.png", "-avatar.jpg")) images.resolve(oldBase + suffix).writeText(suffix)
            images.resolve("artist-$oldBase-${oldPolicy.cacheToken}-avatar-320.jpg").writeText("thumbnail")
            val catalog = AppleMusicCatalog(dir, log, { error("A migrated hit must not use the network") })
            assertEquals("42", catalog.cached("Local Artist", identity = policy)?.artistId)
            assertTrue(catalog.cachedIsFresh("Local Artist", ArtistLanguagePolicy(), policy))
            assertEquals("-hero.jpg", catalog.heroFile("Local Artist", policy)?.readText())
            assertEquals("-logo.png", catalog.logoFile("Local Artist", policy)?.readText())
            assertEquals("thumbnail", catalog.avatarThumbnailFile("Local Artist", policy)?.readText())
            val reloaded = AppleMusicCatalog(dir, log, { error("A warm restart must stay local") })
            assertEquals("42", reloaded.resolve("Local Artist", identity = policy)?.artistId)
            assertEquals("42", reloaded.resolveAvatar("Local Artist", policy)?.artistId)
            assertEquals("42", reloaded.cached("Local Artist", identity = policy.copy(overrides = "Local Artist=42;Another=77"))?.artistId)
        } finally { dir.deleteRecursively() }
    }

    @Test fun oldWrongIdsAmbiguousBindingsAndRemovedManualBindingsCannotSupplyPictures() {
        for (current in listOf(policy.copy(overrides = "Local Artist=43"), policy.copy(overrides = ""),
            policy.copy(overrides = "Local Artist=42;Local Artist=43"), policy.copy(enabled = false))) {
            val dir = Files.createTempDirectory("salt-cache-isolation").toFile()
            try {
                dir.resolve("apple-music-cache.tsv").writeText(AppleMusicCache.encode("${policy.legacyArtistCacheKey("Local Artist")}|zh-Hans", record()) + "\n")
                assertNull(AppleMusicCatalog(dir, log).cached("Local Artist", identity = current))
            } finally { dir.deleteRecursively() }
        }
    }

    @Test fun exactAutomaticLegacyPolicyStillMigratesButUnrelatedHistoricalOverridesAreNotGuessed() {
        val dir = Files.createTempDirectory("salt-auto-cache").toFile()
        try {
            val automatic = ArtistIdentityPolicy()
            val historical = automatic.copy(overrides = "Historical=42")
            dir.resolve("apple-music-cache.tsv").writeText(
                AppleMusicCache.encode("${automatic.legacyArtistCacheKey("Automatic")}|zh-Hans", record()) + "\n" +
                    AppleMusicCache.encode("${historical.legacyArtistCacheKey("Historical")}|zh-Hans", record()) + "\n")
            val catalog = AppleMusicCatalog(dir, log)
            assertEquals("42", catalog.cached("Automatic", identity = automatic)?.artistId)
            assertEquals("42", catalog.cached("Automatic", identity = automatic.copy(overrides = "Other=99"))?.artistId)
            assertNull(catalog.cached("Historical", identity = automatic))
            // Once the confirmed automatic record is migrated, future unrelated edits keep its stable key.
            assertEquals(automatic.artistCacheKey("Automatic"), automatic.copy(overrides = "Other=99").artistCacheKey("Automatic"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun refreshingOnePunctuationDistinctArtistKeepsTheOtherArtistsCache() {
        val dir = Files.createTempDirectory("salt-cache-punctuation").toFile()
        try {
            val identity = ArtistIdentityPolicy(overrides = "AC/DC=42;AC DC=43")
            dir.resolve("apple-music-cache.tsv").writeText(listOf("AC/DC" to "42", "AC DC" to "43").joinToString("\n") {
                (name, id) -> AppleMusicCache.encode("${identity.artistCacheKey(name)}|zh-Hans", record(id))
            })
            val catalog = AppleMusicCatalog(dir, log)
            assertTrue(catalog.forget("AC/DC"))
            assertNull(catalog.cached("AC/DC", identity = identity))
            assertEquals("43", catalog.cached("AC DC", identity = identity)?.artistId)
        } finally { dir.deleteRecursively() }
    }

    @Test fun aSlowOldProfileCannotRestoreMetadataAfterRefresh() {
        val dir = Files.createTempDirectory("salt-cache-late-profile").toFile()
        val worker = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        try {
            val fixture = checkNotNull(javaClass.getResource("/apple-music/artist-cn-sanitized.html")).readText()
            val catalog = AppleMusicCatalog(dir, log, { url ->
                if ("itunes.apple.com" in url) """{"results":[{"artistId":42,"artistName":"Local Artist","artistLinkUrl":"https://music.apple.com/us/artist/local/42"}]}""" else fixture
            }, { _, _, _, _ -> started.countDown(); check(finish.await(5, TimeUnit.SECONDS)); null })
            val pending = worker.submit<AmArtist?> { catalog.resolve("Local Artist", ArtistLanguagePolicy(showOtherLanguages = false), identity = policy) }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            catalog.forget("Local Artist")
            finish.countDown()
            pending.get(5, TimeUnit.SECONDS)
            assertNull(catalog.cached("Local Artist", identity = policy))
            assertNull(AppleMusicCatalog(dir, log).cached("Local Artist", identity = policy))
        } finally { finish.countDown(); worker.shutdownNow(); dir.deleteRecursively() }
    }

    @Test fun atomicCacheReplacementPreservesTheCompleteFileAndLeavesNoTemporaryFile() {
        val dir = Files.createTempDirectory("salt-atomic-cache").toFile()
        try {
            val file = dir.resolve("nested/metadata.tsv")
            atomicCacheWrite(file, "old".toByteArray())
            atomicCacheWrite(file, "complete new content".toByteArray())
            assertEquals("complete new content", file.readText())
            assertEquals(listOf("metadata.tsv"), file.parentFile.listFiles()?.map { it.name })
        } finally { dir.deleteRecursively() }
    }
}
