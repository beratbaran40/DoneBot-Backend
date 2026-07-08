package com.todoapp.backend.group

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface InvitationRepository : JpaRepository<InvitationEntity, Long> {
    fun findByInviteeUserIdAndStatusOrderByCreatedAtDesc(
        inviteeUserId: Long,
        status: String,
    ): List<InvitationEntity>

    fun findByGroupIdAndStatusOrderByCreatedAtDesc(
        groupId: Long,
        status: String,
    ): List<InvitationEntity>

    fun findByIdAndInviteeUserId(id: Long, inviteeUserId: Long): InvitationEntity?

    fun findByIdAndInviterUserId(id: Long, inviterUserId: Long): InvitationEntity?

    fun findFirstByGroupIdAndInviteeUserIdAndStatus(
        groupId: Long,
        inviteeUserId: Long,
        status: String,
    ): InvitationEntity?
}
