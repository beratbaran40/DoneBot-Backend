package com.todoapp.backend.metrics

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Every aggregate the admin surface reads, as plain portable SQL.
 *
 * Two conventions run through the whole file and both are deliberate:
 *
 * **No date arithmetic in SQL.** Postgres subtracts dates and adds intervals; H2 needs DATEADD and
 * DATEDIFF. Rather than maintain two dialects of every query, all boundaries are computed in Kotlin and
 * bound as parameters. The queries then run identically on the dev/test H2 and on production Postgres —
 * which matters because these queries are otherwise untestable against the real database.
 *
 * **No snapshot table.** These run live on each request behind a short cache. A nightly rollup job would
 * be cheaper per read, but on a single Render instance a deploy landing on the job's window loses that
 * day permanently — and a hole in DAU history cannot be recomputed. At current volume the whole overview
 * is a handful of indexed counts; revisit if MAU passes ~5k or the payload takes over ~300ms warm.
 */
@Repository
class MetricsQueryRepository(private val jdbc: JdbcTemplate) {

    // ---- users ---------------------------------------------------------------------------------

    fun totalUsers(): Long = count("SELECT COUNT(*) FROM users")

    fun usersCreatedSince(from: OffsetDateTime): Long =
        count("SELECT COUNT(*) FROM users WHERE created_at >= ?", from)

    fun usersWithStatus(status: String): Long =
        count("SELECT COUNT(*) FROM users WHERE status = ?", status)

    fun verifiedUsers(): Long = count("SELECT COUNT(*) FROM users WHERE email_verified = TRUE")

    fun usersByProvider(): Map<String, Long> =
        countMap("SELECT providers_csv, COUNT(*) FROM users GROUP BY providers_csv")

    // ---- engagement ----------------------------------------------------------------------------

    fun activeUsersBetween(from: LocalDate, to: LocalDate): Long = count(
        "SELECT COUNT(DISTINCT user_id) FROM user_activity_daily WHERE activity_date >= ? AND activity_date <= ?",
        from,
        to,
    )

    fun neverActiveUsers(): Long = count(
        "SELECT COUNT(*) FROM users u WHERE NOT EXISTS " +
            "(SELECT 1 FROM user_activity_daily a WHERE a.user_id = u.id)",
    )

    /**
     * Each user's signup day paired with the last day they were seen (null if never).
     *
     * The retention comparison is "still active on or after signup + N days", which is per-row date
     * arithmetic — `created_at::date + N` in Postgres, `DATEADD` in H2. Rather than fork the query per
     * vendor, the two dates come back raw and the offset is applied in Kotlin. The result set is one row
     * per user, so this stays cheap well past the scale where the panel would need rethinking anyway.
     */
    fun signupAndLastActive(createdBefore: OffsetDateTime): List<SignupActivity> = jdbc.query(
        "SELECT CAST(u.created_at AS DATE) AS signup_day, MAX(a.activity_date) AS last_active " +
            "FROM users u LEFT JOIN user_activity_daily a ON a.user_id = u.id " +
            "WHERE u.created_at < ? " +
            "GROUP BY u.id, CAST(u.created_at AS DATE)",
        { rs, _ ->
            SignupActivity(
                signupDay = rs.getDate(1).toLocalDate(),
                lastActive = rs.getDate(2)?.toLocalDate(),
            )
        },
        createdBefore,
    )

    /** Raw (signup day, active day) pairs for cohort analysis, aggregated in Kotlin — see [MetricsService]. */
    fun cohortPairs(since: OffsetDateTime): List<Triple<Long, LocalDate, LocalDate>> = jdbc.query(
        "SELECT u.id, CAST(u.created_at AS DATE) AS signup_day, a.activity_date " +
            "FROM users u JOIN user_activity_daily a ON a.user_id = u.id " +
            "WHERE u.created_at >= ?",
        { rs, _ ->
            Triple(
                rs.getLong(1),
                rs.getDate(2).toLocalDate(),
                rs.getDate(3).toLocalDate(),
            )
        },
        since,
    )

    fun cohortSizes(since: OffsetDateTime): Map<LocalDate, Int> = jdbc.query(
        "SELECT CAST(created_at AS DATE) AS signup_day, COUNT(*) FROM users WHERE created_at >= ? " +
            "GROUP BY CAST(created_at AS DATE)",
        { rs, _ -> rs.getDate(1).toLocalDate() to rs.getInt(2) },
        since,
    ).toMap()

    // ---- tasks ---------------------------------------------------------------------------------

    fun totalTasks(): Long = count("SELECT COUNT(*) FROM tasks")

    fun tasksByScope(group: Boolean): Long = count(
        if (group) {
            "SELECT COUNT(*) FROM tasks WHERE family_group_id IS NOT NULL"
        } else {
            "SELECT COUNT(*) FROM tasks WHERE family_group_id IS NULL"
        },
    )

