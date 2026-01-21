package com.kotlinllm.persistence

import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory implementation of ChatStore.
 *
 * Useful for:
 * - Testing
 * - Prototyping
 * - Short-lived applications
 * - Caching layer
 *
 * Note: Data is lost when the application stops.
 *
 * Example:
 * ```kotlin
 * val store = InMemoryChatStore()
 * val chat = KotlinLLM.chat().withStore(store)
 * ```
 */
class InMemoryChatStore : ChatStore {
    private val chats = ConcurrentHashMap<String, ChatRecord>()

    override suspend fun save(chat: ChatRecord): String {
        val updated = chat.copy(updatedAt = System.currentTimeMillis())
        chats[chat.id] = updated
        return chat.id
    }

    override suspend fun load(id: String): ChatRecord? {
        return chats[id]
    }

    override suspend fun delete(id: String): Boolean {
        return chats.remove(id) != null
    }

    override suspend fun list(limit: Int, offset: Int): List<String> {
        return chats.keys
            .sortedByDescending { chats[it]?.updatedAt ?: 0 }
            .drop(offset)
            .take(limit)
    }

    override suspend fun appendMessage(chatId: String, message: MessageRecord) {
        chats.computeIfPresent(chatId) { _, chat ->
            chat.copy(
                messages = chat.messages + message,
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    override suspend fun loadMessages(chatId: String, limit: Int, offset: Int): List<MessageRecord> {
        return chats[chatId]?.messages
            ?.drop(offset)
            ?.take(limit)
            ?: emptyList()
    }

    /**
     * Clear all stored chats.
     */
    fun clear() {
        chats.clear()
    }

    /**
     * Get the number of stored chats.
     */
    fun size(): Int = chats.size

    /**
     * Check if a chat exists.
     */
    fun contains(id: String): Boolean = chats.containsKey(id)
}
