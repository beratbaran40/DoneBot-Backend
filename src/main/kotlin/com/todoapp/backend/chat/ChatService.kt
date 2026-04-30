package com.todoapp.backend.chat

import com.google.cloud.vertexai.api.Content
import com.google.cloud.vertexai.api.FunctionResponse
import com.google.cloud.vertexai.api.Part
import com.todoapp.backend.task.TaskRepository
import com.todoapp.backend.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.nio.charset.StandardCharsets
import java.time.LocalDate

/**
 * Orchestrates one chat turn end-to-end:
 *  1. Build the [Context] preamble from Postgres (today's tasks, tomorrow
 *     count, overdue count, weekly completed count).
 *  2. Wrap the client-supplied history + the new prompt into a Vertex
 *     conversation.
 *  3. Loop generateContent ↔ tool-execute up to MAX_TOOL_ITERATIONS until
 *     the model emits plain text (no function call).
 *  4. Return the final text + per-turn metrics.
 *
 * Conversation state is stateless on the server (Decision 3-A): the client
 * resends the last N turns each request. We trim further server-side to
 * `maxHistoryTurns` so a misbehaving client can't blow our context window.
 */
@Service
class ChatService(
    private val vertex: VertexAiClient,
    private val tools: ChatToolService,
    private val taskRepo: TaskRepository,
    private val users: UserRepository,
    private val props: ChatProperties,
) {
    private val log = LoggerFactory.getLogger(ChatService::class.java)

    private val systemInstructionEn: String by lazy { loadResource("chat/system-instruction-en.md") }
    private val systemInstructionTr: String by lazy { loadResource("chat/system-instruction-tr.md") }

    fun reply(userId: Long, request: ChatMessageRequest): ChatMessageResponse {
        if (!vertex.isReady) {
            throw ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Chat is not configured on this server (vertex.project-id missing).",
            )
        }

        val started = System.currentTimeMillis()
        val locale = (request.locale ?: "en").lowercase()
        val systemInstruction = if (locale == "tr") systemInstructionTr else systemInstructionEn
        val model = vertex.model(systemInstruction)

        val preamble = buildContextPreamble(userId)
        val initialUserText = "$preamble\n\n${request.prompt}"
        val history = trimAndConvertHistory(request.history) + userContent(initialUserText)

        var roundTrips = 0
        var conversation = history
        repeat(props.maxToolIterations) {
            roundTrips++
            val response = vertex.generate(model, conversation)
            val candidate = response.candidatesList.firstOrNull()
                ?: throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "Empty response from model")
            val parts = candidate.content.partsList

            val toolCalls = parts.filter { it.hasFunctionCall() }
            if (toolCalls.isEmpty()) {
                val text = parts
                    .mapNotNull { if (it.hasText()) it.text else null }
                    .joinToString("\n")
                    .trim()
                if (text.isBlank()) {
                    throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "Empty text from model")
                }
                val refused = looksLikeRefusal(text)
                val ms = System.currentTimeMillis() - started
                log.info(
                    "Chat reply: user={} rt={} ms={} refused={}",
                    userId, roundTrips, ms, refused,
                )
                return ChatMessageResponse(
                    text = text,
                    meta = ChatTurnMeta(
                        roundTrips = roundTrips,
                        refused = refused,
                        serverMs = ms,
                    ),
                )
            }

            // Execute tools, append model + function-response turns to history
            conversation = conversation + candidate.content
            val responseParts = toolCalls.map { part ->
                val name = part.functionCall.name
                val args = part.functionCall.args
                val result = tools.execute(userId, name, args)
                Part.newBuilder()
                    .setFunctionResponse(
                        FunctionResponse.newBuilder()
                            .setName(name)
                            .setResponse(result.structValue)
                            .build(),
                    )
                    .build()
            }
            conversation = conversation + Content.newBuilder()
                .setRole("function")
                .addAllParts(responseParts)
                .build()
        }

        // Tool loop hit cap without a final text reply.
        throw ResponseStatusException(
            HttpStatus.BAD_GATEWAY,
            "Tool loop exceeded ${props.maxToolIterations} iterations",
        )
    }

    private fun buildContextPreamble(userId: Long): String {
        val today = LocalDate.now()
        val todayEpoch = today.toEpochDay()
        val tomorrowEpoch = today.plusDays(1).toEpochDay()
        val weekAgoEpoch = todayEpoch - 6
        val tasks = taskRepo.findAllByOwnerIdAndFamilyGroupIdIsNull(userId)
        val tasksToday = tasks.filter { it.date == todayEpoch }
        val tomorrowCount = tasks.count { it.date == tomorrowEpoch }
        val overdueCount = tasks.count { !it.isCompleted && it.date < todayEpoch }
        val weeklyDone = tasks.count { it.isCompleted && it.date in weekAgoEpoch..todayEpoch }

        return buildString {
            append("[Context: Today is ").append(today).append(" (").append(today.dayOfWeek).append(").\n")
            if (tasksToday.isEmpty()) {
                append("Today: no tasks.\n")
            } else {
                append("Today: ").append(tasksToday.size).append(" tasks:\n")
                tasksToday.take(MAX_PREAMBLE_TASKS).forEach { task ->
                    val start = String.format("%02d:%02d", task.timeStart / 3600, (task.timeStart / 60) % 60)
                    val end = String.format("%02d:%02d", task.timeEnd / 3600, (task.timeEnd / 60) % 60)
                    append("  #").append(task.id)
                    append(" \"").append(task.title).append("\" ")
                    append(start).append('-').append(end)
                    append(" [").append(if (task.isCompleted) "completed" else "pending").append("]\n")
                }
                if (tasksToday.size > MAX_PREAMBLE_TASKS) {
                    append("  (and ").append(tasksToday.size - MAX_PREAMBLE_TASKS)
                    append(" more — call getTodaysTasks for full list)\n")
                }
            }
            append("Tomorrow: ").append(tomorrowCount).append(" tasks scheduled.\n")
            append("Overdue: ").append(overdueCount).append(" tasks past due.\n")
            append("Completed this week: ").append(weeklyDone).append(" tasks.\n")
            append("]")
        }
    }

    private fun trimAndConvertHistory(history: List<ChatHistoryTurn>): List<Content> {
        val trimmed = history.takeLast(props.maxHistoryTurns)
        return trimmed.mapNotNull { turn ->
            val role = when (turn.role) {
                "user" -> "user"
                "assistant", "model" -> "model"
                else -> return@mapNotNull null
            }
            Content.newBuilder()
                .setRole(role)
                .addParts(Part.newBuilder().setText(turn.content).build())
                .build()
        }
    }

    private fun userContent(text: String): Content =
        Content.newBuilder()
            .setRole("user")
            .addParts(Part.newBuilder().setText(text).build())
            .build()

    private fun looksLikeRefusal(text: String): Boolean = REFUSAL_PREFIXES.any { text.startsWith(it) }

    private fun loadResource(path: String): String =
        ClassPathResource(path).inputStream.use { it.readAllBytes().toString(StandardCharsets.UTF_8) }

    companion object {
        private const val MAX_PREAMBLE_TASKS = 20
        private val REFUSAL_PREFIXES = listOf(
            "Sorry, I can only help",
            "Üzgünüm, sadece bu uygulamadaki",
        )
    }
}
