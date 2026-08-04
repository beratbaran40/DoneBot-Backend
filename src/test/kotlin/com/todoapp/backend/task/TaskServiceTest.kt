package com.todoapp.backend.task

import com.todoapp.backend.group.GroupMemberEntity
import com.todoapp.backend.group.GroupMemberRepository
import com.todoapp.backend.notif.NotificationPublisher
import com.todoapp.backend.user.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.BDDMockito.given
import org.mockito.Mockito
import org.mockito.Mockito.anyLong
import org.mockito.Mockito.anyString
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.Optional

/**
 * §4.12 sync-write idempotency — a retried `POST /tasks` carrying the same `clientTaskId` must return
 * the existing task instead of inserting a duplicate. Verified at the `TaskService.create` seam with
 * mocked repositories (no Spring context), mirroring the plain-Mockito style of ChatServiceVertexFallbackTest.
 */
class TaskServiceTest {

    private val tasks: TaskRepository = Mockito.mock(TaskRepository::class.java)
    private val users: UserRepository = Mockito.mock(UserRepository::class.java)
    private val photos: TaskPhotoRepository = Mockito.mock(TaskPhotoRepository::class.java)
    private val publisher: NotificationPublisher = Mockito.mock(NotificationPublisher::class.java)
    private val dailyCompletions: TaskDailyCompletionRepository = Mockito.mock(TaskDailyCompletionRepository::class.java)
    private val subtaskRepo: TaskSubtaskRepository = Mockito.mock(TaskSubtaskRepository::class.java)
    private val members: GroupMemberRepository = Mockito.mock(GroupMemberRepository::class.java)

    private val service = TaskService(tasks, users, photos, publisher, dailyCompletions, subtaskRepo, members)

    @BeforeEach
    fun setUp() {
        // toData() reads — return empty so the entity→DTO mapping doesn't NPE on a List return.
        given(photos.findAllByTaskIdOrderByCreatedAtAsc(anyLong())).willReturn(emptyList())
        given(subtaskRepo.findAllByTaskIdOrderByOrderIndexAsc(anyLong())).willReturn(emptyList())
    }

    @Test
    fun `retried create with the same clientTaskId returns the existing task, no second insert`() {
        val existing = taskEntity(id = 42L, clientTaskId = KEY)
        given(tasks.findByOwnerIdAndClientTaskId(anyLong(), anyString())).willReturn(null).willReturn(existing)
        given(tasks.saveAndFlush(any())).willReturn(existing)

        val first = service.create(OWNER, request(clientTaskId = KEY))
        val second = service.create(OWNER, request(clientTaskId = KEY))

        assertThat(first.id).isEqualTo(42L)
        assertThat(second.id).isEqualTo(42L)
        verify(tasks, times(1)).saveAndFlush(any())
    }

    @Test
    fun `distinct clientTaskIds each insert a row`() {
        given(tasks.findByOwnerIdAndClientTaskId(anyLong(), anyString())).willReturn(null)
        given(tasks.saveAndFlush(any()))
            .willReturn(taskEntity(id = 1L, clientTaskId = "a"))
            .willReturn(taskEntity(id = 2L, clientTaskId = "b"))

        service.create(OWNER, request(clientTaskId = "a"))
        service.create(OWNER, request(clientTaskId = "b"))

        verify(tasks, times(2)).saveAndFlush(any())
    }

    @Test
    fun `null clientTaskId inserts without a dedup lookup (legacy behavior)`() {
        given(tasks.saveAndFlush(any())).willReturn(taskEntity(id = 7L, clientTaskId = null))

        service.create(OWNER, request(clientTaskId = null))

        verify(tasks, never()).findByOwnerIdAndClientTaskId(anyLong(), anyString())
        verify(tasks, times(1)).saveAndFlush(any())
    }

    @Test
    fun `concurrent same-key insert surfaces a clean 409`() {
        given(tasks.findByOwnerIdAndClientTaskId(anyLong(), anyString())).willReturn(null)
        given(tasks.saveAndFlush(any())).willThrow(DataIntegrityViolationException("duplicate key"))

        val ex = assertThrows<ResponseStatusException> { service.create(OWNER, request(clientTaskId = KEY)) }

        assertThat(ex.statusCode).isEqualTo(HttpStatus.CONFLICT)
    }

