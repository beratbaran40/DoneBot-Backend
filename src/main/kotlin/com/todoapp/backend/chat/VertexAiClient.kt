package com.todoapp.backend.chat

import com.google.cloud.vertexai.VertexAI
import com.google.cloud.vertexai.api.Content
import com.google.cloud.vertexai.api.GenerateContentResponse
import com.google.cloud.vertexai.api.GenerationConfig
import com.google.cloud.vertexai.generativeai.GenerativeModel
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Component

/**
 * Lazily-initialised wrapper around the Vertex AI Java SDK.
 *
 * `VertexAI` is expensive to construct (auth + gRPC channel) so we hold one
 * shared instance for the life of the application context. When the project
 * id is blank (local dev without GCP credentials) we skip initialisation and
 * surface a clear failure on use, so the rest of the backend boots cleanly.
 */
@Component
@EnableConfigurationProperties(ChatProperties::class)
class VertexAiClient(
    private val props: ChatProperties,
) {
    private val log = LoggerFactory.getLogger(VertexAiClient::class.java)

    private var vertexAi: VertexAI? = null

    val isReady: Boolean get() = vertexAi != null

    @PostConstruct
    fun init() {
        if (props.projectId.isBlank()) {
            log.warn("VertexAI not initialised: app.vertex.project-id is blank — chat endpoint will return 503.")
            return
        }
        runCatching {
            vertexAi = VertexAI(props.projectId, props.location)
            log.info(
                "VertexAI initialised (project={}, location={}, model={}).",
                props.projectId, props.location, props.model,
            )
        }.onFailure {
            log.error("VertexAI initialisation failed: ${it.message}", it)
        }
    }

    @PreDestroy
    fun shutdown() {
        runCatching { vertexAi?.close() }
        vertexAi = null
    }

    /**
     * Build a fresh [GenerativeModel] each call. The model is cheap (just a
     * config wrapper); the underlying gRPC channel lives on the [VertexAI]
     * instance so we don't pay the auth cost again.
     */
    fun model(systemInstruction: String): GenerativeModel {
        val v = vertexAi ?: error("VertexAI not initialised — set app.vertex.project-id")
        val generationConfig = GenerationConfig.newBuilder()
            .setTemperature(props.temperature)
            .setMaxOutputTokens(props.maxOutputTokens)
            .build()
        val systemContent = Content.newBuilder()
            .setRole("system")
            .addParts(com.google.cloud.vertexai.api.Part.newBuilder().setText(systemInstruction).build())
            .build()
        return GenerativeModel.Builder()
            .setModelName(props.model)
            .setVertexAi(v)
            .setGenerationConfig(generationConfig)
            .setSystemInstruction(systemContent)
            .setTools(listOf(ChatToolDeclarations.tool))
            .build()
    }

    /** One-shot generate — used inside the orchestrator's function-call loop. */
    fun generate(model: GenerativeModel, history: List<Content>): GenerateContentResponse =
        model.generateContent(history)
}
