package com.todoapp.backend.group

import com.todoapp.backend.task.TaskEntity
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
        return entity.toDto()
    }

    @Transactional
    fun update(callerId: Long, groupId: Long, taskId: Long, req: GroupTaskUpdateRequest): GroupTaskData {
        groupService.requireMember(groupId, callerId)
        val task = tasks.findById(taskId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found")
        }
        if (task.familyGroupId != groupId) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Task not in this group")
        }
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
        return tasks.save(task).toDto()
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
        tasks.delete(task)
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
                avatarUrl = u.avatarUrl,
                role = m?.role ?: GroupRole.MEMBER.name,
                joinedAt = m?.joinedAt?.toEpochMilli() ?: 0L,
            )
        }
        return GroupTaskData(
            id = id,
            title = title,
            description = description,
            isCompleted = isCompleted,
            priority = priority,
            dueDate = joinDueDate(date, timeStart),
            assignee = assignee,
        )
    }
}
