package com.todoapp.backend.admin

import com.todoapp.backend.chat.ChatReportRepository
import com.todoapp.backend.chat.ReportResolution
import com.todoapp.backend.chat.ReportStatus
import com.todoapp.backend.group.ContentReportRepository
import com.todoapp.backend.task.TaskPhotoRepository
import com.todoapp.backend.task.TaskRepository
import com.todoapp.backend.user.UserRepository
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.time.temporal.ChronoUnit

data class AdminReportItem(
    val id: Long,
    val type: String,
    val status: String,
    val createdAt: String,
    val ageHours: Long,
    val reason: String?,
    val reporterUserId: Long,
    val reporterEmail: String?,
    /** Chat reports only — the flagged reply. The reporter submitted it precisely to be read. */
    val messageContent: String?,
    val groupId: Long?,
    val targetType: String?,
    val targetUserId: Long?,
    /** True when the reported reference resolves to a photo this admin may fetch. */
    val hasViewablePhoto: Boolean,
    val resolution: String?,
    val resolutionNote: String?,
    val resolvedAt: String?,
)

data class AdminReportPage(
    val items: List<AdminReportItem>,
    val page: Int,
    val size: Int,
    val total: Long,
)

data class ResolveReportRequest(val resolution: String, val note: String? = null)

/** Photo bytes plus their content type, for the moderation viewer. */
data class ModerationPhoto(val bytes: ByteArray, val contentType: String)

