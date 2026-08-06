package com.todoapp.backend.notif

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface DeviceTokenRepository : JpaRepository<DeviceTokenEntity, Long> {
    fun findByToken(token: String): DeviceTokenEntity?
    fun findAllByUserId(userId: Long): List<DeviceTokenEntity>
    fun findAllByUserIdIn(userIds: Collection<Long>): List<DeviceTokenEntity>
    fun deleteByUserIdAndToken(userId: Long, token: String): Long

    /**
     * The user's most recently seen device that reported a zone. "Most recent" is the tie-breaker
     * that matters: someone who travels re-registers from the phone they are actually holding, and a
     * long-abandoned tablet in another country should not decide when their reminders fire.
     */
    fun findFirstByUserIdAndTimeZoneIsNotNullOrderByUpdatedAtDesc(userId: Long): DeviceTokenEntity?
}
