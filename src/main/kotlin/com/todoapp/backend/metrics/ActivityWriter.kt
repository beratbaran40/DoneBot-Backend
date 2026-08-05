package com.todoapp.backend.metrics

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Persists one day-marker for a user. Split behind an interface purely so [ActivityRecorder]'s
 * de-duplication can be tested without a database.
 */
fun interface ActivityWriter {
    fun write(userId: Long, day: LocalDate)
}

/**
 * Plain JDBC rather than JPA: there is no entity for `user_activity_daily` and there does not need to
 * be. It is a two-column fact table that is only ever inserted into and aggregated over, so mapping it
 * to a JPA entity with a composite key would add ceremony and an extra `ddl-auto=validate` surface for
 * no benefit.
 */
@Component
class JdbcActivityWriter(private val jdbc: JdbcTemplate) : ActivityWriter {

    override fun write(userId: Long, day: LocalDate) {
        try {
            jdbc.update(INSERT_DAY, userId, day)
        } catch (_: DataIntegrityViolationException) {
            // Already recorded. Expected after a restart clears the recorder's in-memory dedupe map, and
            // harmless: the row we wanted is the row that already exists. Deliberately not written as
            // "SELECT then INSERT" — that is racy and costs an extra round-trip on the common path.
            // Vendor-portable by design: Postgres ON CONFLICT and H2 MERGE have incompatible syntax,
            // whereas insert-and-catch behaves identically on both.
        }
        jdbc.update(TOUCH_USER, OffsetDateTime.now(ZoneOffset.UTC), userId)
    }

    private companion object {
        const val INSERT_DAY =
            "INSERT INTO user_activity_daily (user_id, activity_date, source) VALUES (?, ?, 'live')"

        // Granularity is intentionally one write per user per day, matching the recorder's dedupe. The
        // admin user list needs "which day was this account last seen", not a to-the-second timestamp,
        // and a per-request UPDATE on `users` would be a real write amplification.
        const val TOUCH_USER = "UPDATE users SET last_active_at = ? WHERE id = ?"
    }
}
