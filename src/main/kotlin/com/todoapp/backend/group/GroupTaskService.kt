package com.todoapp.backend.group

import com.todoapp.backend.task.TaskEntity
import com.todoapp.backend.task.TaskPhotoRepository
import com.todoapp.backend.task.TaskRepository
import com.todoapp.backend.user.UserRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Service
class GroupTaskService(
    private val tasks: TaskRepository,
    private val members: GroupMemberRepository,
    private val users: UserRepository,
    private val groupService: GroupService,
    private val activity: GroupActivityService,
    private val push: com.todoapp.backend.notif.PushService,
    private val photos: TaskPhotoRepository,
) {
    @Transactional(readOnly = true)
    fun list(callerId: Long, groupId: Long): GroupTaskListData {
        groupService.requireMember(groupId, callerId)
        val items = tasks.findAllByFamilyGroupId(groupId).map { it.toDto() }
        return GroupTaskListData(items, items.size)
    }

    @Transactional
    fun create(callerId: Long, groupId: Long, req: GroupTaskRequest): GroupTaskData {
        groupService.requireMember(groupId, callerId)
        if (req.assigneeId != null) requireMemberOfGroup(groupId, req.assigneeId)
        val (date, timeStart) = splitDueDate(req.dueDate)
        val entity = tasks.save(
            TaskEntity(
                ownerId = callerId,
                title = req.title,
                description = req.description,
                date = date,
                timeStart = timeStart,
                timeEnd = timeStart,
                isCompleted = req.isCompleted,
                isSecret = false,
                familyGroupId = groupId,
                assignedToUserId = req.assigneeId,
                priority = req.priority,
            )
        )
        activity.log(groupId, callerId, GroupActivityType.TASK_CREATED,
            description = "Created task “${entity.title}”", taskId = entity.id, taskTitle = entity.title)
        if (entity.assignedToUserId != null) {
            val assigneeName = users.findById(entity.assignedToUserId!!).orElse(null)?.displayName ?: "?"
            activity.log(groupId, callerId, GroupActivityType.TASK_ASSIGNED,
                description = "Assigned “${entity.title}” to $assigneeName",
                taskId = entity.id, taskTitle = entity.title)
            push.sendToUsers(
                listOf(entity.assignedToUserId!!),
                title = "New task assigned",
                body = "${entity.title} was assigned to you",
                data = mapOf("taskId" to entity.id.toString(), "groupId" to groupId.toString()),
            )
        }
        return entity.toDto()
    }

    @Transactional
    fun update(callerId: Long, groupId: Long, taskId: Long, req: GroupTaskUpdateRequest): GroupTaskData {
        val membership = groupService.requireMember(groupId, callerId)
        val isAdmin = membership.role.uppercase() == GroupRole.ADMIN.name
        val task = tasks.findById(taskId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found")
        }
        if (task.familyGroupId != groupId) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Task not in this group")
        }
        val isAssignee = task.assignedToUserId == callerId

        // Permission rules:
        // - Editing fields (title/description/dueDate/priority): admin only.
        // - Reassigning to another user: admin only.
        // - Self-unassign (clearAssignee when caller is the current assignee): allowed.
        // - Self-assign (assigneeId == callerId on an unassigned task): allowed.
        // - Toggling isCompleted: admin OR the current assignee.
        val editingFields = req.title != null || req.description != null ||
            req.dueDate != null || req.priority != null
        if (editingFields && !isAdmin) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Only admins can edit tasks")
        }
        if (req.clearAssignee && !isAdmin && !isAssignee) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Only admins or the current assignee can unassign")
        }
        if (req.assigneeId != null && !isAdmin && req.assigneeId != callerId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Only admins can assign tasks to other members")
        }
        if (req.isCompleted != null && !isAdmin && !isAssignee) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Only the assignee or an admin can complete this task")
        }

        val priorAssignee = task.assignedToUserId
        val priorCompleted = task.isCompleted
        req.title?.let { task.title = it }
        req.description?.let { task.description = it }
        req.dueDate?.let {
            val (d, t) = splitDueDate(it)
            task.date = d; task.timeStart = t; task.timeEnd = t
        }
        req.isCompleted?.let { task.isCompleted = it }
        req.priority?.let { task.priority = it }
        when {
            req.clearAssignee -> task.assignedToUserId = null
            req.assigneeId != null -> {
                requireMemberOfGroup(groupId, req.assigneeId)
                task.assignedToUserId = req.assigneeId
            }
        }
        val saved = tasks.save(task)
        // Activity logging
        if (priorAssignee != saved.assignedToUserId) {
            if (saved.assignedToUserId == null) {
                activity.log(groupId, callerId, GroupActivityType.TASK_UNASSIGNED,
                    description = "Unassigned “${saved.title}”", taskId = saved.id, taskTitle = saved.title)
            } else {
                val name = users.findById(saved.assignedToUserId!!).orElse(null)?.displayName ?: "?"
                activity.log(groupId, callerId, GroupActivityType.TASK_ASSIGNED,
                    description = "Assigned “${saved.title}” to $name", taskId = saved.id, taskTitle = saved.title)
                push.sendToUsers(
                    listOf(saved.assignedToUserId!!),
                    title = "Task assigned to you",
                    body = "${saved.title} was assigned to you",
                    data = mapOf("taskId" to saved.id.toString(), "groupId" to groupId.toString()),
                )
            }
        }
        if (!priorCompleted && saved.isCompleted) {
            activity.log(groupId, callerId, GroupActivityType.TASK_COMPLETED,
                description = "Completed “${saved.title}”", taskId = saved.id, taskTitle = saved.title)
        } else if (req.title != null || req.description != null || req.dueDate != null || req.priority != null) {
            activity.log(groupId, callerId, GroupActivityType.TASK_UPDATED,
                description = "Updated “${saved.title}”", taskId = saved.id, taskTitle = saved.title)
        }
        return saved.toDto()
    }

    @Transactional
    fun delete(callerId: Long, groupId: Long, taskId: Long) {
        val membership = groupService.requireMember(groupId, callerId)
        val task = tasks.findById(taskId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found")
        }
        if (task.familyGroupId != groupId) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Task not in this group")
        }
        val isAdmin = membership.role.uppercase() == GroupRole.ADMIN.name
        if (!isAdmin && task.ownerId != callerId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Only admins or task creator can delete")
        }
        val title = task.title
        tasks.delete(task)
        activity.log(groupId, callerId, GroupActivityType.TASK_DELETED,
            description = "Deleted “$title”", taskId = taskId, taskTitle = title)
    }

    private fun requireMemberOfGroup(groupId: Long, userId: Long) {
        if (members.findByGroupIdAndUserId(groupId, userId) == null) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Assignee is not a group member")
        }
    }

    private fun splitDueDate(epochMs: Long): Pair<Long, Long> {
        val zdt = Instant.ofEpochMilli(epochMs).atZone(ZoneId.of("UTC"))
        val date = zdt.toLocalDate().toEpochDay()
        val timeStart = zdt.toLocalTime().toSecondOfDay().toLong()
        return date to timeStart
    }

    private fun joinDueDate(date: Long, timeStart: Long): Long {
        val ld = LocalDate.ofEpochDay(date).atStartOfDay(ZoneId.of("UTC"))
        return ld.plusSeconds(timeStart).toInstant().toEpochMilli()
    }

    private fun TaskEntity.toDto(): GroupTaskData {
        val assignee = assignedToUserId?.let { uid ->
            val u = users.findById(uid).orElse(null) ?: return@let null
            val m = members.findByGroupIdAndUserId(familyGroupId!!, uid)
            GroupMemberData(
                userId = u.id,
                displayName = u.displayName,
                email = u.email,
                avatarUrl = if (u.avatarBytes != null) "/users/${u.id}/avatar" else u.avatarUrl,
                role = m?.role ?: GroupRole.MEMBER.name,
                joinedAt = m?.joinedAt?.toEpochMilli() ?: 0L,
            )
        }
        val urls = photos.findAllByTaskIdOrderByCreatedAtAsc(id).map { "/tasks/$id/photos/${it.id}" }
        return GroupTaskData(
            id = id,
            title = title,
            description = description,
            isCompleted = isCompleted,
            priority = priority,
            dueDate = joinDueDate(date, timeStart),
            assignee = assignee,
            photoUrls = urls,
        )
    }
}
