package com.todoapp.backend.common

import org.springframework.http.HttpStatus
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.server.ResponseStatusException

object CurrentUser {
    fun id(): Long = SecurityContextHolder.getContext().authentication?.principal as? Long
        ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated")
}
