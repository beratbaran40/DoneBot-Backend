package com.todoapp.backend.notif.schedule

import com.todoapp.backend.notif.inbox.NotificationRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Daily 03:00 UTC purge of inbox rows older than 30 days. */
@Component
class NotificationRetentionJob(
    private val notifications: NotificationRepository,
) {
    private val log = LoggerFactory.getLogger(NotificationRetentionJob::class.java)

    @Scheduled(cron = "\${app.scheduling.retention-cron:0 0 3 * * *}", zone = "UTC")
    @Transactional
    fun run() {
        val cutoff = Instant.now().minus(RETENTION_DAYS, ChronoUnit.DAYS)
        val deleted = notifications.deleteAllByCreatedAtBefore(cutoff)
        if (deleted > 0) log.info("NotificationRetentionJob: deleted={} cutoff={}", deleted, cutoff)
    }

    private companion object {
        const val RETENTION_DAYS = 30L
    }
}
