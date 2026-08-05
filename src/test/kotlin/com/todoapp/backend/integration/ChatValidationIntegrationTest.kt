package com.todoapp.backend.integration

import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Locks the §4.16 chat input caps: an over-sized history (count or per-turn length) must be rejected
 * at the @Valid layer with 400 BEFORE the request reaches the rate limiter or the Vertex tool-loop,
 * so a malicious client can't pump unbounded content through the model.
 */
class ChatValidationIntegrationTest : AbstractIntegrationTest() {
    @Test
    fun `chat message with more than 10 history turns is 400`() {
        val user = registerUser()
        val turns = (1..11).joinToString(",") { """{"role":"user","content":"hi"}""" }
        mockMvc
            .perform(
                post("/chat/message")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", bearer(user.user.id))
                    .content("""{"prompt":"hello","history":[$turns]}"""),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `chat message with an over-long history turn is 400`() {
        val user = registerUser()
        val bigContent = "x".repeat(4001)
        mockMvc
            .perform(
                post("/chat/message")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", bearer(user.user.id))
                    .content("""{"prompt":"hello","history":[{"role":"user","content":"$bigContent"}]}"""),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `health points outside the twelve-heart range are 400`() {
        val user = registerUser()
        listOf(25, -1).forEach { value ->
            mockMvc
                .perform(
                    post("/chat/message")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", bearer(user.user.id))
                        .content("""{"prompt":"hello","healthHalfHearts":$value}"""),
                ).andExpect(status().isBadRequest)
        }
    }

    @Test
    fun `a request omitting health points is accepted`() {
        // The deploy-order guarantee: the backend ships before the client that sends this field, and
        // every already-installed client keeps working forever. A 400 here would break chat for the
        // entire install base on deploy. Anything but 400 means validation let it through — chat itself
        // needs Vertex credentials the test profile has no reason to carry.
        val user = registerUser()
        mockMvc
            .perform(
                post("/chat/message")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", bearer(user.user.id))
                    .content("""{"prompt":"hello"}"""),
            ).andExpect { result ->
                check(result.response.status != 400) {
                    "a request without healthHalfHearts must not be rejected by validation"
                }
            }
    }
}
