package dev.saltartist.artwork

import dev.saltartist.settings.MotionResolution
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class ArtistPrefetchTest {
    private val motionUrl = "https://mvod.itunes.apple.com/artist/master.m3u8"

    @Test
    fun forwardsTheSelectedQualityAndWaitsForTheMovie() = withMovie { movie ->
        val prepared = CompletableFuture<PreparedArtistDownload>()
        val video = CompletableFuture<File?>()
        var requested: Pair<String, MotionResolution>? = null
        val result = finishArtistDownload(prepared, MotionResolution.UHD, { url, quality ->
            requested = url to quality
            video
        }, { true })
        assertNull(requested)
        prepared.complete(PreparedArtistDownload(ArtistDownloadResult.COMPLETE, motionUrl))
        assertEquals(motionUrl to MotionResolution.UHD, requested)
        assertFalse(result.isDone)
        video.complete(movie)
        assertEquals(ArtistDownloadResult.COMPLETE, result.get(3, TimeUnit.SECONDS))
    }

    @Test
    fun aPendingVideoDoesNotHoldTheAppleRequestQueue() = withMovie { movie ->
        ArtistRequestQueue().use { queue ->
            val staticStarted = CountDownLatch(1)
            val releaseStatic = CountDownLatch(1)
            val videoStarted = CountDownLatch(1)
            val video = CompletableFuture<File?>()
            val prepared = queue.submit(background = true) {
                staticStarted.countDown()
                check(releaseStatic.await(3, TimeUnit.SECONDS))
                PreparedArtistDownload(ArtistDownloadResult.COMPLETE, motionUrl)
            }
            assertTrue(staticStarted.await(3, TimeUnit.SECONDS))
            val result = finishArtistDownload(prepared, MotionResolution.QHD, { _, _ ->
                videoStarted.countDown()
                video
            }, { true })
            releaseStatic.countDown()
            assertTrue(videoStarted.await(3, TimeUnit.SECONDS))
            assertEquals("page served", queue.submit { "page served" }.get(3, TimeUnit.SECONDS))
            assertFalse(result.isDone)
            video.complete(movie)
            assertEquals(ArtistDownloadResult.COMPLETE, result.get(3, TimeUnit.SECONDS))
        }
    }

    @Test
    fun noMotionKeepsTheStaticResultWithoutStartingAVideoDownload() {
        for (static in ArtistDownloadResult.entries) {
            val result = finishArtistDownload(
                CompletableFuture.completedFuture(PreparedArtistDownload(static)),
                MotionResolution.FULL_HD, { _, _ -> error("No video should be requested") }, { true },
            )
            assertEquals(static, result.get(3, TimeUnit.SECONDS))
        }
    }

    @Test
    fun unavailableAndExceptionalVideoResultsNeedRetry() {
        val videos = listOf(
            CompletableFuture.completedFuture<File?>(null),
            CompletableFuture.failedFuture<File?>(IllegalStateException("download failed")),
        )
        for (video in videos) {
            val result = finishArtistDownload(completeStatic(), MotionResolution.FULL_HD, { _, _ -> video }, { true })
            assertEquals(ArtistDownloadResult.RETRY, result.get(3, TimeUnit.SECONDS))
        }
    }

    @Test
    fun staticAndSynchronousVideoExceptionsNeedRetry() {
        val failedStatic = finishArtistDownload(
            CompletableFuture.failedFuture(IllegalStateException("static failed")),
            MotionResolution.FULL_HD, { _, _ -> error("No prepared video exists") }, { true },
        )
        assertEquals(ArtistDownloadResult.RETRY, failedStatic.get(3, TimeUnit.SECONDS))
        val failedVideo = finishArtistDownload(completeStatic(), MotionResolution.FULL_HD,
            { _, _ -> error("Could not schedule video") }, { true })
        assertEquals(ArtistDownloadResult.RETRY, failedVideo.get(3, TimeUnit.SECONDS))
    }

    @Test
    fun invalidationBeforeOrDuringVideoWorkNeedsRetry() = withMovie { movie ->
        val current = AtomicBoolean(false)
        val alreadyInvalid = finishArtistDownload(completeStatic(), MotionResolution.FULL_HD,
            { _, _ -> error("Invalid work must not start") }, current::get)
        assertEquals(ArtistDownloadResult.RETRY, alreadyInvalid.get(3, TimeUnit.SECONDS))
        val invalidWithoutMotion = finishArtistDownload(
            CompletableFuture.completedFuture(PreparedArtistDownload(ArtistDownloadResult.COMPLETE)),
            MotionResolution.FULL_HD, { _, _ -> error("No motion") }, current::get,
        )
        assertEquals(ArtistDownloadResult.RETRY, invalidWithoutMotion.get(3, TimeUnit.SECONDS))
        current.set(true)
        val video = CompletableFuture<File?>()
        val result = finishArtistDownload(completeStatic(), MotionResolution.FULL_HD, { _, _ -> video }, current::get)
        current.set(false)
        video.complete(movie)
        assertEquals(ArtistDownloadResult.RETRY, result.get(3, TimeUnit.SECONDS))
        assertTrue(movie.isFile)
    }

    @Test
    fun aSuccessfulVideoCannotUpgradeFailedStaticWork() = withMovie { movie ->
        for (static in listOf(ArtistDownloadResult.RETRY, ArtistDownloadResult.UNAVAILABLE)) {
            val requested = AtomicInteger()
            val result = finishArtistDownload(
                CompletableFuture.completedFuture(PreparedArtistDownload(static, motionUrl)),
                MotionResolution.FULL_HD, { _, _ ->
                    requested.incrementAndGet()
                    CompletableFuture.completedFuture(movie)
                }, { true },
            )
            assertEquals(1, requested.get())
            assertEquals(ArtistDownloadResult.RETRY, result.get(3, TimeUnit.SECONDS))
            assertTrue(movie.isFile)
        }
    }

    @Test
    fun missingEmptyAndDirectoryVideoResultsAreRejected() {
        val root = Files.createTempDirectory("artist-prefetch-invalid").toFile()
        try {
            val empty = root.resolve("empty.mp4").apply { createNewFile() }
            for (file in listOf(root.resolve("missing.mp4"), empty, root)) {
                val result = finishArtistDownload(completeStatic(), MotionResolution.FULL_HD,
                    { _, _ -> CompletableFuture.completedFuture(file) }, { true })
                assertEquals(ArtistDownloadResult.RETRY, result.get(3, TimeUnit.SECONDS))
            }
        } finally { root.deleteRecursively() }
    }

    @Test
    fun cancellationStillFinishesTheCurrentArtistIncludingItsVideo() = withMovie { movie ->
        ArtistBatchDownloader().use { batch ->
            val videoStarted = CountDownLatch(1)
            val video = CompletableFuture<File?>()
            val visited = mutableListOf<String>()
            assertTrue(batch.start(listOf("First", "Second")) { name ->
                synchronized(visited) { visited += name }
                finishArtistDownload(completeStatic(), MotionResolution.FULL_HD, { _, _ ->
                    videoStarted.countDown()
                    video
                }, { true })
            })
            assertTrue(videoStarted.await(3, TimeUnit.SECONDS))
            batch.cancel()
            assertTrue(batch.progress.running)
            assertTrue(batch.progress.cancelling)
            assertEquals(0, batch.progress.completed)
            video.complete(movie)
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (batch.progress.running && System.nanoTime() < until) Thread.sleep(5)
            assertFalse(batch.progress.running)
            assertTrue(batch.progress.cancelled)
            assertEquals(1, batch.progress.completed)
            assertEquals(1, batch.progress.available)
            assertEquals(listOf("First"), synchronized(visited) { visited.toList() })
        }
    }

    private fun completeStatic() = CompletableFuture.completedFuture(
        PreparedArtistDownload(ArtistDownloadResult.COMPLETE, motionUrl),
    )

    private fun withMovie(block: (File) -> Unit) {
        val root = Files.createTempDirectory("artist-prefetch").toFile()
        try { block(root.resolve("movie.mp4").apply { writeBytes(byteArrayOf(1)) }) }
        finally { root.deleteRecursively() }
    }
}
