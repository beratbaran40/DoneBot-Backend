package com.todoapp.backend.chat

import com.google.api.gax.grpc.GrpcStatusCode
import com.google.api.gax.rpc.ApiExceptionFactory
import com.google.cloud.vertexai.api.Candidate
import com.google.cloud.vertexai.api.GenerateContentResponse
import com.google.cloud.vertexai.generativeai.GenerativeModel
import com.todoapp.backend.task.TaskRepository
import com.todoapp.backend.user.UserRepository
import io.grpc.Status
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.BDDMockito.given
import org.mockito.Mockito
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.anyLong
import org.mockito.Mockito.anyString
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.io.IOException

/**
 * §7.13 — a failing Vertex call must degrade to a clean, client-classifiable HTTP status instead of
 * an uncaught generic 500. We stub the `VertexAiClient.generate` seam to throw each failure kind and
 * assert `reply()` maps it to the right status + the marker the Android client keys on.
 *
 * gax exceptions have no public constructor, so we build real ones via [ApiExceptionFactory].
 */
class ChatServiceVertexFallbackTest {

    private val vertex: VertexAiClient = Mockito.mock(VertexAiClient::class.java)
    private val tools: ChatToolService = Mockito.mock(ChatToolService::class.java)
    private val taskRepo: TaskRepository = Mockito.mock(TaskRepository::class.java)
    private val users: UserRepository = Mockito.mock(UserRepository::class.java)
    private val tracker: ChatUsageTracker = Mockito.mock(ChatUsageTracker::class.java)

    // A real recorder over an inline executor and a collecting writer, rather than a mock: these tests
    // already drive every Vertex failure branch, so wiring it for real also proves the durable usage
    // counters are written on the failure paths — the exact gap this recorder exists to close.
    private val recordedUsage = mutableListOf<com.todoapp.backend.metrics.ChatUsageDelta>()
    private val chatUsage = com.todoapp.backend.metrics.ChatUsageRecorder(
        { _, _, delta -> recordedUsage += delta },
        java.util.concurrent.Executor { it.run() },
    )

    private val settings: com.todoapp.backend.settings.AppSettingsService =
        Mockito.mock(com.todoapp.backend.settings.AppSettingsService::class.java)

    private val props = ChatProperties()

    private val service = ChatService(vertex, tools, taskRepo, users, props, tracker, chatUsage, settings)

    @BeforeEach
    fun setUp() {
        given(vertex.isReady).willReturn(true)
        given(vertex.model(anyString())).willReturn(Mockito.mock(GenerativeModel::class.java))
        given(taskRepo.findAllByOwnerIdAndFamilyGroupIdIsNull(anyLong())).willReturn(emptyList())
        given(tracker.tryAcquireGlobalDaily(anyInt())).willReturn(true)
        given(settings.isEnabled(com.todoapp.backend.settings.AppSetting.CHAT_ENABLED)).willReturn(true)
        given(settings.intValue(com.todoapp.backend.settings.AppSetting.CHAT_MAX_GLOBAL_DAILY_REQUESTS))
            .willReturn(5000)
    }

