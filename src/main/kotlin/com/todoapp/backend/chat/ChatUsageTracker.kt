package com.todoapp.backend.chat

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

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

    private val globalDay = AtomicReference(LocalDate.now(ZoneOffset.UTC))
    private val globalDailyCount = AtomicLong(0L)

    /**
     * Global (all-users) daily request gate for cost control (§4.10). Resets at UTC midnight.
     * Increments the counter and returns true while within [limit], false once the ceiling is hit.
     * A tiny undercount is possible exactly at the day boundary — fine for a coarse circuit-breaker.
     */
    fun tryAcquireGlobalDaily(limit: Int): Boolean {
        val today = LocalDate.now(ZoneOffset.UTC)
        if (globalDay.getAndSet(today) != today) {
            globalDailyCount.set(0L)
        }
        val count = globalDailyCount.incrementAndGet()
        if (count % LOG_EVERY == 0L) {
            log.info("ChatUsage GLOBAL dailyRequests={} limit={}", count, limit)
        }
        return count <= limit
    }

    /**
     * Requests counted against today's global budget, for the admin ops screen.
     *
     * Reads the live in-memory counter rather than chat_usage_daily on purpose: this is the number the
     * circuit-breaker actually compares against, so showing anything else would let the panel disagree
     * with the behaviour it is meant to explain. It resets on restart and at UTC midnight, exactly like
     * the gate it mirrors.
     */
    fun globalDailyUsed(): Long =
        if (globalDay.get() == LocalDate.now(ZoneOffset.UTC)) globalDailyCount.get() else 0L

    companion object {
        private const val LOG_EVERY = 10L
    }
}
