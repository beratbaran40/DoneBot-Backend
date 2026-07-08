package com.todoapp.backend.group

import com.todoapp.backend.notif.NotificationPublisher
import com.todoapp.backend.notif.inbox.NotificationType
import com.todoapp.backend.user.UserRepository
import com.todoapp.backend.user.UserSummary
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

data class InvitationData(
    val id: Long,
    val groupId: Long,
    val groupName: String,
    val groupAvatarUrl: String?,
    val inviterUserId: Long,
    val inviterName: String,
    val inviterAvatarUrl: String?,
    val inviteeEmail: String,
    val status: String,
    val createdAt: Long,
    val respondedAt: Long?,
)

data class InvitationListData(
    val items: List<InvitationData>,
    val count: Int,
)

@Service
class InvitationService(
    private val invitations: InvitationRepository,
    private val groups: GroupRepository,
    private val members: GroupMemberRepository,
    private val users: UserRepository,
    private val activities: GroupActivityRepository,
    private val publisher: NotificationPublisher,
) {
    @Transactional
    fun invite(callerId: Long, req: InviteMemberRequest): InvitationData {
        val membership = members.findByGroupIdAndUserId(req.groupId, callerId)
            ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this group")
        if (membership.role.uppercase() != GroupRole.ADMIN.name) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Admin only")
        }
        val group = groups.findSummaryById(req.groupId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found")
        val invitee = users.findSummaryByEmail(req.email)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "User with email not found")
        if (invitee.id == callerId) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot invite yourself")
        }
        if (members.findByGroupIdAndUserId(req.groupId, invitee.id) != null) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "User already a member")
        }
        invitations.findFirstByGroupIdAndInviteeUserIdAndStatus(
            groupId = req.groupId,
            inviteeUserId = invitee.id,
            status = InvitationStatus.PENDING.name,
        )?.let {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Invitation already pending")
        }
        val saved = invitations.save(
            InvitationEntity(
                groupId = req.groupId,
                inviterUserId = callerId,
                inviteeUserId = invitee.id,
                inviteeEmail = req.email,
            )
        )
        val inviter = users.findSummaryById(callerId)
        val memberCount = members.countByGroupId(group.id).toInt()
        publisher.publish(
            userIds = listOf(invitee.id),
            type = NotificationType.INVITATION_RECEIVED,
            title = "Group invitation",
            body = "${inviter?.displayName ?: "Someone"} invited you to ${group.name}",
            payload = mapOf(
                "invitationId" to saved.id.toString(),
                "groupId" to group.id.toString(),
                "groupName" to group.name,
                "groupDescription" to group.description,
                "memberCount" to memberCount.toString(),
                "inviterName" to (inviter?.displayName ?: ""),
            ),
        )
        return saved.toData(group, inviter?.displayName, inviter?.avatarUrlOrPath())
    }

    @Transactional(readOnly = true)
    fun listMyPending(callerId: Long): InvitationListData {
        val rows = invitations.findByInviteeUserIdAndStatusOrderByCreatedAtDesc(
            inviteeUserId = callerId,
            status = InvitationStatus.PENDING.name,
        )
        val items = rows.mapNotNull { row ->
            val group = groups.findSummaryById(row.groupId) ?: return@mapNotNull null
            val inviter = users.findSummaryById(row.inviterUserId)
            row.toData(group, inviter?.displayName, inviter?.avatarUrlOrPath())
        }
        return InvitationListData(items, items.size)
    }

    @Transactional
    fun accept(callerId: Long, invitationId: Long): InvitationData {
        val row = invitations.findByIdAndInviteeUserId(invitationId, callerId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Invitation not found")
        if (row.status != InvitationStatus.PENDING.name) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Invitation is not pending")
        }
        val group = groups.findSummaryById(row.groupId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found")
        if (members.findByGroupIdAndUserId(row.groupId, callerId) == null) {
            try {
                // saveAndFlush so a unique-index violation (two concurrent accepts of the same
                // invitation racing past the null-check above) surfaces here, inside the try —
                // not later at commit (same pattern as TaskService.create).
                members.saveAndFlush(
                    GroupMemberEntity(
                        groupId = row.groupId,
                        userId = callerId,
                        role = GroupRole.MEMBER.name,
                    )
                )
            } catch (e: DataIntegrityViolationException) {
                // The concurrent accept won the insert (and commits the status flip); this tx is
                // already rollback-only, so nothing partial persists. Surface the same 409 the
                // status guard gives a late second tap; a client retry heals via the pre-check.
                throw ResponseStatusException(HttpStatus.CONFLICT, "Invitation is not pending", e)
            }
        }
        val acceptor = users.findSummaryById(callerId)
        activities.save(
            GroupActivityEntity(
                groupId = row.groupId,
                actorUserId = callerId,
                type = GroupActivityType.MEMBER_ADDED.name,
                description = "${acceptor?.displayName ?: "User"} joined the group",
            )
        )
        row.status = InvitationStatus.ACCEPTED.name
        row.respondedAt = Instant.now()
        val saved = invitations.save(row)
        publisher.publish(
            userIds = listOf(row.inviterUserId),
            type = NotificationType.INVITATION_ACCEPTED,
            title = "Invitation accepted",
            body = "${acceptor?.displayName ?: "Someone"} joined ${group.name}",
            payload = mapOf(
                "groupId" to group.id.toString(),
                "groupName" to group.name,
                "acceptorName" to (acceptor?.displayName ?: ""),
            ),
        )
        return saved.toData(group, acceptor?.displayName, acceptor?.avatarUrlOrPath())
    }

    @Transactional
    fun decline(callerId: Long, invitationId: Long): InvitationData {
        val row = invitations.findByIdAndInviteeUserId(invitationId, callerId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Invitation not found")
        if (row.status != InvitationStatus.PENDING.name) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Invitation is not pending")
        }
        val group = groups.findSummaryById(row.groupId)
        val decliner = users.findSummaryById(callerId)
        row.status = InvitationStatus.DECLINED.name
        row.respondedAt = Instant.now()
        val saved = invitations.save(row)
        if (group != null) {
            publisher.publish(
                userIds = listOf(row.inviterUserId),
                type = NotificationType.INVITATION_DECLINED,
                title = "Invitation declined",
                body = "${decliner?.displayName ?: "Someone"} declined to join ${group.name}",
                payload = mapOf(
                    "groupId" to group.id.toString(),
                    "groupName" to group.name,
                    "declinerName" to (decliner?.displayName ?: ""),
                ),
            )
        }
        return saved.toData(group, decliner?.displayName, decliner?.avatarUrlOrPath())
    }

    @Transactional
    fun cancel(callerId: Long, invitationId: Long) {
        val row = invitations.findByIdAndInviterUserId(invitationId, callerId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Invitation not found")
        if (row.status != InvitationStatus.PENDING.name) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Invitation is not pending")
        }
        row.status = InvitationStatus.CANCELLED.name
        row.respondedAt = Instant.now()
        invitations.save(row)
    }

    private fun UserSummary.avatarUrlOrPath(): String? =
        if (hasAvatar) "/users/$id/avatar" else avatarUrl

    private fun InvitationEntity.toData(
        group: GroupSummary?,
        inviterDisplayName: String?,
        inviterAvatarUrl: String?,
    ): InvitationData = InvitationData(
        id = id,
        groupId = groupId,
        groupName = group?.name.orEmpty(),
        groupAvatarUrl = if (group?.hasAvatar == true) "/family-groups/${group.id}/avatar" else null,
        inviterUserId = inviterUserId,
        inviterName = inviterDisplayName.orEmpty(),
        inviterAvatarUrl = inviterAvatarUrl,
        inviteeEmail = inviteeEmail,
        status = status,
        createdAt = createdAt.toEpochMilli(),
        respondedAt = respondedAt?.toEpochMilli(),
    )
}
