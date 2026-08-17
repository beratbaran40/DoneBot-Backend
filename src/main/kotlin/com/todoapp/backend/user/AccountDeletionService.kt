package com.todoapp.backend.user

import com.todoapp.backend.group.GroupMemberRepository
import com.todoapp.backend.group.GroupRepository
import com.todoapp.backend.group.GroupRole
import com.todoapp.backend.group.GroupService
import com.todoapp.backend.group.TransferOwnershipRequest
import com.todoapp.backend.task.TaskRepository
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
 * Deletes a user account and orchestrates the parts that can't be a blind DB cascade:
 *  - Owned groups: ownership transfers to a random member (ADMIN-preferred); if the user is the only
 *    member, the group is deleted entirely.
 *  - Personal tasks (owner + no group) are deleted; group tasks the user created stay with the group.
 *
 * Everything else is removed automatically by the ON DELETE CASCADE / SET NULL foreign keys added in
 * Flyway V14: deleting the user row cascades group_members, device_tokens, refresh_tokens, notifications,
 * password_reset_tokens, chat_reports, user_preferences, group_activities and group_invitations, and
 * unassigns the user from any group tasks (assigned_to_user_id -> NULL). Deleting a group/task cascades
 * its task_photos. V30 adds pomodoro_sessions to that cascade — no code here, but it belongs on this
 * list, which is the checklist someone reads to answer "is anything left orphaned?". So no rows are left
 * orphaned (§4.20 / §4.19).
 */
@Service
class AccountDeletionService(
    private val users: UserRepository,
    private val groups: GroupRepository,
    private val members: GroupMemberRepository,
    private val tasks: TaskRepository,
    private val groupService: GroupService,
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
                    // The caller notifies after this transaction commits; see the flag's docstring.
                    notify = false,
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

        // Personal tasks only — group tasks the user created stay with the group; their assignment to
        // this user is dropped to NULL by the assigned_to_user_id SET NULL FK when the user row goes.
        tasks.findAllByOwnerIdAndFamilyGroupIdIsNull(userId).forEach { tasks.delete(it) }

        // The user row: the ON DELETE CASCADE / SET NULL FKs (V14) fan out to every child table.
        users.deleteById(userId)
        log.info("deleteAccount: user=$userId removed (transferred=${transferredGroups.size})")

        return DeleteAccountResult(transferredGroups = transferredGroups)
    }
}
