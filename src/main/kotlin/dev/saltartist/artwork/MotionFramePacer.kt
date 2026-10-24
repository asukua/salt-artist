package dev.saltartist.artwork

/** Keep one timeline instead of accumulating conversion time and rounded sleeps. */
internal class MotionFramePacer(fps: Int) {
    private val period = 1_000_000_000L / (if (fps == 24) 24 else 30)
    private var deadline: Long? = null

    fun afterFrame(started: Long, finished: Long): Long {
        var target = deadline?.plus(period) ?: (started + period)
        // Skip missed slots after a long stall; do not burst through queued frames.
        if (finished - target >= period) target += ((finished - target) / period) * period
        deadline = target
        return ((target - finished + 999_999L) / 1_000_000L).coerceAtLeast(1L)
    }
}
