package com.todoapp.backend.chat

import com.google.cloud.vertexai.api.FunctionDeclaration
import com.google.cloud.vertexai.api.Schema
import com.google.cloud.vertexai.api.Tool
import com.google.cloud.vertexai.api.Type

/**
 * The tool surface DoneBot is given. Tools execute on the backend (Postgres + transactional),
 * not on the device — the client has no tool registry of its own; it only short-circuits a
 * handful of local intents (today/overdue/weekly/hearts/pomodoro) before the request is sent.
 *
 * Keep this file 1:1 in sync with the names / parameter shapes the system instruction promises —
 * DoneBot's tool plan in the prompt only works if the declarations match exactly. A tool named in
 * the prompt but missing here makes the model hallucinate calls; one declared but unprompted is
 * dead weight in every request. Change both files in the same commit.
 */
object ChatToolDeclarations {
    val tool: Tool = Tool.newBuilder()
        .addAllFunctionDeclarations(
            listOf(
                getCurrentDate(),
                getTodaysTasks(),
                getOverdueTasks(),
                getTasksForDateRange(),
                getGroups(),
                getGroupTasks(),
                getCompletedTasksThisWeek(),
                getProductivityInsights(),
                findTaskByTitle(),
                createTask(),
                updateTask(),
                deleteTask(),
                setTaskCompletion(),
                setTaskSecret(),
                bulkSetTaskCompletion(),
                bulkDeleteTasks(),
                bulkRescheduleTasks(),
                setTaskLocation(),
                setTaskSchedule(),
                finishRoutine(),
                setSteps(),
                addStep(),
                renameStep(),
                setStepCompletion(),
                deleteStep(),
            ),
        )
        .build()

    private fun getCurrentDate(): FunctionDeclaration =
        FunctionDeclaration.newBuilder()
            .setName("getCurrentDate")
            .setDescription(
                "Returns today's date in ISO-8601 format (YYYY-MM-DD). " +
                    "Only call this when the [Context] block does not contain the date you need.",
            )
            .build()

    private fun getTodaysTasks(): FunctionDeclaration =
        FunctionDeclaration.newBuilder()
            .setName("getTodaysTasks")
            .setDescription(
                "Returns the user's tasks scheduled for today. " +
                    "Prefer the [Context] block if it lists today's tasks already.",
            )
            .build()

    private fun getOverdueTasks(): FunctionDeclaration =
        FunctionDeclaration.newBuilder()
            .setName("getOverdueTasks")
            .setDescription("Returns the user's incomplete tasks that are past their due date.")
            .build()

    private fun getTasksForDateRange(): FunctionDeclaration =
        FunctionDeclaration.newBuilder()
            .setName("getTasksForDateRange")
            .setDescription("Returns the user's tasks falling within an inclusive date range.")
            .setParameters(
                Schema.newBuilder()
                    .setType(Type.OBJECT)
                    .putProperties("startDate", isoDateSchema("Start date (inclusive), ISO YYYY-MM-DD."))
                    .putProperties("endDate", isoDateSchema("End date (inclusive), ISO YYYY-MM-DD."))
                    .addAllRequired(listOf("startDate", "endDate"))
                    .build(),
            )
            .build()

    private fun getGroups(): FunctionDeclaration =
        FunctionDeclaration.newBuilder()
            .setName("getGroups")
            .setDescription("Returns the user's family groups (id, name, member count).")
            .build()

    private fun getGroupTasks(): FunctionDeclaration =
        FunctionDeclaration.newBuilder()
            .setName("getGroupTasks")
            .setDescription(
                "READ-ONLY. Returns the shared tasks of ONE family group the user belongs to. " +
                    "Call getGroups first to get the group id. Use for 'what's left in the family " +
                    "group', 'what's assigned to me at home', 'ailedeki görevler ne durumda'. " +
                    "Group tasks can NEVER be created, edited, completed, rescheduled or deleted " +
                    "from chat — if the user asks for any of that, tell them to open that group's " +
                    "screen in the app. Returns at most 25 tasks, soonest first: `count` is how many " +
                    "came back, `totalCount` is how many there really are, and when `truncated` is " +
                    "true say the list is partial and point the user at the group screen.",
            )
            .setParameters(
                Schema.newBuilder()
                    .setType(Type.OBJECT)
                    .putProperties("groupId", longSchema("Numeric group id, from getGroups."))
                    .putProperties(
                        "onlyAssignedToMe",
                        Schema.newBuilder().setType(Type.BOOLEAN)
                            .setDescription("true to return only tasks assigned to the user. Defaults to false.")
                            .build(),
                    )
                    .putProperties(
                        "includeCompleted",
                        Schema.newBuilder().setType(Type.BOOLEAN)
                            .setDescription("true to also return finished tasks. Defaults to false (pending only).")
                            .build(),
                    )
                    .addAllRequired(listOf("groupId"))
                    .build(),
            )
            .build()

