package com.kotlinllm.persistence

import com.kotlinllm.core.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.time.Instant
import java.util.UUID

/**
 * Interface for persisting chat conversations.
 *
 * Implement this interface to store chats in your preferred storage system
 * (database, file system, cloud storage, etc.).
 *
 * Example:
 * ```kotlin
 * class MyChatStore : ChatStore {
 *     override suspend fun save(chat: ChatRecord): String {
 *         // Save to your database
 *     }
 * }
 *
 * val chat = KotlinLLM.chat()
 *     .withStore(MyChatStore())
 *
 * chat.ask("Hello!")  // Automatically persisted
 * ```
 */
interface ChatStore {
    /**
     * Save a chat record. Returns the chat ID.
     */
    suspend fun save(chat: ChatRecord): String

    /**
     * Load a chat by ID. Returns null if not found.
     */
    suspend fun load(id: String): ChatRecord?

    /**
     * Delete a chat by ID. Returns true if deleted.
     */
    suspend fun delete(id: String): Boolean

    /**
     * List all chat IDs, optionally with pagination.
     */
    suspend fun list(limit: Int = 100, offset: Int = 0): List<String>

    /**
     * Append a message to an existing chat.
     * More efficient than loading, modifying, and saving the entire chat.
     */
    suspend fun appendMessage(chatId: String, message: MessageRecord)

    /**
     * Load messages for a chat with pagination.
     * Useful for loading conversation history incrementally.
     */
    suspend fun loadMessages(chatId: String, limit: Int = 100, offset: Int = 0): List<MessageRecord>
}

/**
 * Persisted representation of a chat conversation.
 */
@Serializable
data class ChatRecord(
    val id: String = UUID.randomUUID().toString(),
    val model: String,
    val instructions: String? = null,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val toolNames: List<String> = emptyList(),
    val messages: List<MessageRecord> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun createdAtInstant(): Instant = Instant.ofEpochMilli(createdAt)
    fun updatedAtInstant(): Instant = Instant.ofEpochMilli(updatedAt)
}

/**
 * Persisted representation of a message.
 */
@Serializable
data class MessageRecord(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    val content: String,
    val modelId: String? = null,
    val toolCalls: List<ToolCallRecord>? = null,
    val toolCallId: String? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun createdAtInstant(): Instant = Instant.ofEpochMilli(createdAt)
}

/**
 * Persisted representation of a tool call.
 */
@Serializable
data class ToolCallRecord(
    val id: String,
    val name: String,
    val arguments: Map<String, JsonElement>
)

// ==================== Conversion Extensions ====================

/**
 * Convert a Chat to a ChatRecord for persistence.
 */
fun Chat.toRecord(
    id: String = UUID.randomUUID().toString(),
    metadata: Map<String, String> = emptyMap()
): ChatRecord {
    val systemMessage = messages().find { it.role == Role.SYSTEM }

    return ChatRecord(
        id = id,
        model = model(),
        instructions = systemMessage?.text,
        temperature = temperature(),
        maxTokens = maxTokens(),
        toolNames = toolNames(),
        messages = messages().filter { it.role != Role.SYSTEM }.map { it.toRecord() },
        metadata = metadata
    )
}

/**
 * Convert a Message to a MessageRecord.
 */
fun Message.toRecord(): MessageRecord {
    return MessageRecord(
        role = role.toApiString(),
        content = text,
        modelId = modelId,
        toolCalls = toolCalls?.map { it.toRecord() },
        toolCallId = toolCallId,
        inputTokens = tokens?.input,
        outputTokens = tokens?.output
    )
}

/**
 * Convert a ToolCall to a ToolCallRecord.
 */
fun ToolCall.toRecord(): ToolCallRecord {
    return ToolCallRecord(
        id = id,
        name = name,
        arguments = arguments
    )
}

/**
 * Convert a MessageRecord back to a Message.
 */
fun MessageRecord.toMessage(): Message {
    return Message(
        role = Role.fromString(role),
        content = Content.text(content),
        modelId = modelId,
        toolCalls = toolCalls?.map { it.toToolCall() },
        toolCallId = toolCallId,
        tokens = if (inputTokens != null || outputTokens != null) {
            TokenUsage(input = inputTokens ?: 0, output = outputTokens ?: 0)
        } else null
    )
}

/**
 * Convert a ToolCallRecord back to a ToolCall.
 */
fun ToolCallRecord.toToolCall(): ToolCall {
    return ToolCall(
        id = id,
        name = name,
        arguments = arguments
    )
}
