package com.todoapp.backend.task

import com.todoapp.backend.notif.NotificationPublisher
import com.todoapp.backend.notif.inbox.NotificationType
import com.todoapp.backend.user.UserRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

@Service
class TaskService(
    private val tasks: TaskRepository,
    private val users: UserRepository,
    private val photos: TaskPhotoRepository,
    private val publisher: NotificationPublisher,
) {
    @Transactional
    fun create(ownerId: Long, req: TaskRequest): TaskData {
        val entity = TaskEntity(
            ownerId = ownerId,
            title = req.title,
            description = req.description,
            date = req.date,
            timeStart = req.timeStart,
            timeEnd = req.timeEnd,
            isCompleted = req.isCompleted,
            isSecret = req.isSecret,
            familyGroupId = req.familyGroupId,
            assignedToUserId = req.assignedToUserId,
            priority = req.priority,
        )
        val saved = tasks.save(entity)
        notifyAssignmentIfNeeded(
            actorId = ownerId,
            previousAssigneeId = null,
            saved = saved,
        )
        return saved.toData()
    }

    @Transactional
    fun update(ownerId: Long, req: TaskRequest): TaskData {
        val id = req.id ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "id required for update")
        val entity = tasks.findById(id).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found")
        }
        if (entity.ownerId != ownerId && entity.assignedToUserId != ownerId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not allowed")
        }
        val previousAssigneeId = entity.assignedToUserId
        entity.title = req.title
        entity.description = req.description
        entity.date = req.date
        entity.timeStart = req.timeStart
        entity.timeEnd = req.timeEnd
        entity.isCompleted = req.isCompleted
        entity.isSecret = req.isSecret
        entity.priority = req.priority
        // assignedToUserId: null = clear, non-null = set. (For personal tasks this is rare.)
        entity.assignedToUserId = req.assignedToUserId
        val saved = tasks.save(entity)
        notifyAssignmentIfNeeded(
            actorId = ownerId,
            previousAssigneeId = previousAssigneeId,
            saved = saved,
        )
        return saved.toData()
    }

    /**
     * Fires TASK_ASSIGNED to the new assignee when a group task gets a different assignee.
     * Mirrors GroupTaskService — the Android client currently routes group-task creates through
     * POST /tasks (with familyGroupId set), bypassing the dedicated /family-groups endpoint, so
     * the assignment notification must also fire from this path.
     */
    private fun notifyAssignmentIfNeeded(
        actorId: Long,
        previousAssigneeId: Long?,
        saved: TaskEntity,
    ) {
        val newAssigneeId = saved.assignedToUserId ?: return
        val groupId = saved.familyGroupId ?: return
        if (newAssigneeId == previousAssigneeId) return
        if (newAssigneeId == actorId) return
        publisher.publish(
            userIds = listOf(newAssigneeId),
            type = NotificationType.TASK_ASSIGNED,
            title = "New task assigned",
            body = "${saved.title} was assigned to you",
            payload = mapOf(
                "taskId" to saved.id.toString(),
                "groupId" to groupId.toString(),
                "taskTitle" to saved.title,
            ),
        )
    }

    @Transactional(readOnly = true)
    fun getById(callerId: Long, taskId: Long): TaskData {
        val task = tasks.findById(taskId).orElseThrow {
            org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "Task not found"
            )
        }
        // Permission: owner for personal; any member for group tasks (enforced by familyGroupId presence — members see shared tasks via the group endpoints). Here we require the caller to own the task.
        if (task.ownerId != callerId && task.assignedToUserId != callerId) {
            throw org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.FORBIDDEN, "Not allowed"
            )
        }
        return task.toData()
    }

    @Transactional(readOnly = true)
    fun list(ownerId: Long, familyGroupId: Long?): TaskListData {
        val list = if (familyGroupId == null) {
            tasks.findAllByOwnerIdAndFamilyGroupIdIsNull(ownerId)
        } else {
            tasks.findAllByFamilyGroupId(familyGroupId)
        }
        val items = list.map { it.toData() }
        return TaskListData(items, items.size)
    }

    @Transactional
    fun delete(ownerId: Long, taskId: Long) {
        val entity = tasks.findById(taskId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found")
        }
        if (entity.ownerId != ownerId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not allowed")
        }
        tasks.delete(entity)
    }

    private fun TaskEntity.toData(): TaskData {
        val assignee = assignedToUserId?.let { uid ->
            users.findSummaryById(uid)?.let { TaskUserData(it.id, it.displayName) }
        }
        val creator = users.findSummaryById(ownerId)?.let { TaskUserData(it.id, it.displayName) }
        val urls = photos.findAllByTaskIdOrderByCreatedAtAsc(id).map { "/tasks/$id/photos/${it.id}" }
        return TaskData(
            id = id,
            title = title,
            description = description,
            date = date,
            timeStart = timeStart,
            timeEnd = timeEnd,
            isCompleted = isCompleted,
            isSecret = isSecret,
            assignedTo = assignee,
            createdBy = creator,
            familyGroupId = familyGroupId,
            priority = priority,
            photoUrls = urls,
        )
    }
}