    private fun getCompletedTasksThisWeek(): FunctionDeclaration =
        FunctionDeclaration.newBuilder()
            .setName("getCompletedTasksThisWeek")
            .setDescription("Returns the count of tasks the user has completed in the last 7 days.")
            .build()

    private fun createTask(): FunctionDeclaration =
        FunctionDeclaration.newBuilder()
            .setName("createTask")
            .setDescription(
                "Creates a new personal task — this is also how you create a STAGED task (pass " +
                    "`steps`) and a CUSTOM one (pass `steps` AND `recurrence`). There is no separate " +
                    "staged-task create tool. Only title and date are required: timeStart defaults to " +
                    "09:00 (omit it entirely when isAllDay=true), timeEnd to timeStart+30min, " +
                    "category to PERSONAL, recurrence to NONE.",
            )
            .setParameters(
                Schema.newBuilder()
                    .setType(Type.OBJECT)
                    .putProperties("title", stringSchema("Task title. Max 255 characters; longer is truncated."))
                    .putProperties("date", isoDateSchema("Task date, ISO YYYY-MM-DD."))
                    .putProperties(
                        "timeStart",
                        timeSchema(
                            "Start time HH:mm in 24h. Optional — defaults to 09:00. " +
                                "Omit when isAllDay=true.",
                        ),
                    )
                    .putProperties("timeEnd", timeSchema("End time HH:mm in 24h. Optional."))
                    .putProperties(
                        "isAllDay",
                        Schema.newBuilder()
                            .setType(Type.BOOLEAN)
                            .setDescription(
                                "True for all-day tasks (no specific clock time). " +
                                    "Set when the user says 'whole day', 'all day', 'tüm gün', 'günboyu', etc. " +
                                    "When true, omit timeStart and timeEnd. Defaults to false.",
                            )
                            .build(),
                    )
                    .putProperties(
                        "description",
                        stringSchema("Optional description. Max 2000 characters; longer is truncated."),
                    )
                    .putProperties(
                        "category",
                        Schema.newBuilder()
                            .setType(Type.STRING)
                            .setDescription(
                                "Category. One of: SHOPPING, MEDICINE, HEALTH, WORK, STUDY, BIRTHDAY, PERSONAL, OTHER. " +
                                    "Defaults to PERSONAL. Pick the best match from context " +
                                    "(dentist/doctor → HEALTH, exam/study → STUDY, " +
                                    "alışveriş/grocery → SHOPPING, ilaç/medicine → MEDICINE, " +
                                    "iş/work → WORK, doğum günü/birthday → BIRTHDAY).",
                            )
                            .build(),
                    )
                    .putProperties(
                        "customCategoryName",
                        Schema.newBuilder()
                            .setType(Type.STRING)
                            .setDescription(
                                "Custom label for OTHER category (max 64 chars). Ignored unless category=OTHER.",
                            )
                            .build(),
                    )
                    .putProperties(
                        "recurrence",
                        Schema.newBuilder()
                            .setType(Type.STRING)
                            .setDescription(
                                "Recurrence. One of: NONE, DAILY, WEEKLY, MONTHLY, YEARLY. " +
                                    "Set when the user says 'every week', 'her hafta', 'monthly', etc. " +
                                    "Defaults to NONE.",
                            )
                            .build(),
                    )
                    .putProperties(
                        "recurrenceInterval",
                        Schema.newBuilder()
                            .setType(Type.INTEGER)
                            .setDescription(
                                "Repeat every N periods of `recurrence`. 1 = every period (default). " +
                                    "Use 2 for 'every other day', 'gün aşırı', 'every 2 weeks', etc. " +
                                    "Ignored when recurrence is NONE.",
                            )
                            .build(),
                    )
                    .putProperties(
                        "recurrenceByDay",
                        Schema.newBuilder()
                            .setType(Type.STRING)
                            .setDescription(
                                "WEEKLY only: comma-separated weekday names to fire on, e.g. " +
                                    "'MONDAY,WEDNESDAY,FRIDAY'. Use for 'Mon/Wed/Fri', 'hafta içi her gün' " +
                                    "(MONDAY..FRIDAY), 'weekends'. Omit to use the start date's own weekday.",
                            )
                            .build(),
                    )
                    .putProperties(
                        "recurrenceUntil",
                        isoDateSchema(
                            "Last day the routine repeats, inclusive, ISO YYYY-MM-DD. Use for " +
                                "'for a month', '1 ay boyunca', 'for 10 days'. Compute it from the start " +
                                "date. Omit for an open-ended routine.",
                        ),
                    )
                    .putProperties(
                        "steps",
                        Schema.newBuilder()
                            .setType(Type.ARRAY)
                            .setItems(stringSchema("A single step title."))
                            .setDescription(
                                "Ordered step titles, for a task done in stages. Max 20. May be combined " +
                                    "with `recurrence`: a repeating task's steps reset every occurrence " +
                                    "('every morning: water, vitamin, stretch').",
                            )
                            .build(),
                    )
                    .putProperties(
                        "reminderTimes",
                        Schema.newBuilder()
                            .setType(Type.ARRAY)
                            .setItems(timeSchema("A time of day, HH:mm."))
                            .setDescription(
                                "Absolute times of day to remind at on every occurrence, e.g. " +
                                    "['08:00','14:00','20:00'] for 'three times a day', 'günde 3 kez'. " +
                                    "Max 8. Every entry must be a real HH:mm — an unparseable one is " +
                                    "rejected, not skipped. Replaces reminderOffsetMinutes when set; " +
                                    "needs a recurrence.",
                            )
                            .build(),
                    )
                    .putProperties(
                        "reminderOffsetMinutes",
                        Schema.newBuilder()
                            .setType(Type.INTEGER)
                            .setDescription(
                                "Reminder offset in minutes before the task. " +
                                    "0 = no offset (default), 30 = remind 30 min before, etc. " +
                                    "Set when the user says 'remind me 15 min before', 'hatırlat 10 dakika önce', etc. " +
                                    "Use `reminderTimes` instead for a repeating task that reminds at fixed clock times.",
                            )
                            .build(),
                    )
                    .putProperties(
                        "isSecret",
                        Schema.newBuilder()
                            .setType(Type.BOOLEAN)
                            .setDescription("Mark task as secret (biometric-protected). Defaults to false.")
                            .build(),
                    )
                    .putProperties(
                        "locationName",
                        stringSchema(
                            "Short label for the location, e.g. 'Acıbadem Hastanesi', 'Office', 'Galata'. " +
                                "Optional. Set when the user mentions a place (\"at Kadıköy\", \"in Manhattan\").",
                        ),
                    )
                    .putProperties(
                        "locationAddress",
                        stringSchema(
                            "Fuller address line if known, e.g. 'Bağdat Cd. No:123, Kadıköy/İstanbul'. " +
                                "Optional. Often the same as locationName for short references.",
                        ),
                    )
                    .putProperties(
                        "locationLat",
                        Schema.newBuilder()
                            .setType(Type.NUMBER)
                            .setDescription(
                                "Latitude in decimal degrees. Optional. Only set if YOU truly know the " +
                                    "coordinates — never fabricate. The client refines coordinates via its " +
                                    "place picker.",
                            )
                            .build(),
                    )
                    .putProperties(
                        "locationLng",
                        Schema.newBuilder()
                            .setType(Type.NUMBER)
                            .setDescription("Longitude in decimal degrees. Optional. See locationLat.")
                            .build(),
                    )
                    .addAllRequired(listOf("title", "date"))
                    .build(),
            )
            .build()

