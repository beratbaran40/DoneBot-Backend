package com.todoapp.backend.notif

import com.todoapp.backend.notif.inbox.NotificationService
import com.todoapp.backend.notif.inbox.NotificationType
import com.todoapp.backend.user.UserPreferencesService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Single entry point for emitting a notification. Writes the per-user inbox row and, if the user
 * has push enabled, dispatches a data-only FCM push. Callers should use this instead of touching
 * PushService or NotificationService directly.
 */
@Service
class NotificationPublisher(
    private val inbox: NotificationService,
    private val push: PushService,
    private val preferences: UserPreferencesService,
) {
    private val log = LoggerFactory.getLogger(NotificationPublisher::class.java)

    fun publish(
        userIds: Collection<Long>,
        type: NotificationType,
        title: String,
        body: String,
        payload: Map<String, String> = emptyMap(),
    ) {
        if (userIds.isEmpty()) return
        val enriched = payload + mapOf(
            "type" to pushTypeKey(type),
            "title" to title,
            "body" to body,
        )
        userIds.forEach { userId ->
            runCatching { inbox.create(userId, type, title, body, payload) }
                .onFailure { log.warn("Inbox write failed for user=$userId type=$type: ${it.message}") }
        }
        val pushTargets = preferences.pushEnabledUserIds(userIds)
        if (pushTargets.isNotEmpty()) {
            push.sendDataOnly(userIds = pushTargets, data = enriched)
        }
        log.info(
            "Published type={} recipients={} pushed={}",
            type,
            userIds.size,
            pushTargets.size,
        )
    }

    private fun pushTypeKey(type: NotificationType): String = when (type) {
        NotificationType.INVITATION_RECEIVED -> "invitation_received"
        NotificationType.INVITATION_ACCEPTED -> "invitation_accepted"
        NotificationType.INVITATION_DECLINED -> "invitation_declined"
        NotificationType.TASK_ASSIGNED -> "task_assigned"
        NotificationType.TASK_COMPLETED -> "task_completed"
        NotificationType.TASK_DUE_SOON -> "task_due_soon"
    }
}
