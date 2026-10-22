package dev.saltartist.bridge

import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.runtime.Composable
import dev.saltartist.PluginLog
import dev.saltartist.data.normalizeArtistKey
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap

private typealias DirectoryItemContent = @Composable LazyGridItemScope.(Int) -> Unit

/** A stable partition of the source order, with no deduplication or alphabetic sort. */
internal fun <T> stableFavoritesFirst(
    source: List<T>,
    favorites: Set<String>,
    enabled: Boolean,
    nameOf: (T) -> String,
): List<T> {
    if (!enabled || source.size < 2 || favorites.isEmpty()) return source
    val keys = favorites.mapTo(HashSet(), ::normalizeArtistKey)
    val selected = ArrayList<T>()
    val remaining = ArrayList<T>()
    var needsMove = false
    source.forEach { item ->
        if (normalizeArtistKey(nameOf(item)) in keys) {
            if (remaining.isNotEmpty()) needsMove = true
            selected += item
        } else remaining += item
    }
    if (!needsMove) return source
    return ArrayList<T>(source.size).apply { addAll(selected); addAll(remaining) }
}

/**
 * Reorders only the temporary grid builder created by the native ArtistScreen.
 * The original artist lists, StateFlow, native item renderer, click callbacks,
 * search, padding, grid state and navigation are retained.
 *
 * SPW 1.19.1 cannot write MethodHook results back and skips UI replacement at
 * this call's default-mask=1008. The official appendContent wrapper therefore
 * runs after the native plain DSL and exchanges its fully prepared interval list.
 * See docs/artist-design.md for the directory decision and guarded builder contract.
 */
internal class ArtistDirectoryBridge(private val log: PluginLog) {
    private data class Access(
        val intervals: Field,
        val customSpans: Field,
        val headers: Field,
        val constructor: Constructor<*>,
    )

    private class Slot(
        val key: String,
        val originalIndex: Int,
        val localIndex: Int,
        val contentType: Any?,
        val renderer: DirectoryItemContent,
    )

    private val accessCache = ConcurrentHashMap<Class<*>, Access>()

    /** Called synchronously by appendContent; failures leave the original builder intact. */
    fun reorder(scope: LazyGridScope, favorites: Set<String>, enabled: Boolean): Boolean {
        if (!enabled || favorites.isEmpty()) return false
        return runCatching {
            val type = scope.javaClass
            require(type.name == GRID_CONTENT) { "unexpected grid builder ${type.name}" }
            val access = accessCache.getOrPut(type) { inspect(type) }
            requirePlainGrid(scope, access)
            val originalIntervals = checkNotNull(access.intervals.get(scope))
            val slots = readSlots(originalIntervals)
            val ordered = stableFavoritesFirst(slots, favorites, true, Slot::key)
            if (ordered === slots) return false
            require(ordered.size == slots.size && ordered.map { it.originalIndex }.sorted() == slots.indices.toList())

            val builder: LazyGridScope.() -> Unit = {
                items(
                    count = ordered.size,
                    key = { ordered[it].key },
                    contentType = { ordered[it].contentType },
                ) { index ->
                    val slot = ordered[index]
                    slot.renderer(this, slot.localIndex)
                }
            }
            val replacementScope = access.constructor.newInstance(builder)
            requirePlainGrid(replacementScope, access)
            val replacementIntervals = checkNotNull(access.intervals.get(replacementScope))
            require(replacementIntervals.javaClass == originalIntervals.javaClass)
            val replacementSlots = readSlots(replacementIntervals)
            require(replacementSlots.size == slots.size && replacementSlots.map { it.key } == ordered.map { it.key })
            require(replacementSlots.map { it.contentType } == ordered.map { it.contentType })

            // Exactly one reference write, after all reflection, item mapping and
            // validation have succeeded. No clear(), mutation of host lists or rollback.
            access.intervals.set(scope, replacementIntervals)
            log.logOnce("directory-favorites-active", "directory favorites-first applied items=${slots.size}")
            true
        }.getOrElse {
            log.logOnce("directory-favorites-fallback", "directory favorites-first kept native order: ${log.describe(it)}")
            false
        }
    }

    private fun inspect(type: Class<*>): Access {
        fun field(name: String, expectedType: String): Field = type.getDeclaredField(name).also {
            require(it.type.name == expectedType) { "unexpected $name type" }
            it.isAccessible = true
        }
        return Access(
            intervals = field("intervals", INTERVAL_LIST),
            customSpans = field("hasCustomSpans", "boolean"),
            headers = field("_headerIndexes", "androidx.collection.MutableIntList"),
            constructor = type.getConstructor(Function1::class.java),
        )
    }

    private fun requirePlainGrid(scope: Any, access: Access) {
        require(!access.customSpans.getBoolean(scope)) { "custom spans are unsupported" }
        require(access.headers.get(scope) == null) { "sticky headers are unsupported" }
    }

    @Suppress("UNCHECKED_CAST")
    private fun readSlots(intervals: Any): List<Slot> {
        require(intervals.javaClass.name == INTERVAL_LIST)
        val count = intervals.javaClass.getMethod("getSize").invoke(intervals) as Int
        require(count in 0..100_000) { "unexpected directory size" }
        val getInterval = intervals.javaClass.getMethod("get", Int::class.javaPrimitiveType)
        return List(count) { index ->
            val interval = checkNotNull(getInterval.invoke(intervals, index))
            require(interval.javaClass.name == INTERVAL)
            val intervalType = interval.javaClass
            val start = intervalType.getMethod("getStartIndex").invoke(interval) as Int
            val size = intervalType.getMethod("getSize").invoke(interval) as Int
            require(index >= start && index - start < size)
            val localIndex = index - start
            val value = checkNotNull(intervalType.getMethod("getValue").invoke(interval))
            require(value.javaClass.name == GRID_INTERVAL)
            val keyFunction = value.javaClass.getMethod("getKey").invoke(value) as? (Int) -> Any?
                ?: error("directory item has no artist key")
            val key = keyFunction(localIndex) as? String ?: error("directory item key is not an artist name")
            require(key.isNotBlank())
            val contentType = value.javaClass.getMethod("getType").invoke(value) as? (Int) -> Any?
            val item = checkNotNull(value.javaClass.getMethod("getItem").invoke(value))
            require(Function4::class.java.isInstance(item)) { "unexpected directory renderer signature" }
            // ComposableLambdaImpl implements several FunctionN interfaces. A Kotlin
            // function-type cast also checks its reported arity and rejects it even
            // though Function4.invoke is the exact LazyGridInterval JVM contract.
            // Generic erasure leaves only the verified JVM interface checkcast.
            val renderer = erasedCast<DirectoryItemContent>(item)
            Slot(key, index, localIndex, contentType?.invoke(localIndex), renderer)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> erasedCast(value: Any): T = value as T

    private companion object {
        const val GRID_CONTENT = "androidx.compose.foundation.lazy.grid.LazyGridIntervalContent"
        const val INTERVAL_LIST = "androidx.compose.foundation.lazy.layout.MutableIntervalList"
        const val INTERVAL = "androidx.compose.foundation.lazy.layout.IntervalList\$Interval"
        const val GRID_INTERVAL = "androidx.compose.foundation.lazy.grid.LazyGridInterval"
    }
}
