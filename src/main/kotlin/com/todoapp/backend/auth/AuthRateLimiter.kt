package com.todoapp.backend.auth

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * In-memory per-IP rate limiter for the unauthenticated /auth endpoints (login, register,
 * forgot/reset-password). Mirrors [com.todoapp.backend.chat.ChatRateLimiter] but keys on the
 * client IP instead of a userId, since these calls happen before authentication. Two fixed
 * windows: a tight per-minute cap to blunt credential-stuffing / email-spam bursts, and a
 * looser per-day ceiling.
 *
 * No Redis / no DB — counters reset on restart, which is fine because this is rate *shaping*,
 * not strict accounting. Kept separate from ChatRateLimiter on purpose: the chat path is live
 * and pre-launch is the wrong time to refactor it just to share ~40 lines.
 */
@Component
class AuthRateLimiter(
    @Value("\${app.auth.rate-limit.per-minute:15}") private val perMinute: Int,
    @Value("\${app.auth.rate-limit.per-day:200}") private val perDay: Int,
) {
    private val log = LoggerFactory.getLogger(AuthRateLimiter::class.java)
    private val buckets = ConcurrentHashMap<String, AtomicReference<Window>>()

    fun acquire(key: String): Result {
        val now = Instant.now().epochSecond
        val ref = buckets.computeIfAbsent(key) {
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

            if (minuteCount >= perMinute) {
                val retry = (SEC_PER_MIN - (now - minuteStart)).toInt().coerceAtLeast(1)
                log.warn("auth rate-limit minute hit ip={} count={}", key, minuteCount)
                return Result.Denied(retry)
            }
            if (dayCount >= perDay) {
                val retry = (SEC_PER_DAY - (now - dayStart)).toInt().coerceAtLeast(SEC_PER_MIN.toInt())
                log.warn("auth rate-limit daily hit ip={} count={}", key, dayCount)
                return Result.Denied(retry)
            }
            val updated = Window(
                minuteStart = minuteStart,
                minuteCount = minuteCount + 1,
                dayStart = dayStart,
                dayCount = dayCount + 1,
            )
            if (ref.compareAndSet(current, updated)) return Result.Allowed
            // CAS lost → re-read latest state and retry.
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