    private fun updateTask(): FunctionDeclaration =
        FunctionDeclaration.newBuilder()
            .setName("updateTask")
            .setDescription(
                "Updates fields on an existing task. Only the user's own personal tasks are " +
                    "editable — group tasks return an error. Pass only the fields you want changed.",
            )
            .setParameters(
                Schema.newBuilder()
                    .setType(Type.OBJECT)
                    .putProperties("taskId", longSchema("Numeric task id."))
                    .putProperties(
                        "title",
                        stringSchema("New title (optional). Max 255 characters; longer is truncated."),
                    )
                    .putProperties("date", isoDateSchema("New date YYYY-MM-DD (optional)."))
                    .putProperties("timeStart", timeSchema("New start HH:mm (optional)."))
                    .putProperties("timeEnd", timeSchema("New end HH:mm (optional)."))
                    .putProperties(
                        "isAllDay",
                        Schema.newBuilder()
                            .setType(Type.BOOLEAN)
                            .setDescription(
                                "Set true to convert to all-day, false to convert back to a timed task. " +
                                    "Optional.",
                            )
                            .build(),
                    )
                    .putProperties(
                        "description",
                        stringSchema("New description (optional). Max 2000 characters; longer is truncated."),
                    )
                    .putProperties(
                        "category",
                        Schema.newBuilder()
                            .setType(Type.STRING)
                            .setDescription(
                                "New category (optional). One of: SHOPPING, MEDICINE, HEALTH, WORK, STUDY, BIRTHDAY, PERSONAL, OTHER.",
                            )
                            .build(),
                    )
                    .putProperties(
                        "customCategoryName",
                        Schema.newBuilder()
                            .setType(Type.STRING)
                            .setDescription(
                                "Custom label for OTHER category (max 64 chars). Optional. Ignored unless category=OTHER.",
                            )
                            .build(),
                    )
                    .putProperties(
                        "recurrence",
                        Schema.newBuilder()
                            .setType(Type.STRING)
                            .setDescription(
                                "New recurrence (optional). One of: NONE, DAILY, WEEKLY, MONTHLY, YEARLY. " +
                                    "Changing this normalizes the whole repeat rule: NONE clears the " +
                                    "interval, weekdays, end date, reminder times AND the finished-routine " +
                                    "mark, and any change of frequency un-retires a finished routine. To " +
                                    "change the rule itself rather than the frequency, use setTaskSchedule.",
                            )
                            .build(),
                    )
                    .putProperties(
                        "reminderOffsetMinutes",
                        Schema.newBuilder()
                            .setType(Type.INTEGER)
                            .setDescription(
                                "New reminder offset in minutes before the task. 0 to clear. Optional.",
                            )
                            .build(),
                    )
                    .putProperties(
                        "isSecret",
                        Schema.newBuilder()
                            .setType(Type.BOOLEAN)
                            .setDescription("Toggle biometric-protected flag. Optional.")
                            .build(),
                    )
                    .putProperties(
                        "locationName",
                        stringSchema("New location label (optional). Pass an empty string to clear."),
                    )
                    .putProperties(
                        "locationAddress",
                        stringSchema("New full address line (optional). Pass an empty string to clear."),
                    )
                    .putProperties(
                        "locationLat",
                        Schema.newBuilder()
                            .setType(Type.NUMBER)
                            .setDescription("New latitude (optional). Never fabricate.")
                            .build(),
                    )
                    .putProperties(
                        "locationLng",
                        Schema.newBuilder()
                            .setType(Type.NUMBER)
                            .setDescription("New longitude (optional). Never fabricate.")
                            .build(),
                    )
                    .addAllRequired(listOf("taskId"))
                    .build(),
            )
            .build()

