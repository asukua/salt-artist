package dev.saltartist.artwork

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ArtistBatchDownloaderTest {
    @Test
    fun deduplicatesNamesButKeepsPunctuationAndContinuesAfterFailure() {
        ArtistBatchDownloader().use { batch ->
            val visited = mutableListOf<String>()
            assertTrue(batch.start(listOf(" Artist ", "ARTIST", "AC/DC", "AC DC", "")) { name ->
                synchronized(visited) { visited += name }
                if (name == "AC/DC") CompletableFuture.failedFuture(IllegalStateException("unavailable"))
                else CompletableFuture.completedFuture(if (name == "AC DC") ArtistDownloadResult.UNAVAILABLE else ArtistDownloadResult.COMPLETE)
            })
            awaitFinished(batch)
            assertEquals(listOf("Artist", "AC/DC", "AC DC"), visited)
            assertEquals(3, batch.progress.completed)
            assertEquals(1, batch.progress.available)
            assertEquals(1, batch.progress.unavailable)
            assertEquals(1, batch.progress.retry)
        }
    }

    @Test
    fun cancellationFinishesCurrentArtistAndCanResumeWithoutAnOverlappingJob() {
        ArtistBatchDownloader().use { batch ->
            val began = CountDownLatch(1)
            val current = CompletableFuture<ArtistDownloadResult>()
            assertTrue(batch.start(listOf("First", "Second")) { began.countDown(); current })
            assertTrue(began.await(3, TimeUnit.SECONDS))
            assertFalse(batch.start(listOf("Other")) { error("overlapping batch") })
            batch.cancel()
            assertTrue(batch.progress.cancelling)
            current.complete(ArtistDownloadResult.COMPLETE)
            awaitFinished(batch)
            assertTrue(batch.progress.cancelled)
            assertEquals(1, batch.progress.completed)
            assertTrue(batch.start(listOf("First", "Second")) { CompletableFuture.completedFuture(ArtistDownloadResult.COMPLETE) })
            awaitFinished(batch)
            assertEquals(2, batch.progress.available)
            assertFalse(batch.progress.cancelled)
        }
    }

    @Test
    fun closingRejectsNewWorkAndStopsPendingArtists() {
        val batch = ArtistBatchDownloader()
        val began = CountDownLatch(1)
        batch.start(listOf("First", "Second")) { began.countDown(); CompletableFuture() }
        assertTrue(began.await(3, TimeUnit.SECONDS))
        batch.close()
        awaitFinished(batch)
        assertFalse(batch.start(listOf("New")) { error("closed batch") })
        assertEquals(0, batch.progress.completed)
    }

    private fun awaitFinished(batch: ArtistBatchDownloader) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (batch.progress.running && System.nanoTime() < until) Thread.sleep(5)
        assertFalse(batch.progress.running, "Batch did not finish")
    }
}
