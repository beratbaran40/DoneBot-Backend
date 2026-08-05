package com.todoapp.backend.group

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.Instant

/**
 * A user-submitted report flagging offensive or inappropriate group content — another member, a
 * shared task photo, or a task. Persisted for manual moderation review; backs the in-app "Report"
 * action required by Google Play's user-generated-content policy. Blocking a user is handled
 * client-side (device-local), so only reports are stored server-side.
 */
@Entity
@Table(
    name = "content_reports",
    indexes = [
        Index(name = "idx_content_reports_reporter_hash", columnList = "reporterUserId,targetHash", unique = true),
        Index(name = "idx_content_reports_created_at", columnList = "createdAt"),
    ],
)
class ContentReportEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,

    @Column(nullable = false)
    var reporterUserId: Long,

    @Column(nullable = false)
    var groupId: Long,

    /** One of MEMBER, PHOTO, TASK. */
    @Column(nullable = false, length = 16)
    var targetType: String,

    /** Set for MEMBER reports (the reported user). Null for content-only reports. */
    @Column
    var targetUserId: Long? = null,

    /** Photo URL/id or task id for content reports. Null for member reports. */
    @Column(length = 512)
    var targetRef: String? = null,

    @Column(length = 500)
    var reason: String? = null,

    /** SHA-256 hex of "groupId:targetType:targetKey" — backs the (reporter, target) unique dedup index. */
    @Column(nullable = false, length = 64)
    var targetHash: String,

    @Column(nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),

    // Resolution state (V26) — see ChatReportEntity for why this table needed one.
    @Column(nullable = false, length = 16)
    var status: String = com.todoapp.backend.chat.ReportStatus.OPEN.name,

    @Column(length = 24)
    var resolution: String? = null,

    @Column(name = "resolution_note", length = 500)
    var resolutionNote: String? = null,

    @Column(name = "resolved_at")
    var resolvedAt: Instant? = null,

    @Column(name = "resolved_by")
    var resolvedBy: Long? = null,
)
