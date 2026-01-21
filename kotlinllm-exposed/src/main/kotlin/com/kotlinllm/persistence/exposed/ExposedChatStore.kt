package com.kotlinllm.persistence.exposed

import com.kotlinllm.persistence.*
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.Clock
import kotlinx.datetime.toKotlinInstant
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInList
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.Instant

/**
 * JetBrains Exposed implementation of ChatStore.
 *
 * Provides a type-safe, Kotlin-idiomatic way to persist chats.
 *
 * Example:
 * ```kotlin
 * // Setup database
 * Database.connect("jdbc:h2:mem:test", driver = "org.h2.Driver")
 * transaction { createKotlinLLMTables() }
 *
 * // Use the store
 * val store = ExposedChatStore()
 * val chat = PersistedChat.create(store, model = "gpt-4o")
 * ```
 */
class ExposedChatStore : ChatStore {

    override suspend fun save(chat: ChatRecord): String = dbQuery {
        val now = Clock.System.now()
        val existingChat = Chats.select { Chats.id eq chat.id }.singleOrNull()

        if (existingChat != null) {
            // Update existing
            Chats.update({ Chats.id eq chat.id }) {
                it[model] = chat.model
                it[instructions] = chat.instructions
                it[temperature] = chat.temperature
                it[maxTokens] = chat.maxTokens
                it[toolNames] = chat.toolNames
                it[metadata] = chat.metadata
                it[updatedAt] = now
            }

            // Sync messages
            syncMessages(chat.id, chat.messages)
        } else {
            // Insert new
            Chats.insert {
                it[id] = chat.id
                it[model] = chat.model
                it[instructions] = chat.instructions
                it[temperature] = chat.temperature
                it[maxTokens] = chat.maxTokens
                it[toolNames] = chat.toolNames
                it[metadata] = chat.metadata
                it[createdAt] = Instant.ofEpochMilli(chat.createdAt).toKotlinInstant()
                it[updatedAt] = now
            }

            // Insert messages
            chat.messages.forEach { message ->
                insertMessage(chat.id, message)
            }
        }

        chat.id
    }

    override suspend fun load(id: String): ChatRecord? = dbQuery {
        val chatRow = Chats.select { Chats.id eq id }.singleOrNull()
            ?: return@dbQuery null

        val messages = Messages
            .select { Messages.chatId eq id }
            .orderBy(Messages.createdAt, SortOrder.ASC)
            .map { it.toMessageRecord() }

        chatRow.toChatRecord(messages)
    }

    override suspend fun delete(id: String): Boolean = dbQuery {
        val deleted = Chats.deleteWhere { Chats.id eq id }
        deleted > 0
    }

    override suspend fun list(limit: Int, offset: Int): List<String> = dbQuery {
        Chats
            .slice(Chats.id)
            .selectAll()
            .orderBy(Chats.updatedAt, SortOrder.DESC)
            .limit(limit, offset.toLong())
            .map { it[Chats.id].value }
    }

    override suspend fun appendMessage(chatId: String, message: MessageRecord): Unit = dbQuery {
        insertMessage(chatId, message)

        Chats.update({ Chats.id eq chatId }) {
            it[updatedAt] = Clock.System.now()
        }
        Unit
    }

    override suspend fun loadMessages(chatId: String, limit: Int, offset: Int): List<MessageRecord> = dbQuery {
        Messages
            .select { Messages.chatId eq chatId }
            .orderBy(Messages.createdAt, SortOrder.ASC)
            .limit(limit, offset.toLong())
            .map { it.toMessageRecord() }
    }

    /**
     * Search chats by model.
     */
    suspend fun findByModel(model: String): List<ChatRecord> = dbQuery {
        Chats
            .select { Chats.model eq model }
            .orderBy(Chats.updatedAt, SortOrder.DESC)
            .map { row ->
                val messages = Messages
                    .select { Messages.chatId eq row[Chats.id].value }
                    .orderBy(Messages.createdAt, SortOrder.ASC)
                    .map { it.toMessageRecord() }
                row.toChatRecord(messages)
            }
    }

    /**
     * Count total chats.
     */
    suspend fun count(): Long = dbQuery {
        Chats.selectAll().count()
    }

    /**
     * Count messages for a chat.
     */
    suspend fun countMessages(chatId: String): Long = dbQuery {
        Messages.select { Messages.chatId eq chatId }.count()
    }

    /**
     * Delete chats older than a given timestamp.
     */
    suspend fun deleteOlderThan(timestamp: Instant): Int = dbQuery {
        Chats.deleteWhere {
            updatedAt less timestamp.toKotlinInstant()
        }
    }

    /**
     * Get chats updated after a given timestamp.
     */
    suspend fun findUpdatedAfter(timestamp: Instant, limit: Int = 100): List<ChatRecord> = dbQuery {
        Chats
            .select { Chats.updatedAt greater timestamp.toKotlinInstant() }
            .orderBy(Chats.updatedAt, SortOrder.ASC)
            .limit(limit)
            .map { row ->
                val messages = Messages
                    .select { Messages.chatId eq row[Chats.id].value }
                    .orderBy(Messages.createdAt, SortOrder.ASC)
                    .map { it.toMessageRecord() }
                row.toChatRecord(messages)
            }
    }

    private fun insertMessage(chatId: String, message: MessageRecord) {
        Messages.insert {
            it[id] = message.id
            it[Messages.chatId] = chatId
            it[role] = message.role
            it[content] = message.content
            it[modelId] = message.modelId
            it[toolCalls] = message.toolCalls
            it[toolCallId] = message.toolCallId
            it[inputTokens] = message.inputTokens
            it[outputTokens] = message.outputTokens
            it[createdAt] = Instant.ofEpochMilli(message.createdAt).toKotlinInstant()
        }
    }

    private fun syncMessages(chatId: String, messages: List<MessageRecord>) {
        val existingIds = Messages
            .slice(Messages.id)
            .select { Messages.chatId eq chatId }
            .map { it[Messages.id].value }
            .toSet()

        val newIds = messages.map { it.id }.toSet()

        // Delete removed messages
        if (newIds.isNotEmpty()) {
            Messages.deleteWhere {
                (Messages.chatId eq chatId) and (Messages.id notInList newIds.toList())
            }
        } else {
            Messages.deleteWhere { Messages.chatId eq chatId }
        }

        // Insert new messages
        messages.filter { it.id !in existingIds }.forEach { message ->
            insertMessage(chatId, message)
        }
    }

    private suspend fun <T> dbQuery(block: suspend Transaction.() -> T): T {
        return newSuspendedTransaction(Dispatchers.IO) { block() }
    }
}
