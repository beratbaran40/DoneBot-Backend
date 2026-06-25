package com.todoapp.backend.user

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface UserPreferencesRepository : JpaRepository<UserPreferencesEntity, Long> {
    fun findAllByUserIdInAndPushEnabledTrue(userIds: Collection<Long>): List<UserPreferencesEntity>

    fun findByUserId(userId: Long): UserPreferencesEntity?
}
