package dev.saltartist.artwork

import androidx.compose.runtime.State
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.lang.ref.WeakReference
import java.lang.ref.ReferenceQueue
import java.util.concurrent.Executors

/** Visible consumers own decoded images. There is no item-count or lifetime request quota. */
internal class DemandArtworkCache<K, V : Any>(
    parallelism: Int = 2,
    private val load: (K, Context) -> V?,
) : AutoCloseable {
    class Context internal constructor(private val guarded: ((() -> Unit) -> Boolean)) {
        fun current(): Boolean = guarded {}
        /** Clear/close and disk publication share the same generation lock. */
        fun commit(action: () -> Unit): Boolean = guarded(action)
    }

    class Request<V> internal constructor(val state: State<V?>, private val release: () -> Unit) : AutoCloseable, RememberObserver {
        override fun close() = release()
        override fun onRemembered() {}
        override fun onForgotten() = close()
        override fun onAbandoned() = close()
    }

    private inner class Entry(val key: K, val epoch: Long) {
        val state = mutableStateOf<V?>(null)
        var readers = 0
        var started = false
    }

    private class Stored<K, V : Any>(val key: K, value: V, queue: ReferenceQueue<V>) : WeakReference<V>(value, queue)

    init { require(parallelism > 0) }
    private val lock = Any()
    private val workers = Executors.newFixedThreadPool(parallelism) { task ->
        Thread(task, "salt-artist-library-cover").apply { isDaemon = true }
    }
    private val limit = parallelism
    private var running = 0
    private var closed = false
    private val active = LinkedHashMap<K, Entry>()
    private val collected = ReferenceQueue<V>()
    private val memory = HashMap<K, Stored<K, V>>()
    var generation by mutableLongStateOf(0L)
        private set

    fun acquire(key: K): Request<V> = synchronized(lock) {
        if (closed) return@synchronized Request(mutableStateOf(null)) {}
        while (true) {
            @Suppress("UNCHECKED_CAST")
            val reference = collected.poll() as Stored<K, V>? ?: break
            memory.remove(reference.key, reference)
        }
        val entry = active.getOrPut(key) {
            Entry(key, generation).also { it.state.value = memory[key]?.get() }
        }
        entry.readers++
        pump()
        var released = false
        Request(entry.state) {
            synchronized(lock) {
                if (!released) {
                    released = true
                    entry.readers--
                    if (entry.readers == 0 && active[key] === entry) active.remove(key)
                }
            }
        }
    }

    private fun pump() {
        while (!closed && running < limit) {
            val entry = active.values.firstOrNull { !it.started && it.state.value == null } ?: break
            entry.started = true
            running++
            workers.execute {
                val context = Context { action -> synchronized(lock) {
                    if (closed || generation != entry.epoch || active[entry.key] !== entry) false
                    else { action(); true }
                } }
                try {
                    val value = if (context.current()) runCatching { load(entry.key, context) }.getOrNull() else null
                    context.commit {
                        if (value != null) memory[entry.key] = Stored(entry.key, value, collected)
                        entry.state.value = value
                    }
                } finally {
                    synchronized(lock) { running--; pump() }
                }
            }
        }
    }

    /** A fresh acquire retries failures; active failed rows do not create a recompose/retry loop. */
    fun clear(action: () -> Unit = {}) = synchronized(lock) {
        generation++
        active.values.forEach { it.state.value = null }
        active.clear()
        memory.clear()
        action()
    }

    override fun close() {
        synchronized(lock) { closed = true; clear() }
        workers.shutdownNow()
    }
}
