package com.todoapp.backend.chat

import com.google.protobuf.Struct
import com.todoapp.backend.group.GroupMemberEntity
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

    @Test
    fun `getGroupTasks hides secret tasks too`() {
        given(members.findByGroupIdAndUserId(GROUP_ID, USER_ID)).willReturn(
            GroupMemberEntity(groupId = GROUP_ID, userId = USER_ID),
        )
        given(taskRepo.findAllByFamilyGroupId(GROUP_ID)).willReturn(
            listOf(
                task("Take out the bins", TODAY, isSecret = false),
                task("Divorce lawyer", TODAY, isSecret = true),
            ),
        )

        val payload = tools.execute(USER_ID, "getGroupTasks", structOf("groupId" to GROUP_ID)).toString()

        assertThat(payload).contains("Take out the bins")
        assertThat(payload).doesNotContain("Divorce lawyer")
    }

    @Test
    fun `getGroupTasks refuses a group the caller does not belong to`() {
        given(members.findByGroupIdAndUserId(GROUP_ID, USER_ID)).willReturn(null)

        val payload = tools.execute(USER_ID, "getGroupTasks", structOf("groupId" to GROUP_ID)).toString()

        assertThat(payload).contains("not a member")
        // Membership must be checked before any task read — otherwise any group id enumerates
        // someone else's shared tasks.
        Mockito.verify(taskRepo, Mockito.never()).findAllByFamilyGroupId(anyLong())
    }

    @Test
    fun `getGroupTasks carries no ids and no member names`() {
        given(members.findByGroupIdAndUserId(GROUP_ID, USER_ID)).willReturn(
            GroupMemberEntity(groupId = GROUP_ID, userId = USER_ID),
        )
        given(taskRepo.findAllByFamilyGroupId(GROUP_ID)).willReturn(
            listOf(task("Take out the bins", TODAY, isSecret = false).apply { assignedToUserId = 7L }),
        )

        val payload = tools.execute(USER_ID, "getGroupTasks", structOf("groupId" to GROUP_ID)).toString()

        // Group tasks are read-only, so there is no id to chain into — and shipping other members'
        // ids/names to Vertex would buy nothing.
        assertThat(payload).doesNotContain("\"id\"")
        assertThat(payload).contains("readOnly")
        assertThat(payload).contains("assignedToMe")
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
        const val GROUP_ID = 5L
        val TODAY = LocalDate.now().toEpochDay()
    }
}
