package com.todoapp.backend.group

import com.todoapp.backend.user.UserRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class GroupActivityData(
    val id: Long,
    val type: String,
    val actorName: String,
    val actorAvatarUrl: String?,
    val description: String,
    val timestamp: Long,
    val taskTitle: String?,
)

data class GroupActivityListData(
    val activities: List<GroupActivityData>,
    val count: Int,
)

@Service
class GroupActivityService(
    private val activities: GroupActivityRepository,
    private val users: UserRepository,
    private val groupService: GroupService,
) {
    @Transactional
    fun log(
        groupId: Long,
        actorUserId: Long,
        type: GroupActivityType,
        description: String,
        taskId: Long? = null,
        taskTitle: String? = null,
    ) {
        activities.save(
            GroupActivityEntity(
                groupId = groupId,
                actorUserId = actorUserId,
                type = type.name,
                taskId = taskId,
                taskTitle = taskTitle,
                description = description,
            )
        )
    }

    @Transactional(readOnly = true)
    fun list(callerId: Long, groupId: Long): GroupActivityListData {
        groupService.requireMember(groupId, callerId)
        val items = activities.findAllByGroupIdOrderByTimestampDesc(groupId, PageRequest.of(0, 100))
        val dtos = items.map { e ->
            val actor = users.findById(e.actorUserId).orElse(null)
            GroupActivityData(
                id = e.id,
                type = e.type,
                actorName = actor?.displayName ?: "Unknown",
                actorAvatarUrl = actor?.let { if (it.avatarBytes != null) "/users/${it.id}/avatar" else it.avatarUrl },
                description = e.description,
                timestamp = e.timestamp.toEpochMilli(),
                taskTitle = e.taskTitle,
            )
        }
        return GroupActivityListData(dtos, dtos.size)
    }
}
