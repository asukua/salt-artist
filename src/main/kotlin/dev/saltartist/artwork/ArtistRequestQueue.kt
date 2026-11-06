package dev.saltartist.artwork

import java.util.concurrent.CompletableFuture
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Serial network work, with visible artist pages ahead of pending bulk work. */
internal class ArtistRequestQueue : AutoCloseable {
    private val serial = AtomicLong()
    private val executor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, PriorityBlockingQueue(),
        { task -> Thread(task, "salt-artist-apple").apply { isDaemon = true } })

    private class Request<T>(val priority: Int, val order: Long, val result: CompletableFuture<T>, val work: () -> T) :
        Runnable, Comparable<Request<*>> {
        override fun compareTo(other: Request<*>): Int = compareValuesBy(this, other, { it.priority }, { it.order })
        override fun run() {
            if (result.isCancelled) return
            try { result.complete(work()) } catch (error: Throwable) { result.completeExceptionally(error) }
        }
    }

    fun <T> submit(background: Boolean = false, work: () -> T): CompletableFuture<T> {
        val result = CompletableFuture<T>()
        runCatching { executor.execute(Request(if (background) 1 else 0, serial.incrementAndGet(), result, work)) }
            .onFailure(result::completeExceptionally)
        return result
    }

    override fun close() { executor.shutdownNow().filterIsInstance<Request<*>>().forEach { it.result.cancel(false) } }
}
