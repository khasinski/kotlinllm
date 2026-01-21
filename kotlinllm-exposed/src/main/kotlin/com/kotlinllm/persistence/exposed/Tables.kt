package com.kotlinllm.persistence.exposed

import com.kotlinllm.persistence.*
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.json.json
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import kotlinx.datetime.Instant
import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toKotlinInstant

/**
 * Exposed table definition for Chats.
 */
object Chats : IdTable<String>("kotlinllm_chats") {
    override val id = varchar("id", 36).entityId()
    val model = varchar("model", 100)
    val instructions = text("instructions").nullable()
    val temperature = double("temperature").nullable()
    val maxTokens = integer("max_tokens").nullable()
    val toolNames = json<List<String>>("tool_names", Json.Default)
    val metadata = json<Map<String, String>>("metadata", Json.Default)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

/**
 * Exposed table definition for Messages.
 */
object Messages : IdTable<String>("kotlinllm_messages") {
    override val id = varchar("id", 36).entityId()
    val chatId = reference("chat_id", Chats, onDelete = ReferenceOption.CASCADE)
    val role = varchar("role", 20)
    val content = text("content")
    val modelId = varchar("model_id", 100).nullable()
    val toolCalls = json<List<ToolCallRecord>>("tool_calls", Json.Default).nullable()
    val toolCallId = varchar("tool_call_id", 100).nullable()
    val inputTokens = integer("input_tokens").nullable()
    val outputTokens = integer("output_tokens").nullable()
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}

// ==================== Row Mappers ====================

/**
 * Convert a ResultRow to ChatRecord.
 */
fun ResultRow.toChatRecord(messages: List<MessageRecord> = emptyList()): ChatRecord {
    return ChatRecord(
        id = this[Chats.id].value,
        model = this[Chats.model],
        instructions = this[Chats.instructions],
        temperature = this[Chats.temperature],
        maxTokens = this[Chats.maxTokens],
        toolNames = this[Chats.toolNames],
        metadata = this[Chats.metadata],
        messages = messages,
        createdAt = this[Chats.createdAt].toJavaInstant().toEpochMilli(),
        updatedAt = this[Chats.updatedAt].toJavaInstant().toEpochMilli()
    )
}

/**
 * Convert a ResultRow to MessageRecord.
 */
fun ResultRow.toMessageRecord(): MessageRecord {
    return MessageRecord(
        id = this[Messages.id].value,
        role = this[Messages.role],
        content = this[Messages.content],
        modelId = this[Messages.modelId],
        toolCalls = this[Messages.toolCalls],
        toolCallId = this[Messages.toolCallId],
        inputTokens = this[Messages.inputTokens],
        outputTokens = this[Messages.outputTokens],
        createdAt = this[Messages.createdAt].toJavaInstant().toEpochMilli()
    )
}

// ==================== Schema Creation ====================

/**
 * Create the KotlinLLM tables.
 *
 * Call this at application startup:
 * ```kotlin
 * transaction {
 *     createKotlinLLMTables()
 * }
 * ```
 */
fun createKotlinLLMTables() {
    SchemaUtils.create(Chats, Messages)
}

/**
 * Drop the KotlinLLM tables.
 */
fun dropKotlinLLMTables() {
    SchemaUtils.drop(Messages, Chats)
}
