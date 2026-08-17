package com.todoapp.backend.pomodoro

import com.todoapp.backend.common.BaseResponse
import com.todoapp.backend.common.CurrentUser
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Not registered in `SecurityConfig`: the `@Order(2)` chain ends in `anyRequest().authenticated()`, so
 * these routes are JWT-protected by default.
 *
 * The owning user is always [CurrentUser.id] and is never read from the body — there is no field a
 * caller could set to write into, or read out of, someone else's history.
 */
@RestController
@RequestMapping("/pomodoro")
class PomodoroController(private val service: PomodoroService) {
    @PostMapping("/sessions")
    fun upload(
        @Valid @RequestBody req: PomodoroUploadRequest,
    ): BaseResponse<PomodoroUploadData> = BaseResponse.ok(service.upload(CurrentUser.id(), req))

    /** [from] and [to] are epoch days in the caller's own time zone, inclusive. */
    @GetMapping("/sessions")
    fun list(
        @RequestParam from: Long,
        @RequestParam to: Long,
    ): BaseResponse<PomodoroSessionListData> = BaseResponse.ok(service.list(CurrentUser.id(), from, to))
}
