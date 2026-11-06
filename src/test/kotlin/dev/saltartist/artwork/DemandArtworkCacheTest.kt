package dev.saltartist.artwork

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class DemandArtworkCacheTest {
    @Test fun loadsMoreThanTheOldRequestAndCacheLimitsWithoutDroppingVisibleImages() {
        val loads = AtomicInteger()
        DemandArtworkCache<Int, Any> { _, _ -> loads.incrementAndGet(); Any() }.use { cache ->
            val requests = (0 until 1500).map(cache::acquire)
            val images = requests.map(::awaitImage)
            assertEquals(1500, loads.get())
            requests.forEachIndexed { index, request -> assertSame(images[index], request.state.value) }
            requests.forEach { it.close() }
            cache.acquire(0).use { assertSame(images.first(), awaitImage(it)) }
            assertEquals(1500, loads.get(), "A still-owned image must not be evicted by an item quota")
        }
    }

    @Test fun boundsFetchAndDecodeWorkAndDropsOffscreenPendingRequests() {
        val entered = CountDownLatch(2)
        val unblock = CountDownLatch(1)
        val running = AtomicInteger()
        val maximum = AtomicInteger()
        val loads = AtomicInteger()
        DemandArtworkCache<Int, Any>(2) { _, _ ->
            loads.incrementAndGet()
            val current = running.incrementAndGet()
            maximum.accumulateAndGet(current, ::maxOf)
            entered.countDown()
            assertTrue(unblock.await(5, TimeUnit.SECONDS))
            running.decrementAndGet()
            Any()
        }.use { cache ->
            val first = listOf(cache.acquire(0), cache.acquire(1))
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val offscreen = (2 until 3000).map(cache::acquire)
                offscreen.forEach { it.onAbandoned() }
                val visible = cache.acquire(3000)
                unblock.countDown()
                awaitImage(visible)
                first.forEach(::awaitImage)
                assertEquals(3, loads.get())
                assertEquals(2, maximum.get())
                visible.close()
            } finally { unblock.countDown(); first.forEach { it.close() } }
        }
    }

    @Test fun duplicatesShareOneLoadAndOneConsumerCannotCancelAnother() {
        val release = CountDownLatch(1)
        val loads = AtomicInteger()
        DemandArtworkCache<String, Any> { _, _ ->
            loads.incrementAndGet(); release.await(5, TimeUnit.SECONDS); Any()
        }.use { cache ->
            val first = cache.acquire("same")
            val second = cache.acquire("same")
            first.close(); first.close()
            release.countDown()
            val image = awaitImage(second)
            cache.acquire("same").use { assertSame(image, awaitImage(it)) }
            assertEquals(1, loads.get())
            second.close()
        }
    }

    @Test fun failureCanBeRetriedAndDoesNotConsumeALifetimeAllowance() {
        val done = CountDownLatch(1)
        val loads = AtomicInteger()
        DemandArtworkCache<Int, Any> { _, _ ->
            if (loads.incrementAndGet() == 1) { done.countDown(); error("missing cover") }
            Any()
        }.use { cache ->
            cache.acquire(1).use { assertTrue(done.await(5, TimeUnit.SECONDS)); assertNull(it.state.value) }
            cache.acquire(1).use { awaitImage(it) }
            assertEquals(2, loads.get())
        }
    }

    @Test fun clearRejectsLateDiskWritesAndNewGenerationCanLoadTheSameKey() {
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val staleDone = CountDownLatch(1)
        val writes = AtomicInteger()
        val loads = AtomicInteger()
        DemandArtworkCache<String, Any> { _, context ->
            if (loads.incrementAndGet() == 1) {
                entered.countDown(); unblock.await(5, TimeUnit.SECONDS)
                assertFalse(context.commit { writes.incrementAndGet() })
                staleDone.countDown()
            } else assertTrue(context.commit { writes.incrementAndGet() })
            Any()
        }.use { cache ->
            val old = cache.acquire("same")
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                cache.clear()
                cache.acquire("same").use { awaitImage(it) }
                unblock.countDown()
                assertTrue(staleDone.await(5, TimeUnit.SECONDS))
                assertNull(old.state.value)
                assertEquals(1, writes.get())
            } finally { unblock.countDown(); old.close() }
        }
    }

    @Test fun closeRejectsNewRequests() {
        val loads = AtomicInteger()
        val cache = DemandArtworkCache<Int, Any> { _, _ -> loads.incrementAndGet(); Any() }
        cache.close()
        cache.acquire(1).use { assertNull(it.state.value) }
        assertEquals(0, loads.get())
    }

    private fun <T : Any> awaitImage(request: DemandArtworkCache.Request<T>): T {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (request.state.value == null && System.nanoTime() < deadline) Thread.sleep(1)
        return assertNotNull(request.state.value, "Cover did not complete")
    }
}
