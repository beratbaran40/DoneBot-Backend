package com.todoapp.backend.chat

import com.google.protobuf.Struct
import com.google.protobuf.Value
import com.todoapp.backend.group.GroupMemberRepository
import com.todoapp.backend.group.GroupRepository
import com.todoapp.backend.task.TaskCategory
import com.todoapp.backend.task.TaskEntity
import com.todoapp.backend.task.TaskRepository
import com.todoapp.backend.task.Recurrence
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalTime

/**
 * Server-side execution of the 11 chat tools. Replaces the client's
 * `ChatToolRegistry`. All work runs against Postgres directly so we don't
 * need to round-trip the device for read tools, and write tools are atomic
 * within a single Spring transaction.
 *
 * Each `runX` method takes the parsed [Struct] args and returns a [Value]
 * that the orchestrator wraps into a `FunctionResponsePart` for Vertex.
 */
@Service
class ChatToolService(
    private val taskRepo: TaskRepository,
    private val groupRepo: GroupRepository,
    private val members: GroupMemberRepository,
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
                else -> errorPayload("Unknown tool: $name")
            }
        }.getOrElse {
            log.warn("Tool $name failed for user=$userId: ${it.message}", it)
            errorPayload(it.message ?: "tool failed")
        }

    // -------------------- Read tools --------------------

    private fun runGetCurrentDate(): Value =
        objectValue("date" to stringValue(LocalDate.now().toString()))

    private fun runGetTodaysTasks(userId: Long): Value {
        val today = LocalDate.now()
        val tasks = taskRepo.findAllByOwnerIdAndFamilyGroupIdIsNull(userId)
            .filter { it.date == today.toEpochDay() }
        return tasksPayload(tasks)
    }

    private fun runGetOverdueTasks(userId: Long): Value {
        val today = LocalDate.now().toEpochDay()
        val overdue = taskRepo.findAllByOwnerIdAndFamilyGroupIdIsNull(userId)
            .filter { !it.isCompleted && it.date < today }
        return tasksPayload(overdue)
    }

    private fun runGetTasksForDateRange(userId: Long, args: Struct): Value {
        val start = LocalDate.parse(args.fields["startDate"]?.stringValue.orEmpty())
        val end = LocalDate.parse(args.fields["endDate"]?.stringValue.orEmpty())
        val s = start.toEpochDay()
        val e = end.toEpochDay()
        val tasks = taskRepo.findAllByOwnerIdAndFamilyGroupIdIsNull(userId)
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
        val count = taskRepo.findAllByOwnerIdAndFamilyGroupIdIsNull(userId)
            .count { it.isCompleted && it.date in weekAgo..today }
        return objectValue("count" to longValue(count.toLong()))
    }

    private fun runFindTaskByTitle(userId: Long, args: Struct): Value {
        val query = args.fields["query"]?.stringValue.orEmpty().ifBlank {
            return errorPayload("query is required")
        }
        val tasks = taskRepo
            .findFirst5ByOwnerIdAndFamilyGroupIdIsNullAndTitleContainingIgnoreCaseOrderByDateAsc(userId, query)
        return tasksPayload(tasks)
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
        val all = taskRepo.findAllByOwnerIdAndFamilyGroupIdIsNull(userId)
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
        val start = parseTime(args.fields["timeStart"]?.stringValue.orEmpty())
        val end = args.fields["timeEnd"]?.stringValue?.takeIf { it.isNotBlank() }?.let(::parseTime)
            ?: (start + DEFAULT_DURATION_SECONDS).coerceAtMost(SECONDS_PER_DAY - 1)
        val description = args.fields["description"]?.stringValue?.takeIf { it.isNotBlank() }
        val category = args.fields["category"]?.stringValue?.takeIf { it.isNotBlank() }
            ?.let { runCatching { TaskCategory.valueOf(it.uppercase()) }.getOrNull() }
            ?: TaskCategory.PERSONAL
        val recurrence = args.fields["recurrence"]?.stringValue?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Recurrence.valueOf(it.uppercase()) }.getOrNull() }
            ?: Recurrence.NONE
        val isSecret = args.fields["isSecret"]?.boolValue ?: false

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
                recurrence = recurrence,
            ),
        )
        return objectValue(
            "ok" to boolValue(true),
            "task" to taskValue(saved),
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
        args.fields["title"]?.stringValue?.takeIf { it.isNotBlank() }?.let { task.title = it }
        args.fields["date"]?.stringValue?.takeIf { it.isNotBlank() }?.let {
            task.date = LocalDate.parse(it).toEpochDay()
        }
        args.fields["timeStart"]?.stringValue?.takeIf { it.isNotBlank() }?.let {
            task.timeStart = parseTime(it)
        }
        args.fields["timeEnd"]?.stringValue?.takeIf { it.isNotBlank() }?.let {
            task.timeEnd = parseTime(it)
        }
        args.fields["description"]?.stringValue?.let { task.description = it.ifBlank { null } }
        args.fields["category"]?.stringValue?.takeIf { it.isNotBlank() }?.let { raw ->
            runCatching { TaskCategory.valueOf(raw.uppercase()) }.getOrNull()?.let { task.category = it }
        }
        return objectValue("ok" to boolValue(true), "task" to taskValue(taskRepo.save(task)))
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
        taskRepo.delete(task)
        return objectValue("ok" to boolValue(true), "deletedId" to longValue(taskId))
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
        task.isCompleted = isCompleted
        return objectValue("ok" to boolValue(true), "task" to taskValue(taskRepo.save(task)))
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
        task.isSecret = isSecret
        return objectValue("ok" to boolValue(true), "task" to taskValue(taskRepo.save(task)))
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
        return objectValue(
            "ok" to boolValue(true),
            "succeededCount" to longValue(succeeded.size.toLong()),
            "failedCount" to longValue(failed.size.toLong()),
            "failed" to listValue(
                failed.map { (id, reason) ->
                    objectValue("id" to longValue(id), "reason" to stringValue(reason))
                },
            ),
        )
    }

    // -------------------- Helpers --------------------

    private fun tasksPayload(tasks: List<TaskEntity>): Value =
        objectValue(
            "tasks" to listValue(tasks.map(::taskValue)),
            "count" to longValue(tasks.size.toLong()),
        )

    private fun taskValue(task: TaskEntity): Value =
        objectValue(
            "id" to longValue(task.id),
            "title" to stringValue(task.title),
            "date" to stringValue(LocalDate.ofEpochDay(task.date).toString()),
            "timeStart" to stringValue(formatTime(task.timeStart)),
            "timeEnd" to stringValue(formatTime(task.timeEnd)),
            "isCompleted" to boolValue(task.isCompleted),
            "isSecret" to boolValue(task.isSecret),
            "category" to stringValue(task.category.name),
            "recurrence" to stringValue(task.recurrence.name),
        )

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
        private const val SECONDS_PER_DAY = 24L * 3600L
        private const val WEEK_LOOKBACK_DAYS = 6L
        private const val MONTH_LOOKBACK_DAYS = 29L
        private const val PERCENT_SCALE = 100.0
    }
}
