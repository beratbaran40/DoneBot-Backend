package com.todoapp.backend.integration

import com.todoapp.backend.group.CreateGroupRequest
import com.todoapp.backend.group.InvitationEntity
import com.todoapp.backend.group.InvitationRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Regression lock for the invitation IDOR surface. accept/decline scope by invitee
 * (`findByIdAndInviteeUserId`) and cancel scopes by inviter (`findByIdAndInviterUserId`), so an
 * unrelated user cannot act on someone else's invitation. Seeded directly via the repository to keep
 * the test off the FCM/publish path; only the ownership guards are exercised.
 */
class InvitationIdorIntegrationTest : AbstractIntegrationTest() {
    @Autowired
    private lateinit var invitations: InvitationRepository

    @Test
    fun `an unrelated user cannot accept decline or cancel someone else's invitation`() {
        val inviter = registerUser("Inviter")
        val invitee = registerUser("Invitee")
        val attacker = registerUser("Attacker")
        val group = groupService.create(inviter.user.id, CreateGroupRequest(name = "Family"))
        val invitation = invitations.save(
            InvitationEntity(
                groupId = group.id,
                inviterUserId = inviter.user.id,
                inviteeUserId = invitee.user.id,
                inviteeEmail = invitee.user.email,
            ),
        )

        // Attacker is neither the invitee nor the inviter → each mutation 404s before touching state.
        mockMvc
            .perform(
                post("/family-groups/invitations/${invitation.id}/accept")
                    .header("Authorization", bearer(attacker.user.id)),
            ).andExpect(status().isNotFound)
        mockMvc
            .perform(
                post("/family-groups/invitations/${invitation.id}/decline")
                    .header("Authorization", bearer(attacker.user.id)),
            ).andExpect(status().isNotFound)
        mockMvc
            .perform(
                delete("/family-groups/invitations/${invitation.id}")
                    .header("Authorization", bearer(attacker.user.id)),
            ).andExpect(status().isNotFound)

        // Positive control: the inviter can cancel their own still-pending invitation (cancel does not push).
        mockMvc
            .perform(
                delete("/family-groups/invitations/${invitation.id}")
                    .header("Authorization", bearer(inviter.user.id)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value(200))
    }

    @Test
    fun `accepting an invitation without a token is 401`() {
        mockMvc
            .perform(post("/family-groups/invitations/1/accept"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `group detail embeds the group's pending invitations`() {
        val inviter = registerUser("Owner")
        val invitee = registerUser("Guest")
        val group = groupService.create(inviter.user.id, CreateGroupRequest(name = "Family"))
        invitations.save(
            InvitationEntity(
                groupId = group.id,
                inviterUserId = inviter.user.id,
                inviteeUserId = invitee.user.id,
                inviteeEmail = invitee.user.email,
            ),
        )

        mockMvc
            .perform(
                get("/family-groups/${group.id}")
                    .header("Authorization", bearer(inviter.user.id)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.pendingInvitations[0].inviteeEmail").value(invitee.user.email))
            .andExpect(jsonPath("$.data.pendingInvitations[0].status").value("PENDING"))
    }
}
