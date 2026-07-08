package com.todoapp.backend.group

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.Instant

enum class GroupActivityType {
    TASK_CREATED, TASK_UPDATED, TASK_DELETED,
    TASK_ASSIGNED, TASK_UNASSIGNED, TASK_COMPLETED,
    // MEMBER_LEFT split out of MEMBER_REMOVED so clients can localize the two sentences apart
    // (removed-by-admin carries targetName; leaving is the actor's own action). Safe to add:
    // nothing switches exhaustively over this enum and old clients render `description` verbatim.
    MEMBER_ADDED, MEMBER_REMOVED, MEMBER_LEFT, OWNERSHIP_TRANSFERRED,
}

@Entity
@Table(
    name = "group_activities",
    indexes = [Index(name = "idx_group_activities_group", columnList = "groupId,timestamp")],
)
class GroupActivityEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,

    @Column(nullable = false)
    var groupId: Long,

    @Column(nullable = false)
    var actorUserId: Long,

    @Column(nullable = false, length = 32)
    var type: String,

    @Column
    var taskId: Long? = null,

    @Column
    var taskTitle: String? = null,

    // The person the action was done TO (assignee / removed member / new owner) — null for
    // self-contained actions. Clients build localized sentences from (type, taskTitle, targetName).
    @Column(length = 255)
    var targetName: String? = null,

    @Column(nullable = false, length = 500)
    var description: String,

    @Column(nullable = false, updatable = false)
    var timestamp: Instant = Instant.now(),
)
