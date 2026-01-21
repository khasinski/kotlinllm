package com.kotlinllm.persistence.jpa

import com.kotlinllm.persistence.*
import jakarta.persistence.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * JPA Entity for Chat.
 *
 * Usage with Spring Data JPA:
 * ```kotlin
 * interface ChatEntityRepository : JpaRepository<ChatEntity, String>
 *
 * @Service
 * class JpaChatStore(
 *     private val chatRepo: ChatEntityRepository,
 *     private val messageRepo: MessageEntityRepository
 * ) : ChatStore {
 *     // ... implement methods
 * }
 * ```
 */
@Entity
@Table(name = "kotlinllm_chats")
class ChatEntity(
    @Id
    @Column(length = 36)
    var id: String = "",

    @Column(nullable = false)
    var model: String = "",

    @Column(columnDefinition = "TEXT")
    var instructions: String? = null,

    var temperature: Double? = null,

    var maxTokens: Int? = null,

    @Column(columnDefinition = "TEXT")
    var toolNames: String = "[]",

    @Column(columnDefinition = "TEXT")
    var metadata: String = "{}",

    @Column(nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(nullable = false)
    var updatedAt: Instant = Instant.now(),

    @OneToMany(mappedBy = "chat", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("createdAt ASC")
    var messages: MutableList<MessageEntity> = mutableListOf()
) {
    fun toRecord(): ChatRecord {
        return ChatRecord(
            id = id,
            model = model,
            instructions = instructions,
            temperature = temperature,
            maxTokens = maxTokens,
            toolNames = Json.decodeFromString(toolNames),
            metadata = Json.decodeFromString(metadata),
            messages = messages.map { it.toRecord() },
            createdAt = createdAt.toEpochMilli(),
            updatedAt = updatedAt.toEpochMilli()
        )
    }

    companion object {
        fun fromRecord(record: ChatRecord): ChatEntity {
            return ChatEntity(
                id = record.id,
                model = record.model,
                instructions = record.instructions,
                temperature = record.temperature,
                maxTokens = record.maxTokens,
                toolNames = Json.encodeToString(record.toolNames),
                metadata = Json.encodeToString(record.metadata),
                createdAt = record.createdAtInstant(),
                updatedAt = record.updatedAtInstant()
            )
        }
    }
}

/**
 * JPA Entity for Message.
 */
@Entity
@Table(name = "kotlinllm_messages")
class MessageEntity(
    @Id
    @Column(length = 36)
    var id: String = "",

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chat_id", nullable = false)
    var chat: ChatEntity? = null,

    @Column(nullable = false, length = 20)
    var role: String = "",

    @Column(columnDefinition = "TEXT", nullable = false)
    var content: String = "",

    @Column(length = 100)
    var modelId: String? = null,

    @Column(columnDefinition = "TEXT")
    var toolCalls: String? = null,

    @Column(length = 100)
    var toolCallId: String? = null,

    var inputTokens: Int? = null,

    var outputTokens: Int? = null,

    @Column(nullable = false)
    var createdAt: Instant = Instant.now()
) {
    fun toRecord(): MessageRecord {
        return MessageRecord(
            id = id,
            role = role,
            content = content,
            modelId = modelId,
            toolCalls = toolCalls?.let { Json.decodeFromString(it) },
            toolCallId = toolCallId,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            createdAt = createdAt.toEpochMilli()
        )
    }

    companion object {
        fun fromRecord(record: MessageRecord, chat: ChatEntity): MessageEntity {
            return MessageEntity(
                id = record.id,
                chat = chat,
                role = record.role,
                content = record.content,
                modelId = record.modelId,
                toolCalls = record.toolCalls?.let { Json.encodeToString(it) },
                toolCallId = record.toolCallId,
                inputTokens = record.inputTokens,
                outputTokens = record.outputTokens,
                createdAt = record.createdAtInstant()
            )
        }
    }
}
