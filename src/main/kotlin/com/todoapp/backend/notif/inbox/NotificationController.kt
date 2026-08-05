package com.todoapp.backend.notif.inbox

import com.todoapp.backend.common.BaseResponse
import com.todoapp.backend.common.CurrentUser
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/notifications")
class NotificationController(private val service: NotificationService) {

    @GetMapping
    fun list(
        @RequestParam(required = false) before: Long?,
        @RequestParam(required = false, defaultValue = "50") limit: Int,
    ): BaseResponse<NotificationListData> =
        BaseResponse.ok(service.list(CurrentUser.id(), before, limit))

    @PutMapping("/{id}/read")
    fun markRead(@PathVariable id: Long): BaseResponse<Unit> {
        val ok = service.markRead(CurrentUser.id(), id)
        return if (ok) BaseResponse.ok(Unit) else BaseResponse.error(404, "Not found")
    }

    @PutMapping("/read-all")
    fun markAllRead(): BaseResponse<Unit> {
        service.markAllRead(CurrentUser.id())
        return BaseResponse.ok(Unit)
    }

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: Long): BaseResponse<Unit> {
        val ok = service.delete(CurrentUser.id(), id)
        return if (ok) BaseResponse.ok(Unit) else BaseResponse.error(404, "Not found")
    }

    @GetMapping("/unread-count")
    fun unreadCount(): BaseResponse<UnreadCountData> =
        BaseResponse.ok(UnreadCountData(service.unreadCount(CurrentUser.id())))
}
