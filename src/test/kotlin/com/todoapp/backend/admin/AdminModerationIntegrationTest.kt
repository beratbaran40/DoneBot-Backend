package com.todoapp.backend.admin

import com.todoapp.backend.auth.RegisterRequest
import com.todoapp.backend.integration.AbstractIntegrationTest
import com.todoapp.backend.user.UserRepository
import com.todoapp.backend.user.UserRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * The moderation queue, and above all the guard on the photo viewer.
 *
 * `content_reports.target_ref` is written verbatim from the reporting client, and the viewer
 * deliberately bypasses the group-membership check that would otherwise 403 an admin. Those two facts
 * together mean the validation is the only thing standing between a crafted report and an
 * admin-privileged read of someone else's photo, so most of this file is attempts to get through it.
 */
@TestPropertySource(properties = ["app.admin.allowed-emails=mod-admin@test.com"])
class AdminModerationIntegrationTest : AbstractIntegrationTest() {

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    /**
     * Assertions below read through raw JDBC on purpose — reading back through the JPA repository would
     * be satisfied by the persistence context and prove nothing. That means Hibernate has to be flushed
     * first: the test transaction never commits, so without this the UPDATE is still sitting in memory
     * and the row on disk still says OPEN.
     */
    @jakarta.persistence.PersistenceContext
    private lateinit var entityManager: jakarta.persistence.EntityManager