    @Test
    fun `the operator kill switch degrades to the same 503 the client already understands`() {
        // A new error string here would reach users as a raw error instead of the "AI is taking a break"
        // banner, because the client keys on this exact marker.
        given(settings.isEnabled(com.todoapp.backend.settings.AppSetting.CHAT_ENABLED)).willReturn(false)

        val ex = assertThrows<ResponseStatusException> { service.reply(USER_ID, request()) }

        assertThat(ex.statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        assertThat(ex.reason).contains("vertex_unavailable")
        assertThat(recordedUsage.single().errors).isEqualTo(1)
    }

    @Test
    fun `vertex quota (RESOURCE_EXHAUSTED) maps to 429 with a quota marker`() {
        stubGenerateToThrow(vertexException(Status.Code.RESOURCE_EXHAUSTED))

        val ex = assertThrows<ResponseStatusException> { service.reply(USER_ID, request()) }

        assertThat(ex.statusCode).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
        assertThat(ex.reason).contains("quota") // client RATE_LIMIT_MARKERS greps this → RATE_LIMITED
        assertThat(ex.reason).contains("vertex_quota")
    }

    @Test
    fun `vertex outage (UNAVAILABLE) maps to 503 with the unavailable marker`() {
        stubGenerateToThrow(vertexException(Status.Code.UNAVAILABLE))

        val ex = assertThrows<ResponseStatusException> { service.reply(USER_ID, request()) }

        assertThat(ex.statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        assertThat(ex.reason).contains("vertex_unavailable")
    }

    @Test
    fun `bad credentials (PERMISSION_DENIED) maps to 503`() {
        stubGenerateToThrow(vertexException(Status.Code.PERMISSION_DENIED))

        val ex = assertThrows<ResponseStatusException> { service.reply(USER_ID, request()) }

        assertThat(ex.statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        assertThat(ex.reason).contains("vertex_unavailable")
    }

    @Test
    fun `transport IOException maps to 503`() {
        stubGenerateToThrow(IOException("socket closed"))

        val ex = assertThrows<ResponseStatusException> { service.reply(USER_ID, request()) }

        assertThat(ex.statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        assertThat(ex.reason).contains("vertex_unavailable")
    }

    @Test
    fun `a failed turn is recorded as a durable error instead of vanishing`() {
        // Before ChatUsageRecorder, every branch above left no trace but a log line: the in-memory
        // tracker was only touched on the success paths, so chat error rate could not be measured at all.
        stubGenerateToThrow(vertexException(Status.Code.UNAVAILABLE))

        assertThrows<ResponseStatusException> { service.reply(USER_ID, request()) }

        assertThat(recordedUsage).hasSize(1)
        assertThat(recordedUsage.single().requests).isEqualTo(1)
        assertThat(recordedUsage.single().errors).isEqualTo(1)
        assertThat(recordedUsage.single().refusals).isEqualTo(0)
    }

    @Test
    fun `a rejection by the global cost cap is recorded even though vertex was never called`() {
        // This branch returns before logTurn, so it carries its own record; otherwise the cost
        // circuit-breaker firing would be invisible on the ops screen.
        given(tracker.tryAcquireGlobalDaily(anyInt())).willReturn(false)

        assertThrows<ResponseStatusException> { service.reply(USER_ID, request()) }

        assertThat(recordedUsage).hasSize(1)
        assertThat(recordedUsage.single().errors).isEqualTo(1)
    }

    @Test
    fun `a safety-blocked candidate returns a localized refusal, not a 502`() {
        val blocked = GenerateContentResponse.newBuilder()
            .addCandidates(Candidate.newBuilder().setFinishReason(Candidate.FinishReason.SAFETY).build())
            .build()
        given(vertex.generate(any(), any())).willReturn(blocked)

        val result = service.reply(USER_ID, request())

        assertThat(result.meta.refused).isTrue()
        assertThat(result.text).isNotBlank()
    }

    @Test
    fun `global daily cap returns 503 with the unavailable marker`() {
        given(tracker.tryAcquireGlobalDaily(anyInt())).willReturn(false)

        val ex = assertThrows<ResponseStatusException> { service.reply(USER_ID, request()) }

        assertThat(ex.statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        assertThat(ex.reason).contains("vertex_unavailable")
    }

    // Tier 1.5 turn deadline: the server must answer (even if with a marked 503) well before the
    // Android client's 60s read timeout — a client-side timeout renders as a connectivity error
    // and the server keeps paying Vertex for an answer nobody receives.

    @Test
    fun `an exhausted deadline short-circuits to 503 without spending a Vertex call`() {
        val service = ChatService(vertex, tools, taskRepo, users, ChatProperties(turnDeadlineMs = 1), tracker, chatUsage, settings)

        val ex = assertThrows<ResponseStatusException> { service.reply(USER_ID, request()) }

        assertThat(ex.statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        assertThat(ex.reason).contains("vertex_unavailable")
        Mockito.verify(vertex, Mockito.never()).generate(any(), any())
    }

    @Test
    fun `a generate hanging past the deadline is cut and maps to 503`() {
        // Budget over MIN_ROUND_BUDGET_MS so the round actually starts; generate hangs way past it.
        val service = ChatService(vertex, tools, taskRepo, users, ChatProperties(turnDeadlineMs = 2_500), tracker, chatUsage, settings)
        given(vertex.generate(any(), any())).willAnswer {
            Thread.sleep(30_000)
            error("should have been cancelled by the deadline")
        }

        val startedAt = System.currentTimeMillis()
        val ex = assertThrows<ResponseStatusException> { service.reply(USER_ID, request()) }
        val elapsed = System.currentTimeMillis() - startedAt

        assertThat(ex.statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        assertThat(ex.reason).contains("vertex_unavailable")
        assertThat(elapsed).isLessThan(10_000)
    }

    private fun stubGenerateToThrow(t: Throwable) {
        // willAnswer (not willThrow) so a checked IOException — undeclared on the Kotlin seam — is accepted.
        given(vertex.generate(any(), any())).willAnswer { throw t }
    }

    private fun vertexException(code: Status.Code) =
        ApiExceptionFactory.createException(RuntimeException("boom"), GrpcStatusCode.of(code), false)

    private fun request() = ChatMessageRequest(prompt = "give me a productivity tip", locale = "en")

    // Kotlin-friendly Mockito.any() for reference-typed params: returns null, which is safe because the
    // stubbed mock never runs a real body.
    private fun <T> any(): T = Mockito.any()

    private companion object {
        const val USER_ID = 1L
    }
}
