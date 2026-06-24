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
    val locationLat: Double? = null,
    val locationLng: Double? = null,
    @field:jakarta.validation.constraints.Size(max = 120) val locationName: String? = null,
    @field:jakarta.validation.constraints.Size(max = 500) val locationAddress: String? = null,
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
    /** Whole-day flag; null = no change. When set, [timeStart]/[timeEnd] are still honoured if sent. */
    val isAllDay: Boolean? = null,
    /** Seconds since midnight; null = no change. */
    val timeStart: Long? = null,
    /** Seconds since midnight; null = no change. */
    val timeEnd: Long? = null,
    val isCompleted: Boolean? = null,
    val priority: String? = null,
    val assigneeId: Long? = null,
    val clearAssignee: Boolean = false,
    val locationLat: Double? = null,
    val locationLng: Double? = null,
    @field:jakarta.validation.constraints.Size(max = 120) val locationName: String? = null,
    @field:jakarta.validation.constraints.Size(max = 500) val locationAddress: String? = null,
    /**
     * Same JSON-can't-distinguish-null trick as `clearAssignee`. Set true to wipe all four
     * location fields in one request; leave false to keep them as-is (or to overwrite via
     * `locationName`/`locationLat` etc. if those are non-null).
     */
    val clearLocation: Boolean = false,
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
    val locationLat: Double? = null,
    val locationLng: Double? = null,
    val locationName: String? = null,
    val locationAddress: String? = null,
)

data class GroupTaskListData(
    val tasks: List<GroupTaskData>,
    val count: Int,
)
