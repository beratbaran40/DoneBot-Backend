package com.todoapp.backend.chat

import com.google.protobuf.Struct
import com.todoapp.backend.group.GroupMemberRepository
import com.todoapp.backend.group.GroupRepository
import com.todoapp.backend.task.TaskEntity
import com.todoapp.backend.task.TaskRepository
import com.todoapp.backend.task.TaskSubtaskRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.mockito.Mockito
import org.mockito.Mockito.anyLong
import java.time.LocalDate

/**
 * §7.14 — the chat/Vertex read path must never expose `isSecret` (biometric-protected) tasks: their
 * titles + locations would otherwise be sent verbatim to Google Vertex, violating the user's explicit
 * "hide this" intent (KVKK data minimization). Every read tool funnels through `visiblePersonalTasks`.
 */
class ChatToolSecretFilterTest {
    private val taskRepo = Mockito.mock(TaskRepository::class.java)
    private val groupRepo = Mockito.mock(GroupRepository::class.java)
    private val members = Mockito.mock(GroupMemberRepository::class.java)
    private val subtaskRepo = Mockito.mock(TaskSubtaskRepository::class.java)
    private val tools = ChatToolService(taskRepo, groupRepo, members, subtaskRepo)

    @Test
    fun `getTodaysTasks hides secret tasks from the model payload`() {
        val today = LocalDate.now().toEpochDay()
        given(taskRepo.findAllByOwnerIdAndFamilyGroupIdIsNull(anyLong())).willReturn(
            listOf(
                task("Buy milk", today, isSecret = false),
                task("Therapy appointment", today, isSecret = true),
            ),
        )

        val payload = tools.execute(USER_ID, "getTodaysTasks", Struct.getDefaultInstance()).toString()

        assertThat(payload).contains("Buy milk")
        assertThat(payload).doesNotContain("Therapy appointment")
    }

    private fun task(title: String, date: Long, isSecret: Boolean) = TaskEntity(
        ownerId = USER_ID,
        title = title,
        date = date,
        timeStart = 0L,
        timeEnd = 0L,
        isSecret = isSecret,
    )

    private companion object {
        const val USER_ID = 1L
    }
}
