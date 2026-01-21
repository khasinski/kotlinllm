package com.kotlinllm.persistence

import com.kotlinllm.KotlinLLM
import com.kotlinllm.core.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion

/**
 * A chat that automatically persists to a ChatStore.
 *
 * This wraps a regular Chat and automatically saves messages after each interaction.
 *
 * Example:
 * ```kotlin
 * val store = FileChatStore(Path.of("./data"))
 *
 * // Create new persisted chat
 * val chat = PersistedChat.create(store, model = "gpt-4o")
 * chat.ask("Hello!")  // Automatically saved
 *
 * // Load existing chat
 * val loaded = PersistedChat.load(store, "chat-id-123")
 * loaded?.ask("Continue our conversation")
 * ```
 */
class PersistedChat private constructor(
    private val store: ChatStore,
    private val chat: Chat,
    val id: String,
    private var metadata: Map<String, String> = emptyMap()
) {
    /**
     * Get all messages in the conversation.
     */
    fun messages(): List<Message> = chat.messages()

    /**
     * Get the current model.
     */
    fun model(): String = chat.model()

    /**
     * Get metadata.
     */
    fun metadata(): Map<String, String> = metadata

    // ==================== Builder Methods ====================

    /**
     * Set the model to use.
     */
    fun withModel(model: String): PersistedChat = apply {
        chat.withModel(model)
    }

    /**
     * Add system instructions.
     */
    fun withInstructions(instructions: String, replace: Boolean = false): PersistedChat = apply {
        chat.withInstructions(instructions, replace)
    }

    /**
     * Add a tool.
     */
    fun withTool(tool: Tool): PersistedChat = apply {
        chat.withTool(tool)
    }

    /**
     * Add multiple tools.
     */
    fun withTools(vararg tools: Tool, replace: Boolean = false): PersistedChat = apply {
        chat.withTools(*tools, replace = replace)
    }

    /**
     * Set the temperature.
     */
    fun withTemperature(temperature: Double): PersistedChat = apply {
        chat.withTemperature(temperature)
    }

    /**
     * Set max tokens.
     */
    fun withMaxTokens(maxTokens: Int): PersistedChat = apply {
        chat.withMaxTokens(maxTokens)
    }

    /**
     * Add or update metadata.
     */
    fun withMetadata(key: String, value: String): PersistedChat = apply {
        metadata = metadata + (key to value)
    }

    /**
     * Replace all metadata.
     */
    fun withMetadata(metadata: Map<String, String>): PersistedChat = apply {
        this.metadata = metadata
    }

    // ==================== Callbacks ====================

    fun onNewMessage(callback: (Message) -> Unit): PersistedChat = apply {
        chat.onNewMessage(callback)
    }

    fun onToolCall(callback: (ToolCall) -> Unit): PersistedChat = apply {
        chat.onToolCall(callback)
    }

    fun onToolResult(callback: (String, Any) -> Unit): PersistedChat = apply {
        chat.onToolResult(callback)
    }

    fun onChunk(callback: (Chunk) -> Unit): PersistedChat = apply {
        chat.onChunk(callback)
    }

    // ==================== Core Methods ====================

    /**
     * Send a message and get a response.
     * Automatically persists after completion.
     */
    suspend fun ask(message: String): Message {
        val response = chat.ask(message)
        save()
        return response
    }

    /**
     * Send content and get a response.
     */
    suspend fun ask(content: Content): Message {
        val response = chat.ask(content)
        save()
        return response
    }

    /**
     * Send a message and stream the response.
     * Automatically persists after streaming completes.
     */
    fun askStreaming(message: String): Flow<Chunk> {
        return chat.askStreaming(message).onCompletion {
            save()
        }
    }

    /**
     * Manually save the current state.
     */
    suspend fun save() {
        store.save(toRecord())
    }

    /**
     * Delete this chat from the store.
     */
    suspend fun delete(): Boolean {
        return store.delete(id)
    }

    /**
     * Convert to a ChatRecord.
     */
    fun toRecord(): ChatRecord {
        return chat.toRecord(id = id, metadata = metadata)
    }

    /**
     * Get the underlying Chat (use with caution - changes won't auto-persist).
     */
    fun unwrap(): Chat = chat

    companion object {
        /**
         * Create a new persisted chat.
         */
        suspend fun create(
            store: ChatStore,
            model: String = KotlinLLM.config().defaultModel,
            id: String = java.util.UUID.randomUUID().toString(),
            metadata: Map<String, String> = emptyMap()
        ): PersistedChat {
            val chat = KotlinLLM.chat(model)
            val persisted = PersistedChat(store, chat, id, metadata)
            persisted.save()
            return persisted
        }

        /**
         * Load an existing chat from the store.
         */
        suspend fun load(
            store: ChatStore,
            id: String,
            toolRegistry: Map<String, Tool> = emptyMap()
        ): PersistedChat? {
            val record = store.load(id) ?: return null
            return fromRecord(store, record, toolRegistry)
        }

        /**
         * Load or create a chat.
         */
        suspend fun loadOrCreate(
            store: ChatStore,
            id: String,
            model: String = KotlinLLM.config().defaultModel,
            toolRegistry: Map<String, Tool> = emptyMap()
        ): PersistedChat {
            return load(store, id, toolRegistry) ?: create(store, model, id)
        }

        /**
         * Create from a ChatRecord.
         */
        fun fromRecord(
            store: ChatStore,
            record: ChatRecord,
            toolRegistry: Map<String, Tool> = emptyMap()
        ): PersistedChat {
            val chat = KotlinLLM.chat(record.model)

            // Restore instructions
            record.instructions?.let { chat.withInstructions(it) }

            // Restore temperature
            record.temperature?.let { chat.withTemperature(it) }

            // Restore max tokens
            record.maxTokens?.let { chat.withMaxTokens(it) }

            // Restore tools from registry
            record.toolNames.forEach { toolName ->
                toolRegistry[toolName]?.let { chat.withTool(it) }
            }

            // Restore messages
            record.messages.forEach { messageRecord ->
                chat.addMessage(messageRecord.toMessage())
            }

            return PersistedChat(store, chat, record.id, record.metadata)
        }

        /**
         * List all chat IDs in the store.
         */
        suspend fun list(store: ChatStore, limit: Int = 100, offset: Int = 0): List<String> {
            return store.list(limit, offset)
        }
    }
}

// ==================== Extension Functions ====================

/**
 * Make a regular Chat persisted.
 */
suspend fun Chat.persisted(
    store: ChatStore,
    id: String = java.util.UUID.randomUUID().toString(),
    metadata: Map<String, String> = emptyMap()
): PersistedChat {
    val persisted = PersistedChat.fromRecord(
        store,
        this.toRecord(id, metadata),
        emptyMap()
    )
    persisted.save()
    return persisted
}
