package com.todoapp.backend.task

import com.todoapp.backend.common.BaseResponse
import com.todoapp.backend.common.CurrentUser
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/tasks")
class TaskController(private val service: TaskService) {

    @PostMapping
    fun create(@Valid @RequestBody req: TaskRequest): BaseResponse<TaskData> =
        BaseResponse.ok(service.create(CurrentUser.id(), req))

    @PutMapping
    fun update(@Valid @RequestBody req: TaskRequest): BaseResponse<TaskData> =
        BaseResponse.ok(service.update(CurrentUser.id(), req))

    @GetMapping
    fun list(@RequestParam(required = false) familyGroupId: Long?): BaseResponse<TaskListData> =
        BaseResponse.ok(service.list(CurrentUser.id(), familyGroupId))

    @GetMapping("/{id}")
    fun getById(@PathVariable id: Long): BaseResponse<TaskData> =
        BaseResponse.ok(service.getById(CurrentUser.id(), id))

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: Long): BaseResponse<Unit> {
        service.delete(CurrentUser.id(), id)
        return BaseResponse.ok()
    }
}
