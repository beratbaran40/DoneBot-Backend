package com.todoapp.backend.admin

import com.todoapp.backend.auth.RegisterRequest
import com.todoapp.backend.integration.AbstractIntegrationTest
import com.todoapp.backend.user.UserRepository
import com.todoapp.backend.user.UserRole
import com.todoapp.backend.user.UserStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * The support surface. Two themes run through these tests: the destructive actions must be hard to fire
 * by accident, and the read surface must stay at metadata — counts and dates, never the content of
 * anyone's to-do list.
 */
@TestPropertySource(properties = ["app.admin.allowed-emails=user-admin@test.com"])
class AdminUserManagementIntegrationTest : AbstractIntegrationTest() {

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @jakarta.persistence.PersistenceContext
    private lateinit var entityManager: jakarta.persistence.EntityManager

    @Test
    fun `search finds a user by a fragment of their email`() {
        val admin = admin()
        register("findme@test.com")

        mockMvc.perform(
            get("/admin/users").param("q", "FINDME").header("Authorization", bearer(admin)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.items[0].email").value("findme@test.com"))
    }

    @Test
    fun `an oversized page request is clamped instead of dumping every account`() {
        // Without the cap this single request is a full export of every email address in the product.
        val admin = admin()

        mockMvc.perform(
            get("/admin/users").param("size", "100000").header("Authorization", bearer(admin)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.size").value(100))
    }

    @Test
    fun `an unknown sort is refused rather than reaching the query builder`() {
        val admin = admin()

        mockMvc.perform(
            get("/admin/users").param("sort", "email; DROP TABLE users")
                .header("Authorization", bearer(admin)),
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `detail reports counts and never task content`() {
        val admin = admin()
        val target = register("detail@test.com")
        insertTask(target, title = "Buy a very private thing", secret = true)
        insertTask(target, title = "Ordinary task", secret = false)

        val body = mockMvc.perform(get("/admin/users/$target").header("Authorization", bearer(admin)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.counts.tasksTotal").value(2))
            .andExpect(jsonPath("$.data.counts.tasksSecret").value(1))
            .andExpect(jsonPath("$.data.activitySparkline.length()").value(30))
            .andReturn().response.contentAsString

        assertTrue(
            !body.contains("Buy a very private thing") && !body.contains("Ordinary task"),
            "task titles must never appear in an admin payload",
        )
    }

    @Test
    fun `suspending flips the status, kills sessions and drops device registrations`() {
        val admin = admin()
        val session = authService.register(
            RegisterRequest(email = "tosuspend@test.com", password = "password123", displayName = "T"),
        )
        jdbc.update(
            "INSERT INTO device_tokens (user_id, token, device_id, device_name) VALUES (?, 'tok', 'd', 'Phone')",
            session.user.id,
        )

        mockMvc.perform(
            post("/admin/users/${session.user.id}/suspend")
                .header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"reason":"spam"}"""),
        ).andExpect(status().isOk)
        entityManager.flush()

        assertEquals(UserStatus.SUSPENDED.name, userRepository.findById(session.user.id).orElseThrow().status)
        assertEquals(0, activeRefreshTokens(session.user.id))
        assertEquals(0, deviceTokens(session.user.id))
        assertEquals(1, auditRows(AdminAction.USER_SUSPEND, session.user.id))
    }

    @Test
    fun `an admin cannot suspend their own account`() {
        // Otherwise the panel offers a one-click way to lock yourself out of the panel.
        val admin = admin()

        mockMvc.perform(post("/admin/users/$admin/suspend").header("Authorization", bearer(admin)))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `admin accounts cannot be suspended or deleted through this API`() {
        val admin = admin()
        val otherAdmin = register("other-admin@test.com")
        promoteToAdmin(otherAdmin)

        mockMvc.perform(post("/admin/users/$otherAdmin/suspend").header("Authorization", bearer(admin)))
            .andExpect(status().isBadRequest)

        mockMvc.perform(
            delete("/admin/users/$otherAdmin")
                .header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"confirmEmail":"other-admin@test.com"}"""),
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `deleting requires the exact email of the account being deleted`() {
        val admin = admin()
        val target = register("todelete@test.com")

        mockMvc.perform(
            delete("/admin/users/$target")
                .header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"confirmEmail":"someone-else@test.com"}"""),
        ).andExpect(status().isBadRequest)

        assertTrue(userRepository.findById(target).isPresent, "the account must survive a mismatched confirmation")
    }

    @Test
    fun `deleting with the right confirmation removes the account and records it`() {
        val admin = admin()
        val target = register("reallydelete@test.com")

        mockMvc.perform(
            delete("/admin/users/$target")
                .header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"confirmEmail":"reallydelete@test.com"}"""),
        ).andExpect(status().isOk)
        entityManager.flush()

        assertTrue(userRepository.findById(target).isEmpty)
        assertEquals(1, auditRows(AdminAction.USER_DELETE, target))
    }

    @Test
    fun `unsuspending restores access`() {
        val admin = admin()
        val target = register("restore@test.com")
        mockMvc.perform(post("/admin/users/$target/suspend").header("Authorization", bearer(admin)))
            .andExpect(status().isOk)

        mockMvc.perform(post("/admin/users/$target/unsuspend").header("Authorization", bearer(admin)))
            .andExpect(status().isOk)
        entityManager.flush()

        assertEquals(UserStatus.ACTIVE.name, userRepository.findById(target).orElseThrow().status)
    }

    @Test
    fun `ops endpoints answer`() {
        val admin = admin()

        mockMvc.perform(get("/admin/ops/health").header("Authorization", bearer(admin)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.dbUp").value(true))
            .andExpect(jsonPath("$.data.zone").value("UTC"))
            .andExpect(jsonPath("$.data.flags.chat_enabled").value("true"))

        mockMvc.perform(get("/admin/ops/chat-usage").header("Authorization", bearer(admin)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.globalDailyLimit").value(5000))

        mockMvc.perform(get("/admin/ops/errors").header("Authorization", bearer(admin)))
            .andExpect(status().isOk)

        mockMvc.perform(get("/admin/ops/config").header("Authorization", bearer(admin)))
            .andExpect(status().isOk)
            // Presence flags only — never the credential values themselves.
            .andExpect(jsonPath("$.data.vertexConfigured").exists())

        mockMvc.perform(get("/admin/ops/audit").header("Authorization", bearer(admin)))
            .andExpect(status().isOk)
    }

    @Test
    fun `the support surface is closed to non-admins`() {
        val ordinary = registerUser().user.id

        mockMvc.perform(get("/admin/users").header("Authorization", bearer(ordinary)))
            .andExpect(status().isForbidden)
        mockMvc.perform(get("/admin/ops/health").header("Authorization", bearer(ordinary)))
            .andExpect(status().isForbidden)
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private fun admin(): Long = register("user-admin@test.com").also { promoteToAdmin(it) }

    private fun register(email: String): Long = authService.register(
        RegisterRequest(email = email, password = "password123", displayName = "User"),
    ).user.id

    private fun promoteToAdmin(userId: Long) {
        val entity = userRepository.findById(userId).orElseThrow()
        entity.role = UserRole.ADMIN.name
        userRepository.saveAndFlush(entity)
    }

    private fun insertTask(ownerId: Long, title: String, secret: Boolean) {
        jdbc.update(
            "INSERT INTO tasks (owner_id, title, date, time_start, time_end, is_secret) VALUES (?, ?, 0, 0, 0, ?)",
            ownerId,
            title,
            secret,
        )
    }

    private fun activeRefreshTokens(userId: Long): Int = jdbc.queryForObject(
        "SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ? AND revoked = FALSE",
        Int::class.java,
        userId,
    ) ?: 0

    private fun deviceTokens(userId: Long): Int = jdbc.queryForObject(
        "SELECT COUNT(*) FROM device_tokens WHERE user_id = ?",
        Int::class.java,
        userId,
    ) ?: 0

    private fun auditRows(action: String, targetId: Long): Int = jdbc.queryForObject(
        "SELECT COUNT(*) FROM admin_audit_log WHERE action = ? AND target_id = ?",
        Int::class.java,
        action,
        targetId.toString(),
    ) ?: 0
}
