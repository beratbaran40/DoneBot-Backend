package com.todoapp.backend.chat

import com.todoapp.backend.group.GroupMemberEntity
import com.todoapp.backend.group.GroupMemberRepository
import com.todoapp.backend.group.GroupRepository
import com.todoapp.backend.task.Recurrence
import com.todoapp.backend.task.TaskEntity
import com.todoapp.backend.task.TaskRepository
import com.todoapp.backend.task.TaskSubtaskEntity
import com.todoapp.backend.task.TaskSubtaskRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.mockito.Mockito
import org.mockito.Mockito.anyLong
import java.time.LocalDate

/**
 * Everything the model can oversize. Each of these is untrusted input with a hard limit somewhere
 * downstream — a VARCHAR, a transaction budget, or a payload the model reads as a total — and the
 * failure when the limit isn't enforced here is never a graceful one:
 *
 * an over-long string reaches the column and raises `DataIntegrityViolationException` INSIDE
 * `execute`'s transaction, which leaves it rollback-only and turns one failed tool call into an HTTP
 * 500 for the whole chat turn.
 */
class ChatToolLimitsTest {
    private val taskRepo = Mockito.mock(TaskRepository::class.java)
    private val groupRepo = Mockito.mock(GroupRepository::class.java)
    private val members = Mockito.mock(GroupMemberRepository::class.java)
    private val subtaskRepo = Mockito.mock(TaskSubtaskRepository::class.java)
    private val tools = ChatToolService(taskRepo, groupRepo, members, subtaskRepo)

    @BeforeEach
    fun setUp() {
        given(taskRepo.save(anyRef<TaskEntity>())).willAnswer { it.arguments[0] }
        given(subtaskRepo.save(anyRef<TaskSubtaskEntity>())).willAnswer { it.arguments[0] }
        given(subtaskRepo.findAllByTaskIdOrderByOrderIndexAsc(anyLong())).willReturn(emptyList())
        given(taskRepo.findByOwnerIdAndClientTaskId(anyLong(), Mockito.anyString())).willReturn(null)
    }

    @Test
    fun `an over-long title is capped to the column width instead of failing the whole turn`() {
        val saved = mutableListOf<TaskEntity>()
        given(taskRepo.save(anyRef<TaskEntity>())).willAnswer { (it.arguments[0] as TaskEntity).also(saved::add) }

        tools.execute(
            USER_ID,
            "createTask",
            structOf("title" to "x".repeat(400), "date" to "2026-08-06"),
        )

        // tasks.title is VARCHAR(255); 400 characters would have thrown at flush.
        assertThat(saved.single().title).hasSize(255)
    }

    @Test
    fun `an over-long description is capped too`() {
        val saved = mutableListOf<TaskEntity>()
        given(taskRepo.save(anyRef<TaskEntity>())).willAnswer { (it.arguments[0] as TaskEntity).also(saved::add) }

        tools.execute(
            USER_ID,
            "createTask",
            structOf("title" to "Plan", "date" to "2026-08-06", "description" to "y".repeat(3000)),
        )

        assertThat(saved.single().description).hasSize(2000)
    }

    @Test
    fun `an update with a bad time changes nothing at all, not even the fields before it`() {
        val task = TaskEntity(
            id = TASK_ID,
            ownerId = USER_ID,
            title = "Gym",
            date = 20_000L,
            timeStart = 9 * 3600L,
            timeEnd = 10 * 3600L,
        )
        given(taskRepo.findById(TASK_ID)).willReturn(java.util.Optional.of(task))

        val payload = tools.execute(
            USER_ID,
            "updateTask",
            structOf("taskId" to TASK_ID, "title" to "Gym session", "timeStart" to "morning"),
        ).toString()

        // The entity is managed inside the transaction, so a throw halfway through the assignments
        // still flushed the ones already made: the tool reported an error AND renamed the task.
        assertThat(payload).contains("timeStart must be an HH:mm")
        assertThat(task.title).isEqualTo("Gym")
    }

    @Test
    fun `more steps than the cap are refused on create as well`() {
        val payload = tools.execute(
            USER_ID,
            "createTask",
            structOf(
                "title" to "Read the book",
                "date" to "2026-08-06",
                "steps" to (1..2000).map { "page $it" },
            ),
        ).toString()

        assertThat(payload).contains("too many steps")
        Mockito.verify(subtaskRepo, Mockito.never()).save(anyRef<TaskSubtaskEntity>())
    }

    @Test
    fun `getGroupTasks reports the true total and flags that the list was clipped`() {
        given(members.findByGroupIdAndUserId(GROUP_ID, USER_ID))
            .willReturn(GroupMemberEntity(id = 1L, groupId = GROUP_ID, userId = USER_ID))
        given(taskRepo.findAllByFamilyGroupId(GROUP_ID))
            .willReturn((1..60).map { groupTask(id = it.toLong()) })

        val payload = tools.execute(USER_ID, "getGroupTasks", structOf("groupId" to GROUP_ID)).toString()

        // Deriving `count` from the truncated list told the model the group had exactly 25 tasks,
        // contradicting the [Context] block's "60 open shared tasks" with no way to tell which won.
        assertThat(payload).contains("\"count\"").contains("25.0")
        assertThat(payload).contains("\"totalCount\"").contains("60.0")
        assertThat(payload).contains("truncated").contains("true")
    }

    @Test
    fun `a group list that fits is not flagged as truncated`() {
        given(members.findByGroupIdAndUserId(GROUP_ID, USER_ID))
            .willReturn(GroupMemberEntity(id = 1L, groupId = GROUP_ID, userId = USER_ID))
        given(taskRepo.findAllByFamilyGroupId(GROUP_ID))
            .willReturn((1..3).map { groupTask(id = it.toLong()) })

        val payload = tools.execute(USER_ID, "getGroupTasks", structOf("groupId" to GROUP_ID)).toString()

        assertThat(payload).contains("truncated").contains("false")
    }

    private fun groupTask(id: Long) = TaskEntity(
        id = id,
        ownerId = USER_ID + 1,
        familyGroupId = GROUP_ID,
        title = "Shared $id",
        date = LocalDate.now().toEpochDay(),
        timeStart = 9 * 3600L,
        timeEnd = 10 * 3600L,
        recurrence = Recurrence.NONE,
    )

    /** Kotlin-friendly Mockito.any() for reference-typed params (see ChatToolScheduleTest). */
    private fun <T> anyRef(): T = Mockito.any()

    private companion object {
        const val USER_ID = 1L
        const val TASK_ID = 42L
        const val GROUP_ID = 7L
    }
}
