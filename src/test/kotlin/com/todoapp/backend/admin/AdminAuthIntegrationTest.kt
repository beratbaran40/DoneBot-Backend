package com.todoapp.backend.admin

import com.todoapp.backend.auth.RegisterRequest
import com.todoapp.backend.integration.AbstractIntegrationTest
import com.todoapp.backend.user.UserRepository
import com.todoapp.backend.user.UserRole
import com.todoapp.backend.user.UserStatus
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Locks the three gates on the /admin endpoints. The allowlist here is deliberately written with
 * whitespace and mixed case so the normalisation on both sides stays covered.
 */
@TestPropertySource(properties = ["app.admin.allowed-emails= Admin-Allowed@Test.com ,someone-else@test.com"])
class AdminAuthIntegrationTest : AbstractIntegrationTest() {

    @Autowired
    private lateinit var userRepository: UserRepository

    @Test
    fun `unauthenticated request is 401, not 403`() {
        // 401 vs 403 is load-bearing for the panel: 401 means "try refreshing the token",
        // 403 means "stop, this account may not use the panel" — conflating them causes a refresh loop.
        mockMvc.perform(get("/admin/me")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `ordinary authenticated user is refused`() {
        val user = registerUser()

        mockMvc.perform(get("/admin/me").header("Authorization", bearer(user.user.id)))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.errorCode").value("admin_forbidden"))
    }

    @Test
    fun `ADMIN role alone is not enough when the email is off the allowlist`() {
        val user = register("admin-not-listed@test.com")
        promote(user, UserRole.ADMIN)

        mockMvc.perform(get("/admin/me").header("Authorization", bearer(user)))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `allowlisted email alone is not enough without the ADMIN role`() {
        val user = register("admin-allowed@test.com")

        mockMvc.perform(get("/admin/me").header("Authorization", bearer(user)))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `suspended admin is refused even though role and allowlist match`() {
        val user = register("admin-allowed@test.com")
        promote(user, UserRole.ADMIN, UserStatus.SUSPENDED)

        mockMvc.perform(get("/admin/me").header("Authorization", bearer(user)))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `admin with all three gates satisfied reaches the endpoint`() {
        val user = register("admin-allowed@test.com")
        promote(user, UserRole.ADMIN)

        mockMvc.perform(get("/admin/me").header("Authorization", bearer(user)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.email").value("admin-allowed@test.com"))
            .andExpect(jsonPath("$.data.role").value("ADMIN"))
            .andExpect(jsonPath("$.data.zone").value("UTC"))
    }

    @Test
    fun `demotion takes effect immediately, without waiting for the token to expire`() {
        val user = register("admin-allowed@test.com")
        promote(user, UserRole.ADMIN)
        val token = bearer(user)

        mockMvc.perform(get("/admin/me").header("Authorization", token)).andExpect(status().isOk)

        // Same token, role revoked in the database. This is the whole reason the role is read fresh
        // per request instead of being carried as a JWT claim.
        promote(user, UserRole.USER)

        mockMvc.perform(get("/admin/me").header("Authorization", token)).andExpect(status().isForbidden)
    }

    private fun register(email: String): Long =
        authService.register(
            RegisterRequest(email = email, password = "password123", displayName = "Admin"),
        ).user.id

    private fun promote(
        userId: Long,
        role: UserRole,
        status: UserStatus = UserStatus.ACTIVE,
    ) {
        val entity = userRepository.findById(userId).orElseThrow()
        entity.role = role.name
        entity.status = status.name
        userRepository.saveAndFlush(entity)
    }
}

/**
 * A blank allowlist must deny everyone — including a genuine ADMIN. Forgetting ADMIN_ALLOWED_EMAILS on
 * Render should lock the operator out of the panel, never open it to every registered user. Needs its
 * own context because the allowlist is bound once at startup.
 */
@TestPropertySource(properties = ["app.admin.allowed-emails="])
class AdminAllowlistFailClosedTest : AbstractIntegrationTest() {

    @Autowired
    private lateinit var userRepository: UserRepository

    @Test
    fun `blank allowlist denies even a genuine admin`() {
        val userId = authService.register(
            RegisterRequest(email = "admin@test.com", password = "password123", displayName = "Admin"),
        ).user.id
        val entity = userRepository.findById(userId).orElseThrow()
        entity.role = UserRole.ADMIN.name
        userRepository.saveAndFlush(entity)

        mockMvc.perform(get("/admin/me").header("Authorization", bearer(userId)))
            .andExpect(status().isForbidden)
    }
}
