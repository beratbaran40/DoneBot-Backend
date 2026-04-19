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
            val group = groups.findById(membership.groupId).orElseThrow {
                ResponseStatusException(HttpStatus.NOT_FOUND, "Group ${membership.groupId} not found")
            }
            GroupSummaryData(
                id = group.id,
                name = group.name,
                description = group.description,
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

    @Transactional
    fun invite(userId: Long, req: InviteMemberRequest) {
        requireAdmin(req.groupId, userId)
        val invitee = users.findByEmail(req.email)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "User with email not found")
        if (members.findByGroupIdAndUserId(req.groupId, invitee.id) != null) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "User already a member")
        }
        members.save(GroupMemberEntity(groupId = req.groupId, userId = invitee.id, role = GroupRole.MEMBER.name))
        logActivity(req.groupId, userId, GroupActivityType.MEMBER_ADDED, "Added ${invitee.displayName} to the group")
    }

    @Transactional
    fun removeMember(userId: Long, groupId: Long, memberUserId: Long) {
        requireAdmin(groupId, userId)
        if (memberUserId == userId) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Use transfer-ownership to leave as admin")
        }
        val name = users.findById(memberUserId).orElse(null)?.displayName ?: "user"
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
        val newOwnerName = users.findById(req.userId).orElse(null)?.displayName ?: "user"
        logActivity(groupId, userId, GroupActivityType.OWNERSHIP_TRANSFERRED, "Transferred ownership to $newOwnerName")
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
        val memberDtos = members.findAllByGroupId(id).mapNotNull { m ->
            users.findById(m.userId).orElse(null)?.let { u -> u.toMemberDto(m) }
        }
        return GroupData(
            id = id,
            name = name,
            description = description,
            createdAt = createdAt.toEpochMilli(),
            updatedAt = updatedAt.toEpochMilli(),
            members = memberDtos,
        )
    }

    private fun UserEntity.toMemberDto(m: GroupMemberEntity) = GroupMemberData(
        userId = id,
        displayName = displayName,
        email = email,
        avatarUrl = avatarUrl,
        role = m.role,
        joinedAt = m.joinedAt.toEpochMilli(),
    )
}
