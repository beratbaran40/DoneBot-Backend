package com.todoapp.backend.admin

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Instant

data class RecentError(
    val at: String,
    val level: String,
    val logger: String,
    val message: String,
    val requestId: String?,
    val exception: String?,
)

/**
 * A bounded, in-memory tail of recent warnings and errors.
 *
 * Deliberately not a database table. Errors arrive in bursts exactly when the database is the thing
 * having trouble, so writing them to it is the worst possible moment to add writes — and the value here
 * is "what just broke", not history. Render keeps the durable log; this exists so the answer is one
 * click away in the panel instead of a dashboard login and a search.
 *
 * Contents are lost on restart, which is fine and worth saying out loud: if the process died, the
 * interesting question is why it died, and that is in Render's log, not here.
 */
@Component
class RecentErrors(
    @Value("\${app.admin.recent-errors-capacity:200}") private val capacity: Int,
) {
    private val buffer = ArrayDeque<RecentError>()

    @Synchronized
    fun add(error: RecentError) {
        buffer.addLast(error)
        while (buffer.size > capacity) buffer.removeFirst()
    }

    /** Newest first — the panel reads top-down. */
    @Synchronized
    fun snapshot(limit: Int): List<RecentError> = buffer.toList().takeLast(limit).asReversed()

    @Synchronized
    fun size(): Int = buffer.size
}

/**
 * Feeds [RecentErrors] from the logging pipeline rather than from the exception handler.
 *
 * `GlobalExceptionHandler` only sees exceptions that reach a controller. Hooking the logger instead
 * also catches the two categories that hurt most and are otherwise invisible:
 *
 *  - **Scheduled job failures.** TaskDueSoonJob and the retention job run on a timer with nobody
 *    watching; a job that has been dead for a week produces no HTTP response to notice.
 *  - **Filter-level failures**, which never reach the advice at all.
 *
 * It also inherits the MDC `requestId` that CorrelationIdFilter already stamps and echoes as the
 * X-Request-Id response header — so a user reporting a problem can be matched to the exact entry.
 */
@Component
class RecentErrorLogAppender(private val recentErrors: RecentErrors) : AppenderBase<ILoggingEvent>() {

    @PostConstruct
    fun register() {
        val loggerContext = LoggerFactory.getILoggerFactory() as? LoggerContext ?: return
        context = loggerContext
        name = APPENDER_NAME
        start()
        loggerContext.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).addAppender(this)
    }

    @PreDestroy
    fun deregister() {
        (LoggerFactory.getILoggerFactory() as? LoggerContext)
            ?.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
            ?.detachAppender(APPENDER_NAME)
        stop()
    }

    override fun append(event: ILoggingEvent) {
        if (!event.level.isGreaterOrEqual(Level.WARN)) return
        recentErrors.add(
            RecentError(
                at = Instant.ofEpochMilli(event.timeStamp).toString(),
                level = event.level.toString(),
                logger = event.loggerName.substringAfterLast('.'),
                message = LogRedaction.sanitize(event.formattedMessage),
                requestId = event.mdcPropertyMap[REQUEST_ID_KEY],
                exception = event.throwableProxy?.className,
            ),
        )
    }

    private companion object {
        const val APPENDER_NAME = "adminRecentErrors"
        const val REQUEST_ID_KEY = "requestId"
    }
}

/**
 * Makes a log line safe to display in the panel.
 *
 * Kept as a plain function rather than a method on the appender so it can be tested directly. That is
 * not only convenience: `AppenderBase.doAppend` swallows exceptions thrown by `append`, so a fault in
 * this logic would silently produce an empty error list rather than a failing test.
 *
 * Redaction is not hypothetical. Spring Boot's own auto-configuration logs "Using generated security
 * password: <uuid>" at WARN, and it appeared in this table on the first run. Libraries print secrets,
 * and a screen that mirrors WARN and ERROR verbatim will eventually mirror one onto a browser.
 *
 * Emails and task titles are deliberately left alone: this is an operator-only surface where the email
 * is frequently the point of the message, and the length cap already bounds how much can appear.
 */
internal object LogRedaction {

    private const val MESSAGE_MAX_LENGTH = 500

    private val REDACTIONS = listOf(
        Regex("""Bearer\s+[A-Za-z0-9._\-]+""") to "Bearer [redacted]",
        // Any JWT-shaped token, wherever it appears — not only behind an Authorization header.
        Regex("""eyJ[A-Za-z0-9._\-]{10,}""") to "[token]",
        Regex("""(?i)(password|secret|api[_\-]?key|token)(["']?\s*[:=]\s*"?)[^\s"',}]+""") to "$1$2[redacted]",
    )

    fun sanitize(message: String?): String {
        val redacted = REDACTIONS.fold(message.orEmpty()) { acc, (pattern, replacement) ->
            pattern.replace(acc, replacement)
        }
        return redacted.take(MESSAGE_MAX_LENGTH)
    }
}
