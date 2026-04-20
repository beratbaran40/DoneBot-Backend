package com.todoapp.backend.task

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
        return tasks.save(entity).toData()
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
        return tasks.save(entity).toData()
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
            users.findById(uid).orElse(null)?.let { TaskUserData(it.id, it.displayName) }
        }
        val creator = users.findById(ownerId).orElse(null)?.let { TaskUserData(it.id, it.displayName) }
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
