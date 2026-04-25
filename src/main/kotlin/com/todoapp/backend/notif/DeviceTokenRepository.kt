package com.todoapp.backend.notif

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface DeviceTokenRepository : JpaRepository<DeviceTokenEntity, Long> {
    fun findByToken(token: String): DeviceTokenEntity?
    fun findAllByUserId(userId: Long): List<DeviceTokenEntity>
    fun findAllByUserIdIn(userIds: Collection<Long>): List<DeviceTokenEntity>
    fun deleteByUserIdAndToken(userId: Long, token: String): Long
}