    private fun setTaskLocation(): FunctionDeclaration =
        FunctionDeclaration.newBuilder()
            .setName("setTaskLocation")
            .setDescription(
                "Updates ONLY the location fields on an existing task. Use this when the user " +
                    "explicitly wants to add/change/clear the location and nothing else. " +
                    "Pass empty strings for locationName/locationAddress to clear. " +
                    "Group tasks return an error.",
            )
            .setParameters(
                Schema.newBuilder()
                    .setType(Type.OBJECT)
                    .putProperties("taskId", longSchema("Numeric task id."))
                    .putProperties(
                        "locationName",
                        stringSchema("Location label, e.g. 'Acıbadem Hastanesi'. Empty string clears."),
                    )
                    .putProperties(
                        "locationAddress",
                        stringSchema("Full address line. Empty string clears."),
                    )
                    .putProperties(
                        "locationLat",
                        Schema.newBuilder()
                            .setType(Type.NUMBER)
                            .setDescription("Latitude (optional). Never fabricate; client refines via picker.")
                            .build(),
                    )
                    .putProperties(
                        "locationLng",
                        Schema.newBuilder()
                            .setType(Type.NUMBER)
                            .setDescription("Longitude (optional). Never fabricate.")
                            .build(),
                    )
                    .addAllRequired(listOf("taskId"))
                    .build(),
            )
            .build()

