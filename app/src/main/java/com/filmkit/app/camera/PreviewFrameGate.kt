package com.filmkit.app.camera

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** One queued draw, with a trailing draw when a notification races with rendering. */
internal class PreviewFrameGate {
    private val pending = AtomicBoolean(false)
    private val sequence = AtomicLong(0)

    fun offer(): Boolean {
        sequence.incrementAndGet()
        return pending.compareAndSet(false, true)
    }

    fun begin(): Long = sequence.get()

    fun complete(consumed: Long): Boolean {
        pending.set(false)
        return sequence.get() != consumed && pending.compareAndSet(false, true)
    }

    fun cancel() { pending.set(false) }
}
