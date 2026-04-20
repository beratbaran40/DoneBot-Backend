package com.todoapp.backend.task

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

@Entity
@Table(
    name = "task_photos",
    indexes = [Index(name = "idx_task_photos_task", columnList = "taskId")],
)
class TaskPhotoEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,

    @Column(nullable = false)
    var taskId: Long,

    @Lob
    @Basic(fetch = FetchType.LAZY)
    @Column(nullable = false)
    var bytes: ByteArray,

    @Column(nullable = false, length = 64)
    var contentType: String,

    @Column(nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),
)