    @Test
    fun `a weekly routine accepts a per-day completion`() {
        // Regression: the guard used to demand Recurrence.DAILY, so every WEEKLY/MONTHLY/YEARLY
        // completion the client pushed came back 400 and was never persisted.
        val weekly = taskEntity(id = 7L, clientTaskId = null, recurrence = Recurrence.WEEKLY)
        given(tasks.findById(7L)).willReturn(Optional.of(weekly))
        given(dailyCompletions.findByTaskIdAndDate(7L, DAY)).willReturn(null)

        service.setDailyCompletion(OWNER, 7L, TaskDailyCompletionRequest(date = DAY, completed = true))

        verify(dailyCompletions, times(1)).save(any())
    }

    @Test
    fun `a non-recurring task still rejects a per-day completion`() {
        val once = taskEntity(id = 8L, clientTaskId = null, recurrence = Recurrence.NONE)
        given(tasks.findById(8L)).willReturn(Optional.of(once))

        val ex = assertThrows<ResponseStatusException> {
            service.setDailyCompletion(OWNER, 8L, TaskDailyCompletionRequest(date = DAY, completed = true))
        }

        assertThat(ex.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        verify(dailyCompletions, never()).save(any())
    }

    @Test
    fun `an update from a client that predates the extended rule leaves it untouched`() {
        val stored = taskEntity(id = 9L, clientTaskId = null, recurrence = Recurrence.DAILY).apply {
            recurrenceInterval = 2
            recurrenceUntil = 500L
            reminderTimes = "28800,50400"
        }
        given(tasks.findById(9L)).willReturn(Optional.of(stored))
        given(tasks.save(any())).willReturn(stored)

        // Old client: no recurrenceRuleSet, every new field null. It must not wipe what it can't model.
        service.update(OWNER, TaskRequest(id = 9L, title = "Task", date = 0, timeStart = 0, timeEnd = 0))

        assertThat(stored.recurrenceInterval).isEqualTo(2)
        assertThat(stored.recurrenceUntil).isEqualTo(500L)
        assertThat(stored.reminderTimes).isEqualTo("28800,50400")
    }

    @Test
    fun `an update that sets the rule flag can clear the scheduled end`() {
        val stored = taskEntity(id = 10L, clientTaskId = null, recurrence = Recurrence.DAILY).apply {
            recurrenceUntil = 500L
        }
        given(tasks.findById(10L)).willReturn(Optional.of(stored))
        given(tasks.save(any())).willReturn(stored)

        service.update(
            OWNER,
            TaskRequest(id = 10L, title = "Task", date = 0, timeStart = 0, timeEnd = 0, recurrenceRuleSet = true),
        )

        assertThat(stored.recurrenceUntil).isNull()
    }

    @Test
    fun `a group member who did not create the task can still tick its day`() {
        // A group task belongs to the group. Owner-only meant a shared recurring chore could be
        // completed by exactly one person — its creator — which is the opposite of shared.
        val groupTask = taskEntity(id = 11L, clientTaskId = null, recurrence = Recurrence.DAILY)
            .apply { familyGroupId = GROUP }
        given(tasks.findById(11L)).willReturn(Optional.of(groupTask))
        given(members.findByGroupIdAndUserId(GROUP, OTHER_MEMBER)).willReturn(groupMember())
        given(dailyCompletions.findByTaskIdAndDate(11L, DAY)).willReturn(null)

        service.setDailyCompletion(OTHER_MEMBER, 11L, TaskDailyCompletionRequest(date = DAY, completed = true))

        verify(dailyCompletions, times(1)).save(any())
    }

    @Test
    fun `a stranger to the group is still refused`() {
        val groupTask = taskEntity(id = 12L, clientTaskId = null, recurrence = Recurrence.DAILY)
            .apply { familyGroupId = GROUP }
        given(tasks.findById(12L)).willReturn(Optional.of(groupTask))
        given(members.findByGroupIdAndUserId(GROUP, OTHER_MEMBER)).willReturn(null)

        val ex = assertThrows<ResponseStatusException> {
            service.setDailyCompletion(OTHER_MEMBER, 12L, TaskDailyCompletionRequest(date = DAY, completed = true))
        }

        assertThat(ex.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        verify(dailyCompletions, never()).save(any())
    }

    @Test
    fun `a personal task is still owner-only`() {
        // The group branch must not become a hole in the personal task guard.
        val personal = taskEntity(id = 13L, clientTaskId = null, recurrence = Recurrence.DAILY)
        given(tasks.findById(13L)).willReturn(Optional.of(personal))

        val ex = assertThrows<ResponseStatusException> {
            service.setDailyCompletion(OTHER_MEMBER, 13L, TaskDailyCompletionRequest(date = DAY, completed = true))
        }

        assertThat(ex.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        verify(members, never()).findByGroupIdAndUserId(anyLong(), anyLong())
    }

    @Test
    fun `a teammate's tick shows up in my completion list`() {
        // The by-author filter used to hide it, so the day looked undone on every other device.
        given(members.findAllByUserId(OWNER)).willReturn(listOf(groupMember()))
        given(tasks.findAllByFamilyGroupIdIn(listOf(GROUP)))
            .willReturn(listOf(taskEntity(id = 14L, clientTaskId = null).apply { familyGroupId = GROUP }))
        given(dailyCompletions.findAllByUserIdAndDateBetween(OWNER, 0L, 100L)).willReturn(emptyList())
        given(dailyCompletions.findAllByTaskIdInAndDateBetween(listOf(14L), 0L, 100L))
            .willReturn(listOf(completion(taskId = 14L, userId = OTHER_MEMBER)))

        val result = service.listDailyCompletions(OWNER, 0L, 100L)

        assertThat(result.items).hasSize(1)
        assertThat(result.items.first().taskId).isEqualTo(14L)
    }

    @Test
    fun `my own tick on a group task is not counted twice`() {
        // It comes back from both queries; the occurrence is (taskId, date), not the row.
        given(members.findAllByUserId(OWNER)).willReturn(listOf(groupMember()))
        given(tasks.findAllByFamilyGroupIdIn(listOf(GROUP)))
            .willReturn(listOf(taskEntity(id = 15L, clientTaskId = null).apply { familyGroupId = GROUP }))
        given(dailyCompletions.findAllByUserIdAndDateBetween(OWNER, 0L, 100L))
            .willReturn(listOf(completion(taskId = 15L, userId = OWNER)))
        given(dailyCompletions.findAllByTaskIdInAndDateBetween(listOf(15L), 0L, 100L))
            .willReturn(listOf(completion(taskId = 15L, userId = OWNER)))

        val result = service.listDailyCompletions(OWNER, 0L, 100L)

        assertThat(result.items).hasSize(1)
    }

    @Test
    fun `a user in no group never runs the group queries`() {
        given(members.findAllByUserId(OWNER)).willReturn(emptyList())
        given(dailyCompletions.findAllByUserIdAndDateBetween(OWNER, 0L, 100L)).willReturn(emptyList())

        service.listDailyCompletions(OWNER, 0L, 100L)

        verify(tasks, never()).findAllByFamilyGroupIdIn(any())
        verify(dailyCompletions, never()).findAllByTaskIdInAndDateBetween(any(), anyLong(), anyLong())
    }

    private fun request(clientTaskId: String?) =
        TaskRequest(title = "Task", date = 0, timeStart = 0, timeEnd = 0, clientTaskId = clientTaskId)

    private fun taskEntity(id: Long, clientTaskId: String?, recurrence: Recurrence = Recurrence.NONE) =
        TaskEntity(
            id = id,
            ownerId = OWNER,
            clientTaskId = clientTaskId,
            title = "Task",
            date = 0,
            timeStart = 0,
            timeEnd = 0,
            recurrence = recurrence,
        )

    private fun groupMember() = GroupMemberEntity(id = 1L, groupId = GROUP, userId = OTHER_MEMBER)

    private fun completion(taskId: Long, userId: Long) =
        TaskDailyCompletionEntity(taskId = taskId, userId = userId, date = DAY, completedAt = 0L)

    // Kotlin-friendly Mockito.any() for reference-typed params (returns null; safe on a stubbed mock).
    private fun <T> any(): T = Mockito.any()

    private companion object {
        const val OWNER = 1L
        const val OTHER_MEMBER = 2L
        const val GROUP = 77L
        const val KEY = "client-key-123"
        const val DAY = 20_000L
    }
}
