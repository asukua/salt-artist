package dev.saltartist.artwork

import kotlin.test.*

class MotionFramePacerTest {
    @Test fun roundingAndCopyTimeDoNotAccumulateIntoSlowerPlayback() {
        val pacing = MotionFramePacer(30)
        var now = 0L
        repeat(300) {
            val finished = now + 5_000_000L
            now = finished + pacing.afterFrame(now, finished) * 1_000_000L
        }
        assertTrue(now in 10_000_000_000L..10_001_000_000L, "Elapsed: $now")
    }

    @Test fun longStallSkipsBacklogWithoutBurstingOrLosingTheTimeline() {
        val pacing = MotionFramePacer(30)
        assertEquals(29L, pacing.afterFrame(0, 5_000_000))
        assertTrue(pacing.afterFrame(34_000_000, 300_000_000) in 1..34)
        assertTrue(pacing.afterFrame(301_000_000, 305_000_000) in 1..34)
    }

    @Test fun twentyFourUsesItsOwnStableTimeline() {
        val pacing = MotionFramePacer(24)
        var now = 0L
        repeat(240) {
            val finished = now + 6_000_000L
            now = finished + pacing.afterFrame(now, finished) * 1_000_000L
        }
        assertTrue(now in 10_000_000_000L..10_001_000_000L, "Elapsed: $now")
    }
}
