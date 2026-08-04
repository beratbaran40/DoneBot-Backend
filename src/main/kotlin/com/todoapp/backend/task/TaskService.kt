package com.todoapp.backend.task

import com.todoapp.backend.group.GroupMemberRepository
import com.todoapp.backend.notif.NotificationPublisher
import com.todoapp.backend.notif.inbox.NotificationType
import com.todoapp.backend.user.UserRepository
import org.springframework.dao.DataIntegrityViolationException
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
    private val members: GroupMemberRepository,
) {
    @Transactional
    fun create(ownerId: Long, req: TaskRequest): TaskData {
        // §4.12: a retried sync POST (lost response) must not create a second task. Pre-check is the
        // primary fix — SyncWorker retries are sequential (same device), so the earlier insert is
        // already committed and visible here; return it unchanged.
        req.clientTaskId?.let { key ->
            tasks.findByOwnerIdAndClientTaskId(ownerId, key)?.let { return it.toData() }
        }
        val category = req.category ?: TaskCategory.PERSONAL
        // AUTH: bir gruba görev ekleyebilmek için o grubun üyesi olmalısın (yazma tarafı IDOR — §4.8 denetimi).
        req.familyGroupId?.let { requireGroupMembership(ownerId, it) }
        val entity = TaskEntity(
            ownerId = ownerId,
            clientTaskId = req.clientTaskId,
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
            finishedOn = req.finishedOn,
            // No clobber risk on create, so the extended rule is taken verbatim without the
            // recurrenceRuleSet gate that update() needs.
            recurrenceInterval = req.recurrenceInterval?.coerceAtLeast(1) ?: 1,
            recurrenceByDay = req.recurrenceByDay?.takeIf { it.isNotBlank() },
            recurrenceUntil = req.recurrenceUntil,
            reminderTimes = req.reminderTimes.toStorageCsv(),
        )
        val saved = try {
            // saveAndFlush so a unique-index violation surfaces HERE (inside the try), not later at commit.
            tasks.saveAndFlush(entity)
        } catch (e: DataIntegrityViolationException) {
            // Genuine concurrent double-submit of the same key (both passed the pre-check): the unique
            // index rejects the loser. Surface a clean 409 — the client retries, by then the winning row
            // is SYNCED so nothing is re-sent, and the index guarantees no duplicate ever persists.
            throw ResponseStatusException(HttpStatus.CONFLICT, "duplicate clientTaskId")
        }
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
        entity.finishedOn = req.finishedOn
        // Gated, unlike every field above: a client that predates the extended rule sends nulls for
        // these and must not wipe a rule it cannot represent. See TaskRequest.recurrenceRuleSet.
        if (req.recurrenceRuleSet) {
            entity.recurrenceInterval = req.recurrenceInterval?.coerceAtLeast(1) ?: 1
            entity.recurrenceByDay = req.recurrenceByDay?.takeIf { it.isNotBlank() }
            entity.recurrenceUntil = req.recurrenceUntil
            entity.reminderTimes = req.reminderTimes.toStorageCsv()
        }
        val saved = tasks.save(entity)
        req.subtasks?.let { reconcileSubtasks(saved.id, it) }
        notifyAssignmentIfNeeded(
            actorId = ownerId,
            previousAssigneeId = previousAssigneeId,
            saved = saved,
        )
        return saved.toData()
    }

    /** See the free function of the same name — shared with the group task path. */
    private fun reconcileSubtasks(taskId: Long, incoming: List<SubtaskRequest>) =
        reconcileSubtasks(subtaskRepo, taskId, incoming)

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
            requireGroupMembership(ownerId, familyGroupId)
            tasks.findAllByFamilyGroupId(familyGroupId)
        }
        val items = list.map { it.toData() }
        return TaskListData(items, items.size)
    }

    /**
     * Authorization gate for group-scoped task access: the caller must belong to the group.
     * Mirrors TaskPhotoController.requireAccessibleTask. Group owners are members too
     * (GroupService.create adds the creator to group_members), so this also admits the owner.
     * Without it, GET /tasks?familyGroupId=N leaked ANY group's tasks incl. isSecret (IDOR, §4.8).
     */
    private fun requireGroupMembership(callerId: Long, familyGroupId: Long) {
        if (members.findByGroupIdAndUserId(familyGroupId, callerId) == null) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not a group member")
        }
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
            finishedOn = finishedOn,
            clientTaskId = clientTaskId,
            photoUrls = urls,
            subtasks = subtaskRepo.findAllByTaskIdOrderByOrderIndexAsc(id).map {
                SubtaskData(id = it.id, title = it.title, isCompleted = it.isCompleted, orderIndex = it.orderIndex)
            },
            recurrenceInterval = recurrenceInterval,
            recurrenceByDay = recurrenceByDay,
            recurrenceUntil = recurrenceUntil,
            reminderTimes = reminderTimes.toSecondsOfDay(),
        )
    }

    /** CSV of second-of-day ⇄ list. Unparseable entries are dropped rather than failing the response. */
    private fun String?.toSecondsOfDay(): List<Int> =
        this?.split(',')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()

    private fun List<Int>?.toStorageCsv(): String? =
        this?.takeIf { it.isNotEmpty() }?.sorted()?.distinct()?.joinToString(",")

    @Transactional
    fun setDailyCompletion(callerId: Long, taskId: Long, req: TaskDailyCompletionRequest) {
        val task = tasks.findById(taskId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found")
        }
        // A group task belongs to the group, not to whoever typed it in. Ticking today's occurrence
        // is the one thing every member must be able to do — the owner-only guard meant a recurring
        // chore could only ever be completed by its creator.
        val isGroupMember = task.familyGroupId
            ?.let { members.findByGroupIdAndUserId(it, callerId) != null }
            ?: false
        if (task.ownerId != callerId && !isGroupMember) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not allowed")
        }
        // Any recurring task tracks completion per-day, not just DAILY ones. The old DAILY-only guard
        // silently 400'd every WEEKLY/MONTHLY/YEARLY routine the client pushed, so those completions
        // never reached the server and were lost on device change — the client sends this for every
        // `recurrence != NONE` task (SetTaskCompletionUseCase) and retries the rejects forever.
        if (task.recurrence == Recurrence.NONE) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Task is not recurring")
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
        val own = dailyCompletions.findAllByUserIdAndDateBetween(callerId, fromDay, toDay)
        // A group task's occurrence is completed for everyone by whoever ticks it first, so the
        // by-author filter above would hide a teammate's tick. Fold in every completion on a task
        // in one of the caller's groups, whoever wrote it.
        val groupIds = members.findAllByUserId(callerId).map { it.groupId }
        val groupTaskIds = if (groupIds.isEmpty()) {
            emptyList()
        } else {
            tasks.findAllByFamilyGroupIdIn(groupIds).map { it.id }
        }
        val shared = if (groupTaskIds.isEmpty()) {
            emptyList()
        } else {
            dailyCompletions.findAllByTaskIdInAndDateBetween(groupTaskIds, fromDay, toDay)
        }
        // The caller's own rows on a group task appear in both lists; key on (taskId, date) because
        // that pair — not the row id — is what "this occurrence is done" means.
        val items = (own + shared)
            .distinctBy { it.taskId to it.date }
            .map { TaskDailyCompletionData(taskId = it.taskId, date = it.date, completedAt = it.completedAt) }
        return TaskDailyCompletionListData(items, items.size)
    }
}
