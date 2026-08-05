package com.todoapp.backend.metrics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.Executor

/**
 * Pure unit test of the de-duplication rule — the part that decides how much database traffic activity
 * tracking costs. Runs the "background" work inline so assertions are deterministic.
 */
class ActivityRecorderTest {

    private val written = mutableListOf<Pair<Long, LocalDate>>()
    private val writer = ActivityWriter { userId, day -> written += userId to day }
    private val inlineExecutor = Executor { it.run() }

    private fun recorder(enabled: Boolean = true) = ActivityRecorder(
        writer,
        inlineExecutor,
        MetricsProperties().apply { activity.enabled = enabled },
    )

    @Test
    fun `many requests from one user in a day produce exactly one write`() {
        val recorder = recorder()

        repeat(100) { recorder.record(userId = 7L) }

        assertEquals(1, written.size)
        assertEquals(7L to LocalDate.now(ZoneOffset.UTC), written.single())
    }

    @Test
    fun `distinct users each get their own row`() {
        val recorder = recorder()

        recorder.record(1L)
        recorder.record(2L)
        recorder.record(1L)
        recorder.record(3L)

        assertEquals(listOf(1L, 2L, 3L), written.map { it.first })
    }

    @Test
    fun `the day is bucketed in UTC, not the server's local zone`() {
        // Istanbul is UTC+3, so a local-zone bucket would disagree with the chat usage counters and
        // with every chart on the panel for three hours of each day.
        recorder().record(42L)

        assertEquals(LocalDate.now(ZoneOffset.UTC), written.single().second)
    }

    @Test
    fun `the kill switch stops writes entirely`() {
        recorder(enabled = false).record(1L)

        assertTrue(written.isEmpty())
    }

    @Test
    fun `a failing writer does not propagate to the caller`() {
        // In production this runs on the metrics thread; an escaping exception there would kill the
        // thread and silently stop all later recording.
        val exploding = ActivityWriter { _, _ -> error("database is down") }
        val recorder = ActivityRecorder(
            exploding,
            inlineExecutor,
            MetricsProperties(),
        )

        recorder.record(1L) // must not throw
    }
}
