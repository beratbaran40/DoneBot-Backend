package com.todoapp.backend.integration

import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Contract lock for the auth endpoints the mobile client's OkHttp authenticator depends on.
 *
 * The 401-on-bad-credentials case is load-bearing: the client's token-refresh authenticator keys off
 * a 401, so a backend change that returned 403/500 instead would silently break re-authentication.
 */
class AuthIntegrationTest : AbstractIntegrationTest() {
    @Test
    fun `login with valid credentials returns a token pair`() {
        val user = registerUser()
        mockMvc
            .perform(
                post("/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"email":"${user.user.email}","password":"password123"}"""),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.accessToken").exists())
            .andExpect(jsonPath("$.data.refreshToken").exists())
            .andExpect(jsonPath("$.data.user.email").value(user.user.email))
    }

    @Test
    fun `login with wrong password is 401`() {
        val user = registerUser()
        mockMvc
            .perform(
                post("/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"email":"${user.user.email}","password":"wrong-password"}"""),
            ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `refresh with a valid refresh token returns a new access token`() {
        val user = registerUser()
        mockMvc
            .perform(
                post("/auth/refresh")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"refreshToken":"${user.refreshToken}"}"""),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.accessToken").exists())
    }
}
