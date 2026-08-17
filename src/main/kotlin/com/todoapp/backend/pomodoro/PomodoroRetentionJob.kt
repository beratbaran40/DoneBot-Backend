package com.todoapp.backend.pomodoro

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Daily purge of focus sessions older than 24 months.
 *
 * The privacy policy promises this number, so it exists from the first release rather than as a later
 * addition: a session row is a fine-grained record of when someone was at their desk, and "we keep it
 * for 24 months" is only true if something actually deletes it.
 *
 * Cheap by construction — the rows are immutable and independent, so the delete is a range scan on
 * `ended_at`, which `idx_pomodoro_sessions_ended_at` already serves for the admin daily series.
 *
 * Runs an hour after [com.todoapp.backend.notif.schedule.NotificationRetentionJob] rather than beside it,
 * so two purges never contend for the same Neon compute wake-up.
 */
@Component
class PomodoroRetentionJob(
    private val sessions: PomodoroSessionRepository,
) {
    private val log = LoggerFactory.getLogger(PomodoroRetentionJob::class.java)

    @Scheduled(cron = "\${app.scheduling.pomodoro-retention-cron:0 0 4 * * *}", zone = "UTC")
    @Transactional
    fun run() {
        val cutoff = Instant.now().minus(RETENTION_DAYS, ChronoUnit.DAYS)
        val deleted = sessions.deleteAllByEndedAtBefore(cutoff)
        if (deleted > 0) log.info("PomodoroRetentionJob: deleted={} cutoff={}", deleted, cutoff)
    }

    private companion object {
        /** 24 months, as stated in the privacy policy. */
        const val RETENTION_DAYS = 730L
    }
}
