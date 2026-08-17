package com.todoapp.backend.pomodoro

import com.todoapp.backend.auth.RegisterRequest
import com.todoapp.backend.integration.AbstractIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The privacy policy states that focus sessions are kept for 24 months. That sentence is only true if
 * something deletes them, and a retention promise that quietly does nothing is worse than no promise —
 * it is a claim in a published legal document that the data does not support.
 */
class PomodoroRetentionJobTest : AbstractIntegrationTest() {

    @Autowired
    private lateinit var job: PomodoroRetentionJob

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var entityManager: jakarta.persistence.EntityManager

    @Test
    fun `deletes sessions past the promised window and keeps everything inside it`() {
        val userId = register()
        val now = Instant.now()

        val old = insert(userId, now.minus(800, ChronoUnit.DAYS))
        // 700 days is inside the 730-day window: the boundary is what a careless off-by-one would move.
        val recent = insert(userId, now.minus(700, ChronoUnit.DAYS))
        val today = insert(userId, now)

        job.run()
        // The derived delete queues removals in the persistence context; in production its transaction
        // commits and they land, but here the assertions read through JdbcTemplate, which bypasses that
        // context entirely. Without this flush the test would report a failure the job does not have.
        entityManager.flush()

        assertEquals(0, countOf(old), "a session older than 24 months must be gone")
        assertEquals(1, countOf(recent), "a session inside the window must survive")
        assertEquals(1, countOf(today), "today's session must survive")
    }

    @Test
    fun `is a no-op when nothing has aged out`() {
        val userId = register()
        insert(userId, Instant.now())

        job.run()
        entityManager.flush()

        assertEquals(
            1,
            jdbc.queryForObject("SELECT COUNT(*) FROM pomodoro_sessions WHERE user_id = ?", Int::class.java, userId),
        )
    }

    private fun register(): Long = authService.register(
        RegisterRequest(email = "ret-${UUID.randomUUID()}@test.com", password = "password123", displayName = "R"),
    ).user.id

    private fun countOf(clientSessionId: String): Int = jdbc.queryForObject(
        "SELECT COUNT(*) FROM pomodoro_sessions WHERE client_session_id = ?",
        Int::class.java,
        clientSessionId,
    ) ?: 0

    private fun insert(userId: Long, endedAt: Instant): String {
        val clientSessionId = UUID.randomUUID().toString()
        val ended = OffsetDateTime.ofInstant(endedAt, ZoneOffset.UTC)
        jdbc.update(
            "INSERT INTO pomodoro_sessions (user_id, client_session_id, client_run_id, session_index, mode, " +
                "planned_seconds, elapsed_seconds, completed, started_at, ended_at, local_date, tz_offset_minutes) " +
                "VALUES (?, ?, ?, 0, 'FOCUS', 1500, 1500, TRUE, ?, ?, ?, 0)",
            userId,
            clientSessionId,
            UUID.randomUUID().toString(),
            ended.minusSeconds(1500),
            ended,
            ended.toLocalDate().toEpochDay(),
        )
        return clientSessionId
    }
}
