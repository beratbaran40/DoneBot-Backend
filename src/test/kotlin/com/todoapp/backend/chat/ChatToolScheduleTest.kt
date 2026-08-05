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
import java.util.Optional

/**
 * The three write tools added for parity with what the app's own edit screen can do:
 * `setTaskSchedule` (the repeat rule + reminder clock times `updateTask` deliberately can't touch),
 * `finishRoutine` (retire/resume, the long-press action on Home) and `setSteps` (rewrite a whole
 * step list at once).
 *
 * The clamps they inherit from `createTask` via `parseScheduleRule` are pinned here rather than on
 * create, because create only ever exercises the "field absent ⇒ use the default" half. Only
 * `setTaskSchedule` exercises "absent ⇒ keep what's stored" and "present-but-empty ⇒ clear", and
 * getting those two backwards silently rewrites a user's routine.
 */
class ChatToolScheduleTest {
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

    // -------------------- setTaskSchedule --------------------

    @Test
    fun `interval is clamped to the client's 1-30 stepper range`() {
        val task = stubTask(recurrence = Recurrence.WEEKLY)

        tools.execute(USER_ID, "setTaskSchedule", structOf("taskId" to TASK_ID, "recurrenceInterval" to 41))
        assertThat(task.recurrenceInterval).isEqualTo(30)

        tools.execute(USER_ID, "setTaskSchedule", structOf("taskId" to TASK_ID, "recurrenceInterval" to 0))
        assertThat(task.recurrenceInterval).isEqualTo(1)
    }

    @Test
    fun `byDay is normalized to real weekday names and only kept for WEEKLY`() {
        // Re-sorted into weekday order and anything that isn't a java.time.DayOfWeek name is dropped —
        // the client parses this CSV straight into a Set<DayOfWeek>, so junk would crash it.
        val weekly = stubTask(recurrence = Recurrence.WEEKLY)
        tools.execute(
            USER_ID,
            "setTaskSchedule",
            structOf("taskId" to TASK_ID, "recurrenceByDay" to "FRIDAY,MONDAY,garbage,MONDAY"),
        )
        assertThat(weekly.recurrenceByDay).isEqualTo("MONDAY,FRIDAY")

        // The same argument on a DAILY task is meaningless — keeping it would make the client render
        // weekday chips on a routine that fires every day.
        val daily = stubTask(recurrence = Recurrence.DAILY)
        tools.execute(
            USER_ID,
            "setTaskSchedule",
            structOf("taskId" to TASK_ID, "recurrenceByDay" to "MONDAY,FRIDAY"),
        )
        assertThat(daily.recurrenceByDay).isNull()
    }

    @Test
    fun `reminder times are deduped, sorted and capped at the eight alarm slots`() {
        val task = stubTask(recurrence = Recurrence.DAILY)

        tools.execute(
            USER_ID,
            "setTaskSchedule",
            structOf(
                "taskId" to TASK_ID,
                "reminderTimes" to listOf(
                    "20:00", "08:00", "20:00", "09:00", "10:00",
                    "11:00", "12:00", "13:00", "14:00", "15:00",
                ),
            ),
        )

        val stored = task.reminderTimes!!.split(',').map { it.toLong() }
        assertThat(stored).hasSize(8)
        assertThat(stored).isSorted
        assertThat(stored.first()).isEqualTo(8 * 3600L)
        assertThat(stored).doesNotHaveDuplicates()
    }

    @Test
    fun `recurrence NONE drops the whole extended rule`() {
        val task = stubTask(
            recurrence = Recurrence.WEEKLY,
            interval = 3,
            byDay = "MONDAY",
            until = 20_000L,
            reminderTimes = "28800,50400",
        )

        tools.execute(USER_ID, "setTaskSchedule", structOf("taskId" to TASK_ID, "recurrence" to "NONE"))

        assertThat(task.recurrence).isEqualTo(Recurrence.NONE)
        assertThat(task.recurrenceInterval).isEqualTo(1)
        assertThat(task.recurrenceByDay).isNull()
        assertThat(task.recurrenceUntil).isNull()
        assertThat(task.reminderTimes).isNull()
    }

