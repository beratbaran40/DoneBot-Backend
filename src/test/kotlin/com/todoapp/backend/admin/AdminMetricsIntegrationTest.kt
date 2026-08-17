package com.todoapp.backend.admin

import com.todoapp.backend.auth.RegisterRequest
import com.todoapp.backend.integration.AbstractIntegrationTest
import com.todoapp.backend.user.UserRepository
import com.todoapp.backend.user.UserRole
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Executes every metrics query against a real database.
 *
 * That is the whole point: these are hand-written SQL strings that the Kotlin compiler cannot check,
 * and production runs Postgres while this runs H2 — so the queries are deliberately written without any
 * dialect-specific date arithmetic, and this suite is what proves each one at least parses and returns
 * the shape the service expects. A silent typo here would surface as a 500 on the panel's first load.
 *
 * The overview cache is disabled so each test observes its own seeded data; caching is covered
 * separately by [AdminMetricsCacheTest].
 */
@TestPropertySource(
    properties = [
        "app.admin.allowed-emails=metrics-admin@test.com",
        "app.metrics.overview-cache-seconds=0",
    ],
)
class AdminMetricsIntegrationTest : AbstractIntegrationTest() {

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    private val today: LocalDate = LocalDate.now(ZoneOffset.UTC)

    @Test
    fun `overview returns every block and is labelled UTC`() {
        val admin = admin()

        mockMvc.perform(get("/admin/metrics/overview").header("Authorization", bearer(admin)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.zone").value("UTC"))
            .andExpect(jsonPath("$.data.generatedAt").exists())
            .andExpect(jsonPath("$.data.users.total").exists())
            .andExpect(jsonPath("$.data.engagement.dau").exists())
            .andExpect(jsonPath("$.data.tasks.total").exists())
            .andExpect(jsonPath("$.data.groups.total").exists())
            .andExpect(jsonPath("$.data.chat.requestsToday").exists())
            .andExpect(jsonPath("$.data.pomodoro.uniqueUsers7d").exists())
            .andExpect(jsonPath("$.data.moderation.openChatReports").exists())
    }

    @Test
    fun `pomodoro figures are null until the first session is recorded, not zero`() {
        val admin = admin()

        // pomodoro_sessions ships empty with nothing to backfill from, so a confident 0 would read as
        // "nobody focused today" when the truth is "this could not be measured until today". Anyone
        // tempted to "fix" these to 0 should fail here.
        mockMvc.perform(get("/admin/metrics/overview").header("Authorization", bearer(admin)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.pomodoro.focusMinutesToday").doesNotExist())
            .andExpect(jsonPath("$.data.pomodoro.focusMinutes7d").doesNotExist())
            .andExpect(jsonPath("$.data.pomodoro.sessionsCompleted7d").doesNotExist())
            .andExpect(jsonPath("$.data.pomodoro.uniqueUsers7d").value(0))
    }

    @Test
    fun `focus time sums elapsed seconds while completion counts exclude abandoned sessions`() {
        val admin = admin()
        val now = OffsetDateTime.now(ZoneOffset.UTC)

        // 25 minutes run to completion, plus 6 minutes of a 25-minute session that was abandoned.
        insertPomodoro(admin, elapsedSeconds = 1500, completed = true, endedAt = now)
        insertPomodoro(admin, elapsedSeconds = 360, plannedSeconds = 1500, completed = false, endedAt = now)
        // A break must never reach a focus figure.
        insertPomodoro(admin, mode = "SHORT_BREAK", elapsedSeconds = 300, completed = true, endedAt = now)

        mockMvc.perform(get("/admin/metrics/overview").header("Authorization", bearer(admin)))
            .andExpect(status().isOk)
            // 1500 + 360 = 1860s = 31 min. Summing planned_seconds instead would give 50 and report the
            // time the app offered as the time the user spent.
            .andExpect(jsonPath("$.data.pomodoro.focusMinutes7d").value(31))
            .andExpect(jsonPath("$.data.pomodoro.sessionsCompleted7d").value(1))
            .andExpect(jsonPath("$.data.pomodoro.completionRate7d").value(0.5))
            .andExpect(jsonPath("$.data.pomodoro.uniqueUsers7d").value(1))
            .andExpect(jsonPath("$.data.pomodoro.runs7d").value(3))
    }

    @Test
    fun `a session ending late in the UTC day is counted on that day and not the next`() {
        val admin = admin()
        // 23:30Z is the case that breaks if CAST(ended_at AS DATE) ever resolves in a non-UTC session
        // zone: in UTC+3 it would move to tomorrow and every daily total would shift.
        val lateToday = today.atTime(23, 30).atOffset(ZoneOffset.UTC)
        insertPomodoro(admin, elapsedSeconds = 1500, completed = true, endedAt = lateToday)

        mockMvc.perform(
            get("/admin/metrics/timeseries")
                .param("from", today.minusDays(6).toString())
                .param("to", today.toString())
                .header("Authorization", bearer(admin)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.series.pomodoroFocusMinutes.length()").value(7))
            .andExpect(jsonPath("$.data.series.pomodoroFocusMinutes[6].date").value(today.toString()))
            .andExpect(jsonPath("$.data.series.pomodoroFocusMinutes[6].value").value(25))
    }

    @Test
    fun `the user detail reports focus totals and never individual session times`() {
        val admin = admin()
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        insertPomodoro(admin, elapsedSeconds = 1500, completed = true, endedAt = now)
        insertPomodoro(admin, elapsedSeconds = 600, plannedSeconds = 1500, completed = false, endedAt = now)

        mockMvc.perform(get("/admin/users/$admin").header("Authorization", bearer(admin)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.pomodoro30d.focusMinutes").value(35))
            .andExpect(jsonPath("$.data.pomodoro30d.sessionsCompleted").value(1))
            .andExpect(jsonPath("$.data.pomodoro30d.sessionsStarted").value(2))
            // Totals only. A list of session timestamps would be a minute-by-minute record of when this
            // person was at their desk — the same reason task titles are absent from this payload.
            .andExpect(jsonPath("$.data.pomodoro30d.sessions").doesNotExist())
            .andExpect(jsonPath("$.data.pomodoro30d.endedAt").doesNotExist())
    }

    @Test
    fun `daily active users counts distinct people, not requests`() {
        val admin = admin()
        val other = register("second@test.com")
        // Two rows for the same person on the same day cannot happen (primary key), but two people can.
        markActive(admin, today)
        markActive(other, today)
        markActive(other, today.minusDays(1))

        mockMvc.perform(get("/admin/metrics/overview").header("Authorization", bearer(admin)))
            .andExpect(jsonPath("$.data.engagement.dau").value(2))
            .andExpect(jsonPath("$.data.engagement.wau").value(2))
    }

    @Test
    fun `completed task counts come from the new completion timestamp`() {
        val admin = admin()
        insertTask(admin, completedAt = OffsetDateTime.now(ZoneOffset.UTC))
        insertTask(admin, completedAt = null)

        mockMvc.perform(get("/admin/metrics/overview").header("Authorization", bearer(admin)))
            .andExpect(jsonPath("$.data.tasks.total").value(2))
            .andExpect(jsonPath("$.data.tasks.completedToday").value(1))
    }

    @Test
    fun `chat usage is aggregated from the durable daily table`() {
        val admin = admin()
        jdbc.update(
            "INSERT INTO chat_usage_daily (usage_date, user_id, requests, refusals, errors, " +
                "prompt_tokens, response_tokens, total_server_ms) VALUES (?, ?, 10, 2, 1, 500, 700, 20000)",
            today,
            admin,
        )

        mockMvc.perform(get("/admin/metrics/overview").header("Authorization", bearer(admin)))
            .andExpect(jsonPath("$.data.chat.requestsToday").value(10))
            .andExpect(jsonPath("$.data.chat.promptTokens7d").value(500))
            .andExpect(jsonPath("$.data.chat.errorRate7d").value(0.1))
            .andExpect(jsonPath("$.data.chat.avgServerMs7d").value(2000))
    }

    @Test
    fun `time series is gap-filled so quiet days are zeros and not missing points`() {
        val admin = admin()
        val from = today.minusDays(6)

        mockMvc.perform(
            get("/admin/metrics/timeseries")
                .param("from", from.toString())
                .param("to", today.toString())
                .header("Authorization", bearer(admin)),
        )
            .andExpect(status().isOk)
            // Seven days requested, seven points returned, even though almost nothing happened.
            .andExpect(jsonPath("$.data.series.newUsers.length()").value(7))
            .andExpect(jsonPath("$.data.series.activeUsers.length()").value(7))
            .andExpect(jsonPath("$.data.series.tasksCreated.length()").value(7))
            .andExpect(jsonPath("$.data.series.tasksCompleted.length()").value(7))
            .andExpect(jsonPath("$.data.series.chatRequests.length()").value(7))
            .andExpect(jsonPath("$.data.series.newUsers[0].date").value(from.toString()))
    }

    @Test
    fun `an inverted or oversized window is refused rather than silently clamped`() {
        val admin = admin()

        mockMvc.perform(
            get("/admin/metrics/timeseries")
                .param("from", today.toString())
                .param("to", today.minusDays(5).toString())
                .header("Authorization", bearer(admin)),
        ).andExpect(status().isBadRequest)

        mockMvc.perform(
            get("/admin/metrics/timeseries")
                .param("from", today.minusYears(3).toString())
                .param("to", today.toString())
                .header("Authorization", bearer(admin)),
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `breakdown accepts only the closed set of dimensions`() {
        val admin = admin()
        insertTask(admin, completedAt = null)

        mockMvc.perform(
            get("/admin/metrics/breakdown").param("dimension", "category")
                .header("Authorization", bearer(admin)),
        ).andExpect(status().isOk)

        // A free-text dimension selects a query, so it must never reach the service unchecked.
        mockMvc.perform(
            get("/admin/metrics/breakdown").param("dimension", "owner_id; DROP TABLE users")
                .header("Authorization", bearer(admin)),
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `retention and funnel queries execute`() {
        val admin = admin()
        markActive(admin, today)

        mockMvc.perform(get("/admin/metrics/retention").header("Authorization", bearer(admin)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.dayOffsets").isArray)

        mockMvc.perform(get("/admin/metrics/funnel").header("Authorization", bearer(admin)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.registered").exists())
    }

    @Test
    fun `metrics are not readable by a non-admin`() {
        val ordinary = registerUser().user.id

        mockMvc.perform(get("/admin/metrics/overview").header("Authorization", bearer(ordinary)))
            .andExpect(status().isForbidden)
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private fun admin(): Long {
        val id = register("metrics-admin@test.com")
        val entity = userRepository.findById(id).orElseThrow()
        entity.role = UserRole.ADMIN.name
        userRepository.saveAndFlush(entity)
        return id
    }

    private fun register(email: String): Long = authService.register(
        RegisterRequest(email = email, password = "password123", displayName = "Metrics"),
    ).user.id

    private fun markActive(userId: Long, day: LocalDate) {
        jdbc.update(
            "INSERT INTO user_activity_daily (user_id, activity_date, source) VALUES (?, ?, 'live')",
            userId,
            day,
        )
    }

    private fun insertPomodoro(
        userId: Long,
        mode: String = "FOCUS",
        plannedSeconds: Int = 1500,
        elapsedSeconds: Int = 1500,
        completed: Boolean = true,
        endedAt: OffsetDateTime,
    ) {
        jdbc.update(
            "INSERT INTO pomodoro_sessions (user_id, client_session_id, client_run_id, session_index, mode, " +
                "planned_seconds, elapsed_seconds, completed, started_at, ended_at, local_date, tz_offset_minutes) " +
                "VALUES (?, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?, 0)",
            userId,
            java.util.UUID.randomUUID().toString(),
            java.util.UUID.randomUUID().toString(),
            mode,
            plannedSeconds,
            elapsedSeconds,
            completed,
            endedAt.minusSeconds(plannedSeconds.toLong()),
            endedAt,
            endedAt.toLocalDate().toEpochDay(),
        )
    }

    private fun insertTask(ownerId: Long, completedAt: OffsetDateTime?) {
        jdbc.update(
            "INSERT INTO tasks (owner_id, title, date, time_start, time_end, category, recurrence, completed_at) " +
                "VALUES (?, 'seed', 0, 0, 0, 'PERSONAL', 'NONE', ?)",
            ownerId,
            completedAt,
        )
    }
}

/**
 * The overview cache is the only thing standing between an idle browser tab and a query storm against a
 * serverless database that bills for wake-ups, so its behaviour is worth pinning rather than assuming.
 */
@TestPropertySource(
    properties = [
        "app.admin.allowed-emails=cache-admin@test.com",
        "app.metrics.overview-cache-seconds=300",
    ],
)
class AdminMetricsCacheTest : AbstractIntegrationTest() {

    @Autowired
    private lateinit var userRepository: UserRepository

    @Test
    fun `a second call inside the window is served from cache and says how stale it is`() {
        val adminId = authService.register(
            RegisterRequest(email = "cache-admin@test.com", password = "password123", displayName = "Cache"),
        ).user.id
        val entity = userRepository.findById(adminId).orElseThrow()
        entity.role = UserRole.ADMIN.name
        userRepository.saveAndFlush(entity)

        mockMvc.perform(get("/admin/metrics/overview").header("Authorization", bearer(adminId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.users.total").value(1))

        // A brand new signup must NOT appear until the window elapses — that is what proves the second
        // request did no database work.
        authService.register(
            RegisterRequest(email = "late-arrival@test.com", password = "password123", displayName = "Late"),
        )

        mockMvc.perform(get("/admin/metrics/overview").header("Authorization", bearer(adminId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.users.total").value(1))
            .andExpect(jsonPath("$.data.cacheAgeSeconds").exists())
    }
}
