package com.todoapp.backend.chat

import com.todoapp.backend.group.GroupMemberRepository
import com.todoapp.backend.group.GroupRepository
import com.todoapp.backend.task.REMINDER_OFF
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
import java.util.Optional

/**
 * `reminderOffsetMinutes` says three things — [REMINDER_OFF] for none, 0 for "at the start time", a
 * positive number for "N minutes before" — and the client's task form offers the first two as separate
 * chips.
 *
 * The chat path used to floor the value at 0, so a user who asked DoneBot to turn a reminder off got
 * one that rang exactly on time instead, and the next sync pushed that onto every device they owned.
 * The floor is now REMINDER_OFF, and these pin both ends of it: the sentinel survives, and anything
 * more negative still lands on it rather than reaching the client as a nonsense offset.
 */
class ChatToolReminderOffsetTest {
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

    // ── updateTask ────────────────────────────────────────────────────────────

    @Test
    fun `turning a reminder off is stored as off, not as a reminder at the start time`() {
        val task = stubTask(reminderOffsetMinutes = 30L)

        tools.execute(USER_ID, "updateTask", structOf("taskId" to TASK_ID, "reminderOffsetMinutes" to -1))

        assertThat(task.reminderOffsetMinutes).isEqualTo(REMINDER_OFF)
    }

    @Test
    fun `zero still means a reminder at the start time`() {
        // The whole point of the sentinel: these two must not converge.
        val task = stubTask(reminderOffsetMinutes = 30L)

        tools.execute(USER_ID, "updateTask", structOf("taskId" to TASK_ID, "reminderOffsetMinutes" to 0))

        assertThat(task.reminderOffsetMinutes).isEqualTo(0L)
    }

    @Test
    fun `an ordinary offset is untouched`() {
        val task = stubTask(reminderOffsetMinutes = 0L)

        tools.execute(USER_ID, "updateTask", structOf("taskId" to TASK_ID, "reminderOffsetMinutes" to 15))

        assertThat(task.reminderOffsetMinutes).isEqualTo(15L)
    }

    @Test
    fun `a value below the sentinel lands on it`() {
        // -1 is the only negative the client understands; -5 would be read as an offset AFTER the task.
        val task = stubTask(reminderOffsetMinutes = 30L)

        tools.execute(USER_ID, "updateTask", structOf("taskId" to TASK_ID, "reminderOffsetMinutes" to -60))

        assertThat(task.reminderOffsetMinutes).isEqualTo(REMINDER_OFF)
    }

    // ── createTask ────────────────────────────────────────────────────────────

    @Test
    fun `a task created with no reminder keeps it off`() {
        var saved: TaskEntity? = null
        given(taskRepo.save(anyRef<TaskEntity>())).willAnswer { inv ->
            (inv.arguments[0] as TaskEntity).also { saved = it }
        }

        tools.execute(
            USER_ID,
            "createTask",
            structOf("title" to "Vitamin", "date" to "2026-08-06", "reminderOffsetMinutes" to -1),
        )

        assertThat(saved?.reminderOffsetMinutes).isEqualTo(REMINDER_OFF)
    }

    // ── read-back ─────────────────────────────────────────────────────────────

    @Test
    fun `the bot can see that a reminder is off`() {
        // Reported unconditionally now. While it was only emitted for positive values, "off" and "at
        // the start time" both looked like "unknown", so the bot would offer to add a reminder to a
        // task that had one, or claim one that was switched off.
        stubTask(reminderOffsetMinutes = REMINDER_OFF)

        val result = tools.execute(USER_ID, "findTaskByTitle", structOf("query" to "Plan the trip"))

        assertThat(result.toString()).contains("reminderOffsetMinutes")
        assertThat(result.toString()).contains("-1")
    }

    private fun stubTask(reminderOffsetMinutes: Long): TaskEntity {
        val task = TaskEntity(
            id = TASK_ID,
            ownerId = USER_ID,
            title = "Plan the trip",
            date = 20_000L,
            timeStart = 9 * 3600L,
            timeEnd = 10 * 3600L,
            recurrence = Recurrence.NONE,
            reminderOffsetMinutes = reminderOffsetMinutes,
        )
        given(taskRepo.findById(TASK_ID)).willReturn(Optional.of(task))
        given(
            taskRepo.findFirst5ByOwnerIdAndFamilyGroupIdIsNullAndTitleContainingIgnoreCaseOrderByDateAsc(
                USER_ID,
                "Plan the trip",
            ),
        ).willReturn(listOf(task))
        return task
    }

    private fun <T> anyRef(): T = Mockito.any()

    private companion object {
        const val USER_ID = 1L
        const val TASK_ID = 42L
    }
}