    private fun deleteTask(): FunctionDeclaration =
        FunctionDeclaration.newBuilder()
            .setName("deleteTask")
            .setDescription(
                "Deletes a personal task by id, permanently. " +
                    "REQUIRES_CONFIRMATION: ALWAYS state the task (title + date) and ask the user to " +
                    "confirm BEFORE calling this tool — never on the same turn they ask for it. " +
                    "Cannot be undone. To stop a routine without losing its history use finishRoutine " +
                    "instead. Group tasks cannot be deleted from chat.",
            )
            .setParameters(
                Schema.newBuilder()
                    .setType(Type.OBJECT)
                    .putProperties("taskId", longSchema("Numeric task id."))
                    .addAllRequired(listOf("taskId"))
                    .build(),
            )
            .build()

    private fun setTaskCompletion(): FunctionDeclaration =
        FunctionDeclaration.newBuilder()
            .setName("setTaskCompletion")
            .setDescription("Marks a task complete or incomplete.")
            .setParameters(
                Schema.newBuilder()
                    .setType(Type.OBJECT)
                    .putProperties("taskId", longSchema("Numeric task id."))
                    .putProperties(
                        "isCompleted",
                        Schema.newBuilder().setType(Type.BOOLEAN).setDescription("true to complete.").build(),
                    )
                    .addAllRequired(listOf("taskId", "isCompleted"))
                    .build(),
            )
            .build()

    private fun setTaskSecret(): FunctionDeclaration =
        FunctionDeclaration.newBuilder()
            .setName("setTaskSecret")
            .setDescription("Toggles a task's secret (biometric-protected) flag.")
            .setParameters(
                Schema.newBuilder()
                    .setType(Type.OBJECT)
                    .putProperties("taskId", longSchema("Numeric task id."))
                    .putProperties(
                        "isSecret",
                        Schema.newBuilder().setType(Type.BOOLEAN).setDescription("true to mark secret.").build(),
                    )
                    .addAllRequired(listOf("taskId", "isSecret"))
                    .build(),
            )
            .build()

    private fun getProductivityInsights(): FunctionDeclaration =
        FunctionDeclaration.newBuilder()
            .setName("getProductivityInsights")
            .setDescription(
                "Returns productivity stats for a date range: completedCount, totalCount, " +
                    "completionPercent (0-100), activeDays (days in the range with at least one " +
                    "completion), rangeDays, and busiestDayName (day-of-week with most completions). " +
                    "Use for questions like 'how productive was I', 'best day', 'completed this " +
                    "month'. Do NOT call this for health points / hearts — the [Context] block " +
                    "already has them.",
            )
            .setParameters(
                Schema.newBuilder()
                    .setType(Type.OBJECT)
                    .putProperties(
                        "range",
                        Schema.newBuilder().setType(Type.STRING)
                            .setDescription(
                                "One of: 'week' (last 7 days, default), 'month' (last 30 days), 'all' (all time).",
                            )
                            .build(),
                    )
                    .build(),
            )
            .build()

    private fun findTaskByTitle(): FunctionDeclaration =
        FunctionDeclaration.newBuilder()
            .setName("findTaskByTitle")
            .setDescription(
                "Searches the user's personal tasks by title (case-insensitive substring match). " +
                    "Returns up to 5 matches with id, title, date, isCompleted. " +
                    "ALWAYS call this BEFORE update/delete/setCompletion when the user references a task " +
                    "by name without giving an id (e.g. 'delete the grocery task').",
            )
            .setParameters(
                Schema.newBuilder()
                    .setType(Type.OBJECT)
                    .putProperties("query", stringSchema("Title search substring (e.g. 'grocery', 'dentist')."))
                    .addAllRequired(listOf("query"))
                    .build(),
            )
            .build()

