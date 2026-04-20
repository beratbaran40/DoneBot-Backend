package com.todoapp.backend.user

import com.todoapp.backend.common.BaseResponse
import com.todoapp.backend.common.CurrentUser
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
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
class UserController(private val users: UserRepository) {

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
