package com.todoapp.backend.metrics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.Executor

/**
 * Pins how a chat turn is classified. These three rules decide whether the ops screen reads as healthy
 * or broken, and each one is a judgement call rather than an obvious mapping.
 */
class ChatUsageRecorderTest {

    private val recorded = mutableListOf<Triple<Long, LocalDate, ChatUsageDelta>>()
    private val recorder = ChatUsageRecorder(
        { userId, day, delta -> recorded += Triple(userId, day, delta) },
        Executor { it.run() },
    )

    private val delta get() = recorded.single().third

    @Test
    fun `a clean turn counts as a request and nothing else`() {
        recorder.recordTurn(1L, promptTokens = 120, responseTokens = 340, serverMs = 900, refused = false, error = null)

        assertEquals(1, delta.requests)
        assertEquals(0, delta.errors)
        assertEquals(0, delta.refusals)
        assertEquals(120L, delta.promptTokens)
        assertEquals(340L, delta.responseTokens)
    }

    @Test
    fun `a safety refusal is a refusal, not an error`() {
        // The model answered and declined; the turn returns HTTP 200 and nothing is broken. Counting it
        // as an error would make the service look unhealthy every time someone asks something off-limits.
        recorder.recordTurn(1L, 10, 5, 400, refused = true, error = "safety_block")

        assertEquals(1, delta.refusals)
        assertEquals(0, delta.errors)
        assertEquals(1, delta.requests)
    }

    @Test
    fun `a degraded turn that still returns 200 is an error`() {
        // tool_loop_cap returns a polite fallback with HTTP 200, but the turn did not do its job — from
        // the operator's point of view that is a failure worth seeing.
        recorder.recordTurn(1L, 900, 20, 8000, refused = false, error = "tool_loop_cap")

        assertEquals(1, delta.errors)
        assertEquals(0, delta.refusals)
    }

    @Test
    fun `a failed turn still counts as a request so the error rate has a denominator`() {
        recorder.recordTurn(1L, 0, 0, 45_000, refused = false, error = "vertex_outage")

        assertEquals(1, delta.requests)
        assertEquals(1, delta.errors)
    }

    @Test
    fun `a pre-flight rejection counts as a failed request with no tokens`() {
        recorder.recordRejected(7L)

        assertEquals(1, delta.requests)
        assertEquals(1, delta.errors)
        assertEquals(0L, delta.promptTokens)
        assertEquals(0L, delta.responseTokens)
    }

    @Test
    fun `the day is bucketed in UTC to match the global budget gate`() {
        // ChatUsageTracker.tryAcquireGlobalDaily already rolls at UTC midnight. Bucketing storage in
        // Istanbul time would make "N of the 5000 daily budget used" wrong for three hours every day.
        recorder.recordTurn(1L, 1, 1, 1, refused = false, error = null)

        assertEquals(LocalDate.now(ZoneOffset.UTC), recorded.single().second)
    }
}
