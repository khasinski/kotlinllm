package com.kotlinllm.persistence.jpa

import com.kotlinllm.persistence.*
import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * JPA implementation of ChatStore.
 *
 * Works with any JPA provider (Hibernate, EclipseLink, etc.).
 *
 * Example with plain JPA:
 * ```kotlin
 * val emf = Persistence.createEntityManagerFactory("my-unit")
 * val store = JpaChatStore(emf)
 * ```
 *
 * Example with Spring:
 * ```kotlin
 * @Configuration
 * class ChatStoreConfig {
 *     @Bean
 *     fun chatStore(emf: EntityManagerFactory) = JpaChatStore(emf)
 * }
 * ```
 */
class JpaChatStore(
    private val entityManagerFactory: EntityManagerFactory
) : ChatStore {

    override suspend fun save(chat: ChatRecord): String = withContext(Dispatchers.IO) {
        withTransaction { em ->
            val existingEntity = em.find(ChatEntity::class.java, chat.id)

            if (existingEntity != null) {
                // Update existing
                existingEntity.model = chat.model
                existingEntity.instructions = chat.instructions
                existingEntity.temperature = chat.temperature
                existingEntity.maxTokens = chat.maxTokens
                existingEntity.toolNames = Json.encodeToString(ListSerializer(String.serializer()), chat.toolNames)
                existingEntity.metadata = Json.encodeToString(MapSerializer(String.serializer(), String.serializer()), chat.metadata)
                existingEntity.updatedAt = Instant.now()

                // Sync messages
                syncMessages(em, existingEntity, chat.messages)

                em.merge(existingEntity)
            } else {
                // Create new
                val entity = ChatEntity.fromRecord(chat)
                em.persist(entity)

                // Add messages
                chat.messages.forEach { messageRecord ->
                    val messageEntity = MessageEntity.fromRecord(messageRecord, entity)
                    entity.messages.add(messageEntity)
                    em.persist(messageEntity)
                }
            }

            chat.id
        }
    }

    override suspend fun load(id: String): ChatRecord? = withContext(Dispatchers.IO) {
        withEntityManager { em ->
            val entity = em.find(ChatEntity::class.java, id)
            entity?.toRecord()
        }
    }

    override suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        withTransaction { em ->
            val entity = em.find(ChatEntity::class.java, id)
            if (entity != null) {
                em.remove(entity)
                true
            } else {
                false
            }
        }
    }

    override suspend fun list(limit: Int, offset: Int): List<String> = withContext(Dispatchers.IO) {
        withEntityManager { em ->
            em.createQuery(
                "SELECT c.id FROM ChatEntity c ORDER BY c.updatedAt DESC",
                String::class.java
            )
                .setFirstResult(offset)
                .setMaxResults(limit)
                .resultList
        }
    }

    override suspend fun appendMessage(chatId: String, message: MessageRecord): Unit = withContext(Dispatchers.IO) {
        withTransaction { em ->
            val chatEntity = em.find(ChatEntity::class.java, chatId)
                ?: throw IllegalArgumentException("Chat not found: $chatId")

            val messageEntity = MessageEntity.fromRecord(message, chatEntity)
            chatEntity.messages.add(messageEntity)
            chatEntity.updatedAt = Instant.now()

            em.persist(messageEntity)
            em.merge(chatEntity)
            Unit
        }
    }

    override suspend fun loadMessages(chatId: String, limit: Int, offset: Int): List<MessageRecord> =
        withContext(Dispatchers.IO) {
            withEntityManager { em ->
                em.createQuery(
                    """
                    SELECT m FROM MessageEntity m
                    WHERE m.chat.id = :chatId
                    ORDER BY m.createdAt ASC
                    """.trimIndent(),
                    MessageEntity::class.java
                )
                    .setParameter("chatId", chatId)
                    .setFirstResult(offset)
                    .setMaxResults(limit)
                    .resultList
                    .map { it.toRecord() }
            }
        }

    /**
     * Search chats by metadata.
     */
    suspend fun findByMetadata(key: String, value: String): List<ChatRecord> = withContext(Dispatchers.IO) {
        withEntityManager { em ->
            // Note: This uses JSON path syntax, works with Postgres/MySQL 8+
            // For other databases, you may need to adjust
            em.createQuery(
                """
                SELECT c FROM ChatEntity c
                WHERE c.metadata LIKE :pattern
                ORDER BY c.updatedAt DESC
                """.trimIndent(),
                ChatEntity::class.java
            )
                .setParameter("pattern", "%\"$key\":\"$value\"%")
                .resultList
                .map { it.toRecord() }
        }
    }

    /**
     * Count total chats.
     */
    suspend fun count(): Long = withContext(Dispatchers.IO) {
        withEntityManager { em ->
            em.createQuery("SELECT COUNT(c) FROM ChatEntity c", Long::class.java)
                .singleResult
        }
    }

    /**
     * Delete all chats older than a given timestamp.
     */
    suspend fun deleteOlderThan(timestamp: Instant): Int = withContext(Dispatchers.IO) {
        withTransaction { em ->
            em.createQuery("DELETE FROM ChatEntity c WHERE c.updatedAt < :timestamp")
                .setParameter("timestamp", timestamp)
                .executeUpdate()
        }
    }

    private fun syncMessages(em: EntityManager, entity: ChatEntity, messages: List<MessageRecord>) {
        val existingIds = entity.messages.map { it.id }.toSet()
        val newIds = messages.map { it.id }.toSet()

        // Remove deleted messages
        entity.messages.removeAll { it.id !in newIds }

        // Add new messages
        messages.filter { it.id !in existingIds }.forEach { messageRecord ->
            val messageEntity = MessageEntity.fromRecord(messageRecord, entity)
            entity.messages.add(messageEntity)
            em.persist(messageEntity)
        }
    }

    private fun <T> withEntityManager(block: (EntityManager) -> T): T {
        val em = entityManagerFactory.createEntityManager()
        return try {
            block(em)
        } finally {
            em.close()
        }
    }

    private fun <T> withTransaction(block: (EntityManager) -> T): T {
        val em = entityManagerFactory.createEntityManager()
        val tx = em.transaction
        return try {
            tx.begin()
            val result = block(em)
            tx.commit()
            result
        } catch (e: Exception) {
            if (tx.isActive) tx.rollback()
            throw e
        } finally {
            em.close()
        }
    }
}
