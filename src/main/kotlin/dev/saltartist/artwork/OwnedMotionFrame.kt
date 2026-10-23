package dev.saltartist.artwork

/** Drawing and retirement share a lock, so a frame cannot be freed during a draw call. */
internal class OwnedMotionFrame<T : AutoCloseable> : AutoCloseable {
    private var current: T? = null
    private var closed = false
    @Synchronized fun replace(frame: T): Boolean {
        if (closed) { frame.close(); return false }
        val previous = current
        current = frame
        previous?.close()
        return true
    }
    @Synchronized fun useFrame(draw: (T) -> Unit) { current?.let(draw) }
    @Synchronized override fun close() {
        closed = true
        current?.close()
        current = null
    }
}
