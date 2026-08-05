package com.todoapp.backend.chat

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
import java.time.Instant
import java.util.Optional

/**
 * `completedAt` (V27) is what every "completed on day X" query keys off. `TaskService.update` has
 * always stamped it; the chat tools set `isCompleted` directly and never touched it, so a task the
 * bot ticked was complete in the UI but invisible to those queries.
 *
 * The edge semantics matter as much as the stamping: re-stamping on every save would silently move a
 * week-old completion into today the next time anything about the task changed.
 */
class ChatToolCompletedAtTest {
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
    }

    @Test
    fun `completing stamps completedAt and un-completing clears it`() {
        val task = stubTask()

        tools.execute(USER_ID, "setTaskCompletion", structOf("taskId" to TASK_ID, "isCompleted" to true))
        assertThat(task.completedAt).isNotNull()

        tools.execute(USER_ID, "setTaskCompletion", structOf("taskId" to TASK_ID, "isCompleted" to false))
        assertThat(task.completedAt).isNull()
    }

    @Test
    fun `re-completing an already-done task does not re-date it`() {
        val lastWeek = Instant.now().minusSeconds(7 * 24 * 3600)
        val task = stubTask(completed = true, completedAt = lastWeek)

        tools.execute(USER_ID, "setTaskCompletion", structOf("taskId" to TASK_ID, "isCompleted" to true))

        assertThat(task.completedAt).isEqualTo(lastWeek)
    }

    @Test
    fun `finishing the last step stamps the parent through the staged auto-complete path`() {
        val task = stubTask()
        val only = TaskSubtaskEntity(id = 10L, taskId = TASK_ID, title = "book flight", orderIndex = 0)
        given(subtaskRepo.findAllByTaskIdOrderByOrderIndexAsc(TASK_ID)).willReturn(listOf(only))
        given(subtaskRepo.findById(10L)).willReturn(Optional.of(only))

        tools.execute(USER_ID, "setStepCompletion", structOf("stepId" to 10L, "isCompleted" to true))

        // recomputeStagedParentCompletion is the easiest of the three call sites to miss — it is the
        // only one that completes a task without anyone naming the task.
        assertThat(task.isCompleted).isTrue()
        assertThat(task.completedAt).isNotNull()
    }

    @Test
    fun `bulk completion stamps every task it touched`() {
        val first = stubTask(id = 42L)
        val second = stubTask(id = 43L)

        tools.execute(
            USER_ID,
            "bulkSetTaskCompletion",
            structOf("taskIds" to listOf(42L, 43L), "isCompleted" to true),
        )

        assertThat(first.completedAt).isNotNull()
        assertThat(second.completedAt).isNotNull()
    }

    private fun stubTask(
        id: Long = TASK_ID,
        completed: Boolean = false,
        completedAt: Instant? = null,
    ): TaskEntity {
        val task = TaskEntity(
            id = id,
            ownerId = USER_ID,
            title = "Plan the trip",
            date = 20_000L,
            timeStart = 9 * 3600L,
            timeEnd = 10 * 3600L,
            isCompleted = completed,
            recurrence = Recurrence.NONE,
            completedAt = completedAt,
        )
        given(taskRepo.findById(id)).willReturn(Optional.of(task))
        return task
    }

    private fun <T> anyRef(): T = Mockito.any()

    private companion object {
        const val USER_ID = 1L
        const val TASK_ID = 42L
    }
}
