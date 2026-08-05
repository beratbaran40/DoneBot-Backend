package com.todoapp.backend.metrics

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.Executor

/** What one chat turn adds to the day's totals. */
data class ChatUsageDelta(
    val requests: Int = 1,
    val refusals: Int = 0,
    val errors: Int = 0,
    val promptTokens: Long = 0,
    val responseTokens: Long = 0,
    val serverMs: Long = 0,
)

fun interface ChatUsageWriter {
    fun add(userId: Long, day: LocalDate, delta: ChatUsageDelta)
}

/**
 * Turns one chat turn into durable usage counters.
 *
 * Classification is the only real logic here, and it is deliberate:
 *
 *  - **Every** turn counts as a request, including ones that failed. Error *rate* is the number that
 *    matters, and a denominator that quietly excludes failures makes it meaningless.
 *  - A safety refusal is a **refusal, not an error**. The model answered; it declined. It returns HTTP
 *    200 and nothing is broken, so folding it into the error rate would make a working service look
 *    unhealthy every time someone asks something off-limits.
 *  - Everything else carrying an error code — a Vertex outage, a quota rejection, the turn deadline, an
 *    empty response, the tool-loop cap — is an error, even the ones that return 200 with a fallback
 *    message. From the operator's point of view the turn did not do its job.
 */
@Component
class ChatUsageRecorder(
    private val writer: ChatUsageWriter,
    @Qualifier(MetricsExecutorConfig.METRICS_WRITE_EXECUTOR) private val executor: Executor,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun recordTurn(
        userId: Long,
        promptTokens: Long,
        responseTokens: Long,
        serverMs: Long,
        refused: Boolean,
        error: String?,
    ) = submit(
        userId,
        ChatUsageDelta(
            requests = 1,
            refusals = if (refused) 1 else 0,
            errors = if (error != null && !refused) 1 else 0,
            promptTokens = promptTokens,
            responseTokens = responseTokens,
            serverMs = serverMs,
        ),
    )

    /**
     * A turn rejected before Vertex was ever called — the global daily cost cap, or chat not being
     * configured. No tokens were spent, but the user was refused service, so it belongs in the error
     * rate. Without this the cost circuit-breaker would fire invisibly.
     */
    fun recordRejected(userId: Long) = submit(userId, ChatUsageDelta(requests = 1, errors = 1))

    private fun submit(userId: Long, delta: ChatUsageDelta) {
        // Off the request thread on purpose. A synchronous write here could add a cold Neon connection
        // wait (hikari connection-timeout is 20s) to a turn that already runs against a 45s deadline
        // sitting under the client's 60s read timeout.
        val day = LocalDate.now(ZoneOffset.UTC)
        executor.execute {
            runCatching { writer.add(userId, day, delta) }
                .onFailure { log.debug("Chat usage write failed for user {}", userId, it) }
        }
    }
}

/**
 * Portable accumulating upsert.
 *
 * Postgres would do this with ON CONFLICT DO UPDATE and H2 with MERGE … KEY, and the two syntaxes are
 * not interchangeable. Update-then-insert-then-update behaves identically on both, and the common path
 * (a row already exists for today) is a single statement. The duplicate catch covers the first turn of
 * a day if two writes ever raced — they cannot today, since the metrics executor is single-threaded,
 * but the guard costs nothing and removes a latent assumption.
 */
@Component
class JdbcChatUsageWriter(private val jdbc: JdbcTemplate) : ChatUsageWriter {

    override fun add(userId: Long, day: LocalDate, delta: ChatUsageDelta) {
        if (applyDelta(userId, day, delta) > 0) return
        try {
            jdbc.update(
                INSERT,
                day,
                userId,
                delta.requests,
                delta.refusals,
                delta.errors,
                delta.promptTokens,
                delta.responseTokens,
                delta.serverMs,
            )
        } catch (_: DataIntegrityViolationException) {
            applyDelta(userId, day, delta)
        }
    }

    private fun applyDelta(userId: Long, day: LocalDate, delta: ChatUsageDelta): Int = jdbc.update(
        UPDATE,
        delta.requests,
        delta.refusals,
        delta.errors,
        delta.promptTokens,
        delta.responseTokens,
        delta.serverMs,
        day,
        userId,
    )

    private companion object {
        const val UPDATE =
            "UPDATE chat_usage_daily SET requests = requests + ?, refusals = refusals + ?, " +
                "errors = errors + ?, prompt_tokens = prompt_tokens + ?, " +
                "response_tokens = response_tokens + ?, total_server_ms = total_server_ms + ? " +
                "WHERE usage_date = ? AND user_id = ?"

        const val INSERT =
            "INSERT INTO chat_usage_daily (usage_date, user_id, requests, refusals, errors, " +
                "prompt_tokens, response_tokens, total_server_ms) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
    }
}
