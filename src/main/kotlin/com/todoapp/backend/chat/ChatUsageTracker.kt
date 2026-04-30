package com.todoapp.backend.chat

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-user request counter for the chat endpoint. In-memory only — no Redis,
 * no persistence. Instance restarts wipe history; that's fine because monthly
 * cost reporting comes from the structured `ChatCost` log lines emitted by
 * [ChatService] and aggregated downstream (Render dashboard / log search).
 *
 * Logs a `ChatUsage` summary every [LOG_EVERY] requests per user so a
 * `grep ChatUsage` over Render logs gives totals without trawling individual
 * turns.
 */
@Component
class ChatUsageTracker {
    private val log = LoggerFactory.getLogger(ChatUsageTracker::class.java)
    private val counter = ConcurrentHashMap<Long, AtomicLong>()

    fun record(userId: Long) {
        val total = counter.computeIfAbsent(userId) { AtomicLong(0L) }.incrementAndGet()
        if (total % LOG_EVERY == 0L) {
            log.info("ChatUsage user={} totalRequests={}", userId, total)
        }
    }

    companion object {
        private const val LOG_EVERY = 10L
    }
}
