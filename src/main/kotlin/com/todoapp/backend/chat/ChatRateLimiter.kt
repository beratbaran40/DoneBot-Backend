package com.todoapp.backend.chat

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * In-memory per-user rate limiter for [ChatController]. Two fixed windows:
 *  - per-minute: prevents a runaway client from burning Vertex tokens in a tight loop
 *  - per-day: soft daily ceiling so a compromised account can't run up an overnight bill
 *
 * No Redis / no DB — instance restarts wipe counters, which is fine because the
 * intent is rate *shaping* not strict accounting (the structured ChatCost log lines
 * remain authoritative for billing/audit). Memory grows with active-user count but
 * each entry is ~32 bytes; for the app's scale that's acceptable.
 */
@Component
class ChatRateLimiter(private val props: ChatProperties) {
    private val log = LoggerFactory.getLogger(ChatRateLimiter::class.java)
    private val buckets = ConcurrentHashMap<Long, AtomicReference<Window>>()

    fun acquire(userId: Long): Result {
        val now = Instant.now().epochSecond
        val ref = buckets.computeIfAbsent(userId) {
            AtomicReference(Window(minuteStart = now, minuteCount = 0, dayStart = now, dayCount = 0))
        }
        while (true) {
            val current = ref.get()
            val rolloverMinute = (now - current.minuteStart) >= SEC_PER_MIN
            val rolloverDay = (now - current.dayStart) >= SEC_PER_DAY
            val minuteStart = if (rolloverMinute) now else current.minuteStart
            val dayStart = if (rolloverDay) now else current.dayStart
            val minuteCount = if (rolloverMinute) 0 else current.minuteCount
            val dayCount = if (rolloverDay) 0 else current.dayCount

            if (minuteCount >= props.rateLimitPerMinute) {
                val retry = (SEC_PER_MIN - (now - minuteStart)).toInt().coerceAtLeast(1)
                log.warn("rate-limit minute hit user={} count={}", userId, minuteCount)
                return Result.Denied(retry)
            }
            if (dayCount >= props.rateLimitPerDay) {
                val retry = (SEC_PER_DAY - (now - dayStart)).toInt().coerceAtLeast(SEC_PER_MIN.toInt())
                log.warn("rate-limit daily hit user={} count={}", userId, dayCount)
                return Result.Denied(retry)
            }
            val updated = Window(
                minuteStart = minuteStart,
                minuteCount = minuteCount + 1,
                dayStart = dayStart,
                dayCount = dayCount + 1,
            )
            if (ref.compareAndSet(current, updated)) return Result.Allowed
            // CAS lost → re-read latest state and try again.
        }
    }

    private data class Window(
        val minuteStart: Long,
        val minuteCount: Int,
        val dayStart: Long,
        val dayCount: Int,
    )

    sealed interface Result {
        object Allowed : Result
        data class Denied(val retryAfterSeconds: Int) : Result
    }

    companion object {
        private const val SEC_PER_MIN = 60L
        private const val SEC_PER_DAY = 24L * 3600L
    }
}