@Service
class AdminReportService(
    private val chatReports: ChatReportRepository,
    private val contentReports: ContentReportRepository,
    private val users: UserRepository,
    private val photos: TaskPhotoRepository,
    private val tasks: TaskRepository,
    private val audit: AdminAuditService,
) {

    @Transactional(readOnly = true)
    fun list(type: String, status: String?, page: Int, size: Int): AdminReportPage {
        val pageable = PageRequest.of(page, size)
        return when (type) {
            TYPE_CHAT -> {
                val result = if (status == null) {
                    chatReports.findAllByOrderByCreatedAtDesc(pageable)
                } else {
                    chatReports.findAllByStatusOrderByCreatedAtAsc(status, pageable)
                }
                val emails = emailsFor(result.content.map { it.userId })
                AdminReportPage(
                    items = result.content.map { report ->
                        AdminReportItem(
                            id = report.id,
                            type = TYPE_CHAT,
                            status = report.status,
                            createdAt = report.createdAt.toString(),
                            ageHours = ChronoUnit.HOURS.between(report.createdAt, Instant.now()),
                            reason = report.reason,
                            reporterUserId = report.userId,
                            reporterEmail = emails[report.userId],
                            messageContent = report.messageContent,
                            groupId = null,
                            targetType = null,
                            targetUserId = null,
                            hasViewablePhoto = false,
                            resolution = report.resolution,
                            resolutionNote = report.resolutionNote,
                            resolvedAt = report.resolvedAt?.toString(),
                        )
                    },
                    page = page,
                    size = size,
                    total = result.totalElements,
                )
            }

            TYPE_CONTENT -> {
                val result = if (status == null) {
                    contentReports.findAllByOrderByCreatedAtDesc(pageable)
                } else {
                    contentReports.findAllByStatusOrderByCreatedAtAsc(status, pageable)
                }
                val emails = emailsFor(result.content.map { it.reporterUserId })
                AdminReportPage(
                    items = result.content.map { report ->
                        AdminReportItem(
                            id = report.id,
                            type = TYPE_CONTENT,
                            status = report.status,
                            createdAt = report.createdAt.toString(),
                            ageHours = ChronoUnit.HOURS.between(report.createdAt, Instant.now()),
                            reason = report.reason,
                            reporterUserId = report.reporterUserId,
                            reporterEmail = emails[report.reporterUserId],
                            messageContent = null,
                            groupId = report.groupId,
                            targetType = report.targetType,
                            targetUserId = report.targetUserId,
                            // The raw targetRef is never sent to the panel. It is attacker-controlled
                            // text; letting the browser build a URL out of it would push the trust
                            // decision to the client. The panel only learns whether a viewer is
                            // available and asks this service for the bytes.
                            hasViewablePhoto = resolvePhoto(report.id, audit = false) != null,
                            resolution = report.resolution,
                            resolutionNote = report.resolutionNote,
                            resolvedAt = report.resolvedAt?.toString(),
                        )
                    },
                    page = page,
                    size = size,
                    total = result.totalElements,
                )
            }

            else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "type must be chat or content")
        }
    }

    @Transactional
    fun resolve(type: String, id: Long, request: ResolveReportRequest) {
        val resolution = runCatching { ReportResolution.valueOf(request.resolution) }.getOrNull()
            ?: throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "resolution must be one of ${ReportResolution.entries}",
            )
        val note = request.note?.take(NOTE_MAX_LENGTH)
        val now = Instant.now()
        val adminId = CurrentAdmin.get().id
        val status = if (resolution == ReportResolution.NO_ACTION) ReportStatus.DISMISSED else ReportStatus.RESOLVED

        when (type) {
            TYPE_CHAT -> {
                val report = chatReports.findById(id).orElseThrow { notFound() }
                report.status = status.name
                report.resolution = resolution.name
                report.resolutionNote = note
                report.resolvedAt = now
                report.resolvedBy = adminId
                chatReports.save(report)
            }

            TYPE_CONTENT -> {
                val report = contentReports.findById(id).orElseThrow { notFound() }
                report.status = status.name
                report.resolution = resolution.name
                report.resolutionNote = note
                report.resolvedAt = now
                report.resolvedBy = adminId
                contentReports.save(report)
            }

            else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "type must be chat or content")
        }

        // Recorded after the write, inside the same transaction: a rolled back decision must not leave
        // an audit row claiming it happened.
        audit.record(
            action = AdminAction.REPORT_RESOLVE,
            targetType = "report:$type",
            targetId = id.toString(),
            detail = "resolution=${resolution.name} note=${note ?: ""}",
        )
    }

    /**
     * Fetches the photo behind a content report.
     *
     * This endpoint exists because moderation is otherwise impossible: the ordinary photo endpoint
     * requires group membership, so an admin who is not a member of the reported group gets a 403 and
     * cannot see what was reported.
     *
     * Bypassing that check makes the validation below load-bearing rather than defensive.
     * `content_reports.target_ref` is **free text supplied by the reporting client** — it is not
     * generated server-side — so treating it as a trustworthy path would hand any user an
     * admin-privileged read of arbitrary photos. Three checks close that:
     *
     *  1. the reference must match the exact expected shape, not merely contain digits;
     *  2. the photo must actually belong to the task named in the reference;
     *  3. that task must belong to the group the report was filed in.
     *
     * Step 3 is the one that matters. Without it a crafted report pointing at someone else's photo id
     * would render happily in the panel.
     */
    @Transactional(readOnly = true)
    fun photo(reportId: Long): ModerationPhoto {
        val photo = resolvePhoto(reportId, audit = true)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No viewable photo for this report")
        return photo
    }

    private fun resolvePhoto(reportId: Long, audit: Boolean): ModerationPhoto? {
        val report = contentReports.findById(reportId).orElse(null) ?: return null
        val match = PHOTO_REF.matchEntire(report.targetRef?.trim().orEmpty()) ?: return null
        val taskId = match.groupValues[1].toLongOrNull() ?: return null
        val photoId = match.groupValues[2].toLongOrNull() ?: return null

        val photo = photos.findById(photoId).orElse(null) ?: return null
        if (photo.taskId != taskId) return null
        val task = tasks.findById(photo.taskId).orElse(null) ?: return null
        if (task.familyGroupId != report.groupId) return null

        if (audit) {
            this.audit.record(
                action = AdminAction.REPORT_VIEW_PHOTO,
                targetType = "report:content",
                targetId = reportId.toString(),
                detail = "photoId=$photoId groupId=${report.groupId}",
            )
        }
        return ModerationPhoto(photo.bytes, photo.contentType)
    }

    private fun emailsFor(userIds: List<Long>): Map<Long, String> {
        if (userIds.isEmpty()) return emptyMap()
        return users.findAllSummariesByIdIn(userIds.distinct()).associate { it.id to it.email }
    }

    private fun notFound() = ResponseStatusException(HttpStatus.NOT_FOUND, "Report not found")

    private companion object {
        const val TYPE_CHAT = "chat"
        const val TYPE_CONTENT = "content"
        const val NOTE_MAX_LENGTH = 500

        /**
         * Anchored and fully specified. A looser pattern — say, extracting the first two numbers found —
         * would accept `/tasks/1/photos/2/../../9999` and quietly resolve to something else entirely.
         */
        val PHOTO_REF = Regex("""^/tasks/(\d{1,18})/photos/(\d{1,18})$""")
    }
}
