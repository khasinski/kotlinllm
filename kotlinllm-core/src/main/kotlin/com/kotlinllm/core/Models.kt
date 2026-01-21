package com.kotlinllm.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Represents a role in a conversation.
 */
enum class Role {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL;

    fun toApiString(): String = name.lowercase()

    companion object {
        fun fromString(value: String): Role = when (value.lowercase()) {
            "system" -> SYSTEM
            "user" -> USER
            "assistant" -> ASSISTANT
            "tool" -> TOOL
            else -> throw IllegalArgumentException("Unknown role: $value")
        }
    }
}

/**
 * Represents a message in a conversation.
 */
data class Message(
    val role: Role,
    val content: Content,
    val modelId: String? = null,
    val toolCalls: List<ToolCall>? = null,
    val toolCallId: String? = null,
    val tokens: TokenUsage? = null
) {
    constructor(role: Role, text: String) : this(role, Content.text(text))

    val text: String get() = content.text ?: ""

    fun isToolCall(): Boolean = !toolCalls.isNullOrEmpty()
    fun isToolResult(): Boolean = !toolCallId.isNullOrBlank()

    companion object {
        fun system(text: String) = Message(Role.SYSTEM, text)
        fun user(text: String) = Message(Role.USER, text)
        fun assistant(text: String) = Message(Role.ASSISTANT, text)
        fun tool(content: String, toolCallId: String) = Message(
            Role.TOOL,
            Content.text(content),
            toolCallId = toolCallId
        )
    }
}

/**
 * Content of a message - can be text, images, or mixed.
 */
sealed class Content {
    abstract val text: String?
    abstract val attachments: List<Attachment>

    data class Text(override val text: String) : Content() {
        override val attachments: List<Attachment> = emptyList()
    }

    data class Mixed(
        override val text: String?,
        override val attachments: List<Attachment>
    ) : Content()

    companion object {
        fun text(value: String): Content = Text(value)
        fun mixed(text: String?, attachments: List<Attachment>): Content = Mixed(text, attachments)
    }
}

/**
 * Attachment types for multimodal content.
 */
sealed class Attachment {
    data class Image(val url: String?, val base64: String?, val mediaType: String?) : Attachment()
    data class File(val url: String?, val base64: String?, val mediaType: String?) : Attachment()
}

/**
 * A tool call requested by the model.
 */
@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: Map<String, JsonElement>
)

/**
 * Token usage statistics.
 */
data class TokenUsage(
    val input: Int = 0,
    val output: Int = 0,
    val cached: Int = 0,
    val total: Int = input + output
) {
    operator fun plus(other: TokenUsage): TokenUsage = TokenUsage(
        input = input + other.input,
        output = output + other.output,
        cached = cached + other.cached
    )
}

/**
 * A streaming chunk from the model.
 */
data class Chunk(
    val content: String = "",
    val toolCalls: List<ToolCall>? = null,
    val finishReason: String? = null
)

/**
 * Model information.
 */
data class ModelInfo(
    val id: String,
    val provider: String,
    val displayName: String? = null,
    val contextWindow: Int? = null,
    val maxOutputTokens: Int? = null,
    val supportsTools: Boolean = true,
    val supportsVision: Boolean = false,
    val supportsStreaming: Boolean = true
)
