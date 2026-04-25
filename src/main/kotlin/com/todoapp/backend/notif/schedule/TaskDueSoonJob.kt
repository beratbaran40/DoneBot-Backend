package com.todoapp.backend.notif.schedule

import com.todoapp.backend.group.GroupRepository
import com.todoapp.backend.notif.NotificationPublisher
import com.todoapp.backend.notif.inbox.NotificationType
import com.todoapp.backend.task.TaskEntity
import com.todoapp.backend.task.TaskRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

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
) {
    private val log = LoggerFactory.getLogger(TaskDueSoonJob::class.java)
    private val zone: ZoneId = ZoneOffset.UTC

    @Scheduled(fixedDelayString = "\${app.scheduling.due-soon-interval-ms:300000}", initialDelay = 60000)
    @Transactional
    fun run() {
        val now = Instant.now()
        val horizon = now.plusSeconds(LOOKAHEAD_SECONDS)
        val today = LocalDate.now(zone).toEpochDay()
        val candidates = tasks.findDueSoonCandidates(fromDay = today - 1, toDay = today + 1)
        if (candidates.isEmpty()) return
        var notified = 0
        candidates.forEach { task ->
            val due = task.dueInstant() ?: return@forEach
            if (due.isBefore(now) || due.isAfter(horizon)) return@forEach
            val assignee = task.assignedToUserId ?: return@forEach
            val group = groups.findById(task.familyGroupId ?: return@forEach).orElse(null) ?: return@forEach
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

    private fun TaskEntity.dueInstant(): Instant? = runCatching {
        LocalDate.ofEpochDay(date)
            .atStartOfDay(zone)
            .plusSeconds(timeEnd)
            .toInstant()
    }.getOrNull()

    private companion object {
        const val LOOKAHEAD_SECONDS = 20L * 60L
    }
}