    fun tasksCreatedSince(from: OffsetDateTime): Long =
        count("SELECT COUNT(*) FROM tasks WHERE created_at >= ?", from)

    /** Total rows that have ever carried a completion timestamp — 0 means V27 has no data yet. */
    fun tasksWithCompletionTimestamp(): Long =
        count("SELECT COUNT(*) FROM tasks WHERE completed_at IS NOT NULL")

    fun tasksCompletedSince(from: OffsetDateTime): Long =
        count("SELECT COUNT(*) FROM tasks WHERE completed_at >= ?", from)

    /** Routine ticks live in their own table keyed by epoch DAY, not a timestamp. */
    fun routineCompletionsSince(fromEpochDay: Long): Long = count(
        "SELECT COUNT(*) FROM task_daily_completions WHERE date >= ?",
        fromEpochDay,
    )

    fun recurringTasks(): Long =
        count("SELECT COUNT(*) FROM tasks WHERE recurrence IS NOT NULL AND recurrence <> 'NONE'")

    fun tasksWithPhotos(): Long = count("SELECT COUNT(DISTINCT task_id) FROM task_photos")

    fun tasksByCategory(): Map<String, Long> = countMap(
        "SELECT COALESCE(category, 'UNKNOWN'), COUNT(*) FROM tasks GROUP BY COALESCE(category, 'UNKNOWN')",
    )

    fun tasksByRecurrence(): Map<String, Long> = countMap(
        "SELECT COALESCE(recurrence, 'NONE'), COUNT(*) FROM tasks GROUP BY COALESCE(recurrence, 'NONE')",
    )

    // ---- groups --------------------------------------------------------------------------------

    fun totalGroups(): Long = count("SELECT COUNT(*) FROM family_groups")

    /**
     * `group_activities.timestamp` must be referenced **unquoted and table-qualified**.
     *
     * V1 created the column unquoted, and unquoted identifiers fold to lowercase in Postgres but
     * UPPERCASE in H2 — so a quoted spelling can only ever be right on one of them, and there is no
     * per-vendor variant to reach for here the way there is in a migration. Unquoted works on both:
     * TIMESTAMP is not a reserved word in H2, and in Postgres it is a col_name_keyword, which is exactly
     * the class that may appear as a column reference. The qualifier keeps the parser unambiguous.
     */
    fun groupsActiveSince(from: OffsetDateTime): Long = count(
        "SELECT COUNT(DISTINCT ga.group_id) FROM group_activities ga WHERE ga.timestamp >= ?",
        from,
    )

    fun averageGroupMembers(): Double {
        val groups = totalGroups()
        if (groups == 0L) return 0.0
        return count("SELECT COUNT(*) FROM group_members").toDouble() / groups
    }

    fun pendingInvitations(): Long =
        count("SELECT COUNT(*) FROM group_invitations WHERE status = 'PENDING'")

    fun groupsBySize(): Map<String, Long> = countMap(
        "SELECT CAST(member_count AS VARCHAR), COUNT(*) FROM " +
            "(SELECT group_id, COUNT(*) AS member_count FROM group_members GROUP BY group_id) sizes " +
            "GROUP BY CAST(member_count AS VARCHAR)",
    )

    // ---- chat ----------------------------------------------------------------------------------

    fun chatRequestsBetween(from: LocalDate, to: LocalDate): Long = sum(
        "SELECT SUM(requests) FROM chat_usage_daily WHERE usage_date >= ? AND usage_date <= ?",
        from,
        to,
    )

    fun chatTotalsBetween(from: LocalDate, to: LocalDate): ChatTotals = jdbc.query(
        "SELECT COALESCE(SUM(requests), 0), COALESCE(SUM(refusals), 0), COALESCE(SUM(errors), 0), " +
            "COALESCE(SUM(prompt_tokens), 0), COALESCE(SUM(response_tokens), 0), " +
            "COALESCE(SUM(total_server_ms), 0), COUNT(DISTINCT user_id) " +
            "FROM chat_usage_daily WHERE usage_date >= ? AND usage_date <= ?",
        { rs, _ ->
            ChatTotals(
                requests = rs.getLong(1),
                refusals = rs.getLong(2),
                errors = rs.getLong(3),
                promptTokens = rs.getLong(4),
                responseTokens = rs.getLong(5),
                serverMs = rs.getLong(6),
                uniqueUsers = rs.getLong(7),
            )
        },
        from,
        to,
    ).firstOrNull() ?: ChatTotals()

    // ---- moderation ----------------------------------------------------------------------------

    fun openReports(table: String): Long = count("SELECT COUNT(*) FROM $table WHERE status = 'OPEN'")

