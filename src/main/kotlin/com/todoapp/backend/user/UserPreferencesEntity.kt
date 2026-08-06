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

    /**
     * CSV of [com.todoapp.backend.notif.inbox.NotificationType] names the user has muted. Empty
     * means "nothing muted", which is what every pre-V29 row defaults to. Stored as a list rather
     * than a column per type so adding a notification type never needs a migration.
     */
    @Column(name = "push_disabled_types", nullable = false)
    var pushDisabledTypes: String = "",

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)
