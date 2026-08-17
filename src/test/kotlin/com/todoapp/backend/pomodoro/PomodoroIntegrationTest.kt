package com.todoapp.backend.pomodoro

import com.todoapp.backend.integration.AbstractIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * Locks the bug this endpoint exists to make impossible: **a retried batch upload doubling the user's
 * focus time**. Everything else here guards the edges around that one guarantee.
 *
 * The upload path is deliberately forgiving with numbers and strict with meaning, and both halves are
 * asserted — clamping an out-of-range duration is fine, coercing an unrecognised mode into FOCUS is not,
 * because the second silently inflates the only figure this table exists to state honestly.
 */
class PomodoroIntegrationTest : AbstractIntegrationTest() {

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    private val today: Long = LocalDate.now(ZoneOffset.UTC).toEpochDay()

    // ---------------------------------------------------------------- round trip

    @Test
    fun `uploads a session and reads it back on the same local day`() {
        val userId = registerUser().user.id
        val sessionId = uuid()

        upload(userId, body(session(clientSessionId = sessionId, elapsedSeconds = 360, completed = false)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.accepted").value(1))
            .andExpect(jsonPath("$.data.duplicates").value(0))

        mockMvc.perform(
            get("/pomodoro/sessions?from=${today - 1}&to=${today + 1}")
                .header(HttpHeaders.AUTHORIZATION, bearer(userId)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.count").value(1))
            .andExpect(jsonPath("$.data.items[0].clientSessionId").value(sessionId))
            .andExpect(jsonPath("$.data.items[0].elapsedSeconds").value(360))
            .andExpect(jsonPath("$.data.items[0].completed").value(false))
    }

    // ---------------------------------------------------------------- idempotency

    @Test
    fun `re-uploading the same batch reports duplicates and does not double the rows`() {
        val userId = registerUser().user.id
        val payload = body(session(), session(), session())

        upload(userId, payload)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.accepted").value(3))
            .andExpect(jsonPath("$.data.duplicates").value(0))

        // The retry a flaky network produces. 200 with duplicates, NOT 409: a 409 here would poison the
        // client's push loop permanently, because it never stops retrying a batch it believes unsent.
        upload(userId, payload)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.accepted").value(0))
            .andExpect(jsonPath("$.data.duplicates").value(3))

        assertEquals(3, rowCount(userId))
    }

    @Test
    fun `a batch that repeats a client id within itself inserts it once`() {
        val userId = registerUser().user.id
        val shared = uuid()

        upload(userId, body(session(clientSessionId = shared), session(clientSessionId = shared)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.accepted").value(1))

        assertEquals(1, rowCount(userId))
    }

    // ---------------------------------------------------------------- ownership

    @Test
    fun `one user cannot see another user's sessions`() {
        val owner = registerUser().user.id
        val stranger = registerUser().user.id

        upload(owner, body(session())).andExpect(status().isOk)

        mockMvc.perform(
            get("/pomodoro/sessions?from=${today - 1}&to=${today + 1}")
                .header(HttpHeaders.AUTHORIZATION, bearer(stranger)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.count").value(0))
    }

    @Test
    fun `the endpoints reject an unauthenticated caller`() {
        mockMvc.perform(
            post("/pomodoro/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(session())),
        ).andExpect(status().isUnauthorized)

        mockMvc.perform(get("/pomodoro/sessions?from=$today&to=$today"))
            .andExpect(status().isUnauthorized)
    }

    // ---------------------------------------------------------------- refusals

    @Test
    fun `a range wider than a year is refused`() {
        val userId = registerUser().user.id

        mockMvc.perform(
            get("/pomodoro/sessions?from=0&to=99999")
                .header(HttpHeaders.AUTHORIZATION, bearer(userId)),
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `a session ended a year in the future is refused`() {
        val userId = registerUser().user.id
        val farFuture = Instant.now().plusSeconds(365L * 24 * 60 * 60).toEpochMilli()

        upload(userId, body(session(endedAt = farFuture)))
            .andExpect(status().isBadRequest)

        assertEquals(0, rowCount(userId))
    }

    @Test
    fun `an unrecognised mode is refused rather than coerced to FOCUS`() {
        val userId = registerUser().user.id

        upload(userId, body(session(mode = "NONSENSE")))
            .andExpect(status().isBadRequest)

        assertEquals(0, rowCount(userId))
    }

    @Test
    fun `the reserved OVERTIME mode is already accepted`() {
        val userId = registerUser().user.id

        // Accepted today so a client release that starts writing overtime rows needs no backend deploy.
        upload(userId, body(session(mode = "OVERTIME")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.accepted").value(1))
    }

    // ---------------------------------------------------------------- clamping

    @Test
    fun `a completed session is stored as having run its full planned length`() {
        val userId = registerUser().user.id
        val sessionId = uuid()

        // The client claims completion but reports ten seconds of elapsed time. Storing both as given
        // would leave "completed" and "elapsed == planned" disagreeing, and every aggregate would then
        // have to decide which one to believe.
        upload(
            userId,
            body(session(clientSessionId = sessionId, plannedSeconds = 1500, elapsedSeconds = 10, completed = true)),
        ).andExpect(status().isOk)

        val elapsed = jdbc.queryForObject(
            "SELECT elapsed_seconds FROM pomodoro_sessions WHERE client_session_id = ?",
            Int::class.java,
            sessionId,
        )
        assertEquals(1500, elapsed)
    }

    @Test
    fun `an absurd duration is clamped instead of failing the whole batch`() {
        val userId = registerUser().user.id
        val sessionId = uuid()

        // One bad row must not cost the other forty-nine: the batch is the unit of retry, so the server
        // clamps anything it can still interpret.
        upload(
            userId,
            body(
                session(clientSessionId = sessionId, plannedSeconds = 999_999, elapsedSeconds = 999_999, completed = false),
                session(),
            ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.accepted").value(2))

        val planned = jdbc.queryForObject(
            "SELECT planned_seconds FROM pomodoro_sessions WHERE client_session_id = ?",
            Int::class.java,
            sessionId,
        )
        assertEquals(86_400, planned)
    }

    // ---------------------------------------------------------------- helpers

    private fun upload(userId: Long, json: String) = mockMvc.perform(
        post("/pomodoro/sessions")
            .header(HttpHeaders.AUTHORIZATION, bearer(userId))
            .contentType(MediaType.APPLICATION_JSON)
            .content(json),
    )

    private fun rowCount(userId: Long): Int = jdbc.queryForObject(
        "SELECT COUNT(*) FROM pomodoro_sessions WHERE user_id = ?",
        Int::class.java,
        userId,
    ) ?: 0

    private fun uuid(): String = UUID.randomUUID().toString()

    private fun session(
        clientSessionId: String = UUID.randomUUID().toString(),
        clientRunId: String = UUID.randomUUID().toString(),
        sessionIndex: Int = 0,
        mode: String = "FOCUS",
        plannedSeconds: Int = 1500,
        elapsedSeconds: Int = 1500,
        completed: Boolean = true,
        endedAt: Long = Instant.now().toEpochMilli(),
    ): String = """
        {
          "clientSessionId": "$clientSessionId",
          "clientRunId": "$clientRunId",
          "sessionIndex": $sessionIndex,
          "mode": "$mode",
          "plannedSeconds": $plannedSeconds,
          "elapsedSeconds": $elapsedSeconds,
          "completed": $completed,
          "startedAt": ${endedAt - plannedSeconds * 1000L},
          "endedAt": $endedAt,
          "localDate": $today,
          "tzOffsetMinutes": 180
        }
    """.trimIndent()

    private fun body(vararg sessions: String): String = """{"sessions":[${sessions.joinToString(",")}]}"""
}
