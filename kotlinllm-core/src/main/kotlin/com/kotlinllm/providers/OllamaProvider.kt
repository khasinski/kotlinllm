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
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Ollama API provider for local LLM inference.
 *
 * Supports Llama 3, Mistral, CodeLlama, and other models running locally via Ollama.
 *
 * Model naming conventions:
 * - "ollama:llama3" - Explicit Ollama prefix
 * - "llama3" - Auto-detected as Ollama model
 * - "mistral" - Auto-detected as Ollama model
 * - "codellama" - Auto-detected as Ollama model
 *
 * Example:
 * ```kotlin
 * KotlinLLM.configure {
 *     ollamaApiBase = "http://localhost:11434"
 * }
 *
 * val response = KotlinLLM.chat("ollama:llama3").ask("Hello!")
 * ```
 */
class OllamaProvider : Provider {
    override val slug: String = "ollama"
    override val name: String = "Ollama"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    override fun isConfigured(config: Configuration): Boolean {
        // Ollama is configured by default on localhost
        return config.ollamaApiBase.isNotBlank()
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
        val actualModel = normalizeModelName(model)
        val requestBody = buildRequestBody(messages, actualModel, tools, temperature, maxTokens, stream = false)

        val request = Request.Builder()
            .url("${config.ollamaApiBase}/api/chat")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: throw IOException("Empty response body")

        if (!response.isSuccessful) {
            throw OllamaException(response.code, parseError(body))
        }

        parseResponse(body, actualModel)
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
        val actualModel = normalizeModelName(model)
        val requestBody = buildRequestBody(messages, actualModel, tools, temperature, maxTokens, stream = true)

        val request = Request.Builder()
            .url("${config.ollamaApiBase}/api/chat")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        val call = client.newCall(request)

        try {
            val response = call.execute()

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                close(OllamaException(response.code, parseError(errorBody)))
                return@callbackFlow
            }

            val inputStream = response.body?.byteStream()
            if (inputStream == null) {
                close(OllamaException(0, "No response body"))
                return@callbackFlow
            }

            val toolCallsAccumulator = mutableMapOf<Int, ToolCallAccumulator>()
            val reader = BufferedReader(InputStreamReader(inputStream))

            reader.useLines { lines ->
                for (line in lines) {
                    if (line.isBlank()) continue

                    try {
                        val chunk = parseStreamChunk(line, toolCallsAccumulator)
                        if (chunk != null) {
                            trySend(chunk)
                        }
                    } catch (e: Exception) {
                        close(OllamaException(0, "Failed to parse stream chunk: ${e.message} - Data: $line"))
                        return@useLines
                    }
                }
            }

            close()
        } catch (e: IOException) {
            close(e)
        }

