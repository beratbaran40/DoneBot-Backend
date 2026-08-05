package com.todoapp.backend.admin

import com.todoapp.backend.auth.AuthException
import com.todoapp.backend.auth.LoginRequest
import com.todoapp.backend.auth.RefreshTokenRequest
import com.todoapp.backend.auth.RegisterRequest
import com.todoapp.backend.integration.AbstractIntegrationTest
import com.todoapp.backend.settings.AppSetting
import com.todoapp.backend.settings.AppSettingsService
import com.todoapp.backend.user.UserRepository
import com.todoapp.backend.user.UserRole
import com.todoapp.backend.user.UserStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * The operator switches and the suspension path.
 *
 * These are the only pieces of the admin work that change behaviour for ordinary users of the live app,
 * so they get end-to-end coverage rather than unit coverage: what matters is not that a flag is stored,
 * but that flipping it actually stops a signup and that a suspended account actually loses its session.
 */
@TestPropertySource(
    properties = [
        "app.admin.allowed-emails=settings-admin@test.com",
        // No caching here. AppSettingsService is a singleton whose cache outlives a rolled-back test
        // transaction, so a cached value written by one test would leak into the next and make the suite
        // order-dependent. Reading through to the database keeps each test honest; the cache itself is
        // production behaviour, verified by the after-commit invalidation rather than by a timer here.
        "app.settings.cache-seconds=0",
    ],
)
class AdminSettingsIntegrationTest : AbstractIntegrationTest() {

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var settings: AppSettingsService

    @Test
    fun `every known switch is listed with its effective value`() {
        val admin = admin()

        mockMvc.perform(get("/admin/settings").header("Authorization", bearer(admin)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.chat_enabled").value("true"))
            .andExpect(jsonPath("$.data.registration_enabled").value("true"))
            .andExpect(jsonPath("$.data.chat_max_global_daily_requests").value("5000"))
    }

    @Test
    fun `an unknown key cannot create a row`() {
        val admin = admin()

        mockMvc.perform(
            put("/admin/settings/drop_everything")
                .header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"value":"true"}"""),
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `a mistyped boolean is refused instead of silently reading as false`() {
        // chat_enabled is read with an equals-"true" comparison, so storing "ture" would disable DoneBot
        // for every user — a typo turning into an outage, in the dangerous direction.
        val admin = admin()

        mockMvc.perform(
            put("/admin/settings/chat_enabled")
                .header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"value":"ture"}"""),
        ).andExpect(status().isBadRequest)

        assertEquals(true, settings.isEnabled(AppSetting.CHAT_ENABLED))
    }

    @Test
    fun `a negative request ceiling is refused`() {
        val admin = admin()

        mockMvc.perform(
            put("/admin/settings/chat_max_global_daily_requests")
                .header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"value":"-1"}"""),
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `closing registration blocks new signups immediately`() {
        val admin = admin()

        mockMvc.perform(
            put("/admin/settings/registration_enabled")
                .header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"value":"false"}"""),
        ).andExpect(status().isOk)

        // No waiting for a cache TTL: a write drops the cache, because an operator closing the door
        // during an abuse incident should not have to wonder whether it has taken effect yet.
        val ex = assertThrows<AuthException> {
            authService.register(
                RegisterRequest(email = "blocked@test.com", password = "password123", displayName = "Blocked"),
            )
        }
        assertEquals("registration_closed", ex.errorCode)
    }

    @Test
    fun `a suspended account cannot log in`() {
        val userId = authService.register(
            RegisterRequest(email = "suspended@test.com", password = "password123", displayName = "Suspended"),
        ).user.id
        suspend(userId)

        val ex = assertThrows<AuthException> {
            authService.login(LoginRequest(email = "suspended@test.com", password = "password123"))
        }
        assertEquals("account_suspended", ex.errorCode)
    }

    @Test
    fun `a suspended account cannot refresh an existing session`() {
        // This is the check that bounds how long a suspension takes to bite. Revoking refresh tokens
        // alone would not be enough if a token issued moments before were still exchangeable.
        val session = authService.register(
            RegisterRequest(email = "suspended2@test.com", password = "password123", displayName = "Suspended"),
        )
        suspend(session.user.id)

        val ex = assertThrows<AuthException> {
            authService.refresh(RefreshTokenRequest(session.refreshToken))
        }
        assertEquals("account_suspended", ex.errorCode)
    }

    @Test
    fun `settings are not writable by a non-admin`() {
        val ordinary = registerUser().user.id

        mockMvc.perform(
            put("/admin/settings/chat_enabled")
                .header("Authorization", bearer(ordinary))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"value":"false"}"""),
        ).andExpect(status().isForbidden)
    }

    private fun admin(): Long {
        val id = authService.register(
            RegisterRequest(email = "settings-admin@test.com", password = "password123", displayName = "Admin"),
        ).user.id
        val entity = userRepository.findById(id).orElseThrow()
        entity.role = UserRole.ADMIN.name
        userRepository.saveAndFlush(entity)
        return id
    }

    private fun suspend(userId: Long) {
        val entity = userRepository.findById(userId).orElseThrow()
        entity.status = UserStatus.SUSPENDED.name
        userRepository.saveAndFlush(entity)
    }
}
