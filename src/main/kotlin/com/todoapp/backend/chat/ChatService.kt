package com.todoapp.backend.chat

import com.google.api.gax.rpc.ApiException
import com.google.api.gax.rpc.ResourceExhaustedException
import com.google.api.gax.rpc.StatusCode
import com.google.cloud.vertexai.api.Candidate
import com.google.cloud.vertexai.api.Content
import com.google.cloud.vertexai.api.FunctionResponse
import com.google.cloud.vertexai.api.GenerateContentResponse
import com.google.cloud.vertexai.api.Part
import com.google.cloud.vertexai.generativeai.GenerativeModel
import com.todoapp.backend.task.TaskRepository
import com.todoapp.backend.user.UserRepository
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

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
    private val tracker: ChatUsageTracker,
) {
    private val log = LoggerFactory.getLogger(ChatService::class.java)

    private val systemInstructionEn: String by lazy { loadResource("chat/system-instruction-en.md") }
    private val systemInstructionTr: String by lazy { loadResource("chat/system-instruction-tr.md") }

    // Side threads for the blocking Vertex calls so the turn deadline can cut a hung/slow generate
    // (Future.get with timeout + cancel(true) → gRPC turns the interrupt into an RPC cancellation).
    // Cached pool: sized by concurrent chat turns, which the per-user + global rate limits already cap.
    private val vertexThreadCounter = AtomicInteger(0)
    private val vertexExecutor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "vertex-generate-${vertexThreadCounter.incrementAndGet()}").apply { isDaemon = true }
    }

    @PreDestroy
    fun shutdownExecutor() {
        vertexExecutor.shutdownNow()
    }

    fun reply(userId: Long, request: ChatMessageRequest): ChatMessageResponse {
        if (!vertex.isReady) {
            throw ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Chat is not configured on this server (vertex.project-id missing).",
            )
        }

        val started = System.currentTimeMillis()
        val locale = (request.locale ?: "en").lowercase()

        // §4.10: global (all-users) daily ceiling — a coarse circuit-breaker against a runaway Vertex
        // bill (per-user rate-limit caps individuals, not total spend). Over the cap → 503 with the
        // same marker the client already renders as a "service busy" banner (§7.13).
        if (!tracker.tryAcquireGlobalDaily(props.maxGlobalDailyRequests)) {
            log.warn("ChatGlobalCap hit — rejecting chat user={} limit={}", userId, props.maxGlobalDailyRequests)
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, vertexUnavailableMessage(locale))
        }

        val systemInstruction = if (locale == "tr") systemInstructionTr else systemInstructionEn
        val model = vertex.model(systemInstruction)

        val preamble = buildContextPreamble(userId)
        val initialUserText = "$preamble\n\n${request.prompt}"
        val history = trimAndConvertHistory(request.history) + userContent(initialUserText)

        var roundTrips = 0
        var conversation = history
        var promptTokens = 0L
        var responseTokens = 0L
        var totalTokens = 0L
        val toolsCalled = mutableListOf<String>()
        repeat(props.maxToolIterations) { iteration ->
            roundTrips++
            val response = try {
                // Turn deadline (Tier 1.5): tools run between rounds on this thread, so checking the
                // remaining budget here bounds the WHOLE turn, and the per-call Future timeout bounds
                // a single hung generate. Without this, a slow Vertex tail pushes the turn past the
                // client's 60s read timeout — the client shows a connectivity error and may retype,
                // while the server keeps paying for an answer nobody receives.
                val remainingMs = props.turnDeadlineMs - (System.currentTimeMillis() - started)
                if (remainingMs < MIN_ROUND_BUDGET_MS) throw TurnDeadlineExceededException()
                generateWithDeadline(model, conversation, remainingMs)
            } catch (e: TurnDeadlineExceededException) {
                failVertex(
                    userId, roundTrips, toolsCalled, promptTokens, responseTokens, totalTokens, started, "turn_deadline", e,
                )
                throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, vertexUnavailableMessage(locale))
            } catch (e: ResourceExhaustedException) {
                // Vertex project quota exhausted (429). Distinct from our own per-user ChatRateLimiter.
                failVertex(
                    userId, roundTrips, toolsCalled, promptTokens, responseTokens, totalTokens, started, "quota", e,
                )
                throw ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, vertexQuotaMessage(locale))
            } catch (e: ApiException) {
                // Timeout / outage / internal / bad-credentials — all degrade to a clean 503 for the user.
                val cause = classifyVertex(e)
                failVertex(
                    userId, roundTrips, toolsCalled, promptTokens, responseTokens, totalTokens, started, cause, e,
                )
                throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, vertexUnavailableMessage(locale))
            } catch (e: IOException) {
                // generateContent declares `throws IOException` (transport / credential I/O).
                failVertex(
                    userId, roundTrips, toolsCalled, promptTokens, responseTokens, totalTokens, started, "io", e,
                )
                throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, vertexUnavailableMessage(locale))
            }
            val usage = response.usageMetadata
            promptTokens += usage.promptTokenCount.toLong()
            responseTokens += usage.candidatesTokenCount.toLong()
            totalTokens += usage.totalTokenCount.toLong()
            val candidate = response.candidatesList.firstOrNull() ?: run {
                logTurn(
                    userId, roundTrips, toolsCalled, promptTokens, responseTokens, totalTokens,
                    System.currentTimeMillis() - started, refused = false, error = "empty_response",
                )
                throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "Empty response from model")
            }
            // §7.15: a safety/recitation/blocklist stop leaves an empty candidate. Return a clean,
            // localized refusal (HTTP 200) instead of collapsing into the meaningless "empty_text" 502.
            if (candidate.finishReason in blockedFinishReasons) {
                val ms = System.currentTimeMillis() - started
                logTurn(
                    userId, roundTrips, toolsCalled, promptTokens, responseTokens, totalTokens,
                    ms, refused = true, error = "safety_block",
                )
                tracker.record(userId)
                return ChatMessageResponse(
                    text = safetyRefusalMessage(locale),
                    meta = ChatTurnMeta(roundTrips = roundTrips, refused = true, serverMs = ms),
                )
            }
            val parts = candidate.content.partsList

            val toolCalls = parts.filter { it.hasFunctionCall() }
            if (toolCalls.isEmpty()) {
                val rawText = parts
                    .mapNotNull { if (it.hasText()) it.text else null }
                    .joinToString("\n")
                    .trim()
                if (rawText.isBlank()) {
                    logTurn(
                        userId, roundTrips, toolsCalled, promptTokens, responseTokens, totalTokens,
                        System.currentTimeMillis() - started, refused = false, error = "empty_text",
                    )
                    throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "Empty text from model")
                }
                val text = sanitizeUserFacingText(rawText)
                val refused = looksLikeRefusal(text)
                val ms = System.currentTimeMillis() - started
                logTurn(userId, roundTrips, toolsCalled, promptTokens, responseTokens, totalTokens, ms, refused, error = null)
                tracker.record(userId)
                return ChatMessageResponse(
                    text = text,
                    meta = ChatTurnMeta(
                        roundTrips = roundTrips,
                        refused = refused,
                        serverMs = ms,
                    ),
                )
            }

            toolsCalled += toolCalls.map { it.functionCall.name }

            // Graceful tool-budget cap: stop spending iterations and return a coherent
            // server-side fallback instead of throwing 502. The model can't summarize
            // because we never hand back the function response, but the user gets a
            // useful message rather than a generic error.
            if (iteration == props.maxToolIterations - 1) {
                val fallback = if (locale == "tr") {
                    "Bu istek için beklediğimden daha fazla araç çağrısı gerekti ve durdum. " +
                        "Daha küçük adımlar halinde tekrar dener misin?"
                } else {
                    "I needed more tool calls than expected and stopped. " +
                        "Could you try again in smaller steps?"
                }
                val ms = System.currentTimeMillis() - started
                logTurn(
                    userId, roundTrips, toolsCalled, promptTokens, responseTokens, totalTokens,
                    ms, refused = false, error = "tool_loop_cap",
                )
                tracker.record(userId)
                return ChatMessageResponse(
                    text = fallback,
                    meta = ChatTurnMeta(roundTrips = roundTrips, refused = false, serverMs = ms),
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

        // Unreachable — the cap branch above always returns. Guard anyway.
        val ms = System.currentTimeMillis() - started
        logTurn(
            userId, roundTrips, toolsCalled, promptTokens, responseTokens, totalTokens,
            ms, refused = false, error = "loop_unreachable",
        )
        throw ResponseStatusException(
            HttpStatus.BAD_GATEWAY,
            "Tool loop exceeded ${props.maxToolIterations} iterations",
        )
    }

    /**
     * Runs one blocking Vertex generate on a side thread, capped at [remainingMs]. On timeout the
     * future is cancelled with interruption (gRPC blocking stubs translate that into an RPC
     * cancellation, so we also stop paying for the abandoned generation) and the turn fails as
     * [TurnDeadlineExceededException]. [ExecutionException] is unwrapped so the caller's existing
     * quota/outage/IO catch branches keep matching the real exception types.
     */
    private fun generateWithDeadline(
        model: GenerativeModel,
        conversation: List<Content>,
        remainingMs: Long,
    ): GenerateContentResponse {
        val future = vertexExecutor.submit(Callable { vertex.generate(model, conversation) })
        return try {
            future.get(remainingMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw TurnDeadlineExceededException(e)
        } catch (e: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            throw TurnDeadlineExceededException(e)
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    /**
     * Maps a gax [ApiException] to a short, machine-grep-able cause string for the SERVER log only.
     * The user-facing HTTP status stays coarse (429 for quota, 503 for everything else) — this detail
     * exists so we can tell a Vertex quota hit apart from bad creds / an outage in Render logs.
     */
    private fun classifyVertex(e: ApiException): String = when (e.statusCode.code) {
        StatusCode.Code.RESOURCE_EXHAUSTED -> "quota"
        StatusCode.Code.DEADLINE_EXCEEDED -> "timeout"
        StatusCode.Code.UNAVAILABLE -> "outage"
        StatusCode.Code.INTERNAL -> "internal"
        StatusCode.Code.PERMISSION_DENIED, StatusCode.Code.UNAUTHENTICATED -> "credentials"
        else -> "unknown"
    }

    /** Records a failed Vertex turn: one ChatCost log line (error=vertex_<cause>) + the real cause server-side. */
    private fun failVertex(
        userId: Long,
        roundTrips: Int,
        toolsCalled: List<String>,
        promptTokens: Long,
        responseTokens: Long,
        totalTokens: Long,
        started: Long,
        cause: String,
        e: Throwable,
    ) {
        logTurn(
            userId, roundTrips, toolsCalled, promptTokens, responseTokens, totalTokens,
            System.currentTimeMillis() - started, refused = false, error = "vertex_$cause",
        )
        // The true cause (quota vs credentials vs outage) is logged on the server only, never sent to the user.
        log.error("Vertex generate failed cause={} user={}", cause, userId, e)
    }

    private fun vertexQuotaMessage(locale: String): String {
        val human = if (locale == "tr") {
            "DoneBot şu anda çok yoğun (kota doldu)."
        } else {
            "DoneBot is very busy right now (quota reached)."
        }
        // The marker contains the substring "quota", which the client's existing RATE_LIMIT_MARKERS already
        // greps → RATE_LIMITED with zero client change; "Retry in Ns" feeds the client's cooldown regex.
        return "$human [$VERTEX_QUOTA_MARKER] Retry in ${VERTEX_QUOTA_RETRY_SECONDS}s"
    }

    private fun vertexUnavailableMessage(locale: String): String =
        if (locale == "tr") {
            "DoneBot'un yapay zekâ servisine şu anda ulaşılamıyor. " +
                "Lütfen birazdan tekrar dene. [$VERTEX_UNAVAILABLE_MARKER]"
        } else {
            "DoneBot's AI service is temporarily unavailable. " +
                "Please try again shortly. [$VERTEX_UNAVAILABLE_MARKER]"
        }

    // §7.15: finishReasons that mean "the model refused/was blocked" — we rely on Gemini's default
    // BLOCK_MEDIUM_AND_ABOVE thresholds (no explicit SafetySettings) and turn these into a polite refusal.
    private val blockedFinishReasons = setOf(
        Candidate.FinishReason.SAFETY,
        Candidate.FinishReason.RECITATION,
        Candidate.FinishReason.BLOCKLIST,
        Candidate.FinishReason.PROHIBITED_CONTENT,
        Candidate.FinishReason.SPII,
    )

    private fun safetyRefusalMessage(locale: String): String =
        if (locale == "tr") "Bu isteğe yanıt veremiyorum." else "I can't respond to that request."

    private fun logTurn(
        userId: Long,
        roundTrips: Int,
        toolsCalled: List<String>,
        promptTokens: Long,
        responseTokens: Long,
        totalTokens: Long,
        ms: Long,
        refused: Boolean,
        error: String?,
    ) {
        // One line, machine-grep-able. Includes the tool list so we can see what the
        // model actually invoked, plus latency and an error code when something went
        // wrong (empty_text, tool_loop_cap, etc.).
        log.info(
            "ChatCost user={} rt={} tools={} promptTok={} respTok={} totalTok={} ms={} refused={} error={}",
            userId,
            roundTrips,
            toolsCalled,
            promptTokens,
            responseTokens,
            totalTokens,
            ms,
            refused,
            error ?: "null",
        )
    }

    /**
     * Defense-in-depth scrubber for ID-shaped patterns the model might emit despite the
     * system-prompt rule against mentioning ids. The model legitimately sees ids in
     * tool inputs/outputs (it needs them to chain calls); we keep them out of the user
     * reply by stripping `#1234`-style and `id: 1234`-style fragments.
     */
    private fun sanitizeUserFacingText(text: String): String {
        var out = text
        ID_LEAK_PATTERNS.forEach { out = it.replace(out, "") }
        return out.replace(MULTISPACE, " ").trim()
    }

    private fun buildContextPreamble(userId: Long): String {
        val today = LocalDate.now()
        val todayEpoch = today.toEpochDay()
        val tomorrowEpoch = today.plusDays(1).toEpochDay()
        val weekAgoEpoch = todayEpoch - 6
        val tasks = taskRepo.findAllByOwnerIdAndFamilyGroupIdIsNull(userId).filterNot { it.isSecret }
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
                .addParts(Part.newBuilder().setText(sanitizeHistoryContent(turn.content)).build())
                .build()
        }
    }

    /**
     * Mitigate prompt-injection via history poisoning: a user could embed fake role
     * markers ("System:", "Assistant:") or control characters in a prior turn that
     * influence the next call. We strip them defensively. We do NOT try to detect
     * malicious intent — just neutralize the most obvious vectors.
     */
    private fun sanitizeHistoryContent(text: String): String {
        var out = text.replace(CONTROL_CHARS, "")
        out = out.replace(ROLE_IMPERSONATION, "")
        if (out.length > MAX_HISTORY_TURN_CHARS) out = out.take(MAX_HISTORY_TURN_CHARS)
        return out
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
        private const val MAX_HISTORY_TURN_CHARS = 4_000

        // Don't start a Vertex round with less budget than this — a call that would be cancelled
        // near-immediately still costs a round trip (and possibly billed tokens).
        private const val MIN_ROUND_BUDGET_MS = 2_000L

        // Vertex-failure markers embedded in the ResponseStatusException reason so the Android client can
        // classify the failure off the message text (mirrors the existing rate-limit marker mechanism).
        private const val VERTEX_QUOTA_MARKER = "vertex_quota"
        private const val VERTEX_UNAVAILABLE_MARKER = "vertex_unavailable"
        private const val VERTEX_QUOTA_RETRY_SECONDS = 30
        private val REFUSAL_PREFIXES = listOf(
            "Sorry, I can only help",
            "Üzgünüm, sadece bu uygulamadaki",
        )
        private val ID_LEAK_PATTERNS = listOf(
            Regex("""#\d{1,12}\b"""),
            Regex("""(?i)\bid\s*[:#=]?\s*\d{1,12}\b"""),
        )
        private val MULTISPACE = Regex("""\s{2,}""")
        private val CONTROL_CHARS = Regex("""[\x00-\x08\x0B-\x1F\x7F]""")
        private val ROLE_IMPERSONATION = Regex(
            """(?im)^\s*(System|Assistant|User|Tool|Function)\s*[:>]\s*""",
        )
    }
}

/** One chat turn exceeded [ChatProperties.turnDeadlineMs] (all Vertex rounds + tool executions). */
private class TurnDeadlineExceededException(cause: Throwable? = null) :
    RuntimeException("Chat turn exceeded the configured deadline", cause)
