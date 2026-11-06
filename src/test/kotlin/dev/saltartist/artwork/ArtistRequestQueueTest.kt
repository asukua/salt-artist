package dev.saltartist.artwork

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ArtistRequestQueueTest {
    @Test
    fun openingAnArtistRunsBeforePendingBulkWorkAndRequestsStaySerial() {
        ArtistRequestQueue().use { queue ->
            val began = CountDownLatch(1)
            val release = CountDownLatch(1)
            val order = mutableListOf<String>()
            val first = queue.submit(background = true) {
                began.countDown()
                check(release.await(3, TimeUnit.SECONDS))
                order += "in-flight"
            }
            assertTrue(began.await(3, TimeUnit.SECONDS))
            val bulk = queue.submit(background = true) { order += "bulk" }
            val page = queue.submit { order += "page" }
            release.countDown()
            first.get(3, TimeUnit.SECONDS)
            page.get(3, TimeUnit.SECONDS)
            bulk.get(3, TimeUnit.SECONDS)
            assertEquals(listOf("in-flight", "page", "bulk"), order)
        }
    }

    @Test
    fun aFailedArtistDoesNotStopTheQueue() {
        ArtistRequestQueue().use { queue ->
            val failed = queue.submit<Unit> { error("failed") }
            val next = queue.submit { 42 }
            assertFails { failed.get(3, TimeUnit.SECONDS) }
            assertEquals(42, next.get(3, TimeUnit.SECONDS))
        }
    }
}
