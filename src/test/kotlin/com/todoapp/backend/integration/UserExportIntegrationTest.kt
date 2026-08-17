package com.todoapp.backend.integration

import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Locks the shape of the GDPR "download my data" export (§6.4) so a refactor can't silently drop a
 * required bucket, and confirms it is auth-gated.
 */
class UserExportIntegrationTest : AbstractIntegrationTest() {
    @Test
    fun `export returns the caller's profile and the data buckets`() {
        val user = registerUser("Export User")
        mockMvc
            .perform(
                get("/users/me/export").header("Authorization", bearer(user.user.id)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.profile.email").value(user.user.email))
            .andExpect(jsonPath("$.data.profile.displayName").value("Export User"))
            .andExpect(jsonPath("$.data.personalTasks").isArray())
            .andExpect(jsonPath("$.data.groupMemberships").isArray())
            // Article 20 portability covers focus sessions too — they are server-held personal data, so
            // an export that silently omitted them would be incomplete rather than merely sparse.
            .andExpect(jsonPath("$.data.pomodoroSessions").isArray())
            .andExpect(jsonPath("$.data.note").exists())
    }

    @Test
    fun `export without a token is 401`() {
        mockMvc
            .perform(get("/users/me/export"))
            .andExpect(status().isUnauthorized)
    }
}
