package com.todoapp.backend.group

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank

data class CreateGroupRequest(
    @field:NotBlank val name: String,
    val description: String = "",
)

data class UpdateGroupRequest(
    val id: Long,
    @field:NotBlank val name: String,
    val description: String = "",
)

data class InviteMemberRequest(
    val groupId: Long,
    @field:Email val email: String,
)

data class TransferOwnershipRequest(
    val userId: Long,
)

data class GroupMemberData(
    val userId: Long,
    val displayName: String,
    val email: String,
    val avatarUrl: String?,
    val role: String,
    val joinedAt: Long,
)

data class GroupData(
    val id: Long,
    val name: String,
    val description: String,
    val avatarUrl: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val members: List<GroupMemberData>,
)

data class GroupSummaryData(
    val id: Long,
    val name: String,
    val description: String,
    val avatarUrl: String?,
    val role: String,
    val memberCount: Int,
    val pendingTaskCount: Int,
    val createdAt: Long,
)

data class GroupSummaryListData(
    @com.fasterxml.jackson.annotation.JsonProperty("familyGroups")
    val groups: List<GroupSummaryData>,
    val count: Int,
)

// --- Group tasks ---

data class GroupTaskRequest(
    @field:NotBlank val title: String,
    val description: String? = null,
    val dueDate: Long,
    val isCompleted: Boolean = false,
    val priority: String? = null,
    val assigneeId: Long? = null,
)

/**
 * Update payload for an existing group task. `assigneeId` follows clear semantics:
 *  - field omitted entirely (null in Kotlin, missing in JSON) → "no change to assignment"
 *  - explicit `{ "assigneeId": null }` AND `clearAssignee = true` → unassign
 *  - explicit user id → assign to that user
 *
 * The `clearAssignee` flag exists because JSON can't distinguish "missing" from "null".
 */
data class GroupTaskUpdateRequest(
    val title: String? = null,
    val description: String? = null,
    val dueDate: Long? = null,
    val isCompleted: Boolean? = null,
    val priority: String? = null,
    val assigneeId: Long? = null,
    val clearAssignee: Boolean = false,
)

data class GroupTaskData(
    val id: Long,
    val title: String,
    val description: String?,
    val isCompleted: Boolean,
    val priority: String?,
    val dueDate: Long?,
    val assignee: GroupMemberData?,
    val photoUrls: List<String> = emptyList(),
)

data class GroupTaskListData(
    val tasks: List<GroupTaskData>,
    val count: Int,
)
