package com.todoapp.backend.integration

import com.todoapp.backend.notif.inbox.NotificationService
import com.todoapp.backend.notif.inbox.NotificationType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Regression lock for the notification-inbox IDOR surface (`PUT /notifications/{id}/read`).
 *
 * `NotificationService.markRead` scopes the lookup with `findByIdAndUserId`, so a non-owner gets the
 * `BaseResponse.error(404)` envelope (HTTP 200, `code = 404`) and the victim's row is NOT flipped to
 * read. A change that dropped the userId scope would silently let anyone clear others' notifications.
 */
class NotificationIdorIntegrationTest : AbstractIntegrationTest() {
    @Autowired
    private lateinit var notifications: NotificationService

    @Test
    fun `a user cannot mark another user's notification as read`() {
        val owner = registerUser()
        val attacker = registerUser()
        val notif = notifications.create(
            userId = owner.user.id,
            type = NotificationType.TASK_ASSIGNED,
            title = "Task assigned",
            body = "You were assigned a task",
            payload = emptyMap(),
        )

        // Non-owner: markRead returns false → controller emits the 404 envelope with HTTP 200, no mutation.
        mockMvc
            .perform(put("/notifications/${notif.id}/read").header("Authorization", bearer(attacker.user.id)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value(404))
        assertThat(notifications.unreadCount(owner.user.id)).isEqualTo(1L)

        // The real owner can mark it read.
        mockMvc
            .perform(put("/notifications/${notif.id}/read").header("Authorization", bearer(owner.user.id)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value(200))
        assertThat(notifications.unreadCount(owner.user.id)).isEqualTo(0L)
    }

    @Test
    fun `marking a notification read without a token is 401`() {
        mockMvc.perform(put("/notifications/1/read")).andExpect(status().isUnauthorized)
    }
}
