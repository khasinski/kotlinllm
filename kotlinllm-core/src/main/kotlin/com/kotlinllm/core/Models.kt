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

/**
 * Well-known model IDs for easy reference.
 *
 * Example:
 * ```kotlin
 * val chat = KotlinLLM.chat(Models.GPT_5)
 * ```
 */
object Models {
    // ==================== OpenAI GPT-5 Series ====================
    /** GPT-5 - OpenAI's flagship model with built-in thinking */
    const val GPT_5 = "gpt-5"
    /** GPT-5.1 - Enhanced reasoning model */
    const val GPT_5_1 = "gpt-5.1"
    /** GPT-5.2 - Latest version with smarter, more precise responses */
    const val GPT_5_2 = "gpt-5.2"
    /** GPT-5 Mini - Smaller, faster variant of GPT-5 */
    const val GPT_5_MINI = "gpt-5-mini"
    /** GPT-5.1 Codex Max - Optimized for agentic coding tasks */
    const val GPT_5_1_CODEX_MAX = "gpt-5.1-codex-max"

    // ==================== OpenAI GPT-4 Series ====================
    /** GPT-4o - Fast multimodal model */
    const val GPT_4O = "gpt-4o"
    /** GPT-4o Mini - Smaller, cheaper GPT-4o variant */
    const val GPT_4O_MINI = "gpt-4o-mini"
    /** GPT-4.1 - Updated GPT-4 variant */
    const val GPT_4_1 = "gpt-4.1"
    /** GPT-4.5 - Preview model between GPT-4 and GPT-5 */
    const val GPT_4_5 = "gpt-4.5"
    /** GPT-4 Turbo - Fast, cost-effective GPT-4 */
    const val GPT_4_TURBO = "gpt-4-turbo"

    // ==================== OpenAI o-Series (Reasoning) ====================
    /** o3 - Smart reasoning model that thinks before responding */
    const val O3 = "o3"
    /** o3-pro - Enhanced o3 with more compute for hard problems */
    const val O3_PRO = "o3-pro"
    /** o3-deep-research - Optimized for deep analysis tasks */
    const val O3_DEEP_RESEARCH = "o3-deep-research"
    /** o4-mini - Fast, cost-efficient reasoning model */
    const val O4_MINI = "o4-mini"
    /** o4-mini-deep-research - Deep research variant */
    const val O4_MINI_DEEP_RESEARCH = "o4-mini-deep-research"
    /** o1 - Original reasoning model */
    const val O1 = "o1"
    /** o1-mini - Smaller o1 variant */
    const val O1_MINI = "o1-mini"

    // ==================== OpenAI Image Generation ====================
    /** GPT-Image-1.5 - Latest and best image generation model */
    const val GPT_IMAGE_1_5 = "gpt-image-1.5"
    /** GPT-Image-1 - Standard image generation model */
    const val GPT_IMAGE_1 = "gpt-image-1"
    /** GPT-Image-1 Mini - Fast, lightweight image generation */
    const val GPT_IMAGE_1_MINI = "gpt-image-1-mini"

    // ==================== Anthropic Claude ====================
    /** Claude Opus 4 - Most capable Claude model */
    const val CLAUDE_OPUS_4 = "claude-opus-4-20250514"
    /** Claude Sonnet 4 - Balanced performance and cost */
    const val CLAUDE_SONNET_4 = "claude-sonnet-4-20250514"
    /** Claude 3.5 Sonnet - Previous generation sonnet */
    const val CLAUDE_3_5_SONNET = "claude-3-5-sonnet-20241022"
    /** Claude 3.5 Haiku - Fast, efficient Claude model */
    const val CLAUDE_3_5_HAIKU = "claude-3-5-haiku-20241022"

    // ==================== Google Gemini ====================
    /** Gemini 2.0 Flash - Fast multimodal model */
    const val GEMINI_2_FLASH = "gemini-2.0-flash"
    /** Gemini 1.5 Pro - Advanced Gemini model */
    const val GEMINI_1_5_PRO = "gemini-1.5-pro"
}
