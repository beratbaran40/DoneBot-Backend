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
) {
    val providers: List<String>
        get() = providersCsv.split(",").filter { it.isNotBlank() }
}
