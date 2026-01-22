package com.kotlinllm.providers

import com.kotlinllm.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Anthropic Claude API provider.
 *
 * Supports Claude 3.5, Claude 3 (Opus, Sonnet, Haiku), and newer models.
 */
class AnthropicProvider : Provider {
    override val slug: String = "anthropic"
    override val name: String = "Anthropic"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    companion object {
        private const val API_VERSION = "2023-06-01"
        private const val DEFAULT_MAX_TOKENS = 4096
    }

    override fun isConfigured(config: Configuration): Boolean {
        return !config.anthropicApiKey.isNullOrBlank()
    }

    override suspend fun complete(
        messages: List<Message>,
        model: String,
        tools: List<Tool>,
        temperature: Double?,
        maxTokens: Int?,
        config: Configuration
    ): Message = withContext(Dispatchers.IO) {
        val client = buildClient(config)
        val requestBody = buildRequestBody(messages, model, tools, temperature, maxTokens, stream = false)

        val request = Request.Builder()
            .url("${config.anthropicApiBase}/v1/messages")
            .addHeader("x-api-key", config.anthropicApiKey!!)
            .addHeader("anthropic-version", API_VERSION)
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: throw IOException("Empty response body")

        if (!response.isSuccessful) {
            throw AnthropicException(response.code, parseError(body))
        }

        parseResponse(body, model)
    }

    override fun stream(
        messages: List<Message>,
        model: String,
        tools: List<Tool>,
        temperature: Double?,
        maxTokens: Int?,
        config: Configuration
    ): Flow<Chunk> = callbackFlow {
        val client = buildClient(config)
        val requestBody = buildRequestBody(messages, model, tools, temperature, maxTokens, stream = true)

        val request = Request.Builder()
            .url("${config.anthropicApiBase}/v1/messages")
            .addHeader("x-api-key", config.anthropicApiKey!!)
            .addHeader("anthropic-version", API_VERSION)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        val toolCallsAccumulator = mutableMapOf<Int, ToolCallAccumulator>()

        val eventSourceFactory = EventSources.createFactory(client)
        val eventSource = eventSourceFactory.newEventSource(request, object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                try {
                    val chunk = parseStreamEvent(type ?: "", data, toolCallsAccumulator)
                    if (chunk != null) {
                        trySend(chunk)
                    }
                } catch (e: Exception) {
                    // Close the channel with the parsing error
                    close(AnthropicException(0, "Failed to parse stream event: ${e.message} - Data: $data"))
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                close(t ?: IOException("Stream failed: ${response?.code}"))
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }
        })

        awaitClose {
            eventSource.cancel()
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun listModels(config: Configuration): List<ModelInfo> {
        // Anthropic doesn't have a models endpoint, return known models
        return listOf(
            ModelInfo("claude-sonnet-4-20250514", slug, "Claude Sonnet 4", 200000, 8192, true, true),
            ModelInfo("claude-opus-4-20250514", slug, "Claude Opus 4", 200000, 8192, true, true),
            ModelInfo("claude-3-5-sonnet-20241022", slug, "Claude 3.5 Sonnet", 200000, 8192, true, true),
            ModelInfo("claude-3-5-haiku-20241022", slug, "Claude 3.5 Haiku", 200000, 8192, true, true),
            ModelInfo("claude-3-opus-20240229", slug, "Claude 3 Opus", 200000, 4096, true, true),
            ModelInfo("claude-3-sonnet-20240229", slug, "Claude 3 Sonnet", 200000, 4096, true, true),
            ModelInfo("claude-3-haiku-20240307", slug, "Claude 3 Haiku", 200000, 4096, true, true)
        )
    }

    private fun buildClient(config: Configuration): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(config.connectTimeout.toMillis(), TimeUnit.MILLISECONDS)
            .readTimeout(config.requestTimeout.toMillis(), TimeUnit.MILLISECONDS)
            .writeTimeout(config.requestTimeout.toMillis(), TimeUnit.MILLISECONDS)
            .build()
    }

    private fun buildRequestBody(
        messages: List<Message>,
        model: String,
        tools: List<Tool>,
        temperature: Double?,
        maxTokens: Int?,
        stream: Boolean
    ): String {
        // Extract system message
        val systemMessage = messages.find { it.role == Role.SYSTEM }?.text
        val conversationMessages = messages.filter { it.role != Role.SYSTEM }

        return buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens ?: DEFAULT_MAX_TOKENS)

            systemMessage?.let { put("system", it) }

            putJsonArray("messages") {
                conversationMessages.forEach { msg ->
                    addJsonObject {
                        put("role", if (msg.role == Role.TOOL) "user" else msg.role.toApiString())

                        when {
                            msg.role == Role.TOOL -> {
                                putJsonArray("content") {
                                    addJsonObject {
                                        put("type", "tool_result")
                                        put("tool_use_id", msg.toolCallId)
                                        put("content", msg.text)
                                    }
                                }
                            }
                            msg.toolCalls != null -> {
                                putJsonArray("content") {
                                    if (msg.text.isNotEmpty()) {
                                        addJsonObject {
                                            put("type", "text")
                                            put("text", msg.text)
                                        }
                                    }
                                    msg.toolCalls.forEach { tc ->
                                        addJsonObject {
                                            put("type", "tool_use")
                                            put("id", tc.id)
                                            put("name", tc.name)
                                            put("input", JsonObject(tc.arguments))
                                        }
                                    }
                                }
                            }
                            else -> {
                                put("content", msg.text)
                            }
                        }
                    }
                }
            }

