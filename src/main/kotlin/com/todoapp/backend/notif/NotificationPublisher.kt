package com.todoapp.backend.notif

import com.todoapp.backend.notif.inbox.NotificationService
import com.todoapp.backend.notif.inbox.NotificationType
import com.todoapp.backend.user.UserPreferencesService
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * The fan-out half of [NotificationPublisher], carried on the application event bus so the FCM
 * round-trips happen outside the caller's transaction.
 */
data class PushRequested(
    val userIds: Collection<Long>,
    val type: NotificationType,
    val data: Map<String, String>,
)

/**
 * Single entry point for emitting a notification. Writes the per-user inbox row and, if the user
 * has push enabled, dispatches a data-only FCM push. Callers should use this instead of touching
 * PushService or NotificationService directly.
 */
@Service
class NotificationPublisher(
    private val inbox: NotificationService,
    private val events: ApplicationEventPublisher,
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
        // The inbox row is the durable record and stays in the caller's transaction. The push is a
        // best-effort side effect on someone else's device: it cannot be rolled back once sent, and
        // sending it inline held a Postgres transaction open for one blocking HTTPS round-trip PER
        // DEVICE TOKEN — a real cost on a serverless database with a small connection pool.
        events.publishEvent(PushRequested(userIds.toList(), type, enriched))
        log.info("Published type={} recipients={}", type, userIds.size)
    }

    private fun pushTypeKey(type: NotificationType): String = when (type) {
        NotificationType.INVITATION_RECEIVED -> "invitation_received"
        NotificationType.INVITATION_ACCEPTED -> "invitation_accepted"
        NotificationType.INVITATION_DECLINED -> "invitation_declined"
        NotificationType.TASK_ASSIGNED -> "task_assigned"
        NotificationType.TASK_COMPLETED -> "task_completed"
        NotificationType.TASK_DUE_SOON -> "task_due_soon"
        NotificationType.GROUP_OWNERSHIP_TRANSFERRED -> "group_ownership_transferred"
    }
}

/**
 * Delivers the push after the emitting transaction commits, on the async pool.
 *
 * `fallbackExecution = true` matters: not every caller is transactional — the account-deletion
 * fan-out in UserController.deleteMe runs deliberately after its service's transaction has already
 * committed, and without the fallback its event would be dropped on the floor.
 */
@Component
class PushDispatcher(
    private val push: PushService,
    private val preferences: UserPreferencesService,
) {
    private val log = LoggerFactory.getLogger(PushDispatcher::class.java)

    @Async("pushExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onPushRequested(event: PushRequested) {
        runCatching {
            val targets = preferences.pushEnabledUserIds(event.userIds, event.type)
            if (targets.isEmpty()) return
            val result = push.sendDataOnly(userIds = targets, data = event.data)
            log.info(
                "Pushed type={} targets={} delivered={} failed={} deadTokensRemoved={}",
                event.type,
                targets.size,
                result.delivered,
                result.failed,
                result.deadTokensRemoved,
            )
        }.onFailure { log.warn("Push dispatch failed for type=${event.type}: ${it.message}") }
    }
}
