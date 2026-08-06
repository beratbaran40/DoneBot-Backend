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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
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
 * A user asked which task TYPE to use for a routine bounded by a date range, and DoneBot answered
 * "…için `createTask` aracını kullanmanı tavsiye ederim" — it named an internal function AND told the
 * user to call something only this service can call. The prompt is where that is fixed; this is the
 * instrument that tells us whether the fix held.
 *
 * The detector deliberately does NOT edit the reply, and the first test pins that: deleting the name
 * out of that sentence leaves "…için aracını kullanmanı tavsiye ederim", which reads worse than the
 * leak and hides the fact that the prompt needs another pass.
 */
class ChatToolNameLeakTest {
    private val vertex = Mockito.mock(VertexAiClient::class.java)
    private val tools = Mockito.mock(ChatToolService::class.java)
    private val taskRepo = Mockito.mock(TaskRepository::class.java)
    private val members = Mockito.mock(GroupMemberRepository::class.java)
    private val users = Mockito.mock(UserRepository::class.java)
    private val tracker = Mockito.mock(ChatUsageTracker::class.java)
    private val settings = Mockito.mock(AppSettingsService::class.java)
    private val chatUsage = ChatUsageRecorder({ _, _, _ -> }, Executor { it.run() })
    private val registry = SimpleMeterRegistry()

    private val service = ChatService(
        vertex, tools, taskRepo, members, users,
        ChatProperties(maxToolIterations = 2), tracker, chatUsage, settings, registry,
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
    fun `a reply that names a tool is counted and returned unchanged`() {
        // The real leaked sentence, verbatim.
        val leaked = "Tekrar eden bir görev oluşturmak için `createTask` aracını kullanmanı tavsiye ederim."
        given(vertex.generate(anyRef(), anyRef())).willReturn(textResponse(leaked))

        val response = service.reply(USER_ID, request())

        assertThat(leakCount("createTask")).isEqualTo(1.0)
        // Detect, don't mutate: the counter is the deliverable, the reply is left alone.
        assertThat(response.text).contains("createTask")
    }

    @Test
    fun `the reply the prompt fix is supposed to produce counts nothing`() {
        given(vertex.generate(anyRef(), anyRef())).willReturn(
            textResponse("Bu ÖZEL bir görev — hem tekrarlıyor hem adımları var. Hemen oluşturuyorum."),
        )

        service.reply(USER_ID, request())

        assertThat(registry.find(ChatService.TOOL_LEAK_METRIC).counters()).isEmpty()
    }

    @Test
    fun `prose that merely resembles a tool name is not a match`() {
        // Word boundaries are the whole defence here: "add step" is ordinary English, `addStep` is not.
        given(vertex.generate(anyRef(), anyRef())).willReturn(
            textResponse("Add step 'book flight' — I created 3 tasks and set the steps for today."),
        )

        service.reply(USER_ID, request())

        assertThat(registry.find(ChatService.TOOL_LEAK_METRIC).counters()).isEmpty()
    }

    @Test
    fun `each name in a reply is counted under its own tag`() {
        // Tagged rather than totalled, so a repeat offender is visible instead of just a number going up.
        given(vertex.generate(anyRef(), anyRef())).willReturn(
            textResponse("Use findTaskByTitle first, then deleteTask, then deleteTask again."),
        )

        service.reply(USER_ID, request())

        assertThat(leakCount("findTaskByTitle")).isEqualTo(1.0)
        // Twice in one reply is still one leaking reply.
        assertThat(leakCount("deleteTask")).isEqualTo(1.0)
    }

    @Test
    fun `the tool-budget fallback names no tool`() {
        // The detector is not run on this path because the text is a server-authored constant. That is
        // only safe for as long as the constant stays clean, which is what this pins.
        given(vertex.generate(anyRef(), anyRef())).willReturn(
            toolResponse("findTaskByTitle"),
            toolResponse("createTask"),
        )

        val response = service.reply(USER_ID, request())

        assertThat(ChatToolDeclarations.tool.functionDeclarationsList.map { it.name })
            .describedAs("the fallback text must name no tool: %s", response.text)
            .noneMatch { response.text.contains(it) }
    }

    private fun leakCount(tool: String): Double =
        registry.find(ChatService.TOOL_LEAK_METRIC).tag("tool", tool).counter()?.count() ?: 0.0

    private fun request() = ChatMessageRequest(prompt = "hangi görev tipini önerirsin?", locale = "tr")

    private fun textResponse(text: String): GenerateContentResponse =
        GenerateContentResponse.newBuilder()
            .addCandidates(
                Candidate.newBuilder().setContent(
                    Content.newBuilder().setRole("model").addParts(Part.newBuilder().setText(text)),
                ),
            )
            .build()

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

    private fun okValue(): Value = Value.newBuilder()
        .setStructValue(Struct.newBuilder().putFields("ok", Value.newBuilder().setBoolValue(true).build()))
        .build()

    private fun <T> anyRef(): T = Mockito.any()

    private companion object {
        const val USER_ID = 1L
    }
}
