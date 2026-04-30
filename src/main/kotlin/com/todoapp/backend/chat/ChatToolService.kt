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
                "createTask" -> runCreateTask(userId, args)
                "updateTask" -> runUpdateTask(userId, args)
                "deleteTask" -> runDeleteTask(userId, args)
                "setTaskCompletion" -> runSetTaskCompletion(userId, args)
                "setTaskSecret" -> runSetTaskSecret(userId, args)
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
    }
}
