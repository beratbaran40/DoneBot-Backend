package com.todoapp.backend.chat

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * §4.10 — the global (all-users) daily gate is the coarse circuit-breaker behind the per-user rate
 * limiter: it must allow up to the limit and then reject, so a runaway load can't spend unbounded.
 */
class ChatUsageTrackerTest {
    @Test
    fun `global daily gate allows up to the limit then rejects`() {
        val tracker = ChatUsageTracker()

        assertThat(tracker.tryAcquireGlobalDaily(2)).isTrue()
        assertThat(tracker.tryAcquireGlobalDaily(2)).isTrue()
        assertThat(tracker.tryAcquireGlobalDaily(2)).isFalse()
    }
}
