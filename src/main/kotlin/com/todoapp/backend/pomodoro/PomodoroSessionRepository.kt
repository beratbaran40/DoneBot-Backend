package com.todoapp.backend.pomodoro

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface PomodoroSessionRepository : JpaRepository<PomodoroSessionEntity, Long> {
    /** Dedupe probe for an upload batch: which of these client ids does this user already have? */
    fun findAllByUserIdAndClientSessionIdIn(
        userId: Long,
        ids: Collection<String>,
    ): List<PomodoroSessionEntity>

    /**
     * Sign-in backfill. Ranges over `local_date` rather than `ended_at` on purpose: the client asked in
     * its own calendar and has to get its own calendar back, or a session near midnight moves a day.
     */
    fun findAllByUserIdAndLocalDateBetweenOrderByEndedAtAsc(
        userId: Long,
        from: Long,
        to: Long,
    ): List<PomodoroSessionEntity>

    /** GDPR Article 20 export. */
    fun findAllByUserId(userId: Long): List<PomodoroSessionEntity>

    /** 24-month retention purge — see [PomodoroRetentionJob]. Returns the number of rows removed. */
    fun deleteAllByEndedAtBefore(cutoff: java.time.Instant): Long
}
