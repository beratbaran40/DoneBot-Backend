package com.todoapp.backend.user

import com.todoapp.backend.common.BaseResponse
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/users")
class UserController(private val users: UserRepository) {

    @GetMapping("/me")
    fun me(): BaseResponse<UserData> {
        val userId = SecurityContextHolder.getContext().authentication?.principal as? Long
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing auth")
        val user = users.findById(userId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")
        }
        return BaseResponse.ok(user.toDto())
    }
}