    @Test
    fun `an omitted field keeps the stored value instead of resetting it`() {
        val task = stubTask(
            recurrence = Recurrence.WEEKLY,
            interval = 2,
            byDay = "MONDAY,FRIDAY",
            until = 20_000L,
            reminderTimes = "28800",
        )

        // Only the interval is named; everything else must survive untouched. This is the half of
        // parseScheduleRule that createTask never reaches.
        tools.execute(USER_ID, "setTaskSchedule", structOf("taskId" to TASK_ID, "recurrenceInterval" to 3))

        assertThat(task.recurrenceInterval).isEqualTo(3)
        assertThat(task.recurrence).isEqualTo(Recurrence.WEEKLY)
        assertThat(task.recurrenceByDay).isEqualTo("MONDAY,FRIDAY")
        assertThat(task.recurrenceUntil).isEqualTo(20_000L)
        assertThat(task.reminderTimes).isEqualTo("28800")
    }

    @Test
    fun `present-but-empty clears, which is not the same as omitted`() {
        val task = stubTask(
            recurrence = Recurrence.WEEKLY,
            byDay = "MONDAY,FRIDAY",
            until = 20_000L,
            reminderTimes = "28800,50400",
        )

        tools.execute(
            USER_ID,
            "setTaskSchedule",
            structOf(
                "taskId" to TASK_ID,
                "recurrenceByDay" to "",
                "recurrenceUntil" to "",
                "reminderTimes" to emptyList<String>(),
            ),
        )

        assertThat(task.recurrenceByDay).isNull()
        assertThat(task.recurrenceUntil).isNull()
        assertThat(task.reminderTimes).isNull()
        // The frequency itself was not named, so it stays.
        assertThat(task.recurrence).isEqualTo(Recurrence.WEEKLY)
    }

    // -------------------- finishRoutine --------------------

    @Test
    fun `finishing a routine records the day and resuming clears it`() {
        val task = stubTask(recurrence = Recurrence.DAILY)

        tools.execute(
            USER_ID,
            "finishRoutine",
            structOf("taskId" to TASK_ID, "finished" to true, "on" to "2026-08-06"),
        )
        assertThat(task.finishedOn).isEqualTo(java.time.LocalDate.parse("2026-08-06").toEpochDay())

        tools.execute(USER_ID, "finishRoutine", structOf("taskId" to TASK_ID, "finished" to false))
        assertThat(task.finishedOn).isNull()
    }

    @Test
    fun `a one-off task cannot be finished as a routine`() {
        val task = stubTask(recurrence = Recurrence.NONE)

        val payload = tools.execute(
            USER_ID,
            "finishRoutine",
            structOf("taskId" to TASK_ID, "finished" to true),
        ).toString()

        // Silently no-opping would have the model report success on a task that never changed.
        assertThat(payload).contains("not a routine")
        assertThat(task.finishedOn).isNull()
    }

    // -------------------- setSteps --------------------

    @Test
    fun `steps passed with their id keep their tick and get reordered, the rest are deleted`() {
        val task = stubTask(recurrence = Recurrence.NONE)
        val first = step(id = 10L, title = "book flight", completed = true, order = 0)
        val second = step(id = 11L, title = "pack", completed = false, order = 1)
        val third = step(id = 12L, title = "passport", completed = false, order = 2)
        given(subtaskRepo.findAllByTaskIdOrderByOrderIndexAsc(TASK_ID)).willReturn(listOf(first, second, third))

        tools.execute(
            USER_ID,
            "setSteps",
            structOf(
                "taskId" to TASK_ID,
                "steps" to listOf(
                    mapOf("stepId" to 11L, "title" to "pack"),
                    mapOf("stepId" to 10L, "title" to "book flight"),
                    mapOf("title" to "check visa"),
                ),
            ),
        )

        // Reordered, and the completed one kept its tick across the move.
        assertThat(second.orderIndex).isEqualTo(0)
        assertThat(first.orderIndex).isEqualTo(1)
        assertThat(first.isCompleted).isTrue()
        // Left out of the list ⇒ removed.
        Mockito.verify(subtaskRepo).delete(third)
        Mockito.verify(subtaskRepo, Mockito.never()).delete(first)
    }

