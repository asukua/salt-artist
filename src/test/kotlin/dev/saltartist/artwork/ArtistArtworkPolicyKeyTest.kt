package dev.saltartist.artwork

import dev.saltartist.am.ArtistIdentityPolicy
import dev.saltartist.am.ArtistLanguagePolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ArtistArtworkPolicyKeyTest {
    @Test fun regionOrderAndChinesePreferenceCannotReuseThePreviousBiographySnapshot() {
        val identity = ArtistIdentityPolicy()
        fun key(policy: ArtistLanguagePolicy) = appleArtworkCacheKey("Artist", policy, identity)
        val first = ArtistLanguagePolicy(storefronts = "US,JP", preferChineseBiography = true)
        assertNotEquals(key(first), key(first.copy(storefronts = "JP,US")))
        assertNotEquals(key(first), key(first.copy(preferChineseBiography = false)))
        assertNotEquals(key(first), key(ArtistLanguagePolicy()))
        assertEquals(key(first), key(first.copy(storefronts = "us, jp, US")))
    }
}
