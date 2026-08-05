package com.todoapp.backend.user

import jakarta.persistence.Basic
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Lob
import jakarta.persistence.Table
import java.time.Instant

/**
 * Platform-level role. Distinct from [com.todoapp.backend.group.GroupRole], which is scoped to a single
 * family group — ADMIN here means "can reach the /admin endpoints", and even that requires the
 * account's email to also be on the app.admin.allowed-emails allowlist.
 */
enum class UserRole { USER, ADMIN }

/** Account state. SUSPENDED accounts are refused at login/refresh and have their sessions revoked. */
enum class UserStatus { ACTIVE, SUSPENDED }

@Entity
@Table(name = "users")
class UserEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,

    @Column(unique = true, nullable = false)
    var email: String,

    @Column(nullable = false)
    var displayName: String,

    @Column(nullable = true)
    var passwordHash: String? = null,

    @Column(nullable = true)
    var avatarUrl: String? = null,

    @Column(nullable = false)
    var emailVerified: Boolean = false,

    @Column(nullable = false)
    var providersCsv: String = "email",

    @Column(nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),

    @Lob
    @Basic(fetch = FetchType.LAZY)
    @Column(name = "avatar_bytes", nullable = true)
    var avatarBytes: ByteArray? = null,

    @Column(name = "avatar_content_type", nullable = true, length = 64)
    var avatarContentType: String? = null,

    // Stored as plain VARCHAR rather than @Enumerated, matching GroupEntity.role — keeps an unknown
    // value read from the DB from blowing up entity hydration.
    @Column(nullable = false, length = 16)
    var role: String = UserRole.USER.name,

    @Column(nullable = false, length = 16)
    var status: String = UserStatus.ACTIVE.name,

    @Column(name = "suspended_at", nullable = true)
    var suspendedAt: Instant? = null,

    @Column(name = "suspended_reason", nullable = true, length = 500)
    var suspendedReason: String? = null,

    // Written by the metrics ActivityRecorder off the request thread; only ever "roughly now".
    @Column(name = "last_active_at", nullable = true)
    var lastActiveAt: Instant? = null,
) {
    val providers: List<String>
        get() = providersCsv.split(",").filter { it.isNotBlank() }
}
