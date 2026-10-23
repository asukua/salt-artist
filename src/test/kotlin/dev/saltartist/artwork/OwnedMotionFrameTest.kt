package dev.saltartist.artwork

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class OwnedMotionFrameTest {
    private class Frame : AutoCloseable { var closes = 0; override fun close() { closes++ } }
    @Test fun replacedDisposedAndLateFramesAreRetiredExactlyOnce() {
        val buffer = OwnedMotionFrame<Frame>()
        val first = Frame(); val second = Frame(); val late = Frame()
        assertTrue(buffer.replace(first)); assertTrue(buffer.replace(second))
        assertEquals(1, first.closes)
        buffer.close(); buffer.close()
        assertFalse(buffer.replace(late))
        assertEquals(1, second.closes); assertEquals(1, late.closes)
        buffer.useFrame { error("disposed frame exposed") }
    }
    @Test fun aFrameCannotBeFreedDuringDrawing() {
        val executor = Executors.newFixedThreadPool(2)
        val buffer = OwnedMotionFrame<Frame>()
        val first = Frame(); val entered = CountDownLatch(1); val finish = CountDownLatch(1)
        buffer.replace(first)
        try {
            val drawing = executor.submit { buffer.useFrame {
                entered.countDown(); check(finish.await(5, TimeUnit.SECONDS)); assertEquals(0, it.closes)
            } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val replacing = executor.submit { buffer.replace(Frame()) }
            assertEquals(0, first.closes)
            finish.countDown(); drawing.get(5, TimeUnit.SECONDS); replacing.get(5, TimeUnit.SECONDS)
            assertEquals(1, first.closes)
        } finally { finish.countDown(); executor.shutdownNow(); buffer.close() }
    }
}
