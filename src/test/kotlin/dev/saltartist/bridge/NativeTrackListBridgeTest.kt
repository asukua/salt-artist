package dev.saltartist.bridge

import dev.saltartist.PluginLog
import dev.saltartist.data.ArtistTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class NativeTrackListBridgeTest {
    private data class Original(val id: String, val title: String)
    private fun track(id: String, title: String = id) = ArtistTrack(
        id, title, "Artist", "Album", "Artist", "path:$id", "", 2024, 1, 180_000L, false,
    )
    private fun resolve(tracks: List<ArtistTrack>, originals: Map<String, Any>) =
        matchingNativeTracks(tracks, originals, Original::class.java) { displayed, raw ->
            raw as Original
            displayed.id == raw.id && displayed.title == raw.title
        }

    @Test
    fun filteredDisplayOrderUsesTheExactLibraryObjects() {
        val first = Original("a", "A")
        val second = Original("b", "B")
        val third = Original("c", "C")
        val originalMap = linkedMapOf<String, Any>("a" to first, "b" to second, "c" to third)
        val result = resolve(listOf(track("c", "C"), track("a", "A")), originalMap)!!
        assertEquals(2, result.size)
        assertSame(third, result[0])
        assertSame(first, result[1])
        assertEquals(listOf("a", "b", "c"), originalMap.keys.toList())
    }

    @Test
    fun incompleteLibrarySnapshotKeepsEverySongInThePluginFallback() {
        assertNull(resolve(listOf(track("a"), track("b")), mapOf("a" to Original("a", "a"))))
    }

    @Test
    fun editedMetadataAndWrongNativeIdentityDoNotSilentlyReplacePluginDisplay() {
        assertNull(resolve(listOf(track("a", "Custom name")), mapOf("a" to Original("a", "Raw name"))))
        assertNull(resolve(listOf(track("a")), mapOf("a" to Original("another id", "a"))))
    }

    @Test
    fun invalidNativeTypeAndDuplicateKeysDoNotReachTheHostList() {
        assertNull(resolve(listOf(track("a")), mapOf("a" to Any())))
        assertNull(resolve(listOf(track("a"), track("a")), mapOf("a" to Original("a", "a"))))
        assertNull(resolve(listOf(track("")), mapOf("" to Original("", ""))))
        assertNull(resolve(emptyList(), emptyMap()))
    }

    @Test
    fun unverifiedHostKeepsFallbackWithoutLoadingObfuscatedHostClasses() {
        val bridge = NativeTrackListBridge(PluginLog(null), verifiedHost = false)
        assertNull(bridge.prepare(listOf(track("a")), mapOf("a" to Original("a", "a"))))
    }
}
