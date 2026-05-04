package com.todoapp.backend.task

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class TaskRequest(
    val id: Long? = null,
    @field:NotBlank val title: String,
    val description: String? = null,
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
    val photoUrls: List<String> = emptyList(),
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
