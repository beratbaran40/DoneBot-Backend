package com.todoapp.backend.notif.schedule

import com.todoapp.backend.group.GroupRepository
import com.todoapp.backend.notif.DeviceTokenRepository
import com.todoapp.backend.notif.NotificationPublisher
import com.todoapp.backend.notif.inbox.NotificationType
import com.todoapp.backend.task.TaskEntity
import com.todoapp.backend.task.TaskRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Polls every 5 minutes for group tasks whose due time falls within the next 20 minutes and sends
 * `TASK_DUE_SOON` to the assignee. `dueSoonNotifiedAt` is set on dispatch so the same task isn't
 * re-notified.
 */
@Component
class TaskDueSoonJob(
    private val tasks: TaskRepository,
    private val groups: GroupRepository,
    private val publisher: NotificationPublisher,
    private val deviceTokens: DeviceTokenRepository,
    // Fallback only, for an assignee whose devices predate the time-zone column. Defaults to the
    // primary market (Türkiye, fixed UTC+3, no DST). Personal-task reminders never come through here
    // — those are client-local exact alarms scheduled in the device's own zone.
    @Value("\${app.default-timezone:Europe/Istanbul}") defaultZone: String,
) {
    private val log = LoggerFactory.getLogger(TaskDueSoonJob::class.java)
    private val fallbackZone: ZoneId = ZoneId.of(defaultZone)

    @Scheduled(fixedDelayString = "\${app.scheduling.due-soon-interval-ms:300000}", initialDelay = 60000)
    @Transactional
    fun run() {
        val now = Instant.now()
        val horizon = now.plusSeconds(LOOKAHEAD_SECONDS)
        // The candidate window is deliberately ±1 day around the widest zone the fallback could
        // mean; the exact due instant is then computed per assignee below.
        val today = LocalDate.now(fallbackZone).toEpochDay()
        val candidates = tasks.findDueSoonCandidates(fromDay = today - 1, toDay = today + 1)
        if (candidates.isEmpty()) return
        var notified = 0
        // One lookup per assignee, not per task: a group's whole backlog usually shares assignees.
        val zoneCache = mutableMapOf<Long, ZoneId>()
        candidates.forEach { task ->
            val assignee = task.assignedToUserId ?: return@forEach
            val zone = zoneCache.getOrPut(assignee) { zoneFor(assignee) }
            val due = task.dueInstant(zone) ?: return@forEach
            if (due.isBefore(now) || due.isAfter(horizon)) return@forEach
            val group = groups.findSummaryById(task.familyGroupId ?: return@forEach) ?: return@forEach
            publisher.publish(
                userIds = listOf(assignee),
                type = NotificationType.TASK_DUE_SOON,
                title = "Task due soon",
                body = "${task.title} is due soon in ${group.name}",
                payload = mapOf(
                    "groupId" to group.id.toString(),
                    "taskId" to task.id.toString(),
                    "taskTitle" to task.title,
                    "groupName" to group.name,
                ),
            )
            task.dueSoonNotifiedAt = now
            tasks.save(task)
            notified++
        }
        if (notified > 0) log.info("TaskDueSoonJob: notified={} candidates={}", notified, candidates.size)
    }

    /** An unparseable stored zone (renamed IANA id, corrupt row) must not kill the whole sweep. */
    private fun zoneFor(userId: Long): ZoneId {
        val stored = deviceTokens.findFirstByUserIdAndTimeZoneIsNotNullOrderByUpdatedAtDesc(userId)?.timeZone
            ?: return fallbackZone
        return runCatching { ZoneId.of(stored) }.getOrElse {
            log.warn("Unparseable device time zone '{}' for user={}; using {}", stored, userId, fallbackZone)
            fallbackZone
        }
    }

    private fun TaskEntity.dueInstant(zone: ZoneId): Instant? = runCatching {
        LocalDate.ofEpochDay(date)
            .atStartOfDay(zone)
            .plusSeconds(timeEnd)
            .toInstant()
    }.getOrNull()

    private companion object {
        const val LOOKAHEAD_SECONDS = 20L * 60L
    }
}
