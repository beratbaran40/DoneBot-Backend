package com.todoapp.backend.integration

import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Locks the §4.18 metrics + §4.17 correlation-id wiring: the Prometheus scrape is collected but
 * auth-gated (never public), and every response carries a traceable X-Request-Id.
 */
class ObservabilityIntegrationTest : AbstractIntegrationTest() {
    @Test
    fun `prometheus metrics require authentication`() {
        mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `authenticated prometheus scrape exposes jvm metrics`() {
        val user = registerUser()
        mockMvc
            .perform(get("/actuator/prometheus").header("Authorization", bearer(user.user.id)))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("jvm_memory_used_bytes")))
    }

    @Test
    fun `every response carries a correlation id header`() {
        // The filter runs first, so the header is present regardless of the endpoint's status.
        mockMvc.perform(get("/actuator/health")).andExpect(header().exists("X-Request-Id"))
    }
}
