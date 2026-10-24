package dev.saltartist.ui

import dev.saltartist.artwork.BoundedArtworkCache
import kotlin.test.*

class DirectoryPortraitMemoryTest {
    @Test fun visibleAvatarsSurviveCachePressureWithoutFlashingBackToCovers() {
        val cache = BoundedArtworkCache<Int, Any>(512)
        val visible = (0..23).associateWith { DirectoryPortraitMemory<Any>() }
        val avatars = visible.keys.associateWith { Any() }
        avatars.forEach { (id, avatar) ->
            cache[id] = avatar
            assertSame(avatar, visible.getValue(id).resolve(cache[id]) { Any() })
        }
        // An async backlog from a larger library can finish after the viewport changes.
        for (id in 24..599) cache[id] = Any()
        assertNull(cache[0])
        repeat(3) {
            visible.forEach { (id, memory) ->
                assertSame(avatars.getValue(id), memory.resolve(cache[id]) { error("Must not downgrade to a cover") })
            }
        }
        assertEquals(512, cache.keys.size)
    }

    @Test fun coverRemainsVisibleUntilAvatarArrivesAndNewAvatarCanReplaceIt() {
        val memory = DirectoryPortraitMemory<Any>()
        val cover = Any()
        val avatar = Any()
        assertNull(memory.resolve(null) { null })
        assertSame(cover, memory.resolve(null) { cover })
        assertSame(cover, memory.resolve(null) { null })
        assertSame(avatar, memory.resolve(avatar) { error("Unnecessary cover load") })
        assertSame(avatar, memory.resolve(null) { error("Must not flash back") })
        val refreshed = Any()
        assertSame(refreshed, memory.resolve(refreshed) { null })
        // The UI creates a new holder after identity/cover changes or an explicit refresh.
        assertSame(cover, DirectoryPortraitMemory<Any>().resolve(null) { cover })
    }
}
