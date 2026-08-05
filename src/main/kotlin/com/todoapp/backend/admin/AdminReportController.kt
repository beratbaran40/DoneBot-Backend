package com.todoapp.backend.admin

import com.todoapp.backend.chat.ReportStatus
import com.todoapp.backend.common.BaseResponse
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/admin/reports")
class AdminReportController(private val reports: AdminReportService) {

    @GetMapping
    fun list(
        @RequestParam type: String,
        @RequestParam(required = false) status: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "25") size: Int,
    ): BaseResponse<AdminReportPage> {
        val normalisedStatus = status?.takeIf { it.isNotBlank() }?.uppercase()
        if (normalisedStatus != null && ReportStatus.entries.none { it.name == normalisedStatus }) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown status: $status")
        }
        return BaseResponse.ok(
            reports.list(
                type = type,
                status = normalisedStatus,
                page = page.coerceAtLeast(0),
                // Hard cap: reports carry reporter emails and flagged message text, so an unbounded
                // page size would be a one-request bulk export of exactly the sensitive rows.
                size = size.coerceIn(1, MAX_PAGE_SIZE),
            ),
        )
    }

    /**
     * POST, not PUT: this is an action taken on a report, not a replacement of it. The distinction also
     * keeps the endpoint out of the "idempotent retry" bucket, which it is not — resolving twice with
     * different notes should be visible in the audit log as two decisions.
     */
    @PostMapping("/{type}/{id}/resolve")
    fun resolve(
        @PathVariable type: String,
        @PathVariable id: Long,
        @RequestBody body: ResolveReportRequest,
    ): BaseResponse<Unit> {
        reports.resolve(type, id, body)
        return BaseResponse.ok()
    }

    /**
     * Returns the reported image itself. Deliberately not cacheable by anything shared, and marked
     * no-store so a moderated photo does not linger in an intermediary — this is the one place the
     * admin surface serves user content rather than counts.
     */
    @GetMapping("/content/{id}/photo")
    fun photo(@PathVariable id: Long): ResponseEntity<ByteArray> {
        val photo = reports.photo(id)
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(photo.contentType))
            .cacheControl(CacheControl.noStore())
            .body(photo.bytes)
    }

    private companion object {
        const val MAX_PAGE_SIZE = 100
    }
}
