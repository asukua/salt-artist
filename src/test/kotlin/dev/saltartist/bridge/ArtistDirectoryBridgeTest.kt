package dev.saltartist.bridge

import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import dev.saltartist.PluginLog
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Uses the real Compose builder without composing or executing any item renderer. */
class ArtistDirectoryBridgeTest {
    private val bridge = ArtistDirectoryBridge(PluginLog(null))
    private val builderType: Class<*>
        get() = Class.forName("androidx.compose.foundation.lazy.grid.LazyGridIntervalContent")

    @Test
    fun swapsOnlyTheTemporaryIntervalsAndPreservesNativeKeyTypeAndLocalIndexMappings() {
        val names = listOf("Z", "B", "Y", "A")
        val scope = newScope {
            items(2, key = { names[it] }, contentType = { "first:$it" }) { error("renderer must stay lazy") }
            items(2, key = { names[it + 2] }, contentType = { "second:$it" }) { error("renderer must stay lazy") }
        }
        val original = intervals(scope)
        assertTrue(bridge.reorder(scope, setOf("A", "B"), true))
        assertNotSame(original, intervals(scope))
        assertEquals(listOf("B" to "first:1", "A" to "second:1", "Z" to "first:0", "Y" to "second:0"), entries(scope))
        assertEquals(listOf("Z", "B", "Y", "A"), names)
        assertEquals(4, intervalSize(original))
    }

    @Test
    fun duplicateArtistKeysKeepEveryOriginalItemAndType() {
        val scope = newScope {
            val names = listOf("B", "A", "A", "B")
            items(names.size, key = { names[it] }, contentType = { it }) { error("renderer must stay lazy") }
        }
        assertTrue(bridge.reorder(scope, setOf("A"), true))
        assertEquals(listOf("A" to 1, "A" to 2, "B" to 0, "B" to 3), entries(scope))
    }

    @Test
    fun disabledAndAlreadyOrderedBuildersKeepTheExactOriginalReference() {
        val disabled = newScope {
            items(2, key = { listOf("B", "A")[it] }) { error("renderer must stay lazy") }
        }
        val untouched = intervals(disabled)
        assertFalse(bridge.reorder(disabled, setOf("A"), false))
        assertSame(untouched, intervals(disabled))
        assertEquals(listOf("B", "A"), entries(disabled).map { it.first })
        val ordered = newScope {
            items(2, key = { listOf("A", "B")[it] }) { error("renderer must stay lazy") }
        }
        val original = intervals(ordered)
        assertFalse(bridge.reorder(ordered, setOf("A"), true))
        assertSame(original, intervals(ordered))
    }

    @Test
    fun rejectsUnsupportedScopeBeforeInvokingIt() {
        var called = false
        val proxy = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(LazyGridScope::class.java)) { _, _, _ ->
            called = true
            error("unsupported scope must not be invoked")
        } as LazyGridScope
        assertFalse(bridge.reorder(proxy, setOf("A"), true))
        assertFalse(called)
    }

    @Test
    fun customSpansAndMissingArtistKeysLeaveTheOriginalBuilderIntact() {
        val custom = newScope {
            items(2, key = { listOf("B", "A")[it] }, span = { GridItemSpan(2) }) { error("renderer must stay lazy") }
        }
        val originalCustom = intervals(custom)
        assertFalse(bridge.reorder(custom, setOf("A"), true))
        assertSame(originalCustom, intervals(custom))

        val noKeys = newScope { items(2) { error("renderer must stay lazy") } }
        val originalNoKeys = intervals(noKeys)
        assertFalse(bridge.reorder(noKeys, setOf("A"), true))
        assertSame(originalNoKeys, intervals(noKeys))
    }

    private fun newScope(builder: LazyGridScope.() -> Unit): LazyGridScope =
        builderType.getConstructor(Function1::class.java).newInstance(builder) as LazyGridScope

    private fun intervals(scope: LazyGridScope): Any =
        builderType.getDeclaredField("intervals").apply { isAccessible = true }.get(scope)

    private fun intervalSize(intervals: Any): Int = intervals.javaClass.getMethod("getSize").invoke(intervals) as Int

    @Suppress("UNCHECKED_CAST")
    private fun entries(scope: LazyGridScope): List<Pair<Any?, Any?>> {
        val intervals = intervals(scope)
        val getInterval = intervals.javaClass.getMethod("get", Int::class.javaPrimitiveType)
        return List(intervalSize(intervals)) { index ->
            val interval = getInterval.invoke(intervals, index)
            val start = interval.javaClass.getMethod("getStartIndex").invoke(interval) as Int
            val value = interval.javaClass.getMethod("getValue").invoke(interval)
            val key = value.javaClass.getMethod("getKey").invoke(value) as? (Int) -> Any?
            val type = value.javaClass.getMethod("getType").invoke(value) as? (Int) -> Any?
            key?.invoke(index - start) to type?.invoke(index - start)
        }
    }
}