    private fun bulkSetTaskCompletion(): FunctionDeclaration =
        FunctionDeclaration.newBuilder()
            .setName("bulkSetTaskCompletion")
            .setDescription(
                "Marks multiple personal tasks complete or incomplete in one call. " +
                    "REQUIRES_CONFIRMATION: ALWAYS list the affected tasks (title + date) and ask " +
                    "the user to confirm BEFORE calling this tool. Group tasks are skipped.",
            )
            .setParameters(
                Schema.newBuilder()
                    .setType(Type.OBJECT)
                    .putProperties(
                        "taskIds",
                        Schema.newBuilder()
                            .setType(Type.ARRAY)
                            .setItems(longSchema("Task id."))
                            .setDescription("Numeric task ids to update.")
                            .build(),
                    )
                    .putProperties(
                        "isCompleted",
                        Schema.newBuilder().setType(Type.BOOLEAN)
                            .setDescription("true to complete, false to mark incomplete.").build(),
                    )
                    .addAllRequired(listOf("taskIds", "isCompleted"))
                    .build(),
            )
            .build()

    private fun bulkDeleteTasks(): FunctionDeclaration =
        FunctionDeclaration.newBuilder()
            .setName("bulkDeleteTasks")
            .setDescription(
                "Deletes multiple personal tasks in one call. " +
                    "REQUIRES_CONFIRMATION: ALWAYS list the affected tasks (title + date) and ask " +
                    "the user to confirm BEFORE calling this tool. Group tasks are skipped. Cannot be undone.",
            )
            .setParameters(
                Schema.newBuilder()
                    .setType(Type.OBJECT)
                    .putProperties(
                        "taskIds",
                        Schema.newBuilder()
                            .setType(Type.ARRAY)
                            .setItems(longSchema("Task id."))
                            .setDescription("Numeric task ids to delete.")
                            .build(),
                    )
                    .addAllRequired(listOf("taskIds"))
                    .build(),
            )
            .build()

    private fun bulkRescheduleTasks(): FunctionDeclaration =
        FunctionDeclaration.newBuilder()
            .setName("bulkRescheduleTasks")
            .setDescription(
                "Moves multiple personal tasks to a new date in one call. " +
                    "REQUIRES_CONFIRMATION: ALWAYS list the affected tasks (title + current date) and " +
                    "ask the user to confirm BEFORE calling this tool. Group tasks are skipped.",
            )
            .setParameters(
                Schema.newBuilder()
                    .setType(Type.OBJECT)
                    .putProperties(
                        "taskIds",
                        Schema.newBuilder()
                            .setType(Type.ARRAY)
                            .setItems(longSchema("Task id."))
                            .setDescription("Numeric task ids to reschedule.")
                            .build(),
                    )
                    .putProperties("newDate", isoDateSchema("Target date YYYY-MM-DD."))
                    .addAllRequired(listOf("taskIds", "newDate"))
                    .build(),
            )
            .build()

    private fun setTaskSchedule(): FunctionDeclaration =
        FunctionDeclaration.newBuilder()
            .setName("setTaskSchedule")
            .setDescription(
                "Updates ONLY how often a task repeats and when it reminds. Use this whenever the " +
                    "user changes the repeat frequency, the interval, the weekdays, the end date, or " +
                    "the reminder times of an existing task — updateTask CANNOT change any of those. " +
                    "Set recurrence=NONE to turn a routine back into a one-off; that also clears the " +
                    "interval, weekdays, end date and reminder times. Changing the frequency of a " +
                    "routine the user had finished also brings it back (say so in your reply). " +
                    "Omitted fields keep their current value; pass an empty string (or an empty array " +
                    "for reminderTimes) to clear one. An unparseable date or time is REJECTED and " +
                    "clears nothing — only an empty value clears. Group tasks return an error.",
            )
            .setParameters(
                Schema.newBuilder()
                    .setType(Type.OBJECT)
                    .putProperties("taskId", longSchema("Numeric task id."))
                    .putProperties(
                        "recurrence",
                        Schema.newBuilder().setType(Type.STRING)
                            .setDescription(
                                "New recurrence. One of: NONE, DAILY, WEEKLY, MONTHLY, YEARLY. Omit to " +
                                    "keep the current frequency and only change the rule below.",
                            )
                            .build(),
                    )
                    .putProperties(
                        "recurrenceInterval",
                        Schema.newBuilder().setType(Type.INTEGER)
                            .setDescription(
                                "Repeat every N periods of the recurrence. 1 = every period. Range 1-30. " +
                                    "Use 2 for 'every other day', 'gün aşırı', 'every 2 weeks'. Ignored " +
                                    "when the task ends up non-recurring.",
                            )
                            .build(),
                    )
                    .putProperties(
                        "recurrenceByDay",
                        Schema.newBuilder().setType(Type.STRING)
                            .setDescription(
                                "WEEKLY only: comma-separated weekday names to fire on, e.g. " +
                                    "'MONDAY,WEDNESDAY,FRIDAY'. Weekdays = MONDAY..FRIDAY. Pass an empty " +
                                    "string to clear it and fall back to the start date's own weekday.",
                            )
                            .build(),
                    )
                    .putProperties(
                        "recurrenceUntil",
                        isoDateSchema(
                            "Last day the routine repeats, inclusive, ISO YYYY-MM-DD. Compute it from " +
                                "the task's start date for 'for one more month', '2 hafta daha' — a " +
                                "phrase like 'end of next month' is rejected, send the computed date. " +
                                "Pass an empty string to make it open-ended again.",
                        ),
                    )
                    .putProperties(
                        "reminderTimes",
                        Schema.newBuilder()
                            .setType(Type.ARRAY)
                            .setItems(timeSchema("A time of day, HH:mm."))
                            .setDescription(
                                "Absolute times of day to remind at on every occurrence, e.g. " +
                                    "['08:00','14:00','20:00'] for 'three times a day', 'günde 3 kez'. " +
                                    "Max 8, every entry a real HH:mm — an unparseable one is rejected, " +
                                    "not skipped. Needs a recurrence and replaces reminderOffsetMinutes. " +
                                    "Pass an empty array to clear all of them.",
                            )
                            .build(),
                    )
                    .addAllRequired(listOf("taskId"))
                    .build(),
            )
            .build()