    fun oldestOpenReport(): OffsetDateTime? = jdbc.query(
        "SELECT MIN(created_at) FROM (" +
            "SELECT created_at FROM chat_reports WHERE status = 'OPEN' " +
            "UNION ALL " +
            "SELECT created_at FROM content_reports WHERE status = 'OPEN'" +
            ") open_reports",
        { rs, _ -> rs.getObject(1, OffsetDateTime::class.java) },
    ).firstOrNull()

    // ---- daily series ---------------------------------------------------------------------------

    fun dailyNewUsers(from: LocalDate, to: LocalDate): Map<LocalDate, Long> = dateCountMap(
        "SELECT CAST(created_at AS DATE) AS d, COUNT(*) FROM users " +
            "WHERE created_at >= ? AND created_at < ? GROUP BY CAST(created_at AS DATE)",
        from,
        to,
    )

    fun dailyActiveUsers(from: LocalDate, to: LocalDate): Map<LocalDate, Long> = jdbc.query(
        "SELECT activity_date, COUNT(DISTINCT user_id) FROM user_activity_daily " +
            "WHERE activity_date >= ? AND activity_date <= ? GROUP BY activity_date",
        { rs, _ -> rs.getDate(1).toLocalDate() to rs.getLong(2) },
        from,
        to,
    ).toMap()

    fun dailyTasksCreated(from: LocalDate, to: LocalDate): Map<LocalDate, Long> = dateCountMap(
        "SELECT CAST(created_at AS DATE) AS d, COUNT(*) FROM tasks " +
            "WHERE created_at >= ? AND created_at < ? GROUP BY CAST(created_at AS DATE)",
        from,
        to,
    )

    fun dailyTasksCompleted(from: LocalDate, to: LocalDate): Map<LocalDate, Long> = dateCountMap(
        "SELECT CAST(completed_at AS DATE) AS d, COUNT(*) FROM tasks " +
            "WHERE completed_at >= ? AND completed_at < ? GROUP BY CAST(completed_at AS DATE)",
        from,
        to,
    )

    fun dailyChatRequests(from: LocalDate, to: LocalDate): Map<LocalDate, Long> = jdbc.query(
        "SELECT usage_date, COALESCE(SUM(requests), 0) FROM chat_usage_daily " +
            "WHERE usage_date >= ? AND usage_date <= ? GROUP BY usage_date",
        { rs, _ -> rs.getDate(1).toLocalDate() to rs.getLong(2) },
        from,
        to,
    ).toMap()

    // ---- funnel ---------------------------------------------------------------------------------

    fun registeredSince(from: OffsetDateTime): Long = usersCreatedSince(from)

    fun usersWhoCreatedATask(since: OffsetDateTime): Long = count(
        "SELECT COUNT(DISTINCT t.owner_id) FROM tasks t " +
            "WHERE t.owner_id IN (SELECT id FROM users WHERE created_at >= ?)",
        since,
    )

    /**
     * Counts both kinds of completion. A one-off task records `completed_at`; a routine records a row in
     * task_daily_completions instead, so looking at only one of them under-counts the users who have
     * actually finished something.
     */
    fun usersWhoCompletedATask(since: OffsetDateTime): Long = count(
        "SELECT COUNT(DISTINCT owner_id) FROM (" +
            "SELECT t.owner_id FROM tasks t WHERE t.completed_at IS NOT NULL " +
            "AND t.owner_id IN (SELECT id FROM users WHERE created_at >= ?) " +
            "UNION " +
            "SELECT c.user_id FROM task_daily_completions c " +
            "WHERE c.user_id IN (SELECT id FROM users WHERE created_at >= ?)" +
            ") finishers",
        since,
        since,
    )

    // ---- plumbing --------------------------------------------------------------------------------

    private fun count(sql: String, vararg args: Any?): Long =
        jdbc.queryForObject(sql, Long::class.java, *args) ?: 0L

    private fun sum(sql: String, vararg args: Any?): Long =
        jdbc.queryForObject(sql, Long::class.java, *args) ?: 0L

    private fun countMap(sql: String, vararg args: Any?): Map<String, Long> =
        jdbc.query(sql, { rs, _ -> (rs.getString(1) ?: "UNKNOWN") to rs.getLong(2) }, *args).toMap()

    private fun dateCountMap(sql: String, from: LocalDate, toExclusive: LocalDate): Map<LocalDate, Long> =
        jdbc.query(
            sql,
            { rs, _ -> rs.getDate(1).toLocalDate() to rs.getLong(2) },
            from.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime(),
            toExclusive.plusDays(1).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime(),
        ).toMap()
}

/** One user's signup day and the last day they were seen — the raw material for retention. */
data class SignupActivity(val signupDay: LocalDate, val lastActive: LocalDate?)

data class ChatTotals(
    val requests: Long = 0,
    val refusals: Long = 0,
    val errors: Long = 0,
    val promptTokens: Long = 0,
    val responseTokens: Long = 0,
    val serverMs: Long = 0,
    val uniqueUsers: Long = 0,
)
