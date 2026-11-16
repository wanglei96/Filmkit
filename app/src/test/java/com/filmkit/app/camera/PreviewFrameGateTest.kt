package com.filmkit.app.camera

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PreviewFrameGateTest {
    @Test fun notificationDuringDrawSchedulesTrailingDraw() {
        val gate = PreviewFrameGate()
        assertTrue(gate.offer())
        val first = gate.begin()
        assertFalse(gate.offer())
        assertFalse(gate.offer())
        assertTrue(gate.complete(first))
        assertFalse(gate.complete(gate.begin()))
        assertTrue(gate.offer())
    }

    @Test fun postFailureAndPauseDoNotLatchGate() {
        val gate = PreviewFrameGate()
        assertTrue(gate.offer())
        gate.cancel()
        assertTrue(gate.offer())
        assertFalse(gate.complete(gate.begin()))
    }

    @Test fun concurrentNotificationsAreNeverStranded() {
        repeat(30) {
            val gate = PreviewFrameGate()
            val renderer = Executors.newSingleThreadExecutor()
            var consumed = 0L
            fun schedule() {
                renderer.execute {
                    do {
                        consumed = gate.begin()
                        Thread.yield()
                    } while (gate.complete(consumed))
                }
            }
            repeat(10_000) { if (gate.offer()) schedule() }
            renderer.shutdown()
            assertTrue(renderer.awaitTermination(5, TimeUnit.SECONDS))
            assertEquals(gate.begin(), consumed)
            assertTrue(gate.offer())
        }
    }
}
