package com.todoapp.backend.chat

import com.google.cloud.vertexai.api.Candidate
import com.google.cloud.vertexai.api.Content
import com.google.cloud.vertexai.api.GenerateContentResponse
import com.google.cloud.vertexai.api.Part
import com.google.cloud.vertexai.generativeai.GenerativeModel
import com.todoapp.backend.group.GroupMemberRepository
import com.todoapp.backend.metrics.ChatUsageRecorder
import com.todoapp.backend.settings.AppSetting
import com.todoapp.backend.settings.AppSettingsService
import com.todoapp.backend.task.Recurrence
import com.todoapp.backend.task.TaskEntity
import com.todoapp.backend.task.TaskRepository
import com.todoapp.backend.user.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.mockito.Mockito
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.anyLong
import org.mockito.Mockito.anyString
import java.time.LocalDate
import java.util.concurrent.Executor

/**
 * The `[Context: …]` block is prepended to every single user turn, so what it costs and what it
 * promises both matter. These tests pin the two things that are easy to break by accident: the task
 * list staying capped, and the health line reflecting exactly what the client sent — including saying
 * nothing at all when an older client sends nothing.
 */
class ChatContextPreambleTest {
    private val vertex = Mockito.mock(VertexAiClient::class.java)
    private val tools = Mockito.mock(ChatToolService::class.java)
    private val taskRepo = Mockito.mock(TaskRepository::class.java)
    private val members = Mockito.mock(GroupMemberRepository::class.java)
    private val users = Mockito.mock(UserRepository::class.java)
    private val tracker = Mockito.mock(ChatUsageTracker::class.java)
    private val settings = Mockito.mock(AppSettingsService::class.java)
    private val chatUsage = ChatUsageRecorder({ _, _, _ -> }, Executor { it.run() })

    private val service = ChatService(
        vertex, tools, taskRepo, members, users, ChatProperties(), tracker, chatUsage, settings,
    )

    /** Every conversation handed to Vertex this test, in call order. */
    private val seenConversations = mutableListOf<List<Content>>()

    @BeforeEach
    fun setUp() {
        given(vertex.isReady).willReturn(true)
        given(vertex.model(anyString())).willReturn(Mockito.mock(GenerativeModel::class.java))
        given(members.findAllByUserId(anyLong())).willReturn(emptyList())
        given(tracker.tryAcquireGlobalDaily(anyInt())).willReturn(true)
        given(settings.isEnabled(AppSetting.CHAT_ENABLED)).willReturn(true)
        given(settings.intValue(AppSetting.CHAT_MAX_GLOBAL_DAILY_REQUESTS)).willReturn(5000)
        // Stubbed once here, not per call: re-running `given(mock.generate(...))` while a willAnswer is
        // already registered actually EXECUTES that answer with null arguments to record the matchers.
        // Hence the `as?` too — the recording invocation must be harmless.
        given(vertex.generate(anyRef(), anyRef())).willAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            (invocation.arguments[1] as? List<Content>)?.let { seenConversations += it }
            textResponse("ok")
        }
    }

    @Test
    fun `the task list is capped and points at the tool for the rest`() {
        given(taskRepo.findAllByOwnerIdAndFamilyGroupIdIsNull(anyLong()))
            .willReturn((1..14).map { task(id = it.toLong(), title = "Task $it") })

        val preamble = capturePreamble()

        assertThat(preamble).contains("Today (14):")
        assertThat(preamble).contains("Task 10")
        assertThat(preamble).doesNotContain("Task 11")
        assertThat(preamble).contains("(+4 more — call getTodaysTasks)")
    }

    @Test
    fun `an odd half-heart count renders as the app renders it`() {
        given(taskRepo.findAllByOwnerIdAndFamilyGroupIdIsNull(anyLong())).willReturn(emptyList())

        // 13 half-hearts is 6½ — the client's HeartsFormat.heartsLabel produces the same string, and the
        // two drifting apart would have the bot quote a number the Activity bar doesn't show.
        assertThat(capturePreamble(healthHalfHearts = 13)).contains("Health: 6½/12 hearts.")
        assertThat(capturePreamble(healthHalfHearts = 24)).contains("Health: 12/12 hearts.")
    }

    @Test
    fun `an older client sending no health value gets no health line at all`() {
        given(taskRepo.findAllByOwnerIdAndFamilyGroupIdIsNull(anyLong())).willReturn(emptyList())

        // Guessing a value here would be worse than omitting it: the bot would confidently quote a
        // number that contradicts the bar on the user's own screen.
        assertThat(capturePreamble(healthHalfHearts = null)).doesNotContain("Health:")
    }

    @Test
    fun `secret tasks never reach the preamble`() {
        given(taskRepo.findAllByOwnerIdAndFamilyGroupIdIsNull(anyLong())).willReturn(
            listOf(
                task(id = 1L, title = "Buy milk"),
                task(id = 2L, title = "Therapy appointment", isSecret = true),
            ),
        )

        val preamble = capturePreamble()

        assertThat(preamble).contains("Buy milk")
        assertThat(preamble).doesNotContain("Therapy appointment")
    }

    @Test
    fun `routines are summarized so the bot can talk about them without a tool call`() {
        given(taskRepo.findAllByOwnerIdAndFamilyGroupIdIsNull(anyLong())).willReturn(
            listOf(
                task(id = 1L, title = "Vitamin", recurrence = Recurrence.DAILY),
                task(id = 2L, title = "Old course", recurrence = Recurrence.DAILY, finishedOn = 19_000L),
            ),
        )

        assertThat(capturePreamble()).contains("Routines: 1 active, 1 finished.")
    }

    /** Runs one turn and returns the user text Vertex was handed — the `[Context: …]` block plus prompt. */
    private fun capturePreamble(healthHalfHearts: Int? = null): String {
        seenConversations.clear()
        service.reply(
            USER_ID,
            ChatMessageRequest(prompt = "what's today?", locale = "en", healthHalfHearts = healthHalfHearts),
        )
        return seenConversations.last().last().partsList.first().text
    }

    private fun task(
        id: Long,
        title: String,
        isSecret: Boolean = false,
        recurrence: Recurrence = Recurrence.NONE,
        finishedOn: Long? = null,
    ) = TaskEntity(
        id = id,
        ownerId = USER_ID,
        title = title,
        date = LocalDate.now().toEpochDay(),
        timeStart = 9 * 3600L,
        timeEnd = 10 * 3600L,
        isSecret = isSecret,
        recurrence = recurrence,
        finishedOn = finishedOn,
    )

    private fun textResponse(text: String): GenerateContentResponse =
        GenerateContentResponse.newBuilder()
            .addCandidates(
                Candidate.newBuilder().setContent(
                    Content.newBuilder().setRole("model").addParts(Part.newBuilder().setText(text)),
                ),
            )
            .build()

    private fun <T> anyRef(): T = Mockito.any()

    private companion object {
        const val USER_ID = 1L
    }
}
