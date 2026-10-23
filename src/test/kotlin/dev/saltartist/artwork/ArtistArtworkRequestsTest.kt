package dev.saltartist.artwork

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArtistArtworkRequestsTest {
    @Test
    fun anOldRequestCannotRestoreArtworkAfterRefresh() {
        val requests = ArtistArtworkRequests()
        var image: String? = "old"
        val beforeRefresh = requests.generation("artist")
        requests.invalidate("artist") { image = null }
        assertFalse(requests.publish("artist", beforeRefresh) { image = "late old image" })
        assertEquals(null, image)
        assertTrue(requests.publish("artist", requests.generation("artist")) { image = "new image" })
        assertEquals("new image", image)
    }

    @Test
    fun refreshingOneArtistDoesNotDiscardAnotherArtistsRequest() {
        val requests = ArtistArtworkRequests()
        val otherGeneration = requests.generation("other")
        requests.invalidate("artist") { }
        var published = false
        assertTrue(requests.publish("other", otherGeneration) { published = true })
        assertTrue(published)
    }

    @Test
    fun repeatedRefreshesRejectAllEarlierGenerations() {
        val requests = ArtistArtworkRequests()
        val first = requests.generation("artist")
        requests.invalidate("artist") { }
        val second = requests.generation("artist")
        requests.invalidate("artist") { }
        assertFalse(requests.publish("artist", first) { error("stale update") })
        assertFalse(requests.publish("artist", second) { error("stale update") })
    }
}
