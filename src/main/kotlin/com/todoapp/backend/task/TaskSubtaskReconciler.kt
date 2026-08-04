package com.todoapp.backend.task

/**
 * Replaces a task's step set with [incoming], preserving server ids where the client supplied a
 * matching [SubtaskRequest.remoteId]. Steps absent from [incoming] are deleted; new ones (null
 * remoteId) are inserted. `orderIndex` is re-packed to the incoming order so the steps render in
 * the order the client sent them.
 *
 * A free function rather than a method on `TaskService` because both the personal task path and the
 * group task path need it, and routing one service through the other just to reach a private helper
 * would couple them for no reason. Callers pass their own injected repository.
 *
 * Called only when the client sends a non-null `subtasks` list — a null list means "leave the steps
 * alone", so a client that doesn't know about steps can never wipe steps another device added.
 */
internal fun reconcileSubtasks(
    subtaskRepo: TaskSubtaskRepository,
    taskId: Long,
    incoming: List<SubtaskRequest>,
) {
    val existing = subtaskRepo.findAllByTaskIdOrderByOrderIndexAsc(taskId)
    val existingById = existing.associateBy { it.id }
    val keptIds = mutableSetOf<Long>()
    incoming.forEachIndexed { index, req ->
        val match = req.remoteId?.let { existingById[it] }
        if (match != null) {
            match.title = req.title
            match.isCompleted = req.isCompleted
            match.orderIndex = index
            subtaskRepo.save(match)
            keptIds.add(match.id)
        } else {
            val created = subtaskRepo.save(
                TaskSubtaskEntity(
                    taskId = taskId,
                    title = req.title,
                    isCompleted = req.isCompleted,
                    orderIndex = index,
                ),
            )
            keptIds.add(created.id)
        }
    }
    existing.filter { it.id !in keptIds }.forEach { subtaskRepo.delete(it) }
}
