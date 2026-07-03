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
}
