package com.todoapp.backend.metrics

import com.todoapp.backend.integration.AbstractIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Exercises the real SQL against the real schema — the primary key, the foreign key and the duplicate
 * path that [JdbcActivityWriter] relies on.
 *
 * The writer is invoked directly rather than through a request, because in production it runs on the
 * metrics thread with no ambient transaction while these tests hold one open. That difference matters
 * for the duplicate case: here the retry is swallowed inside the test's transaction, whereas in
 * production each statement runs autocommitted on its own connection, so a rejected insert cannot
 * affect the UPDATE that follows it.
 */
class ActivityWriterIntegrationTest : AbstractIntegrationTest() {

    @Autowired
    private lateinit var writer: JdbcActivityWriter

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    private val today: LocalDate = LocalDate.now(ZoneOffset.UTC)

    @Test
    fun `records the day and is idempotent when the same day is written twice`() {
        val userId = registerUser().user.id

        writer.write(userId, today)
        writer.write(userId, today) // after a restart the dedupe map is empty and this really happens

        assertEquals(1, dayRows(userId))
    }

    @Test
    fun `stamps last_active_at on the user row`() {
        val userId = registerUser().user.id

        writer.write(userId, today)

        val lastActive = jdbc.queryForObject(
            "SELECT last_active_at FROM users WHERE id = ?",
            java.sql.Timestamp::class.java,
            userId,
        )
        assertNotNull(lastActive)
    }

    @Test
    fun `separate users on the same day are separate rows`() {
        val first = registerUser().user.id
        val second = registerUser().user.id

        writer.write(first, today)
        writer.write(second, today)

        assertEquals(1, dayRows(first))
        assertEquals(1, dayRows(second))
    }

    @Test
    fun `panel traffic is not counted as product usage`() {
        // MetricsWebMvcConfig excludes the /admin tree. Without that exclusion, every day the operator
        // opens the dashboard would add itself to that day's DAU.
        val userId = registerUser().user.id

        mockMvc.perform(get("/admin/me").header("Authorization", bearer(userId)))
            .andExpect(status().isForbidden)

        assertEquals(0, dayRows(userId))
    }

    private fun dayRows(userId: Long): Int = jdbc.queryForObject(
        "SELECT COUNT(*) FROM user_activity_daily WHERE user_id = ? AND activity_date = ?",
        Int::class.java,
        userId,
        today,
    ) ?: 0
}
