package com.todoapp.backend.metrics

import com.todoapp.backend.integration.AbstractIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Runs the **shipped** V23 backfill statement — read from the classpath, not a copy — against seeded
 * data, so the guards inside it are exercised rather than merely reviewed.
 *
 * Flyway already applied this migration to an empty schema at context startup, which only proves it
 * parses. These tests prove it does the right thing, and each one corresponds to a way the migration
 * could take production down on deploy.
 *
 * The statement is re-run here because the NOT EXISTS guard makes it idempotent — a property worth
 * having regardless, since it is what lets the migration coexist with the live rows V22 starts writing
 * before this one ever executes.
 */
class ActivityBackfillMigrationTest : AbstractIntegrationTest() {

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    /**
     * The **JVM's** today, not UTC's, and the difference is not cosmetic.
     *
     * V23 buckets with `CAST(created_at AS DATE)`, and that cast resolves in the session's time zone on
     * both H2 and Postgres — measured, not assumed. On a machine at UTC+3 between 00:00 and 03:00 local,
     * a task inserted "now" casts to today while `LocalDate.now(UTC)` is still yesterday, so this suite
     * failed for three hours a night and passed the rest of the time. Verified by re-running it with
     * TZ=UTC, where all five cases pass.
     *
     * Production is unaffected — Render runs UTC, so the two agree there. This aligns the test with what
     * the migration actually does rather than with what the server happens to make true.
     */
    private val today: LocalDate = LocalDate.now()

    @Test
    fun `a task created today backfills that day`() {
        val userId = registerUser().user.id
        insertTask(userId, OffsetDateTime.now(ZoneOffset.UTC))

        runBackfill()

        assertEquals(1, rowsFor(userId, today))
        assertEquals("backfill", sourceFor(userId, today))
    }

    @Test
    fun `orphan owner_id rows are skipped instead of aborting the migration`() {
        // V14: "Deliberately NO FK on tasks.owner_id" — account deletion transfers owned rows rather
        // than cascading, so tasks pointing at deleted users genuinely exist in production. Without the
        // IN (SELECT id FROM users) guard this insert violates user_activity_daily's foreign key and
        // the whole deploy fails.
        insertTask(ownerId = 999_999_999L, createdAt = OffsetDateTime.now(ZoneOffset.UTC))

        runBackfill() // must not throw

        assertEquals(0, rowsFor(999_999_999L, today))
    }

    @Test
    fun `an already recorded live day is left alone rather than colliding`() {
        // V22 ships first and starts recording immediately, so by the time V23 runs, today is already
        // present. Without NOT EXISTS this is a primary key violation on deploy.
        val userId = registerUser().user.id
        jdbc.update(
            "INSERT INTO user_activity_daily (user_id, activity_date, source) VALUES (?, ?, 'live')",
            userId,
            today,
        )
        insertTask(userId, OffsetDateTime.now(ZoneOffset.UTC))

        runBackfill() // must not throw

        assertEquals(1, rowsFor(userId, today))
        // The stronger live evidence must win; the backfill must not downgrade it.
        assertEquals("live", sourceFor(userId, today))
    }

    @Test
    fun `epoch-day routine completions convert to the right calendar date`() {
        val userId = registerUser().user.id
        val taskId = insertTask(userId, OffsetDateTime.now(ZoneOffset.UTC))
        val threeDaysAgo = today.minusDays(3)
        insertDailyCompletion(taskId, userId, epochDay = threeDaysAgo.toEpochDay())

        runBackfill()

        assertEquals(1, rowsFor(userId, threeDaysAgo))
    }

    @Test
    fun `a zero epoch day is discarded instead of landing on 1970`() {
        // task_daily_completions.date is a raw epoch day. A zero or corrupt value would otherwise plot
        // as 1970-01-01 and stretch every chart's x-axis by half a century.
        val userId = registerUser().user.id
        val taskId = insertTask(userId, OffsetDateTime.now(ZoneOffset.UTC))
        insertDailyCompletion(taskId, userId, epochDay = 0L)

        runBackfill()

        assertEquals(0, rowsFor(userId, LocalDate.of(1970, 1, 1)))
    }

    private fun runBackfill() {
        val resource = "db/migration/h2/V23__user_activity_backfill.sql"
        val raw = checkNotNull(javaClass.classLoader.getResourceAsStream(resource)) {
            "Missing $resource — the migration must be executed as shipped, not as a copy"
        }.bufferedReader().readText()
        val statement = raw.lineSequence()
            .filterNot { it.trimStart().startsWith("--") }
            .joinToString("\n")
            .trim()
            .removeSuffix(";")
        jdbc.execute(statement)
    }

    private fun insertTask(ownerId: Long, createdAt: OffsetDateTime): Long {
        jdbc.update(
            "INSERT INTO tasks (owner_id, title, date, time_start, time_end, created_at) " +
                "VALUES (?, 'seed', 0, 0, 0, ?)",
            ownerId,
            createdAt,
        )
        return jdbc.queryForObject(
            "SELECT MAX(id) FROM tasks WHERE owner_id = ?",
            Long::class.java,
            ownerId,
        )!!
    }

    private fun insertDailyCompletion(taskId: Long, userId: Long, epochDay: Long) {
        jdbc.update(
            "INSERT INTO task_daily_completions (task_id, user_id, date, completed_at) VALUES (?, ?, ?, 0)",
            taskId,
            userId,
            epochDay,
        )
    }

    private fun rowsFor(userId: Long, day: LocalDate): Int = jdbc.queryForObject(
        "SELECT COUNT(*) FROM user_activity_daily WHERE user_id = ? AND activity_date = ?",
        Int::class.java,
        userId,
        day,
    ) ?: 0

    private fun sourceFor(userId: Long, day: LocalDate): String? = jdbc.queryForObject(
        "SELECT source FROM user_activity_daily WHERE user_id = ? AND activity_date = ?",
        String::class.java,
        userId,
        day,
    )
}