            if (tools.isNotEmpty()) {
                putJsonArray("tools") {
                    tools.forEach { tool ->
                        addJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("input_schema", tool.parametersSchema())
                        }
                    }
                }
            }

            temperature?.let { put("temperature", it) }
            if (stream) put("stream", true)
        }.toString()
    }

    private fun parseResponse(body: String, model: String): Message {
        val jsonResponse = json.parseToJsonElement(body).jsonObject

        val content = jsonResponse["content"]?.jsonArray
        val textContent = content?.filter {
            it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "text"
        }?.mapNotNull {
            it.jsonObject["text"]?.jsonPrimitive?.contentOrNull
        }?.joinToString("") ?: ""

        val toolCalls = content?.filter {
            it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "tool_use"
        }?.map { block ->
            val obj = block.jsonObject
            ToolCall(
                id = obj["id"]?.jsonPrimitive?.content ?: "",
                name = obj["name"]?.jsonPrimitive?.content ?: "",
                arguments = obj["input"]?.jsonObject?.toMap() ?: emptyMap()
            )
        }?.takeIf { it.isNotEmpty() }

        val usage = jsonResponse["usage"]?.jsonObject
        val tokens = usage?.let {
            TokenUsage(
                input = it["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                output = it["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0
            )
        }

        return Message(
            role = Role.ASSISTANT,
            content = Content.text(textContent),
            modelId = model,
            toolCalls = toolCalls,
            tokens = tokens
        )
    }

    private data class ToolCallAccumulator(
        var id: String = "",
        var name: String = "",
        var inputJson: StringBuilder = StringBuilder()
    )

    private fun parseStreamEvent(
        eventType: String,
        data: String,
        toolCallsAccumulator: MutableMap<Int, ToolCallAccumulator>
    ): Chunk? {
        val jsonData = json.parseToJsonElement(data).jsonObject

        return when (eventType) {
            "content_block_delta" -> {
                val delta = jsonData["delta"]?.jsonObject
                val deltaType = delta?.get("type")?.jsonPrimitive?.contentOrNull

                when (deltaType) {
                    "text_delta" -> {
                        val text = delta["text"]?.jsonPrimitive?.contentOrNull ?: ""
                        Chunk(content = text)
                    }
                    "input_json_delta" -> {
                        val index = jsonData["index"]?.jsonPrimitive?.intOrNull ?: 0
                        val partialJson = delta["partial_json"]?.jsonPrimitive?.contentOrNull ?: ""
                        toolCallsAccumulator[index]?.inputJson?.append(partialJson)
                        null
                    }
                    else -> null
                }
            }
            "content_block_start" -> {
                val block = jsonData["content_block"]?.jsonObject
                val blockType = block?.get("type")?.jsonPrimitive?.contentOrNull

                if (blockType == "tool_use") {
                    val index = jsonData["index"]?.jsonPrimitive?.intOrNull ?: 0
                    toolCallsAccumulator[index] = ToolCallAccumulator(
                        id = block["id"]?.jsonPrimitive?.contentOrNull ?: "",
                        name = block["name"]?.jsonPrimitive?.contentOrNull ?: ""
                    )
                }
                null
            }
            "content_block_stop" -> {
                val index = jsonData["index"]?.jsonPrimitive?.intOrNull ?: 0
                val accumulator = toolCallsAccumulator[index]

                if (accumulator != null && accumulator.name.isNotEmpty()) {
                    val arguments = try {
                        json.parseToJsonElement(accumulator.inputJson.toString()).jsonObject.toMap()
                    } catch (e: Exception) {
                        emptyMap()
                    }

                    Chunk(
                        toolCalls = listOf(
                            ToolCall(
                                id = accumulator.id,
                                name = accumulator.name,
                                arguments = arguments
                            )
                        )
                    )
                } else {
                    null
                }
            }
            "message_stop" -> {
                Chunk(finishReason = "stop")
            }
            "message_delta" -> {
                val stopReason = jsonData["delta"]?.jsonObject?.get("stop_reason")?.jsonPrimitive?.contentOrNull
                if (stopReason != null) {
                    Chunk(finishReason = stopReason)
                } else {
                    null
                }
            }
            else -> null
        }
    }

    private fun parseError(body: String): String {
        return try {
            val jsonError = json.parseToJsonElement(body).jsonObject
            jsonError["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                ?: "Unknown error"
        } catch (e: Exception) {
            body
        }
    }
}

class AnthropicException(val statusCode: Int, message: String) : Exception("Anthropic API error ($statusCode): $message")
