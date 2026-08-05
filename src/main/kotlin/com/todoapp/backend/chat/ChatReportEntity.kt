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

    // Resolution state (V26). Until now this table was write-only — reports went in and no code path
    // ever read them back, so the whole moderation workflow was a best-effort email to the admin inbox.
    // Existing rows default to OPEN because nothing has in fact been reviewed.
    @Column(nullable = false, length = 16)
    var status: String = ReportStatus.OPEN.name,

    @Column(length = 24)
    var resolution: String? = null,

    @Column(name = "resolution_note", length = 500)
    var resolutionNote: String? = null,

    @Column(name = "resolved_at")
    var resolvedAt: Instant? = null,

    /** No FK: the decision must stay attributable after the deciding admin account is gone. */
    @Column(name = "resolved_by")
    var resolvedBy: Long? = null,
)

enum class ReportStatus { OPEN, RESOLVED, DISMISSED }

/** What the admin actually did. Kept coarse on purpose — a long taxonomy nobody applies consistently. */
enum class ReportResolution { NO_ACTION, CONTENT_REMOVED, USER_SUSPENDED }
