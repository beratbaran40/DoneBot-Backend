package com.todoapp.backend.integration

import com.todoapp.backend.group.CreateGroupRequest
import com.todoapp.backend.group.GroupMemberEntity
import com.todoapp.backend.group.GroupMemberRepository
import com.todoapp.backend.group.GroupRole
import com.todoapp.backend.group.InvitationEntity
import com.todoapp.backend.group.InvitationRepository
import com.todoapp.backend.group.InvitationStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Locks the accept path's duplicate-membership guards (the client renders one group card per
 * membership row, so a duplicate pair becomes a duplicate card). Serial double-accept is blocked
 * by the status guard; the concurrent-accept race is closed by the unique index (V17) + the
 * saveAndFlush/DataIntegrityViolationException 409 in InvitationService.accept — the pre-existing-
 * membership test below simulates the state that race leaves behind.
 */
class InvitationAcceptIdempotencyIntegrationTest : AbstractIntegrationTest() {
    @Autowired
    private lateinit var invitations: InvitationRepository

    @Autowired
    private lateinit var members: GroupMemberRepository

    @Test
    fun `accept succeeds when the membership row already exists and flips the invitation`() {
        val inviter = registerUser("Owner")
        val invitee = registerUser("Guest")
        val group = groupService.create(inviter.user.id, CreateGroupRequest(name = "Family"))
        // Simulate the lost-race window: a concurrent accept already inserted the membership.
        members.save(GroupMemberEntity(groupId = group.id, userId = invitee.user.id, role = GroupRole.MEMBER.name))
        val invitation = invitations.save(
            InvitationEntity(
                groupId = group.id,
                inviterUserId = inviter.user.id,
                inviteeUserId = invitee.user.id,
                inviteeEmail = invitee.user.email,
            ),
        )

        mockMvc
            .perform(
                post("/family-groups/invitations/${invitation.id}/accept")
                    .header("Authorization", bearer(invitee.user.id)),
            ).andExpect(status().isOk)

        assertEquals(1, members.findAllByGroupId(group.id).count { it.userId == invitee.user.id })
        assertEquals(InvitationStatus.ACCEPTED.name, invitations.findById(invitation.id).get().status)
    }

    @Test
    fun `a second sequential accept returns 409 and leaves a single membership row`() {
        val inviter = registerUser("Owner")
        val invitee = registerUser("Guest")
        val group = groupService.create(inviter.user.id, CreateGroupRequest(name = "Family"))
        val invitation = invitations.save(
            InvitationEntity(
                groupId = group.id,
                inviterUserId = inviter.user.id,
                inviteeUserId = invitee.user.id,
                inviteeEmail = invitee.user.email,
            ),
        )

        mockMvc
            .perform(
                post("/family-groups/invitations/${invitation.id}/accept")
                    .header("Authorization", bearer(invitee.user.id)),
            ).andExpect(status().isOk)
        mockMvc
            .perform(
                post("/family-groups/invitations/${invitation.id}/accept")
                    .header("Authorization", bearer(invitee.user.id)),
            ).andExpect(status().isConflict)

        assertEquals(1, members.findAllByGroupId(group.id).count { it.userId == invitee.user.id })
    }
}
