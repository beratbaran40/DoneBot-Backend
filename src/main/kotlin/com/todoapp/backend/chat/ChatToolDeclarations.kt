package com.todoapp.backend.chat

import com.google.cloud.vertexai.api.FunctionDeclaration
import com.google.cloud.vertexai.api.Schema
import com.google.cloud.vertexai.api.Tool
import com.google.cloud.vertexai.api.Type

/**
 * Server-side mirror of the client's `ChatToolDeclarations.kt`. Re-declared here
 * because tools execute on the backend now (Postgres + transactional) instead
 * of in-process on the device.
 *
 * Keep this file 1:1 in sync with the names / parameter shapes the system
 * instruction promises — DoneBot's tool plan in the prompt only works if the
 * declarations match exactly.
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
                createStagedTask(),
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

    private fun getCompletedTasksThisWeek(): FunctionDeclaration =
        FunctionDeclaration.newBuilder()
            .setName("getCompletedTasksThisWeek")
            .setDescription("Returns the count of tasks the user has completed in the last 7 days.")
            .build()

    private fun createTask(): FunctionDeclaration =
        FunctionDeclaration.newBuilder()
            .setName("createTask")
            .setDescription(
                "Creates a new personal task. " +
                    "timeStart is required UNLESS isAllDay=true (then omit timeStart/timeEnd). " +
                    "timeEnd defaults to timeStart+30min when missing. " +
                    "Category defaults to PERSONAL. Recurrence defaults to NONE.",
            )
            .setParameters(
                Schema.newBuilder()
                    .setType(Type.OBJECT)
                    .putProperties("title", stringSchema("Task title."))
                    .putProperties("date", isoDateSchema("Task date, ISO YYYY-MM-DD."))
                    .putProperties(
                        "timeStart",
                        timeSchema(
                            "Start time HH:mm in 24h. Omit when isAllDay=true.",
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
                    .putProperties("description", stringSchema("Optional description."))
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
                        "reminderOffsetMinutes",
                        Schema.newBuilder()
                            .setType(Type.INTEGER)
                            .setDescription(
                                "Reminder offset in minutes before the task. " +
                                    "0 = no offset (default), 30 = remind 30 min before, etc. " +
                                    "Set when the user says 'remind me 15 min before', 'hatırlat 10 dakika önce', etc.",
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
                    .putProperties("title", stringSchema("New title (optional)."))
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
                    .putProperties("description", stringSchema("New description (optional)."))
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
                                "New recurrence (optional). One of: NONE, DAILY, WEEKLY, MONTHLY, YEARLY.",
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
            .setDescription("Deletes a personal task by id. Group tasks cannot be deleted from chat.")
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
                    "completionPercent (0-100), currentStreakDays (consecutive days ending " +
                    "today with at least one completed task), and busiestDayName " +
                    "(day-of-week with most completions). Use for questions like " +
                    "'how productive was I', 'streak', 'best day', 'completed this month'.",
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

    private fun createStagedTask(): FunctionDeclaration =
        FunctionDeclaration.newBuilder()
            .setName("createStagedTask")
            .setDescription(
                "Creates a STAGED personal task: a goal broken into an ordered list of steps " +
                    "(e.g. 'Plan the trip' → book flight, pack, check passport). Use this when the " +
                    "user describes a task with sub-steps / a checklist / phases. Always personal, " +
                    "category PERSONAL, recurrence NONE. Requires at least one step. " +
                    "timeStart defaults to 09:00 unless isAllDay=true.",
            )
            .setParameters(
                Schema.newBuilder()
                    .setType(Type.OBJECT)
                    .putProperties("title", stringSchema("Title of the overall staged task."))
                    .putProperties("date", isoDateSchema("Task date, ISO YYYY-MM-DD."))
                    .putProperties(
                        "steps",
                        Schema.newBuilder()
                            .setType(Type.ARRAY)
                            .setItems(stringSchema("A single step title."))
                            .setDescription(
                                "Ordered list of step titles (at least one). Keep each step short.",
                            )
                            .build(),
                    )
                    .putProperties("description", stringSchema("Optional description for the overall task."))
                    .putProperties(
                        "isAllDay",
                        Schema.newBuilder()
                            .setType(Type.BOOLEAN)
                            .setDescription(
                                "True for an all-day staged task (omit timeStart/timeEnd). Defaults false.",
                            )
                            .build(),
                    )
                    .putProperties("timeStart", timeSchema("Start time HH:mm in 24h. Optional; defaults 09:00."))
                    .putProperties("timeEnd", timeSchema("End time HH:mm in 24h. Optional."))
                    .putProperties(
                        "reminderOffsetMinutes",
                        Schema.newBuilder()
                            .setType(Type.INTEGER)
                            .setDescription("Reminder offset in minutes before the task. 0 = none (default).")
                            .build(),
                    )
                    .putProperties(
                        "isSecret",
                        Schema.newBuilder()
                            .setType(Type.BOOLEAN)
                            .setDescription("Mark task as secret (biometric-protected). Defaults to false.")
                            .build(),
                    )
                    .addAllRequired(listOf("title", "date", "steps"))
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
