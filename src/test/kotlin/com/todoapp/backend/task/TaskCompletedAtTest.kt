package com.todoapp.backend.task

import com.todoapp.backend.integration.AbstractIntegrationTest
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * `tasks.completed_at` is what makes "how many tasks were completed today" answerable at all, so the
 * edge behaviour matters as much as the happy path — a timestamp that drifts is worse than none,
 * because it looks like data.
 */
class TaskCompletedAtTest : AbstractIntegrationTest() {

    @Autowired
    private lateinit var tasks: TaskRepository

    @Test
    fun `a task created as done is stamped immediately`() {
        // Offline completions sync as an already-completed create, not as create-then-update.
        val userId = registerUser().user.id

        val created = taskService.create(userId, request(title = "already done", completed = true))

        assertNotNull(tasks.findById(created.id).orElseThrow().completedAt)
    }

    @Test
    fun `an open task has no completion time`() {
        val userId = registerUser().user.id

        val created = taskService.create(userId, request(title = "open", completed = false))

        assertNull(tasks.findById(created.id).orElseThrow().completedAt)
    }

    @Test
    fun `completing an open task stamps it`() {
        val userId = registerUser().user.id
        val created = taskService.create(userId, request(title = "later", completed = false))

        taskService.update(userId, request(id = created.id, title = "later", completed = true))

        assertNotNull(tasks.findById(created.id).orElseThrow().completedAt)
    }

    @Test
    fun `un-completing clears the stamp so it cannot be counted twice`() {
        val userId = registerUser().user.id
        val created = taskService.create(userId, request(title = "oops", completed = true))

        taskService.update(userId, request(id = created.id, title = "oops", completed = false))

        assertNull(tasks.findById(created.id).orElseThrow().completedAt)
    }

    @Test
    fun `editing a completed task does not re-date its completion`() {
        // The bug this guards against is subtle and would be invisible: writing Instant.now() on every
        // update would move a week-old completion into today the moment someone fixed a typo, quietly
        // inflating today's completion count.
        val userId = registerUser().user.id
        val created = taskService.create(userId, request(title = "done", completed = true))
        val firstStamp = tasks.findById(created.id).orElseThrow().completedAt

        Thread.sleep(5)
        taskService.update(userId, request(id = created.id, title = "done, renamed", completed = true))

        val secondStamp = tasks.findById(created.id).orElseThrow().completedAt
        assertNotNull(secondStamp)
        assertTrue(firstStamp == secondStamp, "completedAt must not move when an unrelated field changes")
    }

    private fun request(
        title: String,
        completed: Boolean,
        id: Long? = null,
    ) = TaskRequest(
        id = id,
        title = title,
        date = 0,
        timeStart = 0,
        timeEnd = 0,
        isCompleted = completed,
    )

    @Autowired
    private lateinit var taskService: TaskService
}
