package com.todoapp.backend.metrics

import com.todoapp.backend.integration.AbstractIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Proves the accumulating upsert against a real database.
 *
 * The write path is deliberately not `ON CONFLICT` (Postgres) or `MERGE … KEY` (H2) — those syntaxes
 * are not interchangeable and only one of them could be tested here. Update-then-insert behaves the
 * same on both engines, which is what makes this test meaningful for production.
 */
class ChatUsageWriterIntegrationTest : AbstractIntegrationTest() {

    @Autowired
    private lateinit var writer: JdbcChatUsageWriter

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    private val today: LocalDate = LocalDate.now(ZoneOffset.UTC)

    @Test
    fun `the first turn of a day inserts the row`() {
        val userId = registerUser().user.id

        writer.add(userId, today, ChatUsageDelta(promptTokens = 100, responseTokens = 200, serverMs = 1500))

        assertEquals(1L, column(userId, "requests"))
        assertEquals(100L, column(userId, "prompt_tokens"))
        assertEquals(1500L, column(userId, "total_server_ms"))
    }

    @Test
    fun `later turns accumulate onto the same row instead of duplicating it`() {
        val userId = registerUser().user.id

        writer.add(userId, today, ChatUsageDelta(promptTokens = 100, responseTokens = 200, serverMs = 1000))
        writer.add(userId, today, ChatUsageDelta(promptTokens = 50, responseTokens = 25, serverMs = 500))
        writer.add(userId, today, ChatUsageDelta(errors = 1, serverMs = 45_000))

        assertEquals(1, rowCount(userId))
        assertEquals(3L, column(userId, "requests"))
        assertEquals(1L, column(userId, "errors"))
        assertEquals(150L, column(userId, "prompt_tokens"))
        assertEquals(225L, column(userId, "response_tokens"))
        assertEquals(46_500L, column(userId, "total_server_ms"))
    }

    @Test
    fun `refusals and errors are tracked separately`() {
        val userId = registerUser().user.id

        writer.add(userId, today, ChatUsageDelta(refusals = 1))
        writer.add(userId, today, ChatUsageDelta(errors = 1))

        assertEquals(1L, column(userId, "refusals"))
        assertEquals(1L, column(userId, "errors"))
        assertEquals(2L, column(userId, "requests"))
    }

    @Test
    fun `separate days are separate rows`() {
        val userId = registerUser().user.id

        writer.add(userId, today, ChatUsageDelta())
        writer.add(userId, today.minusDays(1), ChatUsageDelta())

        assertEquals(1, rowCount(userId))
        assertEquals(
            2,
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_usage_daily WHERE user_id = ?",
                Int::class.java,
                userId,
            ),
        )
    }

    private fun rowCount(userId: Long): Int = jdbc.queryForObject(
        "SELECT COUNT(*) FROM chat_usage_daily WHERE user_id = ? AND usage_date = ?",
        Int::class.java,
        userId,
        today,
    ) ?: 0

    private fun column(userId: Long, name: String): Long = jdbc.queryForObject(
        "SELECT $name FROM chat_usage_daily WHERE user_id = ? AND usage_date = ?",
        Long::class.java,
        userId,
        today,
    ) ?: 0L
}
