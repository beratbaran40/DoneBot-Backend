package com.todoapp.backend.user

import com.todoapp.backend.auth.RefreshTokenRepository
import com.todoapp.backend.common.BaseResponse
import com.todoapp.backend.common.CurrentUser
import com.todoapp.backend.notif.NotificationPublisher
import com.todoapp.backend.notif.inbox.NotificationType
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/users")
class UserController(
    private val users: UserRepository,
    private val refreshTokens: RefreshTokenRepository,
    private val passwordEncoder: PasswordEncoder,
    private val accountDeletionService: AccountDeletionService,
    private val notificationPublisher: NotificationPublisher,
) {

    @GetMapping("/me")
    fun me(): BaseResponse<UserData> {
        val user = users.findById(CurrentUser.id()).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")
        }
        return BaseResponse.ok(user.toDto())
    }

    @PutMapping("/me")
    @Transactional
    fun updateMe(@Valid @RequestBody req: UpdateUserRequest): BaseResponse<UserData> {
        val user = users.findById(CurrentUser.id()).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")
        }
        user.displayName = req.displayName.trim()
        return BaseResponse.ok(users.save(user).toDto())
    }

    @PutMapping("/me/password")
    @Transactional
    fun changePassword(@Valid @RequestBody req: ChangePasswordRequest): BaseResponse<Unit> {
        val user = users.findById(CurrentUser.id()).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")
        }
        val hash = user.passwordHash
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Account has no password")
        if (!passwordEncoder.matches(req.currentPassword, hash)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "current_password_incorrect")
        }
        if (passwordEncoder.matches(req.newPassword, hash)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "new_password_same")
        }
        user.passwordHash = passwordEncoder.encode(req.newPassword)
        users.save(user)
        refreshTokens.revokeAllByUserId(user.id)
        return BaseResponse.ok(Unit)
    }

    @DeleteMapping("/me")
    fun deleteMe(): BaseResponse<Unit> {
        val userId = CurrentUser.id()
        val result = accountDeletionService.deleteAccount(userId)
        // After-commit fan-out: notify each new owner that they took over a group.
        // The deletion transaction has already committed by the time we reach here
        // (controller method itself isn't @Transactional, only the service is).
        result.transferredGroups.forEach { tg ->
            runCatching {
                notificationPublisher.publish(
                    userIds = listOf(tg.newOwnerId),
                    type = NotificationType.GROUP_OWNERSHIP_TRANSFERRED,
                    title = "You're now the admin of ${tg.groupName}",
                    body = "Tap to open the group",
                    payload = mapOf(
                        "groupId" to tg.groupId.toString(),
                        "groupName" to tg.groupName,
                    ),
                )
            }
        }
        return BaseResponse.ok(Unit)
    }

    @PostMapping("/me/avatar", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @Transactional
    fun uploadAvatar(@RequestPart("file") file: MultipartFile): BaseResponse<UserData> {
        if (file.isEmpty) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Empty file")
        }
        val contentType = file.contentType ?: "application/octet-stream"
        if (!contentType.startsWith("image/")) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "File must be an image")
        }
        if (file.size > MAX_AVATAR_BYTES) {
            throw ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Image too large (max 2MB)")
        }
        val user = users.findById(CurrentUser.id()).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")
        }
        user.avatarBytes = file.bytes
        user.avatarContentType = contentType
        return BaseResponse.ok(users.save(user).toDto())
    }

    @GetMapping("/{id}/avatar")
    fun getAvatar(@PathVariable id: Long): ResponseEntity<ByteArray> {
        val user = users.findById(id).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")
        }
        val bytes = user.avatarBytes
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No avatar")
        val type = user.avatarContentType ?: MediaType.IMAGE_JPEG_VALUE
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(type))
            .header("Cache-Control", "public, max-age=300")
            .body(bytes)
    }

    companion object {
        private const val MAX_AVATAR_BYTES: Long = 2L * 1024 * 1024
    }
}
