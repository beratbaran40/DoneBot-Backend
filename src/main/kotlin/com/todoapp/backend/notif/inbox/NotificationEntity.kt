package com.todoapp.backend.notif.inbox

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(
    name = "notifications",
    indexes = [
        Index(name = "idx_notifications_user_created", columnList = "userId,createdAt"),
        Index(name = "idx_notifications_user_unread", columnList = "userId,isRead"),
    ],
)
class NotificationEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,

    @Column(nullable = false)
    var userId: Long,

    @Column(nullable = false, length = 48)
    var type: String,

    @Column(nullable = false, length = 200)
    var title: String,

    @Column(nullable = false, length = 500)
    var body: String,

    @Column(nullable = false, length = 2000)
    var payloadJson: String,

    @Column(nullable = false)
    var isRead: Boolean = false,

    @Column(nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),
)
