package dev.saltartist.data

import dev.saltartist.data.ArtistAddedTimeTest.HostTrack
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame

class HostLibraryQueryTest {
    @Test
    fun adaptsAnImmediateRawLibraryResult() {
        val times = queryHostLibraryAddedTimes(ImmediateLibrary()).get(2, TimeUnit.SECONDS)
        assertEquals(mapOf("immediate" to 123L), times)
    }

    @Test
    fun waitsForTheSuspendedQueryToResumeBeforePublishingTimes() {
        val library = SuspendedLibrary()
        val future = queryHostLibraryAddedTimes(library)
        val continuation = library.request.get(2, TimeUnit.SECONDS)
        assertFalse(future.isDone)

        continuation.resume(listOf(HostTrack("resumed", 456L)))

        assertEquals(mapOf("resumed" to 456L), future.get(2, TimeUnit.SECONDS))
    }

    @Test
    fun suspendedSnapshotRetainsHostObjectIdentityForNativeActions() {
        val library = SuspendedLibrary()
        val future = queryHostLibraryTracks(library)
        val continuation = library.request.get(2, TimeUnit.SECONDS)
        val original = HostTrack("native", 789L)
        assertFalse(future.isDone)

        continuation.resume(listOf(original))

        val snapshot = future.get(2, TimeUnit.SECONDS)
        assertSame(original, snapshot.single())
        assertSame(original, nativeTracksById(snapshot)["native"])
        assertEquals(mapOf("native" to 789L), libraryAddedTimesFromHostSnapshot(snapshot))
    }

    @Test
    fun nativeIdentityIndexIgnoresObjectsWithoutUsableTrackIds() {
        val valid = HostTrack("valid", 123L)
        val snapshot = listOf(Any(), HostTrack("", 456L), valid)
        val indexed = nativeTracksById(snapshot)
        assertEquals(setOf("valid"), indexed.keys)
        assertSame(valid, indexed["valid"])
    }

    @Test
    fun unavailableRawQueryCompletesExceptionallyForTheCallerToDowngrade() {
        val error = assertFailsWith<ExecutionException> {
            queryHostLibraryAddedTimes(Any()).get(2, TimeUnit.SECONDS)
        }
        assertIs<IllegalStateException>(error.cause)
    }

    class ImmediateLibrary {
        companion object {
            @JvmStatic
            @Suppress("UNUSED_PARAMETER")
            fun rawQuery(library: ImmediateLibrary): (Continuation<Any?>) -> Any? =
                { listOf(HostTrack("immediate", 123L)) }
        }
    }

    class SuspendedLibrary {
        val request = CompletableFuture<Continuation<Any?>>()

        companion object {
            @JvmStatic
            fun rawQuery(library: SuspendedLibrary): (Continuation<Any?>) -> Any? = { continuation ->
                library.request.complete(continuation)
                COROUTINE_SUSPENDED
            }
        }
    }
}
