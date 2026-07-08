package com.todoapp.backend.integration

import com.todoapp.backend.group.CreateGroupRequest
import com.todoapp.backend.group.GroupMemberEntity
import com.todoapp.backend.group.GroupMemberRepository
import com.todoapp.backend.group.GroupRole
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Locks the V18 structured-activity contract clients localize from: targetName carries the person
 * the action was done TO, and self-leaves log as MEMBER_LEFT (not MEMBER_REMOVED) so the two
 * sentences can be told apart without parsing the English description.
 */
class GroupActivityTargetNameIntegrationTest : AbstractIntegrationTest() {
    @Autowired
    private lateinit var members: GroupMemberRepository

    @Test
    fun `transfer ownership logs the new owner as targetName`() {
        val owner = registerUser("Owner")
        val heir = registerUser("Heir")
        val group = groupService.create(owner.user.id, CreateGroupRequest(name = "Family"))
        members.save(GroupMemberEntity(groupId = group.id, userId = heir.user.id, role = GroupRole.MEMBER.name))

        mockMvc
            .perform(
                put("/family-groups/${group.id}/transfer-ownership")
                    .header("Authorization", bearer(owner.user.id))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"userId": ${heir.user.id}}"""),
            ).andExpect(status().isOk)

        mockMvc
            .perform(
                get("/family-groups/${group.id}/activity")
                    .header("Authorization", bearer(owner.user.id)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.activities[0].type").value("OWNERSHIP_TRANSFERRED"))
            .andExpect(jsonPath("$.data.activities[0].targetName").value("Heir"))
    }

    @Test
    fun `leaving logs MEMBER_LEFT with no target`() {
        val owner = registerUser("Owner")
        val guest = registerUser("Guest")
        val group = groupService.create(owner.user.id, CreateGroupRequest(name = "Family"))
        members.save(GroupMemberEntity(groupId = group.id, userId = guest.user.id, role = GroupRole.MEMBER.name))

        mockMvc
            .perform(
                post("/family-groups/${group.id}/leave")
                    .header("Authorization", bearer(guest.user.id)),
            ).andExpect(status().isOk)

        mockMvc
            .perform(
                get("/family-groups/${group.id}/activity")
                    .header("Authorization", bearer(owner.user.id)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.activities[0].type").value("MEMBER_LEFT"))
            .andExpect(jsonPath("$.data.activities[0].targetName").isEmpty)
    }
}
