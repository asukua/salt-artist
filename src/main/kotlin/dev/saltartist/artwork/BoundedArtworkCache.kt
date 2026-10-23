package dev.saltartist.artwork

import androidx.compose.runtime.mutableStateMapOf

/** Drop cache ownership on eviction; a currently composed image retains its own reference. */
internal class BoundedArtworkCache<K, V>(private val capacity: Int) {
    init { require(capacity > 0) }
    private val values = mutableStateMapOf<K, V>()
    private val order = LinkedHashMap<K, Unit>(16, 0.75f, true)
    val keys: List<K> @Synchronized get() = values.keys.toList()
    @Synchronized operator fun get(key: K): V? {
        if (values.containsKey(key)) order[key]
        return values[key]
    }
    @Synchronized operator fun set(key: K, value: V) {
        values[key] = value
        order[key] = Unit
        while (order.size > capacity) {
            val oldest = order.keys.first()
            order.remove(oldest)
            values.remove(oldest)
        }
    }
    @Synchronized fun containsKey(key: K): Boolean = values.containsKey(key)
    @Synchronized fun remove(key: K): V? { order.remove(key); return values.remove(key) }
    @Synchronized fun clear() { order.clear(); values.clear() }
}
