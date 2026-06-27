package com.todoapp.backend.chat

import com.todoapp.backend.common.BaseResponse
import com.todoapp.backend.common.CurrentUser
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * DoneBot chat HTTP entry point. Auth is enforced globally by JwtAuthFilter
 * — every /chat path falls under `anyRequest().authenticated()`.
 */
@RestController
@RequestMapping("/chat")
class ChatController(
    private val chatService: ChatService,
    private val chatReportService: ChatReportService,
    private val rateLimiter: ChatRateLimiter,
) {
    @PostMapping("/message")
    fun message(@Valid @RequestBody request: ChatMessageRequest): BaseResponse<ChatMessageResponse> {
        val userId = CurrentUser.id()
        when (val gate = rateLimiter.acquire(userId)) {
            is ChatRateLimiter.Result.Allowed -> Unit
            is ChatRateLimiter.Result.Denied -> throw ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS,
                // Phrase contains "rate limit" + "Retry in <n>s" so the client's
                // ChatViewModel error parser tags it RATE_LIMITED and shows a cooldown.
                "rate limit exceeded. Retry in ${gate.retryAfterSeconds}s",
            )
        }
        return BaseResponse.ok(chatService.reply(userId, request))
    }

    /**
     * Records a user report of an offensive/inappropriate assistant reply for moderation
     * review. Required by Google Play's Generative AI policy (in-app reporting of AI content).
     */
    @PostMapping("/report")
    fun report(@Valid @RequestBody request: ChatReportRequest): BaseResponse<Unit> {
        chatReportService.record(CurrentUser.id(), request)
        return BaseResponse.ok("Report received")
    }
}
