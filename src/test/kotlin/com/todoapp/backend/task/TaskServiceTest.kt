package com.todoapp.backend.task

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

    private fun request(clientTaskId: String?) =
        TaskRequest(title = "Task", date = 0, timeStart = 0, timeEnd = 0, clientTaskId = clientTaskId)

    private fun taskEntity(id: Long, clientTaskId: String?) =
        TaskEntity(id = id, ownerId = OWNER, clientTaskId = clientTaskId, title = "Task", date = 0, timeStart = 0, timeEnd = 0)

    // Kotlin-friendly Mockito.any() for reference-typed params (returns null; safe on a stubbed mock).
    private fun <T> any(): T = Mockito.any()

    private companion object {
        const val OWNER = 1L
        const val KEY = "client-key-123"
    }
}
