package com.todoapp.backend.group

import jakarta.persistence.Basic
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Lob
import jakarta.persistence.Table
import java.time.Instant

enum class GroupRole { ADMIN, MEMBER }

@Entity
@Table(name = "family_groups")
class GroupEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,

    @Column(nullable = false)
    var name: String,

    @Column(length = 2000, nullable = false)
    var description: String = "",

    @Column(nullable = false)
    var ownerId: Long,

    @Column(nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),

    @Column(nullable = false)
    var updatedAt: Instant = Instant.now(),

    @Lob
    @Basic(fetch = FetchType.LAZY)
    @Column(name = "avatar_bytes", nullable = true)
    var avatarBytes: ByteArray? = null,

    @Column(name = "avatar_content_type", nullable = true, length = 64)
    var avatarContentType: String? = null,
)

@Entity
@Table(
    name = "group_members",
    indexes = [
        Index(name = "idx_group_members_group", columnList = "groupId"),
        Index(name = "idx_group_members_user", columnList = "userId"),
        Index(name = "idx_group_members_unique", columnList = "groupId,userId", unique = true),
    ],
)
class GroupMemberEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,

    @Column(nullable = false)
    var groupId: Long,

    @Column(nullable = false)
    var userId: Long,

    @Column(nullable = false)
    var role: String = GroupRole.MEMBER.name,

    @Column(nullable = false, updatable = false)
    var joinedAt: Instant = Instant.now(),
)
