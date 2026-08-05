package com.todoapp.backend.admin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The ops screen mirrors WARN and ERROR log lines into a browser, and libraries print secrets into
 * those lines. Spring Boot's own auto-configuration logs "Using generated security password: <uuid>"
 * at WARN — it appeared in that table on the very first run, which is what prompted these tests.
 */
class RecentErrorsRedactionTest {

    @Test
    fun `a generated password is not shown`() {
        val out = LogRedaction.sanitize("Using generated security password: e2b9f953-03e9-4976-af7b-d70f8b370b56")

        assertFalse(out.contains("e2b9f953"), "the password must not survive: $out")
        assertTrue(out.contains("[redacted]"))
    }

    @Test
    fun `an authorization header is not shown`() {
        val out = LogRedaction.sanitize("Rejected request with Authorization: Bearer abc.def.ghi")

        assertFalse(out.contains("abc.def.ghi"))
        assertTrue(out.contains("Bearer [redacted]"))
    }

    @Test
    fun `a jwt is caught even without a bearer prefix`() {
        val out = LogRedaction.sanitize("Token parse failed for eyJhbGciOiJIUzUxMiJ9.eyJzdWIiOiIxIn0.sigpart")

        assertFalse(out.contains("eyJhbGciOiJIUzUxMiJ9"))
        assertTrue(out.contains("[token]"))
    }

    @Test
    fun `an api key in json is not shown`() {
        val out = LogRedaction.sanitize("""config loaded {"api_key":"AIzaSyDummyValue12345","x":1}""")

        assertFalse(out.contains("AIzaSyDummyValue12345"), out)
    }

    @Test
    fun `an ordinary message is left readable`() {
        // Over-redacting would make the panel useless. An email is often the point of the message, and
        // this is an operator-only surface.
        val message = "TaskDueSoonJob failed for user 42 (elif@example.com): connection reset"

        assertEquals(message, LogRedaction.sanitize(message))
    }

    @Test
    fun `long messages are truncated`() {
        assertEquals(500, LogRedaction.sanitize("x".repeat(900)).length)
    }

    @Test
    fun `a null message does not blow up the appender`() {
        // AppenderBase swallows exceptions thrown from append(), so a crash here would surface as a
        // permanently empty error list rather than as anything visible.
        assertEquals("", LogRedaction.sanitize(null))
    }
}
