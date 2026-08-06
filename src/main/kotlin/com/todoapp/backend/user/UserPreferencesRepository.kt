package com.todoapp.backend.user

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface UserPreferencesRepository : JpaRepository<UserPreferencesEntity, Long> {
    // The push-enabled filter now also has to consider the per-type mute list, which is a CSV column
    // no derived query can read — pushEnabledUserIds loads the rows and decides in Kotlin instead.

    fun findByUserId(userId: Long): UserPreferencesEntity?
}