    @Test
    fun `open chat reports are listed with the flagged text`() {
        val admin = admin()
        val reporter = register("reporter@test.com")
        insertChatReport(reporter, "something the bot said")

        mockMvc.perform(
            get("/admin/reports").param("type", "chat").param("status", "OPEN")
                .header("Authorization", bearer(admin)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.items[0].messageContent").value("something the bot said"))
            .andExpect(jsonPath("$.data.items[0].reporterEmail").value("reporter@test.com"))
            .andExpect(jsonPath("$.data.items[0].status").value("OPEN"))
    }

    @Test
    fun `resolving a report records the decision and the admin who made it`() {
        val admin = admin()
        val reporter = register("reporter2@test.com")
        val reportId = insertChatReport(reporter, "flagged")

        mockMvc.perform(
            post("/admin/reports/chat/$reportId/resolve")
                .header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"resolution":"NO_ACTION","note":"false positive"}"""),
        ).andExpect(status().isOk)
        entityManager.flush()

        assertEquals("DISMISSED", chatReportColumn(reportId, "status"))
        assertEquals("NO_ACTION", chatReportColumn(reportId, "resolution"))
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM admin_audit_log WHERE action = 'report.resolve' AND target_id = ?",
                Int::class.java,
                reportId.toString(),
            ),
        )
    }

    @Test
    fun `an unknown resolution is refused`() {
        val admin = admin()
        val reportId = insertChatReport(register("reporter3@test.com"), "flagged")

        mockMvc.perform(
            post("/admin/reports/chat/$reportId/resolve")
                .header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"resolution":"DELETE_EVERYTHING"}"""),
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `a genuinely reported photo can be viewed`() {
        val admin = admin()
        val reporter = register("reporter4@test.com")
        val groupId = insertGroup(reporter)
        val taskId = insertGroupTask(reporter, groupId)
        val photoId = insertPhoto(taskId)
        val reportId = insertContentReport(reporter, groupId, "/tasks/$taskId/photos/$photoId")

        mockMvc.perform(get("/admin/reports/content/$reportId/photo").header("Authorization", bearer(admin)))
            .andExpect(status().isOk)
    }

    @Test
    fun `a report cannot be used to read a photo from another group`() {
        // The attack: file a report inside your own group whose target_ref points at a photo belonging
        // to a group you are not in. Without the task-belongs-to-the-reported-group check, the viewer
        // would happily serve it with admin privileges.
        val admin = admin()
        val attacker = register("attacker@test.com")
        val victim = register("victim@test.com")

        val victimGroup = insertGroup(victim)
        val victimTask = insertGroupTask(victim, victimGroup)
        val victimPhoto = insertPhoto(victimTask)

        val attackerGroup = insertGroup(attacker)
        val reportId = insertContentReport(attacker, attackerGroup, "/tasks/$victimTask/photos/$victimPhoto")

        mockMvc.perform(get("/admin/reports/content/$reportId/photo").header("Authorization", bearer(admin)))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `a photo id that belongs to a different task than the reference claims is refused`() {
        val admin = admin()
        val reporter = register("reporter5@test.com")
        val groupId = insertGroup(reporter)
        val taskA = insertGroupTask(reporter, groupId)
        val taskB = insertGroupTask(reporter, groupId)
        val photoOnB = insertPhoto(taskB)

        // Same group, so the group check alone would pass — the photo/task consistency check catches it.
        val reportId = insertContentReport(reporter, groupId, "/tasks/$taskA/photos/$photoOnB")

        mockMvc.perform(get("/admin/reports/content/$reportId/photo").header("Authorization", bearer(admin)))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `references that are not exactly a photo path are refused`() {
        val admin = admin()
        val reporter = register("reporter6@test.com")
        val groupId = insertGroup(reporter)
        val taskId = insertGroupTask(reporter, groupId)
        val photoId = insertPhoto(taskId)

        listOf(
            "/tasks/$taskId/photos/$photoId/../../9999",
            "/tasks/$taskId/photos/$photoId?x=1",
            "http://evil.example.com/tasks/$taskId/photos/$photoId",
            "  /tasks/$taskId/photos/$photoId  extra",
            "",
        ).forEach { ref ->
            val reportId = insertContentReport(reporter, groupId, ref)
            mockMvc.perform(
                get("/admin/reports/content/$reportId/photo").header("Authorization", bearer(admin)),
            ).andExpect(status().isNotFound)
        }
    }

    @Test
    fun `the moderation queue is closed to non-admins`() {
        val ordinary = registerUser().user.id

        mockMvc.perform(
            get("/admin/reports").param("type", "chat").header("Authorization", bearer(ordinary)),
        ).andExpect(status().isForbidden)
    }

    // ---- seeding ---------------------------------------------------------------------------------

    private fun admin(): Long {
        val id = register("mod-admin@test.com")
        val entity = userRepository.findById(id).orElseThrow()
        entity.role = UserRole.ADMIN.name
        userRepository.saveAndFlush(entity)
        return id
    }

    private fun register(email: String): Long = authService.register(
        RegisterRequest(email = email, password = "password123", displayName = "Mod"),
    ).user.id

    private fun insertChatReport(userId: Long, content: String): Long {
        jdbc.update(
            "INSERT INTO chat_reports (user_id, message_content, message_hash, reason, status) " +
                "VALUES (?, ?, ?, 'offensive', 'OPEN')",
            userId,
            content,
            "hash-${content.hashCode()}-$userId",
        )
        return jdbc.queryForObject("SELECT MAX(id) FROM chat_reports", Long::class.java)!!
    }

    private fun insertGroup(ownerId: Long): Long {
        jdbc.update("INSERT INTO family_groups (name, owner_id) VALUES ('g', ?)", ownerId)
        return jdbc.queryForObject("SELECT MAX(id) FROM family_groups", Long::class.java)!!
    }

    private fun insertGroupTask(ownerId: Long, groupId: Long): Long {
        jdbc.update(
            "INSERT INTO tasks (owner_id, title, date, time_start, time_end, family_group_id) " +
                "VALUES (?, 'shared', 0, 0, 0, ?)",
            ownerId,
            groupId,
        )
        return jdbc.queryForObject("SELECT MAX(id) FROM tasks", Long::class.java)!!
    }

    private fun insertPhoto(taskId: Long): Long {
        jdbc.update(
            "INSERT INTO task_photos (task_id, bytes, content_type) VALUES (?, ?, 'image/jpeg')",
            taskId,
            byteArrayOf(1, 2, 3, 4),
        )
        return jdbc.queryForObject("SELECT MAX(id) FROM task_photos", Long::class.java)!!
    }

    private fun insertContentReport(reporterId: Long, groupId: Long, targetRef: String): Long {
        jdbc.update(
            "INSERT INTO content_reports (reporter_user_id, group_id, target_type, target_ref, " +
                "target_hash, status) VALUES (?, ?, 'PHOTO', ?, ?, 'OPEN')",
            reporterId,
            groupId,
            targetRef,
            "hash-${targetRef.hashCode()}-$reporterId-$groupId",
        )
        return jdbc.queryForObject("SELECT MAX(id) FROM content_reports", Long::class.java)!!
    }

    private fun chatReportColumn(id: Long, column: String): String? = jdbc.queryForObject(
        "SELECT $column FROM chat_reports WHERE id = ?",
        String::class.java,
        id,
    )
}
