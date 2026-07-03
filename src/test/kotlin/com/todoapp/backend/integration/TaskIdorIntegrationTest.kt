package com.todoapp.backend.integration

import com.todoapp.backend.group.CreateGroupRequest
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Regression lock for the §4.8 group-task IDOR fix.
 *
 * `TaskService.requireGroupMembership` must reject a non-member with 403 BEFORE any task is read,
 * so an authenticated user cannot enumerate other groups' tasks (incl. isSecret ones) by guessing
 * `?familyGroupId=N`. A backend change that drops that check would silently reopen the hole — this
 * test fails loudly if it does.
 */
class TaskIdorIntegrationTest : AbstractIntegrationTest() {
    @Test
    fun `group member lists group tasks but a non-member gets 403`() {
        val owner = registerUser("Owner A")
        val outsider = registerUser("Outsider B")
        val group = groupService.create(owner.user.id, CreateGroupRequest(name = "Family"))

        // Owner is auto-added as an ADMIN member → the group branch is allowed.
        mockMvc
            .perform(
                get("/tasks")
                    .param("familyGroupId", group.id.toString())
                    .header("Authorization", bearer(owner.user.id)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value(200))

        // Outsider is not a member → 403 with the wrapped BaseResponse envelope.
        mockMvc
            .perform(
                get("/tasks")
                    .param("familyGroupId", group.id.toString())
                    .header("Authorization", bearer(outsider.user.id)),
            ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value(403))
            .andExpect(jsonPath("$.message").value("Not a group member"))
    }

    @Test
    fun `listing group tasks without a token is 401`() {
        // HttpStatusEntryPoint(UNAUTHORIZED) returns a bare 401 with no envelope — assert status only.
        mockMvc
            .perform(get("/tasks").param("familyGroupId", "1"))
            .andExpect(status().isUnauthorized)
    }
}
