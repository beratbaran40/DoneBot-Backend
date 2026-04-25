package com.todoapp.backend.auth

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
    name = "password_reset_tokens",
    indexes = [Index(name = "idx_password_reset_token_hash", columnList = "tokenHash", unique = true)],
)
class PasswordResetEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,

    @Column(nullable = false)
    var userId: Long,

    @Column(nullable = false, length = 128)
    var tokenHash: String,

    @Column(nullable = false)
    var expiresAt: Instant,

    @Column(nullable = true)
    var usedAt: Instant? = null,

    @Column(nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),
)
