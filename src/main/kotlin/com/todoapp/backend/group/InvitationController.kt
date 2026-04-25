package com.todoapp.backend.group

import com.todoapp.backend.common.BaseResponse
import com.todoapp.backend.common.CurrentUser
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/family-groups/invitations")
class InvitationController(
    private val service: InvitationService,
) {
    @PostMapping
    fun invite(@Valid @RequestBody req: InviteMemberRequest): BaseResponse<InvitationData> =
        BaseResponse.ok(service.invite(CurrentUser.id(), req))

    @GetMapping("/me")
    fun listMine(): BaseResponse<InvitationListData> =
        BaseResponse.ok(service.listMyPending(CurrentUser.id()))

    @PostMapping("/{id}/accept")
    fun accept(@PathVariable id: Long): BaseResponse<InvitationData> =
        BaseResponse.ok(service.accept(CurrentUser.id(), id))

    @PostMapping("/{id}/decline")
    fun decline(@PathVariable id: Long): BaseResponse<InvitationData> =
        BaseResponse.ok(service.decline(CurrentUser.id(), id))

    @DeleteMapping("/{id}")
    fun cancel(@PathVariable id: Long): BaseResponse<Unit> {
        service.cancel(CurrentUser.id(), id)
        return BaseResponse.ok()
    }
}
