package com.todoapp.backend.chat

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.Instant

/**
 * A user-submitted report flagging an offensive or inappropriate DoneBot (AI) reply.
 * Persisted for manual moderation review — backs the in-app "report this response"
 * action mandated by Google Play's Generative AI content policy.
 */
@Entity
@Table(
    name = "chat_reports",
    indexes = [
        Index(name = "idx_chat_reports_user_hash", columnList = "userId,messageHash", unique = true),
        Index(name = "idx_chat_reports_created_at", columnList = "createdAt"),
    ],
)
class ChatReportEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,

    @Column(nullable = false)
    var userId: Long,

    @Column(nullable = false, length = 4000)
    var messageContent: String,

    /** SHA-256 hex of messageContent — backs the (userId, messageHash) unique dedup index. */
    @Column(nullable = false, length = 64)
    var messageHash: String,

    @Column(length = 500)
    var reason: String? = null,

    @Column(nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),
)
