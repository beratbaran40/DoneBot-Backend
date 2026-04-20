package com.todoapp.backend.task

import jakarta.validation.constraints.NotBlank

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
    val photoUrls: List<String> = emptyList(),
)

data class TaskListData(
    val tasks: List<TaskData>,
    val count: Int,
)
