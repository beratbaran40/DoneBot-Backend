package com.todoapp.backend.metrics

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

/**
 * Decides whether a request is worth a database write, and hands the write to the metrics thread.
 *
 * The de-duplication map is what makes activity tracking essentially free: a user hammering the API all
 * day produces exactly one insert. After a restart the map is empty, so the first request per user
 * re-attempts an insert that already exists — [ActivityWriter] swallows that as a no-op.
 *
 * Days are bucketed in **UTC**, matching every other day boundary on the admin surface (chat usage
 * already counts its global daily budget in UTC). Istanbul is UTC+3, so mixing the two would shift
 * every chart by three hours and make "today" disagree between two screens.
 */
@Component
class ActivityRecorder(
    private val writer: ActivityWriter,
    @Qualifier(MetricsExecutorConfig.METRICS_WRITE_EXECUTOR) private val executor: Executor,
    private val properties: MetricsProperties,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val lastRecordedDay = ConcurrentHashMap<Long, LocalDate>()

    fun record(userId: Long) {
        if (!properties.activity.enabled) return

        val today = LocalDate.now(ZoneOffset.UTC)
        // Bounded by the number of distinct users seen since boot. Clearing wholesale on the (very
        // unlikely) day that exceeds the cap costs one redundant insert per user, not correctness.
        if (lastRecordedDay.size > MAX_TRACKED_USERS) lastRecordedDay.clear()
        if (lastRecordedDay.put(userId, today) == today) return

        executor.execute {
            runCatching { writer.write(userId, today) }
                .onFailure {
                    // Never propagate: this runs on the metrics thread, and an uncaught exception there
                    // would be a silent thread death that stops all later recording.
                    log.debug("Activity write failed for user {}", userId, it)
                }
        }
    }

    private companion object {
        const val MAX_TRACKED_USERS = 50_000
    }
}