        awaitClose {
            call.cancel()
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun listModels(config: Configuration): List<ModelInfo> = withContext(Dispatchers.IO) {
        val client = buildClient(config)

        val request = Request.Builder()
            .url("${config.ollamaApiBase}/api/tags")
            .get()
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: throw IOException("Empty response body")

        if (!response.isSuccessful) {
            throw OllamaException(response.code, parseError(body))
        }

        parseModelList(body)
    }

    /**
     * Normalize the model name by removing the "ollama:" prefix if present.
     */
    private fun normalizeModelName(model: String): String {
        return model.removePrefix("ollama:")
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
            put("stream", stream)

            putJsonArray("messages") {
                messages.forEach { msg ->
                    addJsonObject {
                        put("role", msg.role.toApiString())

                        when {
                            msg.role == Role.TOOL -> {
                                put("content", msg.text)
                            }
                            msg.toolCalls != null -> {
                                put("content", msg.text)
                                putJsonArray("tool_calls") {
                                    msg.toolCalls.forEach { tc ->
                                        addJsonObject {
                                            putJsonObject("function") {
                                                put("name", tc.name)
                                                put("arguments", JsonObject(tc.arguments))
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

            putJsonObject("options") {
                temperature?.let { put("temperature", it) }
                maxTokens?.let { put("num_predict", it) }
            }
        }.toString()
    }

    private fun parseResponse(body: String, model: String): Message {
        val jsonResponse = json.parseToJsonElement(body).jsonObject
        val message = jsonResponse["message"]?.jsonObject
            ?: throw OllamaException(500, "No message in response")

        val content = message["content"]?.jsonPrimitive?.contentOrNull ?: ""

        val toolCalls = message["tool_calls"]?.jsonArray?.mapIndexed { index, tc ->
            val function = tc.jsonObject["function"]?.jsonObject
            val argsJson = function?.get("arguments")
            val arguments = when {
                argsJson is JsonObject -> argsJson.toMap()
                argsJson != null -> try {
                    json.parseToJsonElement(argsJson.jsonPrimitive.content).jsonObject.toMap()
                } catch (e: Exception) {
                    emptyMap()
                }
                else -> emptyMap()
            }

            ToolCall(
                id = "call_${index}",
                name = function?.get("name")?.jsonPrimitive?.contentOrNull ?: "",
                arguments = arguments
            )
        }?.takeIf { it.isNotEmpty() }

        // Parse token usage from eval_count fields
        val promptEvalCount = jsonResponse["prompt_eval_count"]?.jsonPrimitive?.intOrNull ?: 0
        val evalCount = jsonResponse["eval_count"]?.jsonPrimitive?.intOrNull ?: 0

        val tokens = if (promptEvalCount > 0 || evalCount > 0) {
            TokenUsage(input = promptEvalCount, output = evalCount)
        } else {
            null
        }

        return Message(
            role = Role.ASSISTANT,
            content = Content.text(content),
            modelId = model,
            toolCalls = toolCalls,
            tokens = tokens
        )
    }

    private data class ToolCallAccumulator(
        var name: String = "",
        var argumentsJson: StringBuilder = StringBuilder()
    )

    @Suppress("UNUSED_PARAMETER")
    private fun parseStreamChunk(
        data: String,
        toolCallsAccumulator: MutableMap<Int, ToolCallAccumulator>
    ): Chunk? {
        val jsonChunk = json.parseToJsonElement(data).jsonObject

        // Check if done
        val done = jsonChunk["done"]?.jsonPrimitive?.booleanOrNull ?: false

        val message = jsonChunk["message"]?.jsonObject
        val content = message?.get("content")?.jsonPrimitive?.contentOrNull ?: ""

        // Handle tool calls in message
        val toolCalls = message?.get("tool_calls")?.jsonArray?.mapIndexed { index, tc ->
            val function = tc.jsonObject["function"]?.jsonObject
            val argsJson = function?.get("arguments")
            val arguments = when {
                argsJson is JsonObject -> argsJson.toMap()
                argsJson != null -> try {
                    json.parseToJsonElement(argsJson.jsonPrimitive.content).jsonObject.toMap()
                } catch (e: Exception) {
                    emptyMap()
                }
                else -> emptyMap()
            }

            ToolCall(
                id = "call_${index}",
                name = function?.get("name")?.jsonPrimitive?.contentOrNull ?: "",
                arguments = arguments
            )
        }?.takeIf { it.isNotEmpty() }

        return if (done) {
            Chunk(finishReason = "stop")
        } else if (content.isNotEmpty() || toolCalls != null) {
            Chunk(
                content = content,
                toolCalls = toolCalls
            )
        } else {
            null
        }
    }

    private fun parseModelList(body: String): List<ModelInfo> {
        val jsonResponse = json.parseToJsonElement(body).jsonObject
        val models = jsonResponse["models"]?.jsonArray ?: return emptyList()

        return models.mapNotNull { item ->
            val obj = item.jsonObject
            val modelName = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val details = obj["details"]?.jsonObject

            // Determine capabilities based on model name/family
            val supportsTools = isToolCapable(modelName, details)
            val supportsVision = isVisionCapable(modelName, details)

            ModelInfo(
                id = modelName,
                provider = slug,
                displayName = modelName,
                supportsTools = supportsTools,
                supportsVision = supportsVision
            )
        }
    }

    /**
     * Determine if a model supports tool calling based on its name/family.
     */
    private fun isToolCapable(modelName: String, details: JsonObject?): Boolean {
        val family = details?.get("family")?.jsonPrimitive?.contentOrNull ?: ""
        val name = modelName.lowercase()

        // Most recent models support tools
        return name.contains("llama3") ||
                name.contains("llama-3") ||
                name.contains("mistral") ||
                name.contains("mixtral") ||
                name.contains("qwen") ||
                name.contains("command-r") ||
                family.contains("llama3") ||
                family.contains("mistral")
    }

    /**
     * Determine if a model supports vision based on its name.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun isVisionCapable(modelName: String, details: JsonObject?): Boolean {
        val name = modelName.lowercase()

        return name.contains("llava") ||
                name.contains("bakllava") ||
                name.contains("vision") ||
                name.contains("moondream")
    }

    private fun parseError(body: String): String {
        return try {
            val jsonError = json.parseToJsonElement(body).jsonObject
            jsonError["error"]?.jsonPrimitive?.contentOrNull ?: "Unknown error"
        } catch (e: Exception) {
            body
        }
    }

    companion object {
        /**
         * Known Ollama model prefixes for auto-detection.
         */
        private val KNOWN_OLLAMA_MODELS = listOf(
            "llama", "mistral", "mixtral", "codellama", "phi",
            "neural-chat", "starling", "vicuna", "orca", "llava",
            "bakllava", "yi", "qwen", "deepseek", "dolphin",
            "nous-hermes", "openchat", "zephyr", "stable", "gemma",
            "command-r", "moondream", "tinyllama", "falcon"
        )

        /**
         * Check if a model ID should be routed to Ollama.
         */
        fun isOllamaModel(modelId: String): Boolean {
            // Explicit prefix
            if (modelId.startsWith("ollama:")) return true

            // Check against known model prefixes
            val lowercaseId = modelId.lowercase()
            return KNOWN_OLLAMA_MODELS.any { lowercaseId.startsWith(it) }
        }
    }
}

class OllamaException(
    statusCode: Int,
    message: String,
    cause: Throwable? = null
) : com.kotlinllm.core.ProviderException(statusCode, "ollama", message, cause)
