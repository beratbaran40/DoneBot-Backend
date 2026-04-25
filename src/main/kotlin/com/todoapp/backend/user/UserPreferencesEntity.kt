package com.todoapp.backend.user

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "user_preferences")
class UserPreferencesEntity(
    @Id
    @Column(name = "user_id")
    var userId: Long,

    @Column(name = "push_enabled", nullable = false)
    var pushEnabled: Boolean = true,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)
