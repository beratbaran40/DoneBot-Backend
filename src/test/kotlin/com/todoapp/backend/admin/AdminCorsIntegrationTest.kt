package com.todoapp.backend.admin

import com.todoapp.backend.integration.AbstractIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * A browser never reaches the panel's data without a passing preflight, so these assertions guard the
 * whole surface. Note every request here carries NO Authorization header — that is what a real
 * preflight looks like, and the reason CORS has to be answered before authorization runs.
 */
@TestPropertySource(properties = ["app.admin.cors.allowed-origins=https://donebot-admin.vercel.app"])
class AdminCorsIntegrationTest : AbstractIntegrationTest() {

    @Test
    fun `preflight from the allowed origin succeeds without a token`() {
        mockMvc.perform(
            options("/admin/me")
                .header("Origin", ALLOWED_ORIGIN)
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "authorization"),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN))
    }

    @Test
    fun `preflight for a destructive method is allowed too`() {
        // The panel deletes accounts; if DELETE were missing from allowedMethods the button would fail
        // only in the browser, never in a curl test.
        mockMvc.perform(
            options("/admin/users/1")
                .header("Origin", ALLOWED_ORIGIN)
                .header("Access-Control-Request-Method", "DELETE")
                .header("Access-Control-Request-Headers", "authorization,content-type"),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN))
    }

    @Test
    fun `preflight from an unlisted origin is rejected`() {
        mockMvc.perform(
            options("/admin/me")
                .header("Origin", "https://evil.example.com")
                .header("Access-Control-Request-Method", "GET"),
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `login is preflightable because its JSON body makes the request non-simple`() {
        mockMvc.perform(
            options("/auth/login")
                .header("Origin", ALLOWED_ORIGIN)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type"),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN))
    }

    @Test
    fun `google sign-in is preflightable so the panel's primary login works`() {
        mockMvc.perform(
            options("/auth/google")
                .header("Origin", ALLOWED_ORIGIN)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type"),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN))
    }

    @Test
    fun `other auth endpoints stay closed to the browser`() {
        // Only the four endpoints the panel actually calls are opened. Password reset and registration
        // are app-only flows and get no CORS configuration, so a page on another origin cannot drive
        // them even though they are permitAll.
        mockMvc.perform(
            options("/auth/register")
                .header("Origin", ALLOWED_ORIGIN)
                .header("Access-Control-Request-Method", "POST"),
        ).andExpect(header().doesNotExist("Access-Control-Allow-Origin"))
    }

    @Test
    fun `credentials are never allowed`() {
        // Bearer headers, not cookies. Keeping this off forecloses the
        // allowedOrigins("*") + allowCredentials(true) account-takeover combination.
        mockMvc.perform(
            options("/admin/me")
                .header("Origin", ALLOWED_ORIGIN)
                .header("Access-Control-Request-Method", "GET"),
        ).andExpect(header().doesNotExist("Access-Control-Allow-Credentials"))
    }

    @Test
    fun `paths outside the panel's surface get no CORS headers at all`() {
        // The Android app's endpoints must stay exactly as they shipped: no configuration registered,
        // so no Access-Control-Allow-Origin, so browsers still cannot read them cross-origin.
        mockMvc.perform(
            options("/tasks")
                .header("Origin", ALLOWED_ORIGIN)
                .header("Access-Control-Request-Method", "GET"),
        ).andExpect(header().doesNotExist("Access-Control-Allow-Origin"))
    }

    private companion object {
        const val ALLOWED_ORIGIN = "https://donebot-admin.vercel.app"
    }
}
