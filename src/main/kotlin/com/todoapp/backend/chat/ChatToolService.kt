package com.todoapp.backend.chat

import com.google.protobuf.Struct
import com.google.protobuf.Value
import com.todoapp.backend.group.GroupMemberRepository
import com.todoapp.backend.group.GroupRepository
import com.todoapp.backend.task.TaskCategory
import com.todoapp.backend.task.TaskEntity
import com.todoapp.backend.task.TaskRepository
import com.todoapp.backend.task.TaskSubtaskEntity
import com.todoapp.backend.task.TaskSubtaskRepository
import com.todoapp.backend.task.Recurrence
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalTime

/**
 * Server-side execution of the chat tools (read, single/bulk task writes, and
 * staged-task step writes). Replaces the client's `ChatToolRegistry`. All work runs
 * against Postgres directly so we don't need to round-trip the device for read tools,
 * and write tools are atomic within a single Spring transaction.
 *
 * Each `runX` method takes the parsed [Struct] args and returns a [Value]
 * that the orchestrator wraps into a `FunctionResponsePart` for Vertex.
 */
@Service
class ChatToolService(
    private val taskRepo: TaskRepository,
    private val groupRepo: GroupRepository,
    private val members: GroupMemberRepository,
    private val subtaskRepo: TaskSubtaskRepository,
) {
    private val log = LoggerFactory.getLogger(ChatToolService::class.java)

    /**
     * Dispatches a function call by name. Returns a JSON-shaped Value the
     * model will read on the next turn. Any unexpected error is converted to
     * an `{ "error": "..." }` payload so the model can surface it gracefully.
     */
    @Transactional
    fun execute(userId: Long, name: String, args: Struct): Value =
        runCatching {
            when (name) {
                "getCurrentDate" -> runGetCurrentDate()
                "getTodaysTasks" -> runGetTodaysTasks(userId)
                "getOverdueTasks" -> runGetOverdueTasks(userId)
                "getTasksForDateRange" -> runGetTasksForDateRange(userId, args)
                "getGroups" -> runGetGroups(userId)
                "getCompletedTasksThisWeek" -> runGetCompletedTasksThisWeek(userId)
                "getProductivityInsights" -> runGetProductivityInsights(userId, args)
                "findTaskByTitle" -> runFindTaskByTitle(userId, args)
                "createTask" -> runCreateTask(userId, args)
                "updateTask" -> runUpdateTask(userId, args)
                "deleteTask" -> runDeleteTask(userId, args)
                "setTaskCompletion" -> runSetTaskCompletion(userId, args)
                "setTaskSecret" -> runSetTaskSecret(userId, args)
                "bulkSetTaskCompletion" -> runBulkSetTaskCompletion(userId, args)
                "bulkDeleteTasks" -> runBulkDeleteTasks(userId, args)
                "bulkRescheduleTasks" -> runBulkRescheduleTasks(userId, args)
                "setTaskLocation" -> runSetTaskLocation(userId, args)
                "createStagedTask" -> runCreateStagedTask(userId, args)
                "addStep" -> runAddStep(userId, args)
                "renameStep" -> runRenameStep(userId, args)
                "setStepCompletion" -> runSetStepCompletion(userId, args)
                "deleteStep" -> runDeleteStep(userId, args)
                else -> errorPayload("Unknown tool: $name")
            }
        }.getOrElse {
            log.warn("Tool $name failed for user=$userId: ${it.message}", it)
            errorPayload(it.message ?: "tool failed")
        }

    // -------------------- Read tools --------------------

    // §7.14: the chat/Vertex layer must never see biometric-protected (isSecret) personal tasks —
    // their titles and locations would otherwise be sent to Google. Every personal-task read goes here.
    private fun visiblePersonalTasks(userId: Long): List<TaskEntity> =
        taskRepo.findAllByOwnerIdAndFamilyGroupIdIsNull(userId).filterNot { it.isSecret }

    private fun runGetCurrentDate(): Value =
        objectValue("date" to stringValue(LocalDate.now().toString()))

    private fun runGetTodaysTasks(userId: Long): Value {
        val today = LocalDate.now()
        val tasks = visiblePersonalTasks(userId)
            .filter { it.date == today.toEpochDay() }
        return tasksPayload(tasks)
    }

    private fun runGetOverdueTasks(userId: Long): Value {
        val today = LocalDate.now().toEpochDay()
        val overdue = visiblePersonalTasks(userId)
            .filter { !it.isCompleted && it.date < today }
        return tasksPayload(overdue)
    }

    private fun runGetTasksForDateRange(userId: Long, args: Struct): Value {
        val start = LocalDate.parse(args.fields["startDate"]?.stringValue.orEmpty())
        val end = LocalDate.parse(args.fields["endDate"]?.stringValue.orEmpty())
        val s = start.toEpochDay()
        val e = end.toEpochDay()
        val tasks = visiblePersonalTasks(userId)
            .filter { it.date in s..e }
        return tasksPayload(tasks)
    }

    private fun runGetGroups(userId: Long): Value {
        val memberships = members.findAllByUserId(userId)
        val items = memberships.mapNotNull { mem ->
            val group = groupRepo.findById(mem.groupId).orElse(null) ?: return@mapNotNull null
            objectValue(
                "id" to longValue(group.id),
                "name" to stringValue(group.name),
                "memberCount" to longValue(members.countByGroupId(group.id)),
                "role" to stringValue(mem.role),
            )
        }
        return objectValue("groups" to listValue(items), "count" to longValue(items.size.toLong()))
    }

    private fun runGetCompletedTasksThisWeek(userId: Long): Value {
        val today = LocalDate.now().toEpochDay()
        val weekAgo = today - 6  // last 7 days inclusive
        val count = visiblePersonalTasks(userId)
            .count { it.isCompleted && it.date in weekAgo..today }
        return objectValue("count" to longValue(count.toLong()))
    }

    private fun runFindTaskByTitle(userId: Long, args: Struct): Value {
        val query = args.fields["query"]?.stringValue.orEmpty().ifBlank {
            return errorPayload("query is required")
        }
        val tasks = taskRepo
            .findFirst5ByOwnerIdAndFamilyGroupIdIsNullAndTitleContainingIgnoreCaseOrderByDateAsc(userId, query)
            .filterNot { it.isSecret }
        // Include steps (with their stepIds) so the model can chain into addStep / renameStep /
        // setStepCompletion / deleteStep when the user references a staged task by name.
        return objectValue(
            "tasks" to listValue(tasks.map { taskValue(it, includeId = true, includeSteps = true) }),
            "count" to longValue(tasks.size.toLong()),
        )
    }

    private fun runGetProductivityInsights(userId: Long, args: Struct): Value {
        val range = args.fields["range"]?.stringValue?.lowercase()?.takeIf { it.isNotBlank() } ?: "week"
        val today = LocalDate.now()
        val todayEpoch = today.toEpochDay()
        val startEpoch = when (range) {
            "month" -> today.minusDays(MONTH_LOOKBACK_DAYS).toEpochDay()
            "all" -> Long.MIN_VALUE
            else -> today.minusDays(WEEK_LOOKBACK_DAYS).toEpochDay()
        }
        val all = visiblePersonalTasks(userId)
        val inRange = all.filter { it.date in startEpoch..todayEpoch }
        val completedInRange = inRange.count { it.isCompleted }
        val totalInRange = inRange.size
        val completionPercent = if (totalInRange > 0) {
            (completedInRange.toDouble() / totalInRange.toDouble() * PERCENT_SCALE).toLong()
        } else {
            0L
        }

        // Streak: consecutive days ending today with >=1 completed task.
        val completedByDay = all
            .filter { it.isCompleted }
            .groupBy { it.date }
        var streak = 0L
        var cursor = todayEpoch
        while (!completedByDay[cursor].isNullOrEmpty()) {
            streak++
            cursor--
        }

        // Busiest weekday across the range (completed tasks).
        val busiestDay = inRange
            .filter { it.isCompleted }
            .groupBy { LocalDate.ofEpochDay(it.date).dayOfWeek.name }
            .maxByOrNull { it.value.size }
            ?.key
            ?: ""

        return objectValue(
            "range" to stringValue(range),
            "completedCount" to longValue(completedInRange.toLong()),
            "totalCount" to longValue(totalInRange.toLong()),
            "completionPercent" to longValue(completionPercent),
            "currentStreakDays" to longValue(streak),
            "busiestDayName" to stringValue(busiestDay),
        )
    }

    // -------------------- Write tools --------------------

    private fun runCreateTask(userId: Long, args: Struct): Value {
        val title = args.fields["title"]?.stringValue.orEmpty().ifBlank {
            return errorPayload("title is required")
        }
        val date = LocalDate.parse(args.fields["date"]?.stringValue.orEmpty())
        val isAllDay = args.fields["isAllDay"]?.boolValue ?: false
        // For all-day tasks we normalize to 00:00 → 23:59:59 so listing/sorting code paths
        // that key off timeStart/timeEnd keep working — the client uses isAllDay to drive
        // UI rendering (suppress the time chip), not the timestamps themselves.
        val start = if (isAllDay) {
            0L
        } else {
            args.fields["timeStart"]?.stringValue?.takeIf { it.isNotBlank() }?.let(::parseTime)
                ?: return errorPayload("timeStart is required (or set isAllDay=true)")
        }
        val end = if (isAllDay) {
            SECONDS_PER_DAY - 1
        } else {
            args.fields["timeEnd"]?.stringValue?.takeIf { it.isNotBlank() }?.let(::parseTime)
                ?: (start + DEFAULT_DURATION_SECONDS).coerceAtMost(SECONDS_PER_DAY - 1)
        }
        val description = args.fields["description"]?.stringValue?.takeIf { it.isNotBlank() }
        val category = args.fields["category"]?.stringValue?.takeIf { it.isNotBlank() }
            ?.let { runCatching { TaskCategory.valueOf(it.uppercase()) }.getOrNull() }
            ?: TaskCategory.PERSONAL
        val customCategoryName = args.fields["customCategoryName"]?.stringValue?.takeIf { it.isNotBlank() }
            ?.takeIf { category == TaskCategory.OTHER }
            ?.take(MAX_CUSTOM_CATEGORY_NAME)
        val recurrence = args.fields["recurrence"]?.stringValue?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Recurrence.valueOf(it.uppercase()) }.getOrNull() }
            ?: Recurrence.NONE
        val reminderOffsetMinutes = args.fields["reminderOffsetMinutes"]?.numberValue?.toLong()
            ?.coerceAtLeast(0L)
            ?: 0L
        val isSecret = args.fields["isSecret"]?.boolValue ?: false
        val locationName = args.fields["locationName"]?.stringValue?.takeIf { it.isNotBlank() }
            ?.take(MAX_LOCATION_NAME)
        val locationAddress = args.fields["locationAddress"]?.stringValue?.takeIf { it.isNotBlank() }
            ?.take(MAX_LOCATION_ADDRESS)
        val locationLat = args.fields["locationLat"]?.numberValue?.takeIf { it != 0.0 || locationName != null }
            ?.toBigDecimal()
        val locationLng = args.fields["locationLng"]?.numberValue?.takeIf { it != 0.0 || locationName != null }
            ?.toBigDecimal()

        val saved = taskRepo.save(
            TaskEntity(
                ownerId = userId,
                title = title,
                description = description,
                date = date.toEpochDay(),
                timeStart = start,
                timeEnd = end,
                isSecret = isSecret,
                category = category,
                customCategoryName = customCategoryName,
                recurrence = recurrence,
                isAllDay = isAllDay,
                reminderOffsetMinutes = reminderOffsetMinutes,
                locationLat = locationLat,
                locationLng = locationLng,
                locationName = locationName,
                locationAddress = locationAddress,
            ),
        )
        // Write-tool responses omit the numeric id — the model already had it as input
        // (or doesn't need one for confirmation), and we don't want it surfacing in the
        // user-facing reply.
        return objectValue(
            "ok" to boolValue(true),
            "task" to taskValue(saved, includeId = false),
        )
    }

    private fun runUpdateTask(userId: Long, args: Struct): Value {
        val taskId = args.fields["taskId"]?.numberValue?.toLong()
            ?: return errorPayload("taskId is required")
        val task = taskRepo.findById(taskId).orElse(null)
            ?: return errorPayload("task not found")
        if (task.ownerId != userId) return errorPayload("not your task")
        if (task.familyGroupId != null) {
            return errorPayload("group_task_blocked: shared group tasks must be edited from the group screen, not chat")
        }
        // Track whether any field actually changed so the model can phrase its reply
        // without claiming a change that didn't happen ("Already at 3pm — nothing to do").
        var changed = false
        args.fields["title"]?.stringValue?.takeIf { it.isNotBlank() }?.let {
            if (it != task.title) { task.title = it; changed = true }
        }
        args.fields["date"]?.stringValue?.takeIf { it.isNotBlank() }?.let {
            val parsed = LocalDate.parse(it).toEpochDay()
            if (parsed != task.date) { task.date = parsed; changed = true }
        }
        args.fields["timeStart"]?.stringValue?.takeIf { it.isNotBlank() }?.let {
            val parsed = parseTime(it)
            if (parsed != task.timeStart) { task.timeStart = parsed; changed = true }
        }
        args.fields["timeEnd"]?.stringValue?.takeIf { it.isNotBlank() }?.let {
            val parsed = parseTime(it)
            if (parsed != task.timeEnd) { task.timeEnd = parsed; changed = true }
        }
        args.fields["description"]?.stringValue?.let {
            val newValue = it.ifBlank { null }
            if (newValue != task.description) { task.description = newValue; changed = true }
        }
        args.fields["category"]?.stringValue?.takeIf { it.isNotBlank() }?.let { raw ->
            runCatching { TaskCategory.valueOf(raw.uppercase()) }.getOrNull()?.let {
                if (it != task.category) { task.category = it; changed = true }
            }
        }
        args.fields["customCategoryName"]?.stringValue?.let { raw ->
            val newValue = raw.ifBlank { null }?.take(MAX_CUSTOM_CATEGORY_NAME)
                ?.takeIf { task.category == TaskCategory.OTHER }
            if (newValue != task.customCategoryName) {
                task.customCategoryName = newValue
                changed = true
            }
        }
        args.fields["recurrence"]?.stringValue?.takeIf { it.isNotBlank() }?.let { raw ->
            runCatching { Recurrence.valueOf(raw.uppercase()) }.getOrNull()?.let {
                if (it != task.recurrence) { task.recurrence = it; changed = true }
            }
        }
        args.fields["isAllDay"]?.let { raw ->
            val newValue = raw.boolValue
            if (newValue != task.isAllDay) {
                task.isAllDay = newValue
                // When converting to all-day, normalize timestamps so list queries stay sane.
                if (newValue) {
                    task.timeStart = 0L
                    task.timeEnd = SECONDS_PER_DAY - 1
                }
                changed = true
            }
        }
        args.fields["reminderOffsetMinutes"]?.let { raw ->
            val newValue = raw.numberValue.toLong().coerceAtLeast(0L)
            if (newValue != task.reminderOffsetMinutes) {
                task.reminderOffsetMinutes = newValue
                changed = true
            }
        }
        args.fields["isSecret"]?.let { raw ->
            val newValue = raw.boolValue
            if (newValue != task.isSecret) {
                task.isSecret = newValue
                changed = true
            }
        }
        // Location fields: empty-string clears, omitted leaves the existing value alone.
        args.fields["locationName"]?.stringValue?.let { raw ->
            val newValue = raw.takeIf { it.isNotBlank() }?.take(MAX_LOCATION_NAME)
            if (newValue != task.locationName) {
                task.locationName = newValue
                changed = true
            }
        }
        args.fields["locationAddress"]?.stringValue?.let { raw ->
            val newValue = raw.takeIf { it.isNotBlank() }?.take(MAX_LOCATION_ADDRESS)
            if (newValue != task.locationAddress) {
                task.locationAddress = newValue
                changed = true
            }
        }
        args.fields["locationLat"]?.let { raw ->
            val newValue = raw.numberValue.toBigDecimal()
            if (newValue != task.locationLat) {
                task.locationLat = newValue
                changed = true
            }
        }
        args.fields["locationLng"]?.let { raw ->
            val newValue = raw.numberValue.toBigDecimal()
            if (newValue != task.locationLng) {
                task.locationLng = newValue
                changed = true
            }
        }
        return objectValue(
            "ok" to boolValue(true),
            "noop" to boolValue(!changed),
            "task" to taskValue(taskRepo.save(task), includeId = false),
        )
    }

    private fun runSetTaskLocation(userId: Long, args: Struct): Value {
        val taskId = args.fields["taskId"]?.numberValue?.toLong()
            ?: return errorPayload("taskId is required")
        val task = taskRepo.findById(taskId).orElse(null)
            ?: return errorPayload("task not found")
        if (task.ownerId != userId) return errorPayload("not your task")
        if (task.familyGroupId != null) {
            return errorPayload("group_task_blocked: shared group tasks must be edited from the group screen, not chat")
        }
        // Empty string in any field clears it; omitted means leave alone.
        args.fields["locationName"]?.stringValue?.let { raw ->
            task.locationName = raw.takeIf { it.isNotBlank() }?.take(MAX_LOCATION_NAME)
        }
        args.fields["locationAddress"]?.stringValue?.let { raw ->
            task.locationAddress = raw.takeIf { it.isNotBlank() }?.take(MAX_LOCATION_ADDRESS)
        }
        args.fields["locationLat"]?.let { raw -> task.locationLat = raw.numberValue.toBigDecimal() }
        args.fields["locationLng"]?.let { raw -> task.locationLng = raw.numberValue.toBigDecimal() }
        // Pair-clear: clearing the name+address typically means dropping the pin too.
        if (task.locationName == null && task.locationAddress == null) {
            task.locationLat = null
            task.locationLng = null
        }
        return objectValue(
            "ok" to boolValue(true),
            "task" to taskValue(taskRepo.save(task), includeId = false),
        )
    }

    private fun runDeleteTask(userId: Long, args: Struct): Value {
        val taskId = args.fields["taskId"]?.numberValue?.toLong()
            ?: return errorPayload("taskId is required")
        val task = taskRepo.findById(taskId).orElse(null)
            ?: return errorPayload("task not found")
        if (task.ownerId != userId) return errorPayload("not your task")
        if (task.familyGroupId != null) {
            return errorPayload("group_task_blocked: shared group tasks must be edited from the group screen, not chat")
        }
        // Capture title for the model's confirmation reply (no id leak).
        val deletedTitle = task.title
        // Steps are removed by the DB FK (ON DELETE CASCADE); deleting the task is enough.
        taskRepo.delete(task)
        return objectValue(
            "ok" to boolValue(true),
            "deletedTitle" to stringValue(deletedTitle),
        )
    }

    // -------------------- Staged-task (step) write tools --------------------

    private fun runCreateStagedTask(userId: Long, args: Struct): Value {
        val title = args.fields["title"]?.stringValue.orEmpty().ifBlank {
            return errorPayload("title is required")
        }
        val date = LocalDate.parse(args.fields["date"]?.stringValue.orEmpty())
        val steps = args.fields["steps"]?.listValue?.valuesList
            ?.mapNotNull { it.stringValue?.trim()?.takeIf { s -> s.isNotBlank() }?.take(MAX_STEP_TITLE) }
            ?: emptyList()
        if (steps.isEmpty()) {
            return errorPayload("steps is required: a staged task needs at least one step")
        }
        val isAllDay = args.fields["isAllDay"]?.boolValue ?: false
        val start = if (isAllDay) {
            0L
        } else {
            args.fields["timeStart"]?.stringValue?.takeIf { it.isNotBlank() }?.let(::parseTime)
                ?: DEFAULT_STAGED_START_SECONDS
        }
        val end = if (isAllDay) {
            SECONDS_PER_DAY - 1
        } else {
            args.fields["timeEnd"]?.stringValue?.takeIf { it.isNotBlank() }?.let(::parseTime)
                ?: (start + DEFAULT_DURATION_SECONDS).coerceAtMost(SECONDS_PER_DAY - 1)
        }
        val description = args.fields["description"]?.stringValue?.takeIf { it.isNotBlank() }
        val reminderOffsetMinutes = args.fields["reminderOffsetMinutes"]?.numberValue?.toLong()
            ?.coerceAtLeast(0L) ?: 0L
        val isSecret = args.fields["isSecret"]?.boolValue ?: false

        val saved = taskRepo.save(
            TaskEntity(
                ownerId = userId,
                title = title,
                description = description,
                date = date.toEpochDay(),
                timeStart = start,
                timeEnd = end,
                isCompleted = false,
                isSecret = isSecret,
                category = TaskCategory.PERSONAL,
                recurrence = Recurrence.NONE,
                isAllDay = isAllDay,
                reminderOffsetMinutes = reminderOffsetMinutes,
            ),
        )
        steps.forEachIndexed { index, stepTitle ->
            subtaskRepo.save(
                TaskSubtaskEntity(taskId = saved.id, title = stepTitle, orderIndex = index),
            )
        }
        return objectValue(
            "ok" to boolValue(true),
            "task" to taskValue(saved, includeId = false, includeSteps = true),
        )
    }

    private fun runAddStep(userId: Long, args: Struct): Value {
        val taskId = args.fields["taskId"]?.numberValue?.toLong()
            ?: return errorPayload("taskId is required")
        val title = args.fields["title"]?.stringValue?.trim()?.takeIf { it.isNotBlank() }?.take(MAX_STEP_TITLE)
            ?: return errorPayload("title is required")
        val task = taskRepo.findById(taskId).orElse(null)
            ?: return errorPayload("task not found")
        if (task.ownerId != userId) return errorPayload("not your task")
        if (task.familyGroupId != null) {
            return errorPayload("group_task_blocked: shared group tasks must be edited from the group screen, not chat")
        }
        val order = subtaskRepo.findAllByTaskIdOrderByOrderIndexAsc(task.id).size
        subtaskRepo.save(TaskSubtaskEntity(taskId = task.id, title = title, orderIndex = order))
        recomputeStagedParentCompletion(task)
        return objectValue(
            "ok" to boolValue(true),
            "task" to taskValue(task, includeId = false, includeSteps = true),
        )
    }

    private fun runRenameStep(userId: Long, args: Struct): Value {
        val title = args.fields["title"]?.stringValue?.trim()?.takeIf { it.isNotBlank() }?.take(MAX_STEP_TITLE)
            ?: return errorPayload("title is required")
        val step = ownedStep(userId, args) ?: return stepLookupError(userId, args)
        val task = taskRepo.findById(step.taskId).get()
        step.title = title
        subtaskRepo.save(step)
        return objectValue(
            "ok" to boolValue(true),
            "task" to taskValue(task, includeId = false, includeSteps = true),
        )
    }

    private fun runSetStepCompletion(userId: Long, args: Struct): Value {
        val isCompleted = args.fields["isCompleted"]?.boolValue
            ?: return errorPayload("isCompleted is required")
        val step = ownedStep(userId, args) ?: return stepLookupError(userId, args)
        val task = taskRepo.findById(step.taskId).get()
        val noop = step.isCompleted == isCompleted
        step.isCompleted = isCompleted
        subtaskRepo.save(step)
        recomputeStagedParentCompletion(task)
        return objectValue(
            "ok" to boolValue(true),
            "noop" to boolValue(noop),
            "task" to taskValue(task, includeId = false, includeSteps = true),
        )
    }

    private fun runDeleteStep(userId: Long, args: Struct): Value {
        val step = ownedStep(userId, args) ?: return stepLookupError(userId, args)
        val task = taskRepo.findById(step.taskId).get()
        if (subtaskRepo.findAllByTaskIdOrderByOrderIndexAsc(task.id).size <= 1) {
            return errorPayload("cannot delete the last step — delete the whole task instead")
        }
        subtaskRepo.delete(step)
        recomputeStagedParentCompletion(task)
        return objectValue(
            "ok" to boolValue(true),
            "task" to taskValue(task, includeId = false, includeSteps = true),
        )
    }

    /** Returns the step iff it exists and belongs to a personal task the caller owns, else null. */
    private fun ownedStep(userId: Long, args: Struct): TaskSubtaskEntity? {
        val stepId = args.fields["stepId"]?.numberValue?.toLong() ?: return null
        val step = subtaskRepo.findById(stepId).orElse(null) ?: return null
        val task = taskRepo.findById(step.taskId).orElse(null) ?: return null
        if (task.ownerId != userId || task.familyGroupId != null) return null
        return step
    }

    /** Specific error payload when [ownedStep] returned null, so the model can react correctly. */
    private fun stepLookupError(userId: Long, args: Struct): Value {
        val stepId = args.fields["stepId"]?.numberValue?.toLong() ?: return errorPayload("stepId is required")
        val step = subtaskRepo.findById(stepId).orElse(null) ?: return errorPayload("step not found")
        val task = taskRepo.findById(step.taskId).orElse(null) ?: return errorPayload("task not found")
        if (task.familyGroupId != null) {
            return errorPayload("group_task_blocked: shared group tasks must be edited from the group screen, not chat")
        }
        return errorPayload("not your task")
    }

    /** Parent is done iff it has steps and all are done; reopens otherwise (matches the client). */
    private fun recomputeStagedParentCompletion(task: TaskEntity) {
        val steps = subtaskRepo.findAllByTaskIdOrderByOrderIndexAsc(task.id)
        val done = steps.isNotEmpty() && steps.all { it.isCompleted }
        if (task.isCompleted != done) {
            task.isCompleted = done
            taskRepo.save(task)
        }
    }

    private fun runSetTaskCompletion(userId: Long, args: Struct): Value {
        val taskId = args.fields["taskId"]?.numberValue?.toLong()
            ?: return errorPayload("taskId is required")
        val isCompleted = args.fields["isCompleted"]?.boolValue
            ?: return errorPayload("isCompleted is required")
        val task = taskRepo.findById(taskId).orElse(null)
            ?: return errorPayload("task not found")
        if (task.ownerId != userId) return errorPayload("not your task")
        if (task.familyGroupId != null) {
            return errorPayload("group_task_blocked: shared group tasks must be edited from the group screen, not chat")
        }
        val noop = task.isCompleted == isCompleted
        // Staged task: completing the parent cascades to every step, and reopening clears them,
        // mirroring the client's parent-checkbox shortcut so chat and app agree.
        val steps = subtaskRepo.findAllByTaskIdOrderByOrderIndexAsc(task.id)
        steps.forEach {
            if (it.isCompleted != isCompleted) {
                it.isCompleted = isCompleted
                subtaskRepo.save(it)
            }
        }
        task.isCompleted = isCompleted
        return objectValue(
            "ok" to boolValue(true),
            "noop" to boolValue(noop),
            "task" to taskValue(taskRepo.save(task), includeId = false),
        )
    }

    private fun runSetTaskSecret(userId: Long, args: Struct): Value {
        val taskId = args.fields["taskId"]?.numberValue?.toLong()
            ?: return errorPayload("taskId is required")
        val isSecret = args.fields["isSecret"]?.boolValue
            ?: return errorPayload("isSecret is required")
        val task = taskRepo.findById(taskId).orElse(null)
            ?: return errorPayload("task not found")
        if (task.ownerId != userId) return errorPayload("not your task")
        if (task.familyGroupId != null) {
            return errorPayload("group_task_blocked: shared group tasks must be edited from the group screen, not chat")
        }
        val noop = task.isSecret == isSecret
        task.isSecret = isSecret
        return objectValue(
            "ok" to boolValue(true),
            "noop" to boolValue(noop),
            "task" to taskValue(taskRepo.save(task), includeId = false),
        )
    }

    // -------------------- Bulk write tools --------------------

    private fun runBulkSetTaskCompletion(userId: Long, args: Struct): Value {
        val taskIds = extractTaskIds(args) ?: return errorPayload("taskIds is required")
        val isCompleted = args.fields["isCompleted"]?.boolValue
            ?: return errorPayload("isCompleted is required")
        return runBulk(userId, taskIds) { task ->
            task.isCompleted = isCompleted
            taskRepo.save(task)
        }
    }

    private fun runBulkDeleteTasks(userId: Long, args: Struct): Value {
        val taskIds = extractTaskIds(args) ?: return errorPayload("taskIds is required")
        return runBulk(userId, taskIds) { task ->
            taskRepo.delete(task)
        }
    }

    private fun runBulkRescheduleTasks(userId: Long, args: Struct): Value {
        val taskIds = extractTaskIds(args) ?: return errorPayload("taskIds is required")
        val newDate = args.fields["newDate"]?.stringValue?.takeIf { it.isNotBlank() }?.let {
            runCatching { LocalDate.parse(it).toEpochDay() }.getOrNull()
        } ?: return errorPayload("newDate is required (YYYY-MM-DD)")
        return runBulk(userId, taskIds) { task ->
            task.date = newDate
            taskRepo.save(task)
        }
    }

    private fun extractTaskIds(args: Struct): List<Long>? {
        val list = args.fields["taskIds"]?.listValue?.valuesList ?: return null
        if (list.isEmpty()) return null
        return list.mapNotNull { v -> v.numberValue.toLong().takeIf { it > 0 } }.distinct()
    }

    private inline fun runBulk(
        userId: Long,
        taskIds: List<Long>,
        action: (TaskEntity) -> Unit,
    ): Value {
        val succeeded = mutableListOf<Long>()
        val failed = mutableListOf<Pair<Long, String>>()
        for (id in taskIds) {
            val task = taskRepo.findById(id).orElse(null)
            if (task == null) { failed.add(id to "task not found"); continue }
            if (task.ownerId != userId) { failed.add(id to "not your task"); continue }
            if (task.familyGroupId != null) { failed.add(id to "group_task_blocked"); continue }
            try {
                action(task)
                succeeded.add(id)
            } catch (e: Exception) {
                failed.add(id to (e.message ?: "save failed"))
            }
        }
        // Failures are reported by reason only — no ids — so the model can summarize counts
        // ("3 succeeded, 1 failed because it was a group task") without leaking ids.
        return objectValue(
            "ok" to boolValue(true),
            "succeededCount" to longValue(succeeded.size.toLong()),
            "failedCount" to longValue(failed.size.toLong()),
            "failedReasons" to listValue(
                failed.map { (_, reason) -> stringValue(reason) }.distinct(),
            ),
        )
    }

    // -------------------- Helpers --------------------

    private fun tasksPayload(tasks: List<TaskEntity>): Value =
        objectValue(
            "tasks" to listValue(tasks.map(::taskValue)),
            "count" to longValue(tasks.size.toLong()),
        )

    /**
     * Read-tool callers default to `includeId = true` — the model needs ids to chain
     * into write tools (e.g., findTaskByTitle → deleteTask). Write-tool callers pass
     * `false`: the model already had the id as input or doesn't need one for confirmation,
     * and we want to keep ids out of the model's reply context as defense in depth.
     */
    private fun taskValue(task: TaskEntity, includeId: Boolean = true, includeSteps: Boolean = false): Value {
        val struct = Struct.newBuilder()
        if (includeId) struct.putFields("id", longValue(task.id))
        struct.putFields("title", stringValue(task.title))
        struct.putFields("date", stringValue(LocalDate.ofEpochDay(task.date).toString()))
        struct.putFields("timeStart", stringValue(formatTime(task.timeStart)))
        struct.putFields("timeEnd", stringValue(formatTime(task.timeEnd)))
        struct.putFields("isAllDay", boolValue(task.isAllDay))
        struct.putFields("isCompleted", boolValue(task.isCompleted))
        struct.putFields("isSecret", boolValue(task.isSecret))
        struct.putFields("category", stringValue(task.category.name))
        if (task.customCategoryName != null) {
            struct.putFields("customCategoryName", stringValue(task.customCategoryName!!))
        }
        struct.putFields("recurrence", stringValue(task.recurrence.name))
        if (task.reminderOffsetMinutes > 0L) {
            struct.putFields("reminderOffsetMinutes", longValue(task.reminderOffsetMinutes))
        }
        if (task.locationName != null) {
            struct.putFields("locationName", stringValue(task.locationName!!))
        }
        if (task.locationAddress != null) {
            struct.putFields("locationAddress", stringValue(task.locationAddress!!))
        }
        if (includeSteps) {
            val steps = subtaskRepo.findAllByTaskIdOrderByOrderIndexAsc(task.id)
            if (steps.isNotEmpty()) {
                struct.putFields(
                    "steps",
                    listValue(
                        steps.map { step ->
                            objectValue(
                                "stepId" to longValue(step.id),
                                "title" to stringValue(step.title),
                                "isCompleted" to boolValue(step.isCompleted),
                            )
                        },
                    ),
                )
            }
        }
        return Value.newBuilder().setStructValue(struct.build()).build()
    }

    private fun parseTime(hhmm: String): Long {
        val time = LocalTime.parse(hhmm)
        return (time.hour * 3600L + time.minute * 60L)
    }

    private fun formatTime(secondsOfDay: Long): String {
        val time = LocalTime.ofSecondOfDay(secondsOfDay.coerceIn(0, SECONDS_PER_DAY - 1))
        return "%02d:%02d".format(time.hour, time.minute)
    }

    private fun stringValue(s: String): Value = Value.newBuilder().setStringValue(s).build()
    private fun boolValue(b: Boolean): Value = Value.newBuilder().setBoolValue(b).build()
    private fun longValue(n: Long): Value = Value.newBuilder().setNumberValue(n.toDouble()).build()
    private fun listValue(items: List<Value>): Value =
        Value.newBuilder().setListValue(
            com.google.protobuf.ListValue.newBuilder().addAllValues(items).build(),
        ).build()
    private fun objectValue(vararg entries: Pair<String, Value>): Value {
        val struct = Struct.newBuilder()
        entries.forEach { (k, v) -> struct.putFields(k, v) }
        return Value.newBuilder().setStructValue(struct.build()).build()
    }
    private fun errorPayload(message: String): Value =
        objectValue("error" to stringValue(message))

    companion object {
        private const val DEFAULT_DURATION_SECONDS = 30L * 60L
        private const val DEFAULT_STAGED_START_SECONDS = 9L * 3600L
        private const val MAX_STEP_TITLE = 255
        private const val SECONDS_PER_DAY = 24L * 3600L
        private const val WEEK_LOOKBACK_DAYS = 6L
        private const val MONTH_LOOKBACK_DAYS = 29L
        private const val PERCENT_SCALE = 100.0
        private const val MAX_CUSTOM_CATEGORY_NAME = 64
        private const val MAX_LOCATION_NAME = 120
        private const val MAX_LOCATION_ADDRESS = 500
    }
}
