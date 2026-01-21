package com.kotlinllm.providers

import com.kotlinllm.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow

/**
 * OpenAI API provider.
 *
 * Supports GPT-4, GPT-4o, GPT-3.5-turbo, o1, o3, and compatible APIs.
 */
class OpenAIProvider : Provider {
    override val slug: String = "openai"
    override val name: String = "OpenAI"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    override fun isConfigured(config: Configuration): Boolean {
        return !config.openaiApiKey.isNullOrBlank()
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
            .url("${config.openaiApiBase}/chat/completions")
            .addHeader("Authorization", "Bearer ${config.openaiApiKey}")
            .addHeader("Content-Type", "application/json")
            .apply {
                config.openaiOrganization?.let { addHeader("OpenAI-Organization", it) }
            }
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: throw IOException("Empty response body")

        if (!response.isSuccessful) {
            throw OpenAIException(response.code, parseError(body))
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
            .url("${config.openaiApiBase}/chat/completions")
            .addHeader("Authorization", "Bearer ${config.openaiApiKey}")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .apply {
                config.openaiOrganization?.let { addHeader("OpenAI-Organization", it) }
            }
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        val eventSourceFactory = EventSources.createFactory(client)
        val eventSource = eventSourceFactory.newEventSource(request, object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") {
                    close()
                    return
                }

                try {
                    val chunk = parseChunk(data)
                    trySend(chunk)
                } catch (e: Exception) {
                    // Log and continue
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

    override suspend fun listModels(config: Configuration): List<ModelInfo> = withContext(Dispatchers.IO) {
        val client = buildClient(config)

        val request = Request.Builder()
            .url("${config.openaiApiBase}/models")
            .addHeader("Authorization", "Bearer ${config.openaiApiKey}")
            .get()
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: throw IOException("Empty response body")

        if (!response.isSuccessful) {
            throw OpenAIException(response.code, parseError(body))
        }

        parseModelList(body)
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
        return buildJsonObject {
            put("model", model)
            putJsonArray("messages") {
                messages.forEach { msg ->
                    addJsonObject {
                        put("role", msg.role.toApiString())

                        when {
                            msg.role == Role.TOOL -> {
                                put("content", msg.text)
                                put("tool_call_id", msg.toolCallId)
                            }
                            msg.toolCalls != null -> {
                                if (msg.text.isEmpty()) put("content", JsonNull) else put("content", msg.text)
                                putJsonArray("tool_calls") {
                                    msg.toolCalls.forEach { tc ->
                                        addJsonObject {
                                            put("id", tc.id)
                                            put("type", "function")
                                            putJsonObject("function") {
                                                put("name", tc.name)
                                                put("arguments", Json.encodeToString(JsonObject.serializer(), JsonObject(tc.arguments)))
                                            }
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
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", tool.name)
                                put("description", tool.description)
                                put("parameters", tool.parametersSchema())
                            }
                        }
                    }
                }
            }

            temperature?.let { put("temperature", it) }
            maxTokens?.let { put("max_tokens", it) }
            if (stream) put("stream", true)
        }.toString()
    }

    private fun parseResponse(body: String, model: String): Message {
        val jsonResponse = json.parseToJsonElement(body).jsonObject
        val choice = jsonResponse["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw OpenAIException(500, "No choices in response")

        val message = choice["message"]?.jsonObject
            ?: throw OpenAIException(500, "No message in choice")

        val content = message["content"]?.jsonPrimitive?.contentOrNull ?: ""
        val toolCalls = message["tool_calls"]?.jsonArray?.map { parseToolCall(it.jsonObject) }

        val usage = jsonResponse["usage"]?.jsonObject
        val tokens = usage?.let {
            TokenUsage(
                input = it["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                output = it["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0
            )
        }

        return Message(
            role = Role.ASSISTANT,
            content = Content.text(content),
            modelId = model,
            toolCalls = toolCalls,
            tokens = tokens
        )
    }

    private fun parseChunk(data: String): Chunk {
        val jsonChunk = json.parseToJsonElement(data).jsonObject
        val choice = jsonChunk["choices"]?.jsonArray?.firstOrNull()?.jsonObject

        val delta = choice?.get("delta")?.jsonObject
        val content = delta?.get("content")?.jsonPrimitive?.contentOrNull ?: ""
        val finishReason = choice?.get("finish_reason")?.jsonPrimitive?.contentOrNull

        val toolCalls = delta?.get("tool_calls")?.jsonArray?.map { parseToolCall(it.jsonObject) }

        return Chunk(
            content = content,
            toolCalls = toolCalls,
            finishReason = finishReason
        )
    }

    private fun parseToolCall(obj: JsonObject): ToolCall {
        val function = obj["function"]?.jsonObject
        val argsString = function?.get("arguments")?.jsonPrimitive?.contentOrNull ?: "{}"
        val arguments = try {
            json.parseToJsonElement(argsString).jsonObject.toMap()
        } catch (e: Exception) {
            emptyMap()
        }

        return ToolCall(
            id = obj["id"]?.jsonPrimitive?.contentOrNull ?: "",
            name = function?.get("name")?.jsonPrimitive?.contentOrNull ?: "",
            arguments = arguments
        )
    }

    private fun parseModelList(body: String): List<ModelInfo> {
        val jsonResponse = json.parseToJsonElement(body).jsonObject
        val data = jsonResponse["data"]?.jsonArray ?: return emptyList()

        return data.mapNotNull { item ->
            val obj = item.jsonObject
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null

            // Only include chat models
            if (!id.contains("gpt") && !id.startsWith("o1") && !id.startsWith("o3")) {
                return@mapNotNull null
            }

            ModelInfo(
                id = id,
                provider = slug,
                displayName = id,
                supportsTools = !id.startsWith("o1"), // o1 doesn't support tools yet
                supportsVision = id.contains("vision") || id.contains("gpt-4o") || id.contains("gpt-4-turbo")
            )
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

class OpenAIException(val statusCode: Int, message: String) : Exception("OpenAI API error ($statusCode): $message")
