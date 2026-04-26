package com.todoapp.backend.group

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
) {
    private fun logActivity(
        groupId: Long,
        actorUserId: Long,
        type: GroupActivityType,
        description: String,
    ) {
        activities.save(
            GroupActivityEntity(
                groupId = groupId,
                actorUserId = actorUserId,
                type = type.name,
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
        val mine = members.findAllByUserId(userId)
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
        return groups.findById(groupId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found")
        }.toData()
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
        members.findAllByGroupId(groupId).forEach { members.delete(it) }
        tasks.findAllByFamilyGroupId(groupId).forEach { tasks.delete(it) }
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
        logActivity(groupId, userId, GroupActivityType.MEMBER_REMOVED, "Removed $name from the group")
    }

    @Transactional
    fun transferOwnership(userId: Long, groupId: Long, req: TransferOwnershipRequest) {
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
        logActivity(groupId, userId, GroupActivityType.OWNERSHIP_TRANSFERRED, "Transferred ownership to $newOwnerName")
    }

    @Transactional
    fun uploadAvatar(callerId: Long, groupId: Long, file: org.springframework.web.multipart.MultipartFile): GroupData {
        requireAdmin(groupId, callerId)
        if (file.isEmpty) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Empty file")
        val ct = file.contentType ?: "application/octet-stream"
        if (!ct.startsWith("image/")) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "File must be an image")
        if (file.size > 2L * 1024 * 1024) throw ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Image too large (max 2MB)")
        val group = groups.findById(groupId).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found") }
        group.avatarBytes = file.bytes
        group.avatarContentType = ct
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
