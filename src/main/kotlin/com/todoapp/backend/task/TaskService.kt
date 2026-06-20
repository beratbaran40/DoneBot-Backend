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
    private val dailyCompletions: TaskDailyCompletionRepository,
    private val subtaskRepo: TaskSubtaskRepository,
) {
    @Transactional
    fun create(ownerId: Long, req: TaskRequest): TaskData {
        val category = req.category ?: TaskCategory.PERSONAL
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
            category = category,
            customCategoryName = if (category == TaskCategory.OTHER) req.customCategoryName?.takeIf { it.isNotBlank() } else null,
            recurrence = req.recurrence ?: Recurrence.NONE,
            isAllDay = req.isAllDay,
            reminderOffsetMinutes = req.reminderOffsetMinutes,
            locationLat = req.locationLat?.toBigDecimal(),
            locationLng = req.locationLng?.toBigDecimal(),
            locationName = req.locationName?.takeIf { it.isNotBlank() },
            locationAddress = req.locationAddress?.takeIf { it.isNotBlank() },
        )
        val saved = tasks.save(entity)
        req.subtasks?.let { reconcileSubtasks(saved.id, it) }
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
        val newCategory = req.category ?: entity.category
        entity.category = newCategory
        entity.customCategoryName = if (newCategory == TaskCategory.OTHER) {
            req.customCategoryName?.takeIf { it.isNotBlank() }
        } else {
            null
        }
        entity.recurrence = req.recurrence ?: entity.recurrence
        entity.isAllDay = req.isAllDay
        entity.reminderOffsetMinutes = req.reminderOffsetMinutes
        entity.locationLat = req.locationLat?.toBigDecimal()
        entity.locationLng = req.locationLng?.toBigDecimal()
        entity.locationName = req.locationName?.takeIf { it.isNotBlank() }
        entity.locationAddress = req.locationAddress?.takeIf { it.isNotBlank() }
        val saved = tasks.save(entity)
        req.subtasks?.let { reconcileSubtasks(saved.id, it) }
        notifyAssignmentIfNeeded(
            actorId = ownerId,
            previousAssigneeId = previousAssigneeId,
            saved = saved,
        )
        return saved.toData()
    }

    /**
     * Replaces the task's step set with [incoming], preserving server ids where the
     * client supplied a matching [SubtaskRequest.remoteId]. Steps absent from [incoming]
     * are deleted; new ones (null remoteId) are inserted. orderIndex is re-packed to the
     * incoming order so the steps render in the order the client sent them. Called only
     * when the client sends a non-null `subtasks` list (see TaskRequest.subtasks).
     */
    private fun reconcileSubtasks(taskId: Long, incoming: List<SubtaskRequest>) {
        val existing = subtaskRepo.findAllByTaskIdOrderByOrderIndexAsc(taskId)
        val existingById = existing.associateBy { it.id }
        val keptIds = mutableSetOf<Long>()
        incoming.forEachIndexed { index, req ->
            val match = req.remoteId?.let { existingById[it] }
            if (match != null) {
                match.title = req.title
                match.isCompleted = req.isCompleted
                match.orderIndex = index
                subtaskRepo.save(match)
                keptIds.add(match.id)
            } else {
                val created = subtaskRepo.save(
                    TaskSubtaskEntity(
                        taskId = taskId,
                        title = req.title,
                        isCompleted = req.isCompleted,
                        orderIndex = index,
                    ),
                )
                keptIds.add(created.id)
            }
        }
        existing.filter { it.id !in keptIds }.forEach { subtaskRepo.delete(it) }
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
            category = category,
            customCategoryName = customCategoryName,
            recurrence = recurrence,
            isAllDay = isAllDay,
            reminderOffsetMinutes = reminderOffsetMinutes,
            locationLat = locationLat?.toDouble(),
            locationLng = locationLng?.toDouble(),
            locationName = locationName,
            locationAddress = locationAddress,
            photoUrls = urls,
            subtasks = subtaskRepo.findAllByTaskIdOrderByOrderIndexAsc(id).map {
                SubtaskData(id = it.id, title = it.title, isCompleted = it.isCompleted, orderIndex = it.orderIndex)
            },
        )
    }

    @Transactional
    fun setDailyCompletion(callerId: Long, taskId: Long, req: TaskDailyCompletionRequest) {
        val task = tasks.findById(taskId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found")
        }
        if (task.ownerId != callerId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not allowed")
        }
        if (task.recurrence != Recurrence.DAILY) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Task is not a daily task")
        }
        if (req.completed) {
            val existing = dailyCompletions.findByTaskIdAndDate(taskId, req.date)
            if (existing == null) {
                dailyCompletions.save(
                    TaskDailyCompletionEntity(
                        taskId = taskId,
                        userId = callerId,
                        date = req.date,
                        completedAt = System.currentTimeMillis(),
                    ),
                )
            }
        } else {
            dailyCompletions.deleteByTaskIdAndDate(taskId, req.date)
        }
    }

    @Transactional(readOnly = true)
    fun listDailyCompletions(callerId: Long, fromDay: Long, toDay: Long): TaskDailyCompletionListData {
        val items = dailyCompletions.findAllByUserIdAndDateBetween(callerId, fromDay, toDay)
            .map { TaskDailyCompletionData(taskId = it.taskId, date = it.date, completedAt = it.completedAt) }
        return TaskDailyCompletionListData(items, items.size)
    }
}
