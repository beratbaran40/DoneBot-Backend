package com.todoapp.backend.admin

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime

/**
 * The sort orders the user list may be asked for.
 *
 * A closed enum, not a free-text parameter. Spring Data's `Pageable` would happily accept `sort=` from
 * the query string and push arbitrary property names into the generated SQL; an operator-only surface
 * over a user table is the last place to leave that open. Each entry maps to a fixed ORDER BY fragment
 * that never contains anything the caller typed.
 */
enum class AdminUserSort(internal val orderBy: String) {
    CREATED_DESC("u.created_at DESC"),
    CREATED_ASC("u.created_at ASC"),
    LAST_ACTIVE_DESC("u.last_active_at DESC NULLS LAST"),
    EMAIL_ASC("u.email ASC"),
}

data class AdminUserFilter(
    val query: String? = null,
    val status: String? = null,
    val role: String? = null,
    val provider: String? = null,
    val activeSince: OffsetDateTime? = null,
)

@Repository
class AdminUserQueryRepository(private val jdbc: JdbcTemplate) {

    fun search(
        filter: AdminUserFilter,
        sort: AdminUserSort,
        page: Int,
        size: Int,
    ): List<AdminUserListItem> {
        val (where, args) = buildWhere(filter)
        val sql = """
            SELECT u.id, u.email, u.display_name, u.role, u.status, u.providers_csv, u.email_verified,
                   u.created_at, u.last_active_at,
                   (SELECT COUNT(*) FROM tasks t WHERE t.owner_id = u.id) AS task_count,
                   (SELECT COUNT(*) FROM group_members gm WHERE gm.user_id = u.id) AS group_count
            FROM users u
            $where
            ORDER BY ${sort.orderBy}
            LIMIT ? OFFSET ?
        """.trimIndent()
        return jdbc.query(sql, { rs, _ ->
            AdminUserListItem(
                id = rs.getLong("id"),
                email = rs.getString("email"),
                displayName = rs.getString("display_name"),
                role = rs.getString("role"),
                status = rs.getString("status"),
                providers = rs.getString("providers_csv").split(",").filter { it.isNotBlank() },
                emailVerified = rs.getBoolean("email_verified"),
                createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toString(),
                lastActiveAt = rs.getObject("last_active_at", OffsetDateTime::class.java)?.toString(),
                taskCount = rs.getLong("task_count"),
                groupCount = rs.getLong("group_count"),
            )
        }, *(args + listOf(size, page * size)).toTypedArray())
    }

