package com.todoapp.backend.group

import com.todoapp.backend.task.Recurrence
import com.todoapp.backend.task.SubtaskData
import com.todoapp.backend.task.SubtaskRequest
import com.todoapp.backend.task.TaskCategory
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

/**
 * A user report of offensive/inappropriate group content: a member (`targetType = MEMBER`,
 * `targetUserId` set), a shared task photo, or a task (`targetType = PHOTO`/`TASK`, `targetRef` set).
 * Backs the in-app "Report" action required by Google Play's UGC policy; recorded server-side for
 * manual moderation review. Blocking a user is handled client-side.
 */
data class ReportContentRequest(
    @field:NotBlank val targetType: String,
    val targetUserId: Long? = null,
    @field:jakarta.validation.constraints.Size(max = 512) val targetRef: String? = null,
    @field:jakarta.validation.constraints.Size(max = 500) val reason: String? = null,
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
    // Outgoing invites that are still PENDING — rides along on the detail payload so the members
    // tab shows "invited" rows without an extra round-trip. Old clients ignore unknown keys.
    val pendingInvitations: List<GroupInvitationData> = emptyList(),
)

// Slim, members-tab-scoped projection of an invitation (no inviter/group decoration on purpose).
data class GroupInvitationData(
    val id: Long,
    val inviteeEmail: String,
    val status: String,
    val createdAt: Long,
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
    /**
     * Recurrence rule and steps. A group task is a `TaskEntity` like any other, so these have
     * always been storable — only this DTO stopped them from being editable. Null on every field
     * means "no change", matching the rest of this request.
     */
    val recurrence: Recurrence? = null,
    val recurrenceInterval: Int? = null,
    @field:jakarta.validation.constraints.Size(max = 64) val recurrenceByDay: String? = null,
    val recurrenceUntil: Long? = null,
    /**
     * Same JSON-can't-distinguish-null trick as `clearAssignee` and `clearLocation`: set true to make
     * the routine open-ended again.
     *
     * Without it a scheduled end could be set and moved but never removed, since null here means "no
     * change". That left the client unable to offer "No end" at all, and forced it to drag the end
     * forward whenever the user moved the start past it — `firesOn` rejects every day outside a
     * crossed pair, so the alternative was a routine that saves and then fires on no day at all.
     */
    val clearRecurrenceUntil: Boolean = false,
    /** Absolute reminder times as SECOND-of-day. */
    val reminderTimes: List<Int>? = null,
    val category: TaskCategory? = null,
    @field:jakarta.validation.constraints.Size(max = 64) val customCategoryName: String? = null,
    /** Non-null replaces the whole step set; null leaves the existing steps untouched. */
    val subtasks: List<SubtaskRequest>? = null,
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
    /**
     * Full task shape, so a group task can be everything a personal one can. The defaults describe
     * the flat, non-repeating task group tasks used to be limited to.
     */
    val isAllDay: Boolean = false,
    val category: TaskCategory = TaskCategory.PERSONAL,
    val customCategoryName: String? = null,
    val recurrence: Recurrence = Recurrence.NONE,
    val recurrenceInterval: Int = 1,
    val recurrenceByDay: String? = null,
    val recurrenceUntil: Long? = null,
    val reminderTimes: List<Int> = emptyList(),
    val subtasks: List<SubtaskData> = emptyList(),
    /**
     * The shape declared when the task was created; null = never declared, so the client derives it.
     *
     * Carried here so every member sees the same badge. A group task is the same `TaskEntity` as a
     * personal one, so the column has been available all along — and serving it from the server is
     * the only way the declaration can be shared, since the client's group cache is wiped and
     * re-inserted wholesale on every sync.
     */
    val declaredType: String? = null,
)

data class GroupTaskListData(
    val tasks: List<GroupTaskData>,
    val count: Int,
)
