package com.todoapp.backend.chat

import com.google.cloud.vertexai.api.Candidate
import com.google.cloud.vertexai.api.Content
import com.google.cloud.vertexai.api.FunctionCall
import com.google.cloud.vertexai.api.GenerateContentResponse
import com.google.cloud.vertexai.api.Part
import com.google.cloud.vertexai.generativeai.GenerativeModel
import com.google.protobuf.Struct
import com.google.protobuf.Value
import com.todoapp.backend.group.GroupMemberRepository
import com.todoapp.backend.metrics.ChatUsageRecorder
import com.todoapp.backend.settings.AppSetting
import com.todoapp.backend.settings.AppSettingsService
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
import java.util.concurrent.Executor

/**
 * `meta.toolsCalled` is not telemetry — the Android client decides whether to re-sync its task list
 * from it. So it has to name the tools that actually ran, and only those. The tool-budget cap returns
 * a fallback WITHOUT executing the batch it just received, which is the one path where "requested" and
 * "executed" come apart.
 */
class ChatToolLoopMetaTest {
    private val vertex = Mockito.mock(VertexAiClient::class.java)
    private val tools = Mockito.mock(ChatToolService::class.java)
    private val taskRepo = Mockito.mock(TaskRepository::class.java)
    private val members = Mockito.mock(GroupMemberRepository::class.java)
    private val users = Mockito.mock(UserRepository::class.java)
    private val tracker = Mockito.mock(ChatUsageTracker::class.java)
    private val settings = Mockito.mock(AppSettingsService::class.java)
    private val chatUsage = ChatUsageRecorder({ _, _, _ -> }, Executor { it.run() })

    private val service = ChatService(
        vertex, tools, taskRepo, members, users,
        ChatProperties(maxToolIterations = 2), tracker, chatUsage, settings,
    )

    @BeforeEach
    fun setUp() {
        given(vertex.isReady).willReturn(true)
        given(vertex.model(anyString())).willReturn(Mockito.mock(GenerativeModel::class.java))
        given(taskRepo.findAllByOwnerIdAndFamilyGroupIdIsNull(anyLong())).willReturn(emptyList())
        given(members.findAllByUserId(anyLong())).willReturn(emptyList())
        given(tracker.tryAcquireGlobalDaily(anyInt())).willReturn(true)
        given(settings.isEnabled(AppSetting.CHAT_ENABLED)).willReturn(true)
        given(settings.intValue(AppSetting.CHAT_MAX_GLOBAL_DAILY_REQUESTS)).willReturn(5000)
        given(tools.execute(anyLong(), anyString(), anyRef())).willReturn(okValue())
    }

    @Test
    fun `the tool-cap fallback does not report tools it never executed`() {
        // Two iterations, both answered with a function call: the first executes, the second hits the
        // cap and returns the fallback without ever calling execute.
        given(vertex.generate(anyRef(), anyRef())).willReturn(
            toolResponse("findTaskByTitle"),
            toolResponse("createTask"),
        )

        val response = service.reply(USER_ID, request())

        Mockito.verify(tools, Mockito.times(1)).execute(anyLong(), anyString(), anyRef())
        assertThat(response.meta.toolsCalled).containsExactly("findTaskByTitle")
        // createTask would have told the client to re-sync after a turn that wrote nothing.
        assertThat(response.meta.toolsCalled).doesNotContain("createTask")
        assertThat(response.meta.mutated).isFalse()
    }

    @Test
    fun `a turn that ran a write tool is flagged as mutating`() {
        given(vertex.generate(anyRef(), anyRef())).willReturn(
            toolResponse("setSteps"),
            textResponse("Done — 3 steps."),
        )

        val response = service.reply(USER_ID, request())

        // The client gets this from the server rather than keeping its own list of which tool names
        // write — a list that goes stale the moment a write tool is added.
        assertThat(response.meta.toolsCalled).containsExactly("setSteps")
        assertThat(response.meta.mutated).isTrue()
    }

    @Test
    fun `a read-only turn is not flagged as mutating`() {
        given(vertex.generate(anyRef(), anyRef())).willReturn(
            toolResponse("getTodaysTasks"),
            textResponse("You have 3 tasks today."),
        )

        val response = service.reply(USER_ID, request())

        assertThat(response.meta.mutated).isFalse()
    }

    private fun request() = ChatMessageRequest(prompt = "what's today?", locale = "en")

    private fun toolResponse(name: String): GenerateContentResponse =
        GenerateContentResponse.newBuilder()
            .addCandidates(
                Candidate.newBuilder().setContent(
                    Content.newBuilder().setRole("model").addParts(
                        Part.newBuilder().setFunctionCall(
                            FunctionCall.newBuilder().setName(name).setArgs(Struct.getDefaultInstance()),
                        ),
                    ),
                ),
            )
            .build()

    private fun textResponse(text: String): GenerateContentResponse =
        GenerateContentResponse.newBuilder()
            .addCandidates(
                Candidate.newBuilder().setContent(
                    Content.newBuilder().setRole("model").addParts(Part.newBuilder().setText(text)),
                ),
            )
            .build()

    private fun okValue(): Value = Value.newBuilder()
        .setStructValue(Struct.newBuilder().putFields("ok", Value.newBuilder().setBoolValue(true).build()))
        .build()

    private fun <T> anyRef(): T = Mockito.any()

    private companion object {
        const val USER_ID = 1L
    }
}
