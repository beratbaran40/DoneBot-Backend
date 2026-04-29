package com.todoapp.backend.user

import com.todoapp.backend.auth.RefreshTokenRepository
import com.todoapp.backend.group.GroupMemberRepository
import com.todoapp.backend.group.GroupRepository
import com.todoapp.backend.group.GroupRole
import com.todoapp.backend.group.GroupService
import com.todoapp.backend.group.TransferOwnershipRequest
import com.todoapp.backend.notif.DeviceTokenRepository
import com.todoapp.backend.task.TaskRepository
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

/**
 * Carries the result of account deletion so the caller can dispatch
 * post-commit notifications to new group owners.
 */
data class DeleteAccountResult(
    val transferredGroups: List<TransferredGroup>,
)

data class TransferredGroup(
    val groupId: Long,
    val groupName: String,
    val newOwnerId: Long,
)

/**
 * Deletes a user account and orchestrates fan-out cleanup:
 *  - Owned groups: ownership transfers to a random member (ADMIN-preferred);
 *    if the user is the only member, the group is deleted entirely.
 *  - Personal tasks, group memberships, device tokens, refresh tokens, and the
 *    user row are deleted in the same transaction.
 *  - Group task assignments held by the user are unassigned (assignedToUserId = null).
 *  - Returns the list of transferred groups so the caller can FCM-notify the new owners
 *    after the transaction commits.
 *
 * Note: rows in tables that don't have a foreign key back to `users` (notifications inbox,
 * user_preferences, group_invitations, group_activities) are left orphaned. They become
 * unreachable once the user row is gone. A separate housekeeping job can sweep them
 * later if needed.
 */
@Service
class AccountDeletionService(
    private val users: UserRepository,
    private val groups: GroupRepository,
    private val members: GroupMemberRepository,
    private val tasks: TaskRepository,
    private val deviceTokens: DeviceTokenRepository,
    private val refreshTokens: RefreshTokenRepository,
    private val groupService: GroupService,
    @PersistenceContext private val em: EntityManager,
) {
    private val log = LoggerFactory.getLogger(AccountDeletionService::class.java)

    @Transactional
    fun deleteAccount(userId: Long): DeleteAccountResult {
        users.findById(userId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")
        }

        val transferredGroups = mutableListOf<TransferredGroup>()
        val ownedGroups = groups.findAllByOwnerId(userId)
        ownedGroups.forEach { group ->
            val candidates = members.findAllByGroupId(group.id).filter { it.userId != userId }
            if (candidates.isEmpty()) {
                groupService.delete(userId, group.id)
                log.info("deleteAccount: deleted solo group=${group.id} for user=$userId")
            } else {
                val pool = candidates
                    .filter { it.role == GroupRole.ADMIN.name }
                    .ifEmpty { candidates }
                val newOwner = pool.random()
                groupService.transferOwnership(
                    userId = userId,
                    groupId = group.id,
                    req = TransferOwnershipRequest(userId = newOwner.userId),
                )
                transferredGroups += TransferredGroup(
                    groupId = group.id,
                    groupName = group.name,
                    newOwnerId = newOwner.userId,
                )
                log.info(
                    "deleteAccount: transferred group=${group.id} to user=${newOwner.userId} for deleted user=$userId",
                )
            }
        }

        // Remaining group memberships (where user is just a member, not owner).
        members.findAllByUserId(userId).forEach { members.delete(it) }

        // Personal tasks (group tasks were handled inside groupService.delete or stay with the group).
        tasks.findAllByOwnerIdAndFamilyGroupIdIsNull(userId).forEach { tasks.delete(it) }

        // Unassign group tasks where this user was the assignee — keep the task, drop the assignment.
        em.createQuery(
            "UPDATE TaskEntity t SET t.assignedToUserId = NULL WHERE t.assignedToUserId = :uid",
        ).setParameter("uid", userId).executeUpdate()

        // Device tokens & refresh tokens.
        deviceTokens.findAllByUserId(userId).forEach { deviceTokens.delete(it) }
        refreshTokens.revokeAllByUserId(userId)

        // Final user delete.
        users.deleteById(userId)
        log.info("deleteAccount: user=$userId removed (transferred=${transferredGroups.size})")

        return DeleteAccountResult(transferredGroups = transferredGroups)
    }
}
