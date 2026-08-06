package com.todoapp.backend.chat

import com.google.protobuf.Struct
import com.google.protobuf.Value
import com.todoapp.backend.group.GroupMemberRepository
import com.todoapp.backend.group.GroupRepository
import com.todoapp.backend.task.REMINDER_OFF
import com.todoapp.backend.task.SubtaskRequest
import com.todoapp.backend.task.TaskCategory
import com.todoapp.backend.task.TaskEntity
import com.todoapp.backend.task.TaskRepository
import com.todoapp.backend.task.TaskSubtaskEntity
import com.todoapp.backend.task.TaskSubtaskRepository
import com.todoapp.backend.task.Recurrence
import com.todoapp.backend.task.reconcileSubtasks
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

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
                "getGroupTasks" -> runGetGroupTasks(userId, args)
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
                "setTaskSchedule" -> runSetTaskSchedule(userId, args)
                "finishRoutine" -> runFinishRoutine(userId, args)
                "setSteps" -> runSetSteps(userId, args)
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

    /**
     * READ-ONLY view of one group's shared tasks. Chat can never write a group task (every write path
     * returns [GROUP_TASK_BLOCKED]), so the payload deliberately carries **no id** — there is nothing to
     * chain into — and **no member names**: naming who a task is assigned to would ship other people's
     * display names to Vertex for zero capability gain. "Is it mine?" is all the model needs.
     */
    private fun runGetGroupTasks(userId: Long, args: Struct): Value {
        val groupId = args.fields["groupId"]?.numberValue?.toLong()
            ?: return errorPayload("groupId is required")
        // Membership, not existence — mirrors the IDOR gate the REST group endpoints use. Without it
        // any group id would enumerate someone else's shared tasks.
        if (members.findByGroupIdAndUserId(groupId, userId) == null) {
            return errorPayload("not a member of that group")
        }
        val onlyMine = args.fields["onlyAssignedToMe"]?.boolValue ?: false
        val includeCompleted = args.fields["includeCompleted"]?.boolValue ?: false
        // §7.14 defense in depth: group tasks are created with isSecret=false, but a personal secret
        // task moved into a group via POST /tasks would carry the flag, and a secret title must never
        // reach Vertex.
        val matched = taskRepo.findAllByFamilyGroupId(groupId)
            .filterNot { it.isSecret }
            .filter { includeCompleted || !it.isCompleted }
            .filter { !onlyMine || it.assignedToUserId == userId }
            .sortedBy { it.date }
        val tasks = matched.take(MAX_GROUP_TASKS)
        return objectValue(
            "readOnly" to boolValue(true),
            "tasks" to listValue(
                tasks.map { task ->
                    objectValue(
                        "title" to stringValue(task.title),
                        "date" to stringValue(LocalDate.ofEpochDay(task.date).toString()),
                        "isCompleted" to boolValue(task.isCompleted),
                        "assignedToMe" to boolValue(task.assignedToUserId == userId),
                        "hasAssignee" to boolValue(task.assignedToUserId != null),
                    )
                },
            ),
            // `count` is the length of the list above, as everywhere else in this file. `totalCount` is
            // what the group actually has: deriving the total from the truncated list told the model the
            // group had exactly 25 tasks, contradicting the [Context] block's own figure with no way to
            // tell which was right. Counted after the filters, so it can never re-expose a secret task.
            "count" to longValue(tasks.size.toLong()),
            "totalCount" to longValue(matched.size.toLong()),
            "truncated" to boolValue(matched.size > tasks.size),
        )
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

        // Active days replaced the old currentStreakDays when the app dropped streaks for the 12-heart
        // health-points bar: the bar awards a half-heart per ended day with >=1 completion, so this is
        // the same quantity without the streak semantics the UI no longer has anywhere. Hearts
        // themselves are device-derived and arrive in the [Context] block — never computed here.
        //
        // Like completedCount, this keys off TaskEntity.date rather than completedAt and ignores
        // task_daily_completions, so a routine ticked on five days counts once. That is this tool's
        // pre-existing behaviour; a completedAt-based rewrite is a separate change (the column is null
        // for pre-V27 rows).
        val activeDays = inRange.filter { it.isCompleted }.map { it.date }.distinct().size
        val rangeDays = if (range == "all") {
            inRange.minOfOrNull { it.date }?.let { todayEpoch - it + 1 } ?: 0L
        } else {
            todayEpoch - startEpoch + 1
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
            "activeDays" to longValue(activeDays.toLong()),
            "rangeDays" to longValue(rangeDays),
            "busiestDayName" to stringValue(busiestDay),
        )
    }

    // -------------------- Write tools --------------------

    /**
     * The gate every single-task write goes through: taskId present → task exists → caller owns it →
     * it is not a shared group task. Extracted because eight tools repeated it verbatim and a ninth
     * that forgot one line would be an IDOR or a silent group-task write. Adding a write tool now
     * means writing [block] and nothing else.
     *
     * Payload-level validation (`title`, `isCompleted`, …) belongs INSIDE [block] — the guard runs
     * first so a group task is refused as a group task, whatever else the model got wrong.
     */
    private fun withOwnedTask(userId: Long, args: Struct, block: (TaskEntity) -> Value): Value {
        val taskId = args.fields["taskId"]?.numberValue?.toLong()
            ?: return errorPayload("taskId is required")
        val task = taskRepo.findById(taskId).orElse(null)
            ?: return errorPayload("task not found")
        if (task.ownerId != userId) return errorPayload("not your task")
        if (task.familyGroupId != null) return errorPayload(GROUP_TASK_BLOCKED)
        return block(task)
    }

    /**
     * The extended repeat rule (interval / weekdays / end date / reminder times), clamped exactly as
     * createTask has always clamped it: interval bounded by the client's stepper, byDay normalized and
     * kept only for WEEKLY, reminderTimes deduped, sorted and capped at the alarm-slot count.
     *
     * A field ABSENT from [args] falls back to [current] — createTask passes the empty rule so absence
     * means "default", setTaskSchedule passes the stored rule so absence means "leave alone". A field
     * that is PRESENT but empty means CLEAR. That distinction is why presence is tested with
     * `containsFields` instead of a null-coalescing chain.
     *
     * A non-recurring result drops the whole rule: these fields are meaningless without a frequency,
     * and a model that sends "every other day" WITHOUT one must not produce a task the user believes
     * repeats.
     */
    private fun parseScheduleRule(args: Struct, recurrence: Recurrence, current: ScheduleRule): ScheduleRule {
        if (recurrence == Recurrence.NONE) return ScheduleRule()
        val interval = if (args.containsFields("recurrenceInterval")) {
            args.fields["recurrenceInterval"]?.numberValue?.toInt()?.coerceIn(1, MAX_RECURRENCE_INTERVAL) ?: 1
        } else {
            current.interval
        }
        val byDay = if (args.containsFields("recurrenceByDay")) {
            args.fields["recurrenceByDay"]?.stringValue?.takeIf { it.isNotBlank() }?.let(::normalizeByDay)
        } else {
            current.byDay
        }
        val until = if (args.containsFields("recurrenceUntil")) {
            args.fields["recurrenceUntil"]?.stringValue?.takeIf { it.isNotBlank() }
                ?.let { runCatching { LocalDate.parse(it).toEpochDay() }.getOrNull() }
        } else {
            current.until
        }
        val times = if (args.containsFields("reminderTimes")) {
            args.fields["reminderTimes"]?.listValue?.valuesList
                ?.mapNotNull { runCatching { parseTime(it.stringValue) }.getOrNull() }
                ?.distinct()?.sorted()?.take(MAX_REMINDER_TIMES)
                ?.takeIf { it.isNotEmpty() }
        } else {
            current.reminderTimes
        }
        return ScheduleRule(
            interval = interval,
            byDay = byDay,
            until = until,
            reminderTimes = times,
        ).normalizedFor(recurrence)
    }

    /**
     * The frequency and the four extended-recurrence columns, moved around as one value so the clamps
     * can't be split up — and so the frequency can't be written without them. `updateTask` used to set
     * `recurrence = NONE` and leave "every 2 weeks, MON/FRI, until 2026-09-01" behind on a task the
     * client then rendered as a one-off with recurring alarms.
     */
    private data class ScheduleRule(
        val recurrence: Recurrence = Recurrence.NONE,
        val interval: Int = 1,
        val byDay: String? = null,
        val until: Long? = null,
        val reminderTimes: List<Long>? = null,
    )

    /** Drops what a frequency cannot carry: no rule at all without one, and weekdays only for WEEKLY. */
    private fun ScheduleRule.normalizedFor(next: Recurrence): ScheduleRule =
        if (next == Recurrence.NONE) {
            ScheduleRule()
        } else {
            copy(recurrence = next, byDay = byDay?.takeIf { next == Recurrence.WEEKLY })
        }

    /** The five schedule columns read back off the entity as one value. */
    private fun TaskEntity.scheduleRule(): ScheduleRule = ScheduleRule(
        recurrence = recurrence,
        interval = recurrenceInterval,
        byDay = recurrenceByDay,
        until = recurrenceUntil,
        reminderTimes = reminderTimes
            ?.split(',')
            ?.mapNotNull { it.trim().toLongOrNull() }
            ?.takeIf { it.isNotEmpty() },
    )

    /**
     * The ONLY place the frequency and its four rule columns are written. Writing them apart is what
     * let `updateTask` produce a NONE task still carrying a repeat rule.
     *
     * It also enforces the one invariant that spans the pair: `finishedOn` ("I'm done with this
     * routine") only means something while there is a frequency to stop. So a task turned into a
     * one-off loses it, and so does a routine whose frequency changed — "make it weekly again" is the
     * user bringing it back, not asking for a routine that stays retired. Changing only the interval,
     * weekdays or reminder clock is a tweak, and leaves the retirement alone.
     *
     * Returns whether anything actually changed, which is what the tools report as `noop`.
     */
    private fun applySchedule(task: TaskEntity, next: ScheduleRule): Boolean {
        val current = task.scheduleRule()
        val nextFinishedOn = when {
            next.recurrence == Recurrence.NONE -> null
            next.recurrence != current.recurrence -> null
            else -> task.finishedOn
        }
        val changed = next != current || nextFinishedOn != task.finishedOn
        task.recurrence = next.recurrence
        task.recurrenceInterval = next.interval
        task.recurrenceByDay = next.byDay
        task.recurrenceUntil = next.until
        task.reminderTimes = next.reminderTimes?.joinToString(",")
        task.finishedOn = nextFinishedOn
        return changed
    }

    /**
     * The dates and clock times the model supplied, checked BEFORE anything is written. Returns a
     * message for the model, or null when everything round-trips.
     *
     * A BLANK value is not an error — that is the documented "clear this field". Conflating the two is
     * exactly the bug this exists to prevent: `runCatching { LocalDate.parse(it) }.getOrNull()` turned
     * "until the end of next month" into null, which under the present-means-clear rule WIPED the
     * user's end date and reported `ok:true`. Same shape as [runBulkRescheduleTasks], which has always
     * refused an unparseable date rather than substituting one.
     */
    private fun scheduleArgsError(args: Struct): String? {
        args.fields["recurrenceUntil"]?.stringValue?.takeIf { it.isNotBlank() }?.let {
            if (!isIsoDate(it)) {
                return "recurrenceUntil must be an ISO date like 2026-09-01 — pass an empty string to clear it"
            }
        }
        args.fields["reminderTimes"]?.listValue?.valuesList?.forEach { raw ->
            if (!isTimeOfDay(raw.stringValue)) {
                return "reminderTimes entries must be HH:mm clock times — pass an empty array to clear them"
            }
        }
        return null
    }

    private fun isIsoDate(raw: String): Boolean = runCatching { LocalDate.parse(raw) }.isSuccess

    private fun isTimeOfDay(raw: String): Boolean = runCatching { parseTime(raw) }.isSuccess

    private fun runCreateTask(userId: Long, args: Struct): Value {
        val title = args.fields["title"]?.stringValue.orEmpty().ifBlank {
            return errorPayload("title is required")
        }.capped(MAX_TITLE)
        scheduleArgsError(args)?.let { return errorPayload(it) }
        val date = LocalDate.parse(args.fields["date"]?.stringValue.orEmpty())
        val isAllDay = args.fields["isAllDay"]?.boolValue ?: false
        // For all-day tasks we normalize to 00:00 → 23:59:59 so listing/sorting code paths
        // that key off timeStart/timeEnd keep working — the client uses isAllDay to drive
        // UI rendering (suppress the time chip), not the timestamps themselves.
        val start = if (isAllDay) {
            0L
        } else {
            // Defaulting instead of erroring is what the system instruction has always promised
            // ("timeStart=09:00 unless isAllDay") — and it is the only thing createStagedTask did
            // that createTask couldn't, which is what let that duplicate tool be retired.
            args.fields["timeStart"]?.stringValue?.takeIf { it.isNotBlank() }?.let(::parseTime)
                ?: DEFAULT_START_SECONDS
        }
        val end = if (isAllDay) {
            SECONDS_PER_DAY - 1
        } else {
            args.fields["timeEnd"]?.stringValue?.takeIf { it.isNotBlank() }?.let(::parseTime)
                ?: (start + DEFAULT_DURATION_SECONDS).coerceAtMost(SECONDS_PER_DAY - 1)
        }
        val description = args.fields["description"]?.stringValue?.takeIf { it.isNotBlank() }
            ?.capped(MAX_DESCRIPTION)
        val category = args.fields["category"]?.stringValue?.takeIf { it.isNotBlank() }
            ?.let { runCatching { TaskCategory.valueOf(it.uppercase()) }.getOrNull() }
            ?: TaskCategory.PERSONAL
        val customCategoryName = args.fields["customCategoryName"]?.stringValue?.takeIf { it.isNotBlank() }
            ?.takeIf { category == TaskCategory.OTHER }
            ?.capped(MAX_CUSTOM_CATEGORY_NAME)
        val recurrence = args.fields["recurrence"]?.stringValue?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Recurrence.valueOf(it.uppercase()) }.getOrNull() }
            ?: Recurrence.NONE
        // Floor at REMINDER_OFF, not at 0. Clamping to 0 turned "no reminder" into "remind at the
        // task's start time" — the client's two distinct choices collapsed into the noisier one, and
        // the next sync pushed that back to every device.
        val reminderOffsetMinutes = args.fields["reminderOffsetMinutes"]?.numberValue?.toLong()
            ?.coerceAtLeast(REMINDER_OFF)
            ?: 0L
        val isSecret = args.fields["isSecret"]?.boolValue ?: false
        val locationName = args.fields["locationName"]?.stringValue?.takeIf { it.isNotBlank() }
            ?.capped(MAX_LOCATION_NAME)
        val locationAddress = args.fields["locationAddress"]?.stringValue?.takeIf { it.isNotBlank() }
            ?.capped(MAX_LOCATION_ADDRESS)
        val locationLat = args.fields["locationLat"]?.numberValue?.takeIf { it != 0.0 || locationName != null }
            ?.toBigDecimal()
        val locationLng = args.fields["locationLng"]?.numberValue?.takeIf { it != 0.0 || locationName != null }
            ?.toBigDecimal()
        // On create an absent field means "use the default", so the fallback is the empty rule.
        // setTaskSchedule passes the stored rule instead, which is the whole reason this is shared.
        val rule = parseScheduleRule(args, recurrence, ScheduleRule())
        val stepTitles = args.fields["steps"]?.listValue?.valuesList.orEmpty()
        if (stepTitles.size > MAX_STEPS) return tooManyStepsError()
        val steps = stepTitles
            .mapNotNull { it.stringValue?.trim()?.takeIf(String::isNotEmpty)?.capped(MAX_STEP_TITLE) }

        // §4.12 for the chat path. The model never supplies an idempotency key, so we derive a
        // deterministic one from (user, title, date, start, today): a turn retried within the same day
        // dedups against idx_tasks_owner_client, while deliberately creating the same task again
        // tomorrow still lands. Name-based (v3) UUIDs carry a different version nibble than the
        // client's random (v4) keys, so a derived key can never collide with a client-minted one.
        //
        // The pre-check alone is the guarantee here, deliberately without TaskService.create's
        // saveAndFlush/DataIntegrityViolationException dance: chat turns for one user are serialized by
        // the client (isThinking gate + 3s send throttle), so the duplicate we actually see is a
        // sequential retry, not a concurrent one. Catching the violation inside `execute`'s transaction
        // would leave it rollback-only and take down the whole turn instead of just this tool call.
        // The other way into that trap — an over-long value overflowing its column — is closed by
        // [capped], which every model-supplied string on this path goes through.
        val clientTaskId = chatIdempotencyKey(userId, title, date.toEpochDay(), start)
        taskRepo.findByOwnerIdAndClientTaskId(userId, clientTaskId)?.let { existing ->
            return objectValue(
                "ok" to boolValue(true),
                "duplicate" to boolValue(true),
                "task" to taskValue(existing, includeId = false, includeSteps = true),
            )
        }

        val saved = taskRepo.save(
            TaskEntity(
                ownerId = userId,
                clientTaskId = clientTaskId,
                title = title,
                description = description,
                date = date.toEpochDay(),
                timeStart = start,
                timeEnd = end,
                isSecret = isSecret,
                category = category,
                customCategoryName = customCategoryName,
                recurrence = rule.recurrence,
                isAllDay = isAllDay,
                reminderOffsetMinutes = reminderOffsetMinutes,
                locationLat = locationLat,
                locationLng = locationLng,
                locationName = locationName,
                locationAddress = locationAddress,
                recurrenceInterval = rule.interval,
                recurrenceByDay = rule.byDay,
                recurrenceUntil = rule.until,
                reminderTimes = rule.reminderTimes?.joinToString(","),
            ),
        )
        steps.forEachIndexed { index, stepTitle ->
            subtaskRepo.save(TaskSubtaskEntity(taskId = saved.id, title = stepTitle, orderIndex = index))
        }
        // Write-tool responses omit the numeric id — the model already had it as input
        // (or doesn't need one for confirmation), and we don't want it surfacing in the
        // user-facing reply.
        return objectValue(
            "ok" to boolValue(true),
            "task" to taskValue(saved, includeId = false, includeSteps = steps.isNotEmpty()),
        )
    }

    /**
     * Deterministic idempotency key for a chat-created task, scoped to the day so that "add gym at 7"
     * asked twice in one conversation is one task, while the same task asked for again tomorrow is two.
     * `nameUUIDFromBytes` yields a 36-char v3 UUID, matching the column width exactly.
     */
    private fun chatIdempotencyKey(userId: Long, title: String, date: Long, start: Long): String =
        UUID.nameUUIDFromBytes(
            "$userId|${title.trim().lowercase()}|$date|$start|${LocalDate.now()}".toByteArray(),
        ).toString()

    /**
     * Stamp `completedAt` only on the false→true edge and clear it on true→false — the same rule
     * `TaskService.update` follows. Writing `Instant.now()` unconditionally would re-date a week-old
     * completion into today the next time anything about the task changed, inflating every
     * "completed today" figure. Chat skipped this field entirely, so a task the bot ticked was
     * invisible to every query that keys off `completedAt`.
     */
    private fun TaskEntity.applyCompletion(next: Boolean) {
        if (next && !isCompleted) {
            completedAt = Instant.now()
        } else if (!next) {
            completedAt = null
        }
        isCompleted = next
    }

    /** Keeps only real weekday names, uppercased, in weekday order — the client parses the same CSV. */
    private fun normalizeByDay(raw: String): String? = raw.split(',')
        .mapNotNull { runCatching { java.time.DayOfWeek.valueOf(it.trim().uppercase()) }.getOrNull() }
        .distinct()
        .sortedBy { it.value }
        .joinToString(",") { it.name }
        .takeIf { it.isNotBlank() }

    private fun runUpdateTask(userId: Long, args: Struct): Value = withOwnedTask(userId, args) { task ->
        // Parsed BEFORE the first assignment below. These used to throw mid-update, and because the
        // entity is managed inside `execute`'s transaction, the fields already assigned were flushed
        // anyway — the tool reported an error and still renamed the task.
        val newDate = args.fields["date"]?.stringValue?.takeIf { it.isNotBlank() }?.let {
            if (!isIsoDate(it)) return@withOwnedTask errorPayload("date must be an ISO date like 2026-09-01")
            LocalDate.parse(it).toEpochDay()
        }
        val newStart = args.fields["timeStart"]?.stringValue?.takeIf { it.isNotBlank() }?.let {
            if (!isTimeOfDay(it)) return@withOwnedTask errorPayload("timeStart must be an HH:mm clock time")
            parseTime(it)
        }
        val newEnd = args.fields["timeEnd"]?.stringValue?.takeIf { it.isNotBlank() }?.let {
            if (!isTimeOfDay(it)) return@withOwnedTask errorPayload("timeEnd must be an HH:mm clock time")
            parseTime(it)
        }

        // Track whether any field actually changed so the model can phrase its reply
        // without claiming a change that didn't happen ("Already at 3pm — nothing to do").
        var changed = false
        args.fields["title"]?.stringValue?.takeIf { it.isNotBlank() }?.capped(MAX_TITLE)?.let {
            if (it != task.title) { task.title = it; changed = true }
        }
        newDate?.let {
            if (it != task.date) { task.date = it; changed = true }
        }
        newStart?.let {
            if (it != task.timeStart) { task.timeStart = it; changed = true }
        }
        newEnd?.let {
            if (it != task.timeEnd) { task.timeEnd = it; changed = true }
        }
        args.fields["description"]?.stringValue?.let {
            val newValue = it.ifBlank { null }?.capped(MAX_DESCRIPTION)
            if (newValue != task.description) { task.description = newValue; changed = true }
        }
        args.fields["category"]?.stringValue?.takeIf { it.isNotBlank() }?.let { raw ->
            runCatching { TaskCategory.valueOf(raw.uppercase()) }.getOrNull()?.let {
                if (it != task.category) { task.category = it; changed = true }
            }
        }
        args.fields["customCategoryName"]?.stringValue?.let { raw ->
            val newValue = raw.ifBlank { null }?.capped(MAX_CUSTOM_CATEGORY_NAME)
                ?.takeIf { task.category == TaskCategory.OTHER }
            if (newValue != task.customCategoryName) {
                task.customCategoryName = newValue
                changed = true
            }
        }
        args.fields["recurrence"]?.stringValue?.takeIf { it.isNotBlank() }?.let { raw ->
            runCatching { Recurrence.valueOf(raw.uppercase()) }.getOrNull()?.let { next ->
                // Through applySchedule, never by assigning `recurrence` alone: switching a routine to
                // NONE has to take the interval, weekdays, end date, reminder times and the
                // finished-routine mark with it. Called even when the frequency already matches, so a
                // row left inconsistent by an older build heals on the next write.
                if (applySchedule(task, task.scheduleRule().normalizedFor(next))) changed = true
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
            val newValue = raw.numberValue.toLong().coerceAtLeast(REMINDER_OFF)
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
            val newValue = raw.takeIf { it.isNotBlank() }?.capped(MAX_LOCATION_NAME)
            if (newValue != task.locationName) {
                task.locationName = newValue
                changed = true
            }
        }
        args.fields["locationAddress"]?.stringValue?.let { raw ->
            val newValue = raw.takeIf { it.isNotBlank() }?.capped(MAX_LOCATION_ADDRESS)
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
        objectValue(
            "ok" to boolValue(true),
            "noop" to boolValue(!changed),
            "task" to taskValue(taskRepo.save(task), includeId = false),
        )
    }

    private fun runSetTaskLocation(userId: Long, args: Struct): Value = withOwnedTask(userId, args) { task ->
        // Empty string in any field clears it; omitted means leave alone.
        args.fields["locationName"]?.stringValue?.let { raw ->
            task.locationName = raw.takeIf { it.isNotBlank() }?.capped(MAX_LOCATION_NAME)
        }
        args.fields["locationAddress"]?.stringValue?.let { raw ->
            task.locationAddress = raw.takeIf { it.isNotBlank() }?.capped(MAX_LOCATION_ADDRESS)
        }
        args.fields["locationLat"]?.let { raw -> task.locationLat = raw.numberValue.toBigDecimal() }
        args.fields["locationLng"]?.let { raw -> task.locationLng = raw.numberValue.toBigDecimal() }
        // Pair-clear: clearing the name+address typically means dropping the pin too.
        if (task.locationName == null && task.locationAddress == null) {
            task.locationLat = null
            task.locationLng = null
        }
        objectValue(
            "ok" to boolValue(true),
            "task" to taskValue(taskRepo.save(task), includeId = false),
        )
    }

    private fun runDeleteTask(userId: Long, args: Struct): Value = withOwnedTask(userId, args) { task ->
        // Capture title for the model's confirmation reply (no id leak).
        val deletedTitle = task.title
        // Steps are removed by the DB FK (ON DELETE CASCADE); deleting the task is enough.
        taskRepo.delete(task)
        objectValue(
            "ok" to boolValue(true),
            "deletedTitle" to stringValue(deletedTitle),
        )
    }

    /**
     * The repeat rule and the reminder clock times, which [runUpdateTask] deliberately does not touch —
     * "make it every other week" used to change the frequency and silently leave the interval at 1.
     * Splitting it out mirrors [runSetTaskLocation]: one tool, one concern, one description the model
     * can match against instead of sixteen parameters on updateTask.
     */
    private fun runSetTaskSchedule(userId: Long, args: Struct): Value = withOwnedTask(userId, args) { task ->
        // After the ownership gate, never before it: a validation message emitted first would confirm
        // that someone else's task id exists.
        scheduleArgsError(args)?.let { return@withOwnedTask errorPayload(it) }
        val recurrence = args.fields["recurrence"]?.stringValue?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Recurrence.valueOf(it.uppercase()) }.getOrNull() }
            ?: task.recurrence
        // Absence means "leave alone" here, which is why the stored rule is the fallback — createTask
        // passes the empty one so absence means "default". Same clamps either way.
        val changed = applySchedule(task, parseScheduleRule(args, recurrence, task.scheduleRule()))
        objectValue(
            "ok" to boolValue(true),
            "noop" to boolValue(!changed),
            "task" to taskValue(taskRepo.save(task), includeId = false),
        )
    }

    /**
     * Retire or resume a routine — the chat equivalent of the app's long-press "finish routine".
     * Distinct from completion in both directions: [runSetTaskCompletion] ticks the task, this stops
     * it from firing on later days while keeping every past occurrence, and deleteTask would throw the
     * history away.
     */
    private fun runFinishRoutine(userId: Long, args: Struct): Value = withOwnedTask(userId, args) { task ->
        val finished = args.fields["finished"]?.boolValue
            ?: return@withOwnedTask errorPayload("finished is required")
        // A one-off has no later occurrences to stop, so finishedOn would be a silent no-op the model
        // would still report as success. Send it to the tool that actually does something.
        if (task.recurrence == Recurrence.NONE) {
            return@withOwnedTask errorPayload(
                "not a routine — this task does not repeat; use setTaskCompletion to tick it off",
            )
        }
        val explicitOn = args.fields["on"]?.stringValue?.takeIf { it.isNotBlank() }
        if (explicitOn != null && !isIsoDate(explicitOn)) {
            // Substituting today for "end of last month" retired the routine a month late and still
            // reported success, so the user's history for that window was silently wrong.
            return@withOwnedTask errorPayload("on must be an ISO date like 2026-08-06")
        }
        val on = when {
            !finished -> null
            // Already retired and no explicit date: re-finishing is the model confirming, not the user
            // re-deciding. Re-stamping would move the retirement forward and flip every day in between
            // back into the routine.
            task.finishedOn != null && explicitOn == null -> task.finishedOn
            explicitOn != null -> LocalDate.parse(explicitOn).toEpochDay()
            else -> LocalDate.now().toEpochDay()
        }
        val noop = task.finishedOn == on
        task.finishedOn = on
        objectValue(
            "ok" to boolValue(true),
            "noop" to boolValue(noop),
            "task" to taskValue(taskRepo.save(task), includeId = false),
        )
    }

    // -------------------- Staged-task (step) write tools --------------------

    /**
     * Replace a task's whole step list in one call — what the app's edit screen does when the user
     * rewrites or reorders the list, and what the per-step tools would need three or four round-trips
     * to achieve.
     *
     * The reconcile itself is `TaskSubtaskReconciler.reconcileSubtasks` — the same function the REST
     * task and group-task paths use (stepId ≡ remoteId, order re-packed from the incoming list,
     * anything absent deleted). This used to be a hand-copied second implementation of it, and the two
     * had already drifted. All that is left here is mapping the protobuf Struct into its DTO.
     */
    private fun runSetSteps(userId: Long, args: Struct): Value = withOwnedTask(userId, args) { task ->
        val incoming = args.fields["steps"]?.listValue?.valuesList
            ?: return@withOwnedTask errorPayload(
                "steps is required (pass an empty array to remove every step)",
            )
        if (incoming.size > MAX_STEPS) return@withOwnedTask tooManyStepsError()
        val existingById = subtaskRepo.findAllByTaskIdOrderByOrderIndexAsc(task.id).associateBy { it.id }
        // Reject, don't skip. Skipping a blank-titled entry DELETED the step it matched — the list is
        // authoritative, so anything left out is removed — which is silent data loss in a tool the
        // model may call without confirmation.
        if (incoming.any { it.structValue.fieldsMap["title"]?.stringValue?.isBlank() != false }) {
            return@withOwnedTask errorPayload("every step needs a non-blank title")
        }
        val requests = incoming.map { raw ->
            val fields = raw.structValue.fieldsMap
            val match = fields["stepId"]?.numberValue?.toLong()?.let { existingById[it] }
            SubtaskRequest(
                // The matched step's own id, not the raw stepId: an id belonging to someone else's task
                // has to fall through to "brand-new step" rather than address a row we didn't check.
                remoteId = match?.id,
                title = fields.getValue("title").stringValue.trim().capped(MAX_STEP_TITLE),
                // A step carried over keeps its tick unless the model explicitly said otherwise; a
                // brand-new one starts unchecked. Losing ticks on a reorder would be the obvious
                // failure here.
                isCompleted = fields["isCompleted"]?.boolValue ?: match?.isCompleted ?: false,
            )
        }
        reconcileSubtasks(subtaskRepo, task.id, requests)
        recomputeStagedParentCompletion(task)
        objectValue(
            "ok" to boolValue(true),
            "task" to taskValue(task, includeId = false, includeSteps = true),
        )
    }

    private fun runAddStep(userId: Long, args: Struct): Value = withOwnedTask(userId, args) { task ->
        val title = args.fields["title"]?.stringValue?.trim()?.takeIf { it.isNotBlank() }?.capped(MAX_STEP_TITLE)
            ?: return@withOwnedTask errorPayload("title is required")
        val order = subtaskRepo.findAllByTaskIdOrderByOrderIndexAsc(task.id).size
        subtaskRepo.save(TaskSubtaskEntity(taskId = task.id, title = title, orderIndex = order))
        recomputeStagedParentCompletion(task)
        objectValue(
            "ok" to boolValue(true),
            "task" to taskValue(task, includeId = false, includeSteps = true),
        )
    }

    private fun runRenameStep(userId: Long, args: Struct): Value {
        val title = args.fields["title"]?.stringValue?.trim()?.takeIf { it.isNotBlank() }?.capped(MAX_STEP_TITLE)
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
        if (task.familyGroupId != null) return errorPayload(GROUP_TASK_BLOCKED)
        return errorPayload("not your task")
    }

    /** Parent is done iff it has steps and all are done; reopens otherwise (matches the client). */
    private fun recomputeStagedParentCompletion(task: TaskEntity) {
        val steps = subtaskRepo.findAllByTaskIdOrderByOrderIndexAsc(task.id)
        val done = steps.isNotEmpty() && steps.all { it.isCompleted }
        if (task.isCompleted != done) {
            task.applyCompletion(done)
            taskRepo.save(task)
        }
    }

    private fun runSetTaskCompletion(userId: Long, args: Struct): Value = withOwnedTask(userId, args) { task ->
        val isCompleted = args.fields["isCompleted"]?.boolValue
            ?: return@withOwnedTask errorPayload("isCompleted is required")
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
        task.applyCompletion(isCompleted)
        objectValue(
            "ok" to boolValue(true),
            "noop" to boolValue(noop),
            "task" to taskValue(taskRepo.save(task), includeId = false),
        )
    }

    private fun runSetTaskSecret(userId: Long, args: Struct): Value = withOwnedTask(userId, args) { task ->
        val isSecret = args.fields["isSecret"]?.boolValue
            ?: return@withOwnedTask errorPayload("isSecret is required")
        val noop = task.isSecret == isSecret
        task.isSecret = isSecret
        objectValue(
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
            task.applyCompletion(isCompleted)
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
        // Readable back so the bot can tell a retired routine from a live one — without it, it would
        // happily "finish" an already-finished routine and report success.
        task.finishedOn?.let {
            struct.putFields("finishedOn", stringValue(LocalDate.ofEpochDay(it).toString()))
        }
        // Negative is "no reminder" and 0 is "at the start time" — both are real answers, and omitting
        // them left the bot unable to tell either from "I don't know", so it would offer to add a
        // reminder a task already had, or claim one that was switched off.
        struct.putFields("reminderOffsetMinutes", longValue(task.reminderOffsetMinutes))
        if (task.locationName != null) {
            struct.putFields("locationName", stringValue(task.locationName!!))
        }
        if (task.locationAddress != null) {
            struct.putFields("locationAddress", stringValue(task.locationAddress!!))
        }
        // The extended rule must be readable back, or the bot can't answer "how often is this?" or
        // confirm what it just created beyond the bare frequency.
        if (task.recurrenceInterval > 1) {
            struct.putFields("recurrenceInterval", longValue(task.recurrenceInterval.toLong()))
        }
        task.recurrenceByDay?.let { struct.putFields("recurrenceByDay", stringValue(it)) }
        task.recurrenceUntil?.let {
            struct.putFields("recurrenceUntil", stringValue(LocalDate.ofEpochDay(it).toString()))
        }
        task.reminderTimes?.takeIf { it.isNotBlank() }?.let { csv ->
            val times = csv.split(',').mapNotNull { it.trim().toLongOrNull() }
            if (times.isNotEmpty()) {
                struct.putFields("reminderTimes", listValue(times.map { stringValue(formatTime(it)) }))
            }
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

    /**
     * Every string the model supplies is untrusted for LENGTH as well as for content. An over-long
     * value raises `DataIntegrityViolationException` at flush — inside [execute]'s transaction, which
     * marks it rollback-only, so one bad tool call becomes an `UnexpectedRollbackException` thrown from
     * the proxy AFTER `runCatching` already returned its tidy error payload: an HTTP 500 for the whole
     * chat turn instead of one failed tool. Truncating is the only behaviour that keeps the turn alive,
     * and it is honest because [taskValue] hands the STORED value back — the model confirms what was
     * actually written, not what it sent.
     *
     * Surrogate-aware: a plain `take(255)` can split a pair and store a lone half.
     */
    private fun String.capped(max: Int): String =
        if (length <= max) this else take(if (Character.isHighSurrogate(this[max - 1])) max - 1 else max)

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

    /** Shared by createTask and setSteps so one cap can never be enforced with two different messages. */
    private fun tooManyStepsError(): Value =
        errorPayload("too many steps — a task can have at most $MAX_STEPS")

    companion object {
        private const val DEFAULT_DURATION_SECONDS = 30L * 60L
        private const val DEFAULT_START_SECONDS = 9L * 3600L
        private const val SECONDS_PER_DAY = 24L * 3600L
        private const val WEEK_LOOKBACK_DAYS = 6L
        private const val MONTH_LOOKBACK_DAYS = 29L
        private const val PERCENT_SCALE = 100.0

        /**
         * Column widths, not preferences. Every one of these mirrors a VARCHAR in the schema, because
         * the failure mode of an uncapped model-supplied string is not a long title — it is a
         * `DataIntegrityViolationException` at flush time that 500s the whole chat turn (see [capped]).
         * `MAX_TITLE` is deliberately separate from `MAX_STEP_TITLE` despite both being 255: they are
         * different columns and widening one must not silently widen the other.
         */
        private const val MAX_TITLE = 255
        private const val MAX_DESCRIPTION = 2000
        private const val MAX_STEP_TITLE = 255
        private const val MAX_CUSTOM_CATEGORY_NAME = 64
        private const val MAX_LOCATION_NAME = 120
        private const val MAX_LOCATION_ADDRESS = 500

        /**
         * A step list is written one INSERT at a time inside the turn deadline, so its length is the
         * one model-supplied size that costs wall-clock rather than bytes. Well past any hand-managed
         * checklist; raise it if the app's own edit screen ever allows more, because a list the user
         * legitimately built there must not be un-editable from chat.
         */
        private const val MAX_STEPS = 20

        /** One group's shared tasks are a summary, not a listing — the app's group screen is the listing. */
        private const val MAX_GROUP_TASKS = 25

        /** Mirrors the client: interval is bounded by its stepper, reminders by its alarm slots. */
        private const val MAX_RECURRENCE_INTERVAL = 30
        private const val MAX_REMINDER_TIMES = 8

        /**
         * The single refusal every write tool returns for a shared group task. The system instruction
         * matches on the `group_task_blocked` prefix, so the wording must stay stable — keeping it in
         * one place is what makes a new write tool physically unable to drift from the other seven.
         */
        private const val GROUP_TASK_BLOCKED =
            "group_task_blocked: shared group tasks must be edited from the group screen, not chat"

        /**
         * Every branch of [execute]'s dispatch that writes. Exists so `ChatService` can tell the client
         * "this turn changed something" without the client carrying its own copy of these names — a copy
         * that silently goes stale the moment a write tool is added here. Adding a tool to the `when`
         * above without adding it here is the mistake this comment is trying to prevent.
         */
        internal val MUTATING_TOOLS: Set<String> = setOf(
            "createTask", "updateTask", "deleteTask", "setTaskCompletion", "setTaskSecret",
            "setTaskLocation", "setTaskSchedule", "finishRoutine",
            "setSteps", "addStep", "renameStep", "setStepCompletion", "deleteStep",
            "bulkSetTaskCompletion", "bulkDeleteTasks", "bulkRescheduleTasks",
        )
    }
}
