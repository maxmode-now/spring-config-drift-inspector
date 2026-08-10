package io.github.configdrift.engine

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MonotonicSequenceGateTest {

    @Test
    fun `a newer sequence can publish over an older one`() {
        val gate = MonotonicSequenceGate()
        val older = gate.issue()
        val newer = gate.issue()

        assertTrue(gate.tryPublish(newer))
        assertEquals(newer, gate.currentSequence())
    }

    @Test
    fun `an older sequence arriving after a newer one is rejected`() {
        // The exact bug: the earlier request's result shows up after the later one's.
        val gate = MonotonicSequenceGate()
        val older = gate.issue()
        val newer = gate.issue()

        assertTrue(gate.tryPublish(newer))
        assertFalse(gate.tryPublish(older))
        assertEquals(newer, gate.currentSequence())
    }

    @Test
    fun `the same sequence cannot publish twice`() {
        val gate = MonotonicSequenceGate()
        val sequence = gate.issue()

        assertTrue(gate.tryPublish(sequence))
        assertFalse(gate.tryPublish(sequence))
    }

    @Test
    fun `under real thread contention, only the highest issued sequence ends up published`() {
        // Single-threaded tests can't exercise the property this class exists for: whichever
        // thread happens to call tryPublish last must not win just because it finished last. This
        // deliberately randomizes completion order so it isn't just re-testing issue order.
        val gate = MonotonicSequenceGate()
        val threadCount = 64
        val sequences = List(threadCount) { gate.issue() }
        val highest = sequences.max()

        val ready = CountDownLatch(threadCount)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(threadCount)
        try {
            val futures = sequences.shuffled().map { sequence ->
                executor.submit {
                    ready.countDown()
                    start.await()
                    Thread.sleep(Random.nextLong(0, 5))
                    gate.tryPublish(sequence)
                }
            }
            ready.await()
            start.countDown()
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdown()
        }

        assertEquals(highest, gate.currentSequence())
    }
}
