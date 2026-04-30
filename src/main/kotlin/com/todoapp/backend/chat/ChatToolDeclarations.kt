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
                createTask(),
                updateTask(),
                deleteTask(),
                setTaskCompletion(),
                setTaskSecret(),
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
                "Creates a new personal task. timeEnd defaults to timeStart+30min when missing. " +
                    "Category defaults to PERSONAL. Recurrence defaults to NONE.",
            )
            .setParameters(
                Schema.newBuilder()
                    .setType(Type.OBJECT)
                    .putProperties("title", stringSchema("Task title."))
                    .putProperties("date", isoDateSchema("Task date, ISO YYYY-MM-DD."))
                    .putProperties("timeStart", timeSchema("Start time HH:mm in 24h."))
                    .putProperties("timeEnd", timeSchema("End time HH:mm in 24h. Optional."))
                    .putProperties("description", stringSchema("Optional description."))
                    .putProperties(
                        "category",
                        Schema.newBuilder()
                            .setType(Type.STRING)
                            .setDescription(
                                "Category. One of: PERSONAL, WORK, HEALTH, BIRTHDAY, CUSTOM. " +
                                    "Defaults to PERSONAL.",
                            )
                            .build(),
                    )
                    .putProperties(
                        "recurrence",
                        Schema.newBuilder()
                            .setType(Type.STRING)
                            .setDescription(
                                "Recurrence. One of: NONE, DAILY, WEEKLY, MONTHLY, YEARLY. " +
                                    "Defaults to NONE.",
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
                    .addAllRequired(listOf("title", "date", "timeStart"))
                    .build(),
            )
            .build()

    private fun updateTask(): FunctionDeclaration =
        FunctionDeclaration.newBuilder()
            .setName("updateTask")
            .setDescription(
                "Updates fields on an existing task. Only the user's own personal tasks are " +
                    "editable — group tasks return an error.",
            )
            .setParameters(
                Schema.newBuilder()
                    .setType(Type.OBJECT)
                    .putProperties("taskId", longSchema("Numeric task id."))
                    .putProperties("title", stringSchema("New title (optional)."))
                    .putProperties("date", isoDateSchema("New date YYYY-MM-DD (optional)."))
                    .putProperties("timeStart", timeSchema("New start HH:mm (optional)."))
                    .putProperties("timeEnd", timeSchema("New end HH:mm (optional)."))
                    .putProperties("description", stringSchema("New description (optional)."))
                    .putProperties(
                        "category",
                        Schema.newBuilder().setType(Type.STRING).build(),
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

    private fun stringSchema(desc: String): Schema =
        Schema.newBuilder().setType(Type.STRING).setDescription(desc).build()

    private fun isoDateSchema(desc: String): Schema =
        Schema.newBuilder().setType(Type.STRING).setFormat("date").setDescription(desc).build()

    private fun timeSchema(desc: String): Schema =
        Schema.newBuilder().setType(Type.STRING).setDescription(desc).build()

    private fun longSchema(desc: String): Schema =
        Schema.newBuilder().setType(Type.INTEGER).setDescription(desc).build()
}
