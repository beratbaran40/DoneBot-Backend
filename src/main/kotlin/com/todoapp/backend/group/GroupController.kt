package com.todoapp.backend.group

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
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/family-groups")
class GroupController(
    private val service: GroupService,
    private val taskService: GroupTaskService,
    private val activityService: GroupActivityService,
    private val contentReportService: ContentReportService,
) {
    @PostMapping
    fun create(@Valid @RequestBody req: CreateGroupRequest): BaseResponse<GroupData> =
        BaseResponse.ok(service.create(CurrentUser.id(), req))

    @GetMapping
    fun list(): BaseResponse<GroupSummaryListData> =
        BaseResponse.ok(service.listSummaries(CurrentUser.id()))

    @GetMapping("/{id}")
    fun detail(@PathVariable id: Long): BaseResponse<GroupData> =
        BaseResponse.ok(service.detail(CurrentUser.id(), id))

    @PutMapping
    fun update(@Valid @RequestBody req: UpdateGroupRequest): BaseResponse<GroupData> =
        BaseResponse.ok(service.update(CurrentUser.id(), req))

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: Long): BaseResponse<Unit> {
        service.delete(CurrentUser.id(), id)
        return BaseResponse.ok()
    }

    @PostMapping("/members")
    fun invite(@Valid @RequestBody req: InviteMemberRequest): BaseResponse<InvitationData> =
        BaseResponse.ok(service.invite(CurrentUser.id(), req))

    @DeleteMapping("/members/{groupId}/{userId}")
    fun removeMember(@PathVariable groupId: Long, @PathVariable userId: Long): BaseResponse<Unit> {
        service.removeMember(CurrentUser.id(), groupId, userId)
        return BaseResponse.ok()
    }

    /**
     * Records a report of offensive/inappropriate content (member, photo, or task) in [groupId] for
     * manual moderation review. Required by Google Play's UGC policy. Only group members may report,
     * enforced via [GroupService.requireMember] (IDOR guard).
     */
    @PostMapping("/{groupId}/reports")
    fun reportContent(
        @PathVariable groupId: Long,
        @Valid @RequestBody req: ReportContentRequest,
    ): BaseResponse<Unit> {
        val callerId = CurrentUser.id()
        service.requireMember(groupId, callerId)
        contentReportService.record(callerId, groupId, req)
        return BaseResponse.ok("Report received")
    }

    @PostMapping("/{groupId}/leave")
    fun leave(@PathVariable groupId: Long): BaseResponse<Unit> {
        service.leave(CurrentUser.id(), groupId)
        return BaseResponse.ok()
    }

    @PutMapping("/{groupId}/transfer-ownership")
    fun transferOwnership(
        @PathVariable groupId: Long,
        @Valid @RequestBody req: TransferOwnershipRequest,
    ): BaseResponse<Unit> {
        service.transferOwnership(CurrentUser.id(), groupId, req)
        return BaseResponse.ok()
    }

    // ----- Group tasks -----

    @GetMapping("/{groupId}/tasks")
    fun listTasks(@PathVariable groupId: Long): BaseResponse<GroupTaskListData> =
        BaseResponse.ok(taskService.list(CurrentUser.id(), groupId))

    @PostMapping("/{groupId}/tasks")
    fun createTask(
        @PathVariable groupId: Long,
        @Valid @RequestBody req: GroupTaskRequest,
    ): BaseResponse<GroupTaskData> =
        BaseResponse.ok(taskService.create(CurrentUser.id(), groupId, req))

    @PutMapping("/{groupId}/tasks/{taskId}")
    fun updateTask(
        @PathVariable groupId: Long,
        @PathVariable taskId: Long,
        @RequestBody req: GroupTaskUpdateRequest,
    ): BaseResponse<GroupTaskData> =
        BaseResponse.ok(taskService.update(CurrentUser.id(), groupId, taskId, req))

    @DeleteMapping("/{groupId}/tasks/{taskId}")
    fun deleteTask(@PathVariable groupId: Long, @PathVariable taskId: Long): BaseResponse<Unit> {
        taskService.delete(CurrentUser.id(), groupId, taskId)
        return BaseResponse.ok()
    }

    @GetMapping("/{groupId}/activity")
    fun activity(@PathVariable groupId: Long): BaseResponse<GroupActivityListData> =
        BaseResponse.ok(activityService.list(CurrentUser.id(), groupId))

    @org.springframework.web.bind.annotation.PostMapping(
        "/{groupId}/avatar",
        consumes = [org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE],
    )
    @org.springframework.transaction.annotation.Transactional
    fun uploadAvatar(
        @PathVariable groupId: Long,
        @org.springframework.web.bind.annotation.RequestPart("file") file: org.springframework.web.multipart.MultipartFile,
    ): BaseResponse<GroupData> {
        val result = service.uploadAvatar(CurrentUser.id(), groupId, file)
        return BaseResponse.ok(result)
    }

    @GetMapping("/{groupId}/avatar")
    fun getAvatar(@PathVariable groupId: Long): org.springframework.http.ResponseEntity<ByteArray> =
        service.getAvatarBytes(CurrentUser.id(), groupId)
}