    private fun finishRoutine(): FunctionDeclaration =
        FunctionDeclaration.newBuilder()
            .setName("finishRoutine")
            .setDescription(
                "Retires or resumes a REPEATING task (a routine). finished=true stops it from " +
                    "appearing on days after `on` and keeps everything already done; finished=false " +
                    "brings it back. Use for 'I finished my medication course', 'stop the morning run " +
                    "for good', 'ilaç kürünü bitirdim', 'rutini geri getir'. This is NOT the same as " +
                    "ticking today's occurrence — use setTaskCompletion for that — and it is NOT " +
                    "deleting the task; the user keeps the history. A one-off (non-repeating) task " +
                    "returns an error. Group tasks return an error.",
            )
            .setParameters(
                Schema.newBuilder()
                    .setType(Type.OBJECT)
                    .putProperties("taskId", longSchema("Numeric id of the routine."))
                    .putProperties(
                        "finished",
                        Schema.newBuilder().setType(Type.BOOLEAN)
                            .setDescription("true to retire the routine, false to resume it.")
                            .build(),
                    )
                    .putProperties(
                        "on",
                        isoDateSchema(
                            "Day the routine is considered finished, ISO YYYY-MM-DD. Defaults to today; " +
                                "must be a real date, a phrase like 'end of last month' is rejected. " +
                                "Ignored when finished=false. Finishing a routine that is ALREADY " +
                                "finished returns noop:true and keeps the day it was finished on — pass " +
                                "`on` explicitly only when the user wants to CHANGE that day.",
                        ),
                    )
                    .addAllRequired(listOf("taskId", "finished"))
                    .build(),
            )
            .build()

    private fun setSteps(): FunctionDeclaration =
        FunctionDeclaration.newBuilder()
            .setName("setSteps")
            .setDescription(
                "Replaces a task's WHOLE ordered step list in one call. Use this when the user " +
                    "restates the list ('make the steps: a, b, c'), reorders it, or changes several " +
                    "steps at once — one call instead of a chain of addStep/renameStep/deleteStep. " +
                    "Steps you pass with their existing stepId keep their completion state and are " +
                    "reordered; steps you leave OUT are DELETED. For a single change still prefer " +
                    "addStep / renameStep / setStepCompletion / deleteStep. Pass an empty array to " +
                    "strip every step and turn it back into a plain task — that one is destructive and " +
                    "REQUIRES_CONFIRMATION: list the steps you are about to delete and get a yes " +
                    "first. Max 20 steps, each with a non-blank title (a blank one is rejected rather " +
                    "than deleting the step it matched). Get the stepIds from findTaskByTitle. Group " +
                    "tasks return an error.",
            )
            .setParameters(
                Schema.newBuilder()
                    .setType(Type.OBJECT)
                    .putProperties("taskId", longSchema("Numeric id of the parent task."))
                    .putProperties(
                        "steps",
                        Schema.newBuilder()
                            .setType(Type.ARRAY)
                            .setItems(
                                Schema.newBuilder()
                                    .setType(Type.OBJECT)
                                    .putProperties(
                                        "stepId",
                                        longSchema(
                                            "Existing step id from findTaskByTitle. OMIT for a brand-new step.",
                                        ),
                                    )
                                    .putProperties("title", stringSchema("Step title."))
                                    .putProperties(
                                        "isCompleted",
                                        Schema.newBuilder().setType(Type.BOOLEAN)
                                            .setDescription(
                                                "Optional. Defaults to the step's current state " +
                                                    "(or false for a new step).",
                                            )
                                            .build(),
                                    )
                                    .addAllRequired(listOf("title"))
                                    .build(),
                            )
                            .setDescription(
                                "The complete new step list, in the order it should appear. Max 20.",
                            )
                            .build(),
                    )
                    .addAllRequired(listOf("taskId", "steps"))
                    .build(),
            )
            .build()

