package com.todoapp.backend.group

import com.todoapp.backend.notif.NotificationPublisher
import com.todoapp.backend.notif.inbox.NotificationType
import com.todoapp.backend.task.TaskRepository
import com.todoapp.backend.user.UserEntity
import com.todoapp.backend.user.UserRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

@Service
class GroupService(
    private val groups: GroupRepository,
    private val members: GroupMemberRepository,
    private val users: UserRepository,
    private val tasks: TaskRepository,
    private val activities: GroupActivityRepository,
    private val invitations: InvitationService,
    private val invitationRepository: InvitationRepository,
    private val publisher: NotificationPublisher,
) {
    private fun logActivity(
        groupId: Long,
        actorUserId: Long,
        type: GroupActivityType,
        description: String,
        targetName: String? = null,
    ) {
        activities.save(
            GroupActivityEntity(
                groupId = groupId,
                actorUserId = actorUserId,
                type = type.name,
                targetName = targetName,
                description = description,
            )
        )
    }

    @Transactional
    fun create(creatorId: Long, req: CreateGroupRequest): GroupData {
        val saved = groups.save(GroupEntity(name = req.name, description = req.description, ownerId = creatorId))
        members.save(GroupMemberEntity(groupId = saved.id, userId = creatorId, role = GroupRole.ADMIN.name))
        return saved.toData()
    }

    @Transactional(readOnly = true)
    fun listSummaries(userId: Long): GroupSummaryListData {
        // Defense in depth: a duplicate membership pair (pre-V17 data) must not mirror into a
        // duplicate group card on the client.
        val mine = members.findAllByUserId(userId).distinctBy { it.groupId }
        val summaries = mine.map { membership ->
            val group = groups.findSummaryById(membership.groupId)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Group ${membership.groupId} not found")
            GroupSummaryData(
                id = group.id,
                name = group.name,
                description = group.description,
                avatarUrl = if (group.hasAvatar) "/family-groups/${group.id}/avatar" else null,
                role = membership.role,
                memberCount = members.countByGroupId(group.id).toInt(),
                pendingTaskCount = tasks.findAllByFamilyGroupId(group.id).count { !it.isCompleted },
                createdAt = group.createdAt.toEpochMilli(),
            )
        }
        return GroupSummaryListData(summaries, summaries.size)
    }

    @Transactional(readOnly = true)
    fun detail(userId: Long, groupId: Long): GroupData {
        requireMember(groupId, userId)
        val group = groups.findById(groupId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found")
        }
        // Pending outgoing invites ride along only on the detail call (create/update keep the
        // default empty list) — the members tab is the single consumer.
        val pendingInvitations = invitationRepository
            .findByGroupIdAndStatusOrderByCreatedAtDesc(groupId, InvitationStatus.PENDING.name)
            .map {
                GroupInvitationData(
                    id = it.id,
                    inviteeEmail = it.inviteeEmail,
                    status = it.status,
                    createdAt = it.createdAt.toEpochMilli(),
                )
            }
        return group.toData().copy(pendingInvitations = pendingInvitations)
    }

    @Transactional
    fun update(userId: Long, req: UpdateGroupRequest): GroupData {
        requireAdmin(req.id, userId)
        val group = groups.findById(req.id).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found")
        }
        group.name = req.name
        group.description = req.description
        group.updatedAt = Instant.now()
        return groups.save(group).toData()
    }

    @Transactional
    fun delete(userId: Long, groupId: Long) {
        requireAdmin(groupId, userId)
        // ON DELETE CASCADE FKs (V14) remove group_members, group_activities, group_invitations, and the
        // group's tasks (and via them task_photos / subtasks / daily-completions). Just drop the group row.
        groups.deleteById(groupId)
    }

    fun invite(userId: Long, req: InviteMemberRequest): InvitationData = invitations.invite(userId, req)

    @Transactional
    fun removeMember(userId: Long, groupId: Long, memberUserId: Long) {
        requireAdmin(groupId, userId)
        if (memberUserId == userId) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Use transfer-ownership to leave as admin")
        }
        val name = users.findSummaryById(memberUserId)?.displayName ?: "user"
        members.deleteByGroupIdAndUserId(groupId, memberUserId)
        logActivity(groupId, userId, GroupActivityType.MEMBER_REMOVED, "Removed $name from the group", targetName = name)
    }

    /**
     * The caller removes themselves from [groupId]. Any plain member may leave. The owner (the
     * single ADMIN) must transfer ownership first — unless they are the last member left, in which
     * case leaving tears down the whole group (same teardown as [delete]).
     */
    @Transactional
    fun leave(userId: Long, groupId: Long) {
        val membership = requireMember(groupId, userId)
        val isOwner = membership.role.uppercase() == GroupRole.ADMIN.name
        if (isOwner) {
            if (members.countByGroupId(groupId) <= 1L) {
                delete(userId, groupId)
                return
            }
            throw ResponseStatusException(HttpStatus.CONFLICT, "Transfer ownership before leaving")
        }
        val name = users.findSummaryById(userId)?.displayName ?: "user"
        members.deleteByGroupIdAndUserId(groupId, userId)
        // MEMBER_LEFT (not MEMBER_REMOVED): the actor is the leaver, so clients localize this
        // without a target; the description stays English for pre-V18 clients.
        logActivity(groupId, userId, GroupActivityType.MEMBER_LEFT, "$name left the group")
    }

    /**
     * [notify] exists for exactly one caller: account deletion transfers ownership from inside the
     * deletion transaction and fans the notification out **after** it commits (UserController.deleteMe,
     * AdminUserService), so notifying here too would send the new admin the same message twice.
     */
    @Transactional
    fun transferOwnership(userId: Long, groupId: Long, req: TransferOwnershipRequest, notify: Boolean = true) {
        requireAdmin(groupId, userId)
        val target = members.findByGroupIdAndUserId(groupId, req.userId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Target user not in group")
        target.role = GroupRole.ADMIN.name
        members.save(target)
        val current = members.findByGroupIdAndUserId(groupId, userId)
        if (current != null) {
            current.role = GroupRole.MEMBER.name
            members.save(current)
        }
        val group = groups.findById(groupId).get()
        group.ownerId = req.userId
        groups.save(group)
        val newOwnerName = users.findSummaryById(req.userId)?.displayName ?: "user"
        logActivity(groupId, userId, GroupActivityType.OWNERSHIP_TRANSFERRED, "Transferred ownership to $newOwnerName", targetName = newOwnerName)
        // Being handed a group is not something the new admin can discover on their own — the
        // activity feed only says it happened to whoever opens the group. The same event already
        // notifies when it comes from an account deletion (UserController.deleteMe); a manual
        // transfer silently did not, which is the more common case of the two.
        if (!notify) return
        publisher.publish(
            userIds = listOf(req.userId),
            type = NotificationType.GROUP_OWNERSHIP_TRANSFERRED,
            title = "You're now the admin of ${group.name}",
            body = "Tap to open the group",
            payload = mapOf(
                "groupId" to group.id.toString(),
                "groupName" to group.name,
            ),
        )
    }

    @Transactional
    fun uploadAvatar(callerId: Long, groupId: Long, file: org.springframework.web.multipart.MultipartFile): GroupData {
        requireAdmin(groupId, callerId)
        if (file.isEmpty) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Empty file")
        if (file.size > 2L * 1024 * 1024) throw ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Image too large (max 2MB)")
        val sanitized = com.todoapp.backend.common.ImageSanitizer.sanitize(file)
        val group = groups.findById(groupId).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found") }
        group.avatarBytes = sanitized.bytes
        group.avatarContentType = sanitized.contentType
        group.updatedAt = Instant.now()
        return groups.save(group).toData()
    }

    @Transactional(readOnly = true)
    fun getAvatarBytes(callerId: Long, groupId: Long): org.springframework.http.ResponseEntity<ByteArray> {
        requireMember(groupId, callerId)
        val group = groups.findById(groupId).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found") }
        val bytes = group.avatarBytes ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No avatar")
        val type = group.avatarContentType ?: org.springframework.http.MediaType.IMAGE_JPEG_VALUE
        return org.springframework.http.ResponseEntity.ok()
            .contentType(org.springframework.http.MediaType.parseMediaType(type))
            .header("Cache-Control", "public, max-age=300")
            .body(bytes)
    }

    fun requireMember(groupId: Long, userId: Long): GroupMemberEntity =
        members.findByGroupIdAndUserId(groupId, userId)
            ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this group")

    fun requireAdmin(groupId: Long, userId: Long) {
        val membership = requireMember(groupId, userId)
        if (membership.role.uppercase() != GroupRole.ADMIN.name) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Admin only")
        }
    }

    fun GroupEntity.toData(): GroupData {
        val memberships = members.findAllByGroupId(id)
        val memberSummaries = users
            .findAllSummariesByIdIn(memberships.map { it.userId })
            .associateBy { it.id }
        val memberDtos = memberships.mapNotNull { m ->
            memberSummaries[m.userId]?.toMemberDto(m)
        }
        return GroupData(
            id = id,
            name = name,
            description = description,
            avatarUrl = if (avatarBytes != null) "/family-groups/$id/avatar" else null,
            createdAt = createdAt.toEpochMilli(),
            updatedAt = updatedAt.toEpochMilli(),
            members = memberDtos,
        )
    }

    private fun com.todoapp.backend.user.UserSummary.toMemberDto(m: GroupMemberEntity) = GroupMemberData(
        userId = id,
        displayName = displayName,
        email = email,
        avatarUrl = if (hasAvatar) "/users/$id/avatar" else avatarUrl,
        role = m.role,
        joinedAt = m.joinedAt.toEpochMilli(),
    )
}
