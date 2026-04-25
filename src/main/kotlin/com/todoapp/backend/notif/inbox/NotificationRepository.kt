package com.todoapp.backend.notif.inbox

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
interface NotificationRepository : JpaRepository<NotificationEntity, Long> {
    fun findAllByUserIdOrderByCreatedAtDesc(userId: Long, pageable: Pageable): List<NotificationEntity>

    fun findAllByUserIdAndCreatedAtLessThanOrderByCreatedAtDesc(
        userId: Long,
        before: Instant,
        pageable: Pageable,
    ): List<NotificationEntity>

    fun countByUserIdAndIsReadFalse(userId: Long): Long

    fun findByIdAndUserId(id: Long, userId: Long): NotificationEntity?

    fun deleteAllByCreatedAtBefore(cutoff: Instant): Long
}