    private fun addStep(): FunctionDeclaration =
        FunctionDeclaration.newBuilder()
            .setName("addStep")
            .setDescription(
                "Adds a new step to an existing personal (staged) task, appended at the end. " +
                    "Look up the task id via findTaskByTitle first if the user named it. " +
                    "Group tasks return an error.",
            )
            .setParameters(
                Schema.newBuilder()
                    .setType(Type.OBJECT)
                    .putProperties("taskId", longSchema("Numeric id of the parent task."))
                    .putProperties("title", stringSchema("Title of the new step."))
                    .addAllRequired(listOf("taskId", "title"))
                    .build(),
            )
            .build()

    private fun renameStep(): FunctionDeclaration =
        FunctionDeclaration.newBuilder()
            .setName("renameStep")
            .setDescription(
                "Renames a single step. Get the stepId from findTaskByTitle (each returned task " +
                    "lists its steps with stepId). Group tasks return an error.",
            )
            .setParameters(
                Schema.newBuilder()
                    .setType(Type.OBJECT)
                    .putProperties("stepId", longSchema("Numeric id of the step (from findTaskByTitle's steps)."))
                    .putProperties("title", stringSchema("New title for the step."))
                    .addAllRequired(listOf("stepId", "title"))
                    .build(),
            )
            .build()

    private fun setStepCompletion(): FunctionDeclaration =
        FunctionDeclaration.newBuilder()
            .setName("setStepCompletion")
            .setDescription(
                "Marks a single step complete or incomplete. The parent task auto-completes when " +
                    "all its steps are done (and reopens otherwise). Get the stepId from " +
                    "findTaskByTitle. Group tasks return an error.",
            )
            .setParameters(
                Schema.newBuilder()
                    .setType(Type.OBJECT)
                    .putProperties("stepId", longSchema("Numeric id of the step (from findTaskByTitle's steps)."))
                    .putProperties(
                        "isCompleted",
                        Schema.newBuilder().setType(Type.BOOLEAN).setDescription("true to complete the step.").build(),
                    )
                    .addAllRequired(listOf("stepId", "isCompleted"))
                    .build(),
            )
            .build()

    private fun deleteStep(): FunctionDeclaration =
        FunctionDeclaration.newBuilder()
            .setName("deleteStep")
            .setDescription(
                "Deletes a single step from a staged task. Cannot delete the last remaining step " +
                    "(delete the whole task with deleteTask instead). Get the stepId from " +
                    "findTaskByTitle. Group tasks return an error.",
            )
            .setParameters(
                Schema.newBuilder()
                    .setType(Type.OBJECT)
                    .putProperties("stepId", longSchema("Numeric id of the step (from findTaskByTitle's steps)."))
                    .addAllRequired(listOf("stepId"))
                    .build(),
            )
            .build()

    private fun stringSchema(desc: String): Schema =
        Schema.newBuilder().setType(Type.STRING).setDescription(desc).build()

    private fun isoDateSchema(desc: String): Schema =
        Schema.newBuilder().setType(Type.STRING).setFormat("date").setDescription(desc).build()

    private fun timeSchema(desc: String): Schema =
        Schema.newBuilder().setType(Type.STRING).setDescription(desc).build()

    private fun longSchema(desc: String): Schema =
        Schema.newBuilder().setType(Type.INTEGER).setDescription(desc).build()
}
