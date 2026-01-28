package com.kotlinllm.memory

import com.kotlinllm.core.Chat
import com.kotlinllm.core.Chunk
import com.kotlinllm.core.Content
import com.kotlinllm.core.Message
import com.kotlinllm.core.Tool
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart

/**
 * A Chat wrapper that integrates memory management.
 *
 * Automatically syncs messages between the chat and memory.
 *
 * Example:
 * ```kotlin
 * val chat = KotlinLLM.chat("gpt-4o")
 *     .withMemory(BufferMemory(maxMessages = 20))
 *
 * // Memory is automatically managed
 * chat.ask("Hello!")
 * chat.ask("What did I just say?")
 *
 * // Check memory stats
 * println(chat.memoryStats())
 * ```
 */
class MemoryChat(
    private val chat: Chat,
    private val memory: Memory
) {
    /**
     * Get the underlying chat instance.
     */
    fun unwrap(): Chat = chat

    /**
     * Get the memory instance.
     */
    fun memory(): Memory = memory

    /**
     * Get memory statistics.
     */
    fun memoryStats(): MemoryStats = memory.stats()

    // ==================== Builder Methods (delegate to chat) ====================

    fun withModel(model: String): MemoryChat = apply { chat.withModel(model) }

    fun withInstructions(instructions: String, replace: Boolean = false): MemoryChat = apply {
        chat.withInstructions(instructions, replace)
    }

    fun withTool(tool: Tool): MemoryChat = apply { chat.withTool(tool) }

    fun withTools(vararg tools: Tool, replace: Boolean = false): MemoryChat = apply {
        chat.withTools(*tools, replace = replace)
    }

    fun withTemperature(temperature: Double): MemoryChat = apply {
        chat.withTemperature(temperature)
    }

    fun withMaxTokens(maxTokens: Int): MemoryChat = apply {
        chat.withMaxTokens(maxTokens)
    }

    fun onNewMessage(callback: (Message) -> Unit): MemoryChat = apply {
        chat.onNewMessage(callback)
    }

    fun onChunk(callback: (Chunk) -> Unit): MemoryChat = apply {
        chat.onChunk(callback)
    }

    // ==================== Core Methods ====================

    /**
     * Send a message and get a response.
     */
    suspend fun ask(message: String): Message {
        return ask(Content.text(message))
    }

    /**
     * Send content and get a response.
     */
    suspend fun ask(content: Content): Message {
        // Sync memory to chat before asking
        syncMemoryToChat()

        // Ask and get response
        val response = chat.ask(content)

        // Update memory with new messages
        syncChatToMemory()

        return response
    }

    /**
     * Send a message and stream the response.
     */
    fun askStreaming(message: String): Flow<Chunk> {
        return askStreaming(Content.text(message))
    }

    /**
     * Send content and stream the response.
     */
    fun askStreaming(content: Content): Flow<Chunk> {
        // Note: For streaming, we need to sync before and after
        return chat.askStreaming(content)
            .onStart {
                syncMemoryToChat()
            }
            .onCompletion {
                syncChatToMemory()
            }
    }

    /**
     * Get all messages from the chat.
     */
    fun messages(): List<Message> = chat.messages()

    /**
     * Get the current model.
     */
    fun model(): String = chat.model()

    /**
     * Clear all messages and memory.
     */
    suspend fun reset(): MemoryChat = apply {
        chat.reset()
        memory.clear()
    }

    /**
     * Create a copy of this chat with the same memory.
     */
    fun fork(): MemoryChat = MemoryChat(chat.fork(), memory)

    // ==================== Memory Sync ====================

    private suspend fun syncMemoryToChat() {
        // Get context messages from memory
        val contextMessages = memory.getContextMessages()

        // Clear chat and add context messages
        chat.reset()
        contextMessages.forEach { chat.addMessage(it) }
    }

    private suspend fun syncChatToMemory() {
        // Get current chat messages
        val chatMessages = chat.messages()

        // Find new messages not in memory
        val memoryMessages = memory.getAllMessages()
        val newMessages = chatMessages.filter { chatMsg ->
            memoryMessages.none { it.text == chatMsg.text && it.role == chatMsg.role }
        }

        // Add new messages to memory
        memory.addAll(newMessages)
    }
}

// ==================== Extension Functions ====================

/**
 * Add memory to a chat.
 *
 * Example:
 * ```kotlin
 * val chat = KotlinLLM.chat("gpt-4o")
 *     .withMemory(BufferMemory(maxMessages = 20))
 * ```
 */
fun Chat.withMemory(memory: Memory): MemoryChat = MemoryChat(this, memory)

/**
 * Add memory to a chat using DSL.
 *
 * Example:
 * ```kotlin
 * val chat = KotlinLLM.chat("gpt-4o").withMemory {
 *     buffer(20)
 * }
 * ```
 */
fun Chat.withMemory(block: MemoryBuilder.() -> Unit): MemoryChat {
    val builder = MemoryBuilder()
    builder.block()
    return MemoryChat(this, builder.build())
}

/**
 * DSL builder for memory configuration.
 */
class MemoryBuilder {
    private var memory: Memory? = null

    /**
     * Use buffer memory with a maximum number of messages.
     */
    fun buffer(maxMessages: Int = 20, preserveSystemMessage: Boolean = true) {
        memory = BufferMemory(maxMessages, preserveSystemMessage)
    }

    /**
     * Use window memory with a maximum number of conversation turns.
     */
    fun window(maxTurns: Int = 10, preserveSystemMessage: Boolean = true) {
        memory = WindowMemory(maxTurns, preserveSystemMessage)
    }

    /**
     * Use token-limited memory.
     */
    fun tokenLimited(maxTokens: Int = 4000, preserveSystemMessage: Boolean = true) {
        memory = TokenMemory(maxTokens, preserveSystemMessage)
    }

    /**
     * Use summary memory with a custom summarizer.
     */
    fun summary(
        maxMessages: Int = 20,
        summaryThreshold: Int = 15,
        preserveSystemMessage: Boolean = true,
        summarizer: suspend (List<Message>) -> String
    ) {
        memory = SummaryMemory(maxMessages, summaryThreshold, preserveSystemMessage, summarizer)
    }

    /**
     * Use a custom memory implementation.
     */
    fun custom(memory: Memory) {
        this.memory = memory
    }

    internal fun build(): Memory = memory ?: BufferMemory()
}
