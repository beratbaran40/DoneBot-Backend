package com.todoapp.backend.notif.inbox

import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

data class NotificationData(
    val id: Long,
    val type: String,
    val title: String,
    val body: String,
    val payload: Map<String, String>,
    val isRead: Boolean,
    val createdAt: Long,
)

data class NotificationListData(
    val items: List<NotificationData>,
    val hasMore: Boolean,
)

data class UnreadCountData(val count: Long)

@Service
class NotificationService(
    private val repository: NotificationRepository,
    private val objectMapper: ObjectMapper,
) {
    @Transactional
    fun create(
        userId: Long,
        type: NotificationType,
        title: String,
        body: String,
        payload: Map<String, String>,
    ): NotificationEntity {
        val entity = NotificationEntity(
            userId = userId,
            type = type.name,
            title = title.take(200),
            body = body.take(500),
            payloadJson = objectMapper.writeValueAsString(payload),
        )
        return repository.save(entity)
    }

    @Transactional(readOnly = true)
    fun list(userId: Long, before: Long?, limit: Int): NotificationListData {
        val size = limit.coerceIn(1, MAX_PAGE)
        val page = PageRequest.of(0, size + 1)
        val rows = if (before != null) {
            repository.findAllByUserIdAndCreatedAtLessThanOrderByCreatedAtDesc(
                userId = userId,
                before = Instant.ofEpochMilli(before),
                pageable = page,
            )
        } else {
            repository.findAllByUserIdOrderByCreatedAtDesc(userId = userId, pageable = page)
        }
        val hasMore = rows.size > size
        return NotificationListData(
            items = rows.take(size).map { it.toData() },
            hasMore = hasMore,
        )
    }

    @Transactional
    fun markRead(userId: Long, id: Long): Boolean {
        val entity = repository.findByIdAndUserId(id, userId) ?: return false
        if (!entity.isRead) {
            entity.isRead = true
            repository.save(entity)
        }
        return true
    }

    @Transactional
    fun markAllRead(userId: Long) {
        val page = PageRequest.of(0, MAX_PAGE * 4)
        val rows = repository.findAllByUserIdOrderByCreatedAtDesc(userId, page)
            .filter { !it.isRead }
        if (rows.isEmpty()) return
        rows.forEach { it.isRead = true }
        repository.saveAll(rows)
    }

    @Transactional(readOnly = true)
    fun unreadCount(userId: Long): Long = repository.countByUserIdAndIsReadFalse(userId)

    private fun NotificationEntity.toData(): NotificationData {
        val payload: Map<String, String> = runCatching {
            objectMapper.readValue(payloadJson, STRING_MAP_TYPE)
        }.getOrElse { emptyMap() }
        return NotificationData(
            id = id,
            type = type,
            title = title,
            body = body,
            payload = payload,
            isRead = isRead,
            createdAt = createdAt.toEpochMilli(),
        )
    }

    private companion object {
        const val MAX_PAGE = 100
        val STRING_MAP_TYPE = object : TypeReference<Map<String, String>>() {}
    }
}
