package io.github.configdrift.engine

import java.util.concurrent.atomic.AtomicLong

/**
 * Guards against a slow, earlier request overwriting the result of a faster, later one.
 *
 * [ConfigDriftService][io.github.configdrift.ConfigDriftService] can have two analyses in flight
 * at once — a manual "Tools | Analyze" and an automatic re-analysis triggered by a save that
 * happened while it was running — with no relationship between how long each takes. Without this,
 * whichever finished *last* would publish, even if it had started *earlier* and so reflected
 * older file content. [issue] is called when a run is requested, [tryPublish] when it completes;
 * because the sequence number is fixed at request time, a result computed from an earlier request
 * can never win over one from a later request, regardless of completion order.
 *
 * Extracted as its own pure class — no `Project`, no PSI — so the property it guarantees can be
 * verified under real thread contention instead of only reasoned about.
 */
class MonotonicSequenceGate {

    private val issued = AtomicLong(0)
    private val published = AtomicLong(0)

    /** Call when a new run is requested; hand the result to [tryPublish] once it completes. */
    fun issue(): Long = issued.incrementAndGet()

    /**
     * Publishes [sequence] as the current one if — and only if — it is strictly newer than
     * whatever is already published. Returns whether it did.
     */
    fun tryPublish(sequence: Long): Boolean {
        while (true) {
            val current = published.get()
            if (sequence <= current) return false
            if (published.compareAndSet(current, sequence)) return true
        }
    }

    /** The sequence number currently considered current, for tests and diagnostics. */
    fun currentSequence(): Long = published.get()
}
