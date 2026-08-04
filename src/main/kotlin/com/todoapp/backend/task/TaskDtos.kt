package com.todoapp.backend.task

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class TaskRequest(
    val id: Long? = null,
    @field:NotBlank @field:Size(max = 255) val title: String,
    @field:Size(max = 2000) val description: String? = null,
    val date: Long,
    val timeStart: Long,
    val timeEnd: Long,
    val isCompleted: Boolean = false,
    val isSecret: Boolean = false,
    val familyGroupId: Long? = null,
    val assignedToUserId: Long? = null,
    val priority: String? = null,
    val category: TaskCategory? = null,
    @field:Size(max = 64) val customCategoryName: String? = null,
    val recurrence: Recurrence? = null,
    val isAllDay: Boolean = false,
    val reminderOffsetMinutes: Long = 0L,
    val locationLat: Double? = null,
    val locationLng: Double? = null,
    @field:Size(max = 120) val locationName: String? = null,
    @field:Size(max = 500) val locationAddress: String? = null,
    val finishedOn: Long? = null,
    /** Client-generated idempotency key (UUID) for create dedup; null for old clients. §4.12 */
    val clientTaskId: String? = null,
    /**
     * Ordered steps of a staged task. `null` = leave existing steps untouched (chat's
     * updateTask and non-staged clients send null); a list = reconcile the step set
     * (match by [SubtaskRequest.remoteId], insert new, delete missing). The client only
     * sends a non-null list for tasks that actually have steps, so an empty list never
     * accidentally wipes another device's steps.
     */
    val subtasks: List<SubtaskRequest>? = null,
    /** RRULE INTERVAL. Only applied when [recurrenceRuleSet] is true. */
    val recurrenceInterval: Int? = null,
    /** RRULE BYDAY as a DayOfWeek-name CSV. Only applied when [recurrenceRuleSet] is true. */
    @field:Size(max = 64) val recurrenceByDay: String? = null,
    /** RRULE UNTIL as an epoch day. Only applied when [recurrenceRuleSet] is true. */
    val recurrenceUntil: Long? = null,
    /** Absolute reminder times as SECOND-of-day. Only applied when [recurrenceRuleSet] is true. */
    val reminderTimes: List<Int>? = null,
    /**
     * True when the sender understands the extended recurrence rule and the four fields above carry
     * its authoritative state — **including nulls, which then clear the stored value**.
     *
     * Old clients omit it (false) and their nulls are ignored rather than wiping a rule they cannot
     * represent. This flag exists because [TaskService.update] overwrites most fields unconditionally,
     * so without it a stale second device would silently erase the user's "for one month" end date on
     * its next sync. The `?: entity.x` trick used for `recurrence` is not enough here: it makes the
     * value un-clearable, and clearing an end date is a thing users legitimately do.
     */
    val recurrenceRuleSet: Boolean = false,
)

data class SubtaskRequest(
    /** Server id of an existing step, or null for a step created on the client. */
    val remoteId: Long? = null,
    @field:NotBlank @field:Size(max = 255) val title: String,
    val isCompleted: Boolean = false,
    val orderIndex: Int = 0,
)

data class TaskUserData(
    val userId: Long,
    val displayName: String,
)

data class TaskData(
    val id: Long,
    val title: String,
    val description: String?,
    val date: Long,
    val timeStart: Long,
    val timeEnd: Long,
    val isCompleted: Boolean,
    val isSecret: Boolean,
    val assignedTo: TaskUserData?,
    val createdBy: TaskUserData?,
    val familyGroupId: Long?,
    val priority: String?,
    val category: TaskCategory,
    val customCategoryName: String?,
    val recurrence: Recurrence,
    val isAllDay: Boolean = false,
    val reminderOffsetMinutes: Long = 0L,
    val locationLat: Double? = null,
    val locationLng: Double? = null,
    val locationName: String? = null,
    val locationAddress: String? = null,
    val finishedOn: Long? = null,
    /** Echoed back so the client can reconcile a PENDING_CREATE row to its server row by exact key. §4.12 */
    val clientTaskId: String? = null,
    val photoUrls: List<String> = emptyList(),
    /** Ordered steps of a staged task. Empty for a plain task. */
    val subtasks: List<SubtaskData> = emptyList(),
    /** Extended recurrence rule. The defaults describe the legacy "every period, forever" routine. */
    val recurrenceInterval: Int = 1,
    val recurrenceByDay: String? = null,
    val recurrenceUntil: Long? = null,
    /** Absolute reminder times as SECOND-of-day; empty = the single reminderOffsetMinutes reminder. */
    val reminderTimes: List<Int> = emptyList(),
)

data class SubtaskData(
    val id: Long,
    val title: String,
    val isCompleted: Boolean,
    val orderIndex: Int,
)

data class TaskDailyCompletionRequest(
    val date: Long,
    val completed: Boolean,
)

data class TaskDailyCompletionData(
    val taskId: Long,
    val date: Long,
    val completedAt: Long,
)

data class TaskDailyCompletionListData(
    val items: List<TaskDailyCompletionData>,
    val count: Int,
)

data class TaskListData(
    val tasks: List<TaskData>,
    val count: Int,
)
