package com.todoapp.backend.chat

import com.todoapp.backend.common.BaseResponse
import com.todoapp.backend.common.CurrentUser
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * DoneBot chat HTTP entry point. Auth is enforced globally by JwtAuthFilter
 * — every /chat path falls under `anyRequest().authenticated()`.
 */
@RestController
@RequestMapping("/chat")
class ChatController(
    private val chatService: ChatService,
) {
    @PostMapping("/message")
    fun message(@Valid @RequestBody request: ChatMessageRequest): BaseResponse<ChatMessageResponse> {
        val userId = CurrentUser.id()
        return BaseResponse.ok(chatService.reply(userId, request))
    }
}