    fun count(filter: AdminUserFilter): Long {
        val (where, args) = buildWhere(filter)
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM users u $where",
            Long::class.java,
            *args.toTypedArray(),
        ) ?: 0L
    }

    /**
     * Filters are assembled from a fixed set of fragments with bound parameters — never by splicing the
     * caller's text into SQL. `q` is matched case-insensitively against email and display name; the
     * wildcards go in the *bound value*, so a `%` typed by the operator is a literal search, not a
     * pattern they accidentally injected.
     */
    private fun buildWhere(filter: AdminUserFilter): Pair<String, List<Any>> {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<Any>()

        filter.query?.takeIf { it.isNotBlank() }?.let {
            clauses += "(LOWER(u.email) LIKE ? OR LOWER(u.display_name) LIKE ?)"
            val needle = "%${it.trim().lowercase()}%"
            args += needle
            args += needle
        }
        filter.status?.let { clauses += "u.status = ?"; args += it }
        filter.role?.let { clauses += "u.role = ?"; args += it }
        // providers_csv holds values like "email,google"; a LIKE keeps this readable without a join.
        filter.provider?.let { clauses += "u.providers_csv LIKE ?"; args += "%$it%" }
        filter.activeSince?.let { clauses += "u.last_active_at >= ?"; args += it }

        val where = if (clauses.isEmpty()) "" else "WHERE " + clauses.joinToString(" AND ")
        return where to args
    }

    // ---- detail ---------------------------------------------------------------------------------

    fun counts(userId: Long): AdminUserCounts = jdbc.query(
        """
        SELECT
            (SELECT COUNT(*) FROM tasks WHERE owner_id = ?) AS tasks_total,
            (SELECT COUNT(*) FROM tasks WHERE owner_id = ? AND family_group_id IS NULL) AS tasks_personal,
            (SELECT COUNT(*) FROM tasks WHERE owner_id = ? AND family_group_id IS NOT NULL) AS tasks_group,
            (SELECT COUNT(*) FROM tasks WHERE owner_id = ? AND is_completed = TRUE) AS tasks_completed,
            (SELECT COUNT(*) FROM tasks WHERE owner_id = ? AND is_secret = TRUE) AS tasks_secret,
            (SELECT COUNT(*) FROM task_daily_completions WHERE user_id = ?) AS routine_completions,
            (SELECT COUNT(*) FROM task_photos p JOIN tasks t ON t.id = p.task_id WHERE t.owner_id = ?) AS photos,
            (SELECT COUNT(*) FROM notifications WHERE user_id = ? AND is_read = FALSE) AS unread
        FROM (SELECT 1) probe
        """.trimIndent(),
        { rs, _ ->
            AdminUserCounts(
                tasksTotal = rs.getLong("tasks_total"),
                tasksPersonal = rs.getLong("tasks_personal"),
                tasksGroup = rs.getLong("tasks_group"),
                tasksCompleted = rs.getLong("tasks_completed"),
                // A count only. Task titles never leave this service, and `is_secret` is the user
                // telling us in so many words that they expect privacy there.
                tasksSecret = rs.getLong("tasks_secret"),
                routineCompletions = rs.getLong("routine_completions"),
                photos = rs.getLong("photos"),
                notificationsUnread = rs.getLong("unread"),
            )
        },
        userId, userId, userId, userId, userId, userId, userId, userId,
    ).first()

    fun groups(userId: Long): List<AdminUserGroup> = jdbc.query(
        """
        SELECT g.id, g.name, gm.role, g.owner_id, gm.joined_at,
               (SELECT COUNT(*) FROM group_members m WHERE m.group_id = g.id) AS member_count
        FROM group_members gm JOIN family_groups g ON g.id = gm.group_id
        WHERE gm.user_id = ?
        ORDER BY gm.joined_at ASC
        """.trimIndent(),
        { rs, _ ->
            AdminUserGroup(
                groupId = rs.getLong("id"),
                name = rs.getString("name"),
                role = rs.getString("role"),
                isOwner = rs.getLong("owner_id") == userId,
                memberCount = rs.getLong("member_count"),
                joinedAt = rs.getObject("joined_at", OffsetDateTime::class.java)?.toString(),
            )
        },
        userId,
    )

    fun devices(userId: Long): List<AdminUserDevice> = jdbc.query(
        "SELECT id, device_name, device_id, token, created_at, updated_at FROM device_tokens WHERE user_id = ?",
        { rs, _ ->
            val token = rs.getString("token")
            AdminUserDevice(
                id = rs.getLong("id"),
                deviceName = rs.getString("device_name"),
                deviceId = rs.getString("device_id"),
                // Last six characters only. A full FCM token is a capability to push to that device;
                // the suffix is enough to tell two devices apart in a support conversation.
                tokenSuffix = token?.takeLast(6) ?: "",
                createdAt = rs.getObject("created_at", OffsetDateTime::class.java)?.toString(),
                updatedAt = rs.getObject("updated_at", OffsetDateTime::class.java)?.toString(),
            )
        },
        userId,
    )

    fun activeDays(userId: Long, since: java.time.LocalDate): Set<java.time.LocalDate> = jdbc.query(
        "SELECT activity_date FROM user_activity_daily WHERE user_id = ? AND activity_date >= ?",
        { rs, _ -> rs.getDate(1).toLocalDate() },
        userId,
        since,
    ).toSet()

    fun chatUsage(userId: Long, since: java.time.LocalDate): AdminUserChatUsage = jdbc.query(
        """
        SELECT COALESCE(SUM(requests), 0), COALESCE(SUM(refusals), 0), COALESCE(SUM(errors), 0),
               COALESCE(SUM(prompt_tokens), 0), COALESCE(SUM(response_tokens), 0)
        FROM chat_usage_daily WHERE user_id = ? AND usage_date >= ?
        """.trimIndent(),
        { rs, _ ->
            AdminUserChatUsage(
                requests = rs.getLong(1),
                refusals = rs.getLong(2),
                errors = rs.getLong(3),
                promptTokens = rs.getLong(4),
                responseTokens = rs.getLong(5),
            )
        },
        userId,
        since,
    ).first()

    /**
     * Focus totals for one account. A separate windowed aggregate rather than two more columns on
     * `counts()`, which already carries eight correlated subqueries and repeats `userId` positionally
     * eight times — going to ten would make an already fragile argument list worse.
     *
     * Totals only, and deliberately so: individual session timestamps are a minute-by-minute record of
     * when this person was at their desk, which is materially more revealing than a task count. The run
     * id is a COUNT DISTINCT input and never leaves this method.
     */
    fun pomodoro(userId: Long, since: OffsetDateTime): AdminUserPomodoro = jdbc.query(
        """
        SELECT COALESCE(SUM(CASE WHEN mode = 'FOCUS' THEN elapsed_seconds ELSE 0 END), 0),
               COALESCE(SUM(CASE WHEN mode = 'FOCUS' AND completed = TRUE THEN 1 ELSE 0 END), 0),
               COALESCE(SUM(CASE WHEN mode = 'FOCUS' THEN 1 ELSE 0 END), 0),
               COUNT(DISTINCT client_run_id)
        FROM pomodoro_sessions WHERE user_id = ? AND ended_at >= ?
        """.trimIndent(),
        { rs, _ ->
            AdminUserPomodoro(
                focusMinutes = rs.getLong(1) / SECONDS_PER_MINUTE,
                sessionsCompleted = rs.getLong(2),
                sessionsStarted = rs.getLong(3),
                runs = rs.getLong(4),
            )
        },
        userId,
        since,
    ).first()

    fun sessions(userId: Long): AdminUserSessions = jdbc.query(
        "SELECT COUNT(*), MAX(created_at) FROM refresh_tokens WHERE user_id = ? AND revoked = FALSE",
        { rs, _ ->
            AdminUserSessions(
                activeRefreshTokens = rs.getInt(1),
                lastRefreshAt = rs.getObject(2, OffsetDateTime::class.java)?.toString(),
            )
        },
        userId,
    ).first()

    fun reportCounts(userId: Long): Pair<Int, Int> = jdbc.query(
        """
        SELECT
            (SELECT COUNT(*) FROM content_reports WHERE reporter_user_id = ?)
              + (SELECT COUNT(*) FROM chat_reports WHERE user_id = ?) AS filed,
            (SELECT COUNT(*) FROM content_reports WHERE target_user_id = ?) AS against
        FROM (SELECT 1) probe
        """.trimIndent(),
        { rs, _ -> rs.getInt("filed") to rs.getInt("against") },
        userId, userId, userId,
    ).first()

    private companion object {
        const val SECONDS_PER_MINUTE = 60L
    }
}
