package dev.saltartist.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackSubmissionTest {
    @Test fun submitsTheSameSequenceAsTheHost() {
        val calls = mutableListOf<String>()
        assertTrue(submitPlayback(
            enqueue = { calls += "queue"; true },
            prepare = { calls += "prepare"; true },
            play = { calls += "play"; true },
        ))
        assertEquals(listOf("queue", "prepare", "play"), calls)
    }

    @Test fun failedQueueOrPrepareNeverIssuesPlay() {
        for (queueOk in listOf(false, true)) {
            val calls = mutableListOf<String>()
            assertFalse(submitPlayback(
                enqueue = { calls += "queue"; queueOk },
                prepare = { calls += "prepare"; false },
                play = { calls += "play"; true },
            ))
            assertEquals(if (queueOk) listOf("queue", "prepare") else listOf("queue"), calls)
        }
    }
}
