package io.github.configdrift.settings

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * No IDE fixture needed: [ConfigDriftProjectSettings] stores no `Project` reference of its own
 * (only the `getInstance(project)` companion function needs one), so it can be exercised as a
 * plain object.
 */
class ConfigDriftProjectSettingsTest {

    @Test
    fun `manualClassification is an independent snapshot, not a live view`() {
        val settings = ConfigDriftProjectSettings()
        settings.setManualClassification(setOf("dev"), setOf("local"))

        val snapshot = settings.manualClassification()
        settings.setManualClassification(setOf("dev", "prod"), emptySet())

        assertEquals(setOf("dev"), snapshot.manualComplete)
        assertEquals(setOf("local"), snapshot.manualOverlay)
    }

    @Test
    fun `suppressedFindingIds is an independent snapshot, not a live view`() {
        val settings = ConfigDriftProjectSettings()
        settings.mutateSuppressedFindingIds { it += "a" }

        val snapshot = settings.suppressedFindingIds()
        settings.mutateSuppressedFindingIds { it += "b" }

        assertEquals(setOf("a"), snapshot)
    }

    @Test
    fun `getState returns a copy independent of further mutation`() {
        val settings = ConfigDriftProjectSettings()
        settings.mutateSuppressedFindingIds { it += "a" }

        val state = settings.state
        settings.mutateSuppressedFindingIds { it += "b" }

        assertEquals(setOf("a"), state.suppressedFindingIds)
    }

    @Test
    fun `concurrent EDT-style mutation and background-style reads neither throw nor corrupt state`() {
        // This is the exact shape of the reported hazard: one side calling
        // mutateSuppressedFindingIds (what suppress()/unsuppress() do on the EDT) while another
        // reads and iterates suppressedFindingIds() (what an in-flight analysis does on a
        // background Task.Backgroundable thread). Before the fix, both touched the same
        // unsynchronized LinkedHashSet directly, which is exactly the shape of a classic
        // ConcurrentModificationException / HashMap-corruption bug — real under contention, easy
        // to never see in casual single-threaded testing.
        val settings = ConfigDriftProjectSettings()
        val writerCount = 16
        val readerCount = 16
        val iterations = 500
        val failures = ConcurrentLinkedQueue<Throwable>()
        val ready = CountDownLatch(writerCount + readerCount)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(writerCount + readerCount)
        try {
            val writers = (0 until writerCount).map { id ->
                executor.submit {
                    ready.countDown()
                    start.await()
                    repeat(iterations) { i ->
                        try {
                            settings.mutateSuppressedFindingIds { it += "finding-$id-$i" }
                        } catch (t: Throwable) {
                            failures += t
                        }
                    }
                }
            }
            val readers = (0 until readerCount).map {
                executor.submit {
                    ready.countDown()
                    start.await()
                    repeat(iterations) {
                        try {
                            // Mirrors ConfigDriftService.applySuppressions(): read a snapshot, then
                            // iterate it — the iteration is where a live, shared set would throw.
                            val snapshot = settings.suppressedFindingIds()
                            var count = 0
                            for (id in snapshot) {
                                count += id.length
                            }
                        } catch (t: Throwable) {
                            failures += t
                        }
                    }
                }
            }
            ready.await()
            start.countDown()
            (writers + readers).forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdown()
        }

        assertTrue(failures.isEmpty(), "Concurrent access threw: $failures")
        assertEquals(writerCount * iterations, settings.suppressedFindingIds().size)
    }
}