    @Test
    fun `an empty array strips every step`() {
        val task = stubTask(recurrence = Recurrence.NONE)
        val only = step(id = 10L, title = "book flight", completed = false, order = 0)
        given(subtaskRepo.findAllByTaskIdOrderByOrderIndexAsc(TASK_ID)).willReturn(listOf(only))

        tools.execute(USER_ID, "setSteps", structOf("taskId" to TASK_ID, "steps" to emptyList<Any>()))

        Mockito.verify(subtaskRepo).delete(only)
        assertThat(task.title).isEqualTo("Plan the trip")
    }

    // -------------------- the guard every new write tool must inherit --------------------

    @Test
    fun `every new write tool refuses a shared group task`() {
        stubTask(recurrence = Recurrence.WEEKLY, familyGroupId = 99L)

        val calls = mapOf(
            "setTaskSchedule" to structOf("taskId" to TASK_ID, "recurrenceInterval" to 2),
            "finishRoutine" to structOf("taskId" to TASK_ID, "finished" to true),
            "setSteps" to structOf("taskId" to TASK_ID, "steps" to emptyList<Any>()),
        )

        calls.forEach { (name, args) ->
            assertThat(tools.execute(USER_ID, name, args).toString())
                .describedAs("%s must go through withOwnedTask", name)
                .contains("group_task_blocked")
        }
    }

    @Test
    fun `every new write tool refuses another user's task`() {
        stubTask(recurrence = Recurrence.WEEKLY, ownerId = USER_ID + 1)

        listOf(
            "setTaskSchedule" to structOf("taskId" to TASK_ID, "recurrenceInterval" to 2),
            "finishRoutine" to structOf("taskId" to TASK_ID, "finished" to true),
            "setSteps" to structOf("taskId" to TASK_ID, "steps" to emptyList<Any>()),
        ).forEach { (name, args) ->
            assertThat(tools.execute(USER_ID, name, args).toString())
                .describedAs("%s must not be an IDOR", name)
                .contains("not your task")
        }
    }

    private fun stubTask(
        recurrence: Recurrence,
        interval: Int = 1,
        byDay: String? = null,
        until: Long? = null,
        reminderTimes: String? = null,
        familyGroupId: Long? = null,
        ownerId: Long = USER_ID,
    ): TaskEntity {
        val task = TaskEntity(
            id = TASK_ID,
            ownerId = ownerId,
            title = "Plan the trip",
            date = 20_000L,
            timeStart = 9 * 3600L,
            timeEnd = 10 * 3600L,
            recurrence = recurrence,
            familyGroupId = familyGroupId,
            recurrenceInterval = interval,
            recurrenceByDay = byDay,
            recurrenceUntil = until,
            reminderTimes = reminderTimes,
        )
        given(taskRepo.findById(TASK_ID)).willReturn(Optional.of(task))
        return task
    }

    private fun step(id: Long, title: String, completed: Boolean, order: Int) =
        TaskSubtaskEntity(id = id, taskId = TASK_ID, title = title, isCompleted = completed, orderIndex = order)

    /** Kotlin-friendly Mockito.any() for reference-typed params (see ChatServiceVertexFallbackTest). */
    private fun <T> anyRef(): T = Mockito.any()

    private companion object {
        const val USER_ID = 1L
        const val TASK_ID = 42L
    }
}
