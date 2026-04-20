package com.todoapp.backend.task

import com.todoapp.backend.common.BaseResponse
import com.todoapp.backend.common.CurrentUser
import com.todoapp.backend.group.GroupMemberRepository
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException

data class TaskPhotoData(val id: Long, val url: String, val createdAt: Long)

@RestController
@RequestMapping("/tasks")
class TaskPhotoController(
    private val tasks: TaskRepository,
    private val photos: TaskPhotoRepository,
    private val members: GroupMemberRepository,
) {

    @PostMapping("/{taskId}/photos", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @Transactional
    fun upload(
        @PathVariable taskId: Long,
        @RequestPart("file") file: MultipartFile,
    ): BaseResponse<TaskPhotoData> {
        val task = requireAccessibleTask(taskId)
        if (file.isEmpty) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Empty file")
        val contentType = file.contentType ?: "application/octet-stream"
        if (!contentType.startsWith("image/")) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "File must be an image")
        }
        if (file.size > MAX_PHOTO_BYTES) {
            throw ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Image too large (max 5MB)")
        }
        if (photos.countByTaskId(task.id) >= MAX_PHOTOS_PER_TASK) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Max $MAX_PHOTOS_PER_TASK photos per task")
        }
        val saved = photos.save(
            TaskPhotoEntity(taskId = task.id, bytes = file.bytes, contentType = contentType)
        )
        return BaseResponse.ok(saved.toDto())
    }

    @GetMapping("/{taskId}/photos/{photoId}")
    fun get(@PathVariable taskId: Long, @PathVariable photoId: Long): ResponseEntity<ByteArray> {
        requireAccessibleTask(taskId)
        val photo = photos.findById(photoId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Photo not found")
        }
        if (photo.taskId != taskId) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Photo not on this task")
        }
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(photo.contentType))
            .header("Cache-Control", "public, max-age=300")
            .body(photo.bytes)
    }

    @DeleteMapping("/{taskId}/photos/{photoId}")
    @Transactional
    fun delete(@PathVariable taskId: Long, @PathVariable photoId: Long): BaseResponse<Unit> {
        requireAccessibleTask(taskId)
        val photo = photos.findById(photoId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Photo not found")
        }
        if (photo.taskId != taskId) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Photo not on this task")
        }
        photos.delete(photo)
        return BaseResponse.ok()
    }

    private fun requireAccessibleTask(taskId: Long): TaskEntity {
        val task = tasks.findById(taskId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found")
        }
        val callerId = CurrentUser.id()
        val groupId = task.familyGroupId
        val allowed = when {
            groupId == null -> task.ownerId == callerId
            else -> members.findByGroupIdAndUserId(groupId, callerId) != null
        }
        if (!allowed) throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not allowed")
        return task
    }

    companion object {
        const val MAX_PHOTO_BYTES: Long = 5L * 1024 * 1024
        const val MAX_PHOTOS_PER_TASK: Long = 10
    }
}

fun TaskPhotoEntity.toDto(): TaskPhotoData = TaskPhotoData(
    id = id,
    url = "/tasks/$taskId/photos/$id",
    createdAt = createdAt.toEpochMilli(),
)
