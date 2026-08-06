package com.todoapp.backend.notif

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
    name = "device_tokens",
    indexes = [
        Index(name = "idx_device_tokens_user", columnList = "userId"),
        Index(name = "idx_device_tokens_token", columnList = "token", unique = true),
    ],
)
class DeviceTokenEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,

    @Column(nullable = false)
    var userId: Long,

    @Column(nullable = false, length = 512)
    var token: String,

    @Column
    var deviceId: String? = null,

    @Column
    var deviceName: String? = null,

    /**
     * The device's IANA zone (e.g. "Europe/Istanbul"), sent with the token. Null for a token
     * registered before V29; TaskDueSoonJob falls back to the configured default for those.
     */
    @Column(name = "time_zone")
    var timeZone: String? = null,

    @Column(nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),

    @Column(nullable = false)
    var updatedAt: Instant = Instant.now(),
)
