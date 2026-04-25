package com.todoapp.backend.group

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.Instant

enum class InvitationStatus { PENDING, ACCEPTED, DECLINED, CANCELLED, EXPIRED }

@Entity
@Table(
    name = "group_invitations",
    indexes = [
        Index(name = "idx_invitations_invitee_status", columnList = "inviteeUserId,status"),
        Index(name = "idx_invitations_group", columnList = "groupId"),
    ],
)
class InvitationEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,

    @Column(nullable = false)
    var groupId: Long,

    @Column(nullable = false)
    var inviterUserId: Long,

    @Column(nullable = false)
    var inviteeUserId: Long,

    @Column(nullable = false)
    var inviteeEmail: String,

    @Column(nullable = false, length = 16)
    var status: String = InvitationStatus.PENDING.name,

    @Column(nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),

    @Column(nullable = true)
    var respondedAt: Instant? = null,
)
