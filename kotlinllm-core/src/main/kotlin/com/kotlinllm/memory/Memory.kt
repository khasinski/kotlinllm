package com.kotlinllm.memory

import com.kotlinllm.core.Message
import com.kotlinllm.core.Role

/**
 * Memory interface for managing conversation context.
 *
 * Memory implementations control how messages are stored and retrieved
 * for providing context to LLM requests.
 *
 * Example:
 * ```kotlin
 * val memory = BufferMemory(maxMessages = 20)
 * val chat = KotlinLLM.chat("gpt-4o").withMemory(memory)
 *
 * // Memory automatically manages context
 * chat.ask("Hello!")
 * chat.ask("What did I just say?")
 * ```
 */
interface Memory {
    /**
     * Add a message to memory.
     */
    suspend fun add(message: Message)

    /**
     * Add multiple messages to memory.
     */
    suspend fun addAll(messages: List<Message>)

    /**
     * Get messages to include in the context for the next request.
     *
     * This may return a subset of all messages based on the memory strategy.
     */
    suspend fun getContextMessages(): List<Message>

    /**
     * Get all messages stored in memory.
     */
    suspend fun getAllMessages(): List<Message>

    /**
     * Clear all messages from memory.
     */
    suspend fun clear()

    /**
     * Get memory statistics.
     */
    fun stats(): MemoryStats
}

/**
 * Statistics about memory usage.
 */
data class MemoryStats(
    val totalMessages: Int,
    val contextMessages: Int,
    val estimatedTokens: Int,
    val memoryType: String
)

/**
 * Buffer memory that keeps the last N messages.
 *
 * Simple sliding window approach - keeps the most recent messages.
 *
 * Example:
 * ```kotlin
 * val memory = BufferMemory(maxMessages = 20)
 * chat.withMemory(memory)
 * ```
 */
class BufferMemory(
    private val maxMessages: Int = 20,
    private val preserveSystemMessage: Boolean = true
) : Memory {

    private val messages = mutableListOf<Message>()

    override suspend fun add(message: Message) {
        messages.add(message)
        trim()
    }

    override suspend fun addAll(messages: List<Message>) {
        this.messages.addAll(messages)
        trim()
    }

    override suspend fun getContextMessages(): List<Message> {
        return messages.toList()
    }

    override suspend fun getAllMessages(): List<Message> {
        return messages.toList()
    }

    override suspend fun clear() {
        messages.clear()
    }

    override fun stats(): MemoryStats {
        return MemoryStats(
            totalMessages = messages.size,
            contextMessages = messages.size,
            estimatedTokens = estimateTokens(messages),
            memoryType = "BufferMemory(max=$maxMessages)"
        )
    }

    private fun trim() {
        if (messages.size <= maxMessages) return

        if (preserveSystemMessage) {
            // Keep system message if present
            val systemMsg = messages.find { it.role == Role.SYSTEM }
            val nonSystemMessages = messages.filter { it.role != Role.SYSTEM }

            val toKeep = nonSystemMessages.takeLast(maxMessages - (if (systemMsg != null) 1 else 0))

            messages.clear()
            systemMsg?.let { messages.add(it) }
            messages.addAll(toKeep)
        } else {
            val toRemove = messages.size - maxMessages
            repeat(toRemove) { messages.removeAt(0) }
        }
    }
}

/**
 * Window memory that keeps the last N conversation turns (user + assistant pairs).
 *
 * A turn consists of a user message and its corresponding assistant response.
 *
 * Example:
 * ```kotlin
 * val memory = WindowMemory(maxTurns = 10)
 * chat.withMemory(memory)
 * ```
 */
class WindowMemory(
    private val maxTurns: Int = 10,
    private val preserveSystemMessage: Boolean = true
) : Memory {

    private val messages = mutableListOf<Message>()

    override suspend fun add(message: Message) {
        messages.add(message)
        trim()
    }

    override suspend fun addAll(messages: List<Message>) {
        this.messages.addAll(messages)
        trim()
    }

    override suspend fun getContextMessages(): List<Message> {
        return messages.toList()
    }

    override suspend fun getAllMessages(): List<Message> {
        return messages.toList()
    }

    override suspend fun clear() {
        messages.clear()
    }

    override fun stats(): MemoryStats {
        val turns = countTurns()
        return MemoryStats(
            totalMessages = messages.size,
            contextMessages = messages.size,
            estimatedTokens = estimateTokens(messages),
            memoryType = "WindowMemory(maxTurns=$maxTurns, currentTurns=$turns)"
        )
    }

    private fun countTurns(): Int {
        return messages.count { it.role == Role.USER }
    }

    private fun trim() {
        val currentTurns = countTurns()
        if (currentTurns <= maxTurns) return

        val turnsToRemove = currentTurns - maxTurns
        var removedTurns = 0

        val iterator = messages.iterator()
        while (iterator.hasNext() && removedTurns < turnsToRemove) {
            val msg = iterator.next()
            if (msg.role == Role.SYSTEM && preserveSystemMessage) continue

            iterator.remove()
            if (msg.role == Role.USER) {
                removedTurns++
            }
        }

        // Remove any orphaned assistant/tool messages at the start
        while (messages.isNotEmpty() &&
            messages.first().role != Role.SYSTEM &&
            messages.first().role != Role.USER) {
            messages.removeAt(0)
        }
    }
}

/**
 * Token-limited memory that keeps messages within a token budget.
 *
 * Estimates tokens and removes oldest messages when the limit is exceeded.
 *
 * Example:
 * ```kotlin
 * val memory = TokenMemory(maxTokens = 8000)
 * chat.withMemory(memory)
 * ```
 */
class TokenMemory(
    private val maxTokens: Int = 4000,
    private val preserveSystemMessage: Boolean = true,
    private val tokenEstimator: (Message) -> Int = { estimateMessageTokens(it) }
) : Memory {

    private val messages = mutableListOf<Message>()

    override suspend fun add(message: Message) {
        messages.add(message)
        trim()
    }

    override suspend fun addAll(messages: List<Message>) {
        this.messages.addAll(messages)
        trim()
    }

    override suspend fun getContextMessages(): List<Message> {
        return messages.toList()
    }

    override suspend fun getAllMessages(): List<Message> {
        return messages.toList()
    }

    override suspend fun clear() {
        messages.clear()
    }

    override fun stats(): MemoryStats {
        val currentTokens = messages.sumOf { tokenEstimator(it) }
        return MemoryStats(
            totalMessages = messages.size,
            contextMessages = messages.size,
            estimatedTokens = currentTokens,
            memoryType = "TokenMemory(max=$maxTokens, current=$currentTokens)"
        )
    }

    private fun trim() {
        var currentTokens = messages.sumOf { tokenEstimator(it) }
        if (currentTokens <= maxTokens) return

        // Keep system message if needed
        val systemMsg = if (preserveSystemMessage) {
            messages.find { it.role == Role.SYSTEM }
        } else null

        // Remove oldest non-system messages until under budget
        while (currentTokens > maxTokens && messages.size > (if (systemMsg != null) 1 else 0)) {
            val index = messages.indexOfFirst { it.role != Role.SYSTEM }
            if (index == -1) break

            currentTokens -= tokenEstimator(messages[index])
            messages.removeAt(index)
        }
    }
}

/**
 * Summary memory that summarizes old messages to save tokens.
 *
 * When messages exceed the threshold, older messages are summarized
 * using the LLM and replaced with a summary message.
 *
 * Note: This requires an LLM call to generate summaries.
 *
 * Example:
 * ```kotlin
 * val memory = SummaryMemory(
 *     maxMessages = 20,
 *     summaryThreshold = 15,
 *     summarizer = { messages ->
 *         // Call LLM to summarize
 *         llm.ask("Summarize: ${messages.map { it.text }}")
 *     }
 * )
 * chat.withMemory(memory)
 * ```
 */
class SummaryMemory(
    private val maxMessages: Int = 20,
    private val summaryThreshold: Int = 15,
    private val preserveSystemMessage: Boolean = true,
    private val summarizer: suspend (List<Message>) -> String
) : Memory {

    private val messages = mutableListOf<Message>()
    private var summary: String? = null
    private var summarizedMessageCount = 0

    override suspend fun add(message: Message) {
        messages.add(message)
        checkSummarize()
    }

    override suspend fun addAll(messages: List<Message>) {
        this.messages.addAll(messages)
        checkSummarize()
    }

    override suspend fun getContextMessages(): List<Message> {
        val result = mutableListOf<Message>()

        // Add system message first if present
        messages.find { it.role == Role.SYSTEM }?.let { result.add(it) }

        // Add summary if present
        summary?.let {
            result.add(Message.system("[Previous conversation summary: $it]"))
        }

        // Add non-system messages
        result.addAll(messages.filter { it.role != Role.SYSTEM })

        return result
    }

    override suspend fun getAllMessages(): List<Message> {
        return messages.toList()
    }

    override suspend fun clear() {
        messages.clear()
        summary = null
        summarizedMessageCount = 0
    }

    override fun stats(): MemoryStats {
        return MemoryStats(
            totalMessages = messages.size + summarizedMessageCount,
            contextMessages = messages.size + (if (summary != null) 1 else 0),
            estimatedTokens = estimateTokens(messages) + (summary?.length?.div(4) ?: 0),
            memoryType = "SummaryMemory(summarized=$summarizedMessageCount)"
        )
    }

    private suspend fun checkSummarize() {
        val nonSystemMessages = messages.filter { it.role != Role.SYSTEM }

        if (nonSystemMessages.size >= summaryThreshold) {
            // Summarize oldest messages
            val toSummarize = nonSystemMessages.take(nonSystemMessages.size - maxMessages + 5)
            if (toSummarize.isNotEmpty()) {
                val newSummary = summarizer(toSummarize)

                // Combine with existing summary if present
                summary = if (summary != null) {
                    "$summary\n$newSummary"
                } else {
                    newSummary
                }

                // Remove summarized messages
                summarizedMessageCount += toSummarize.size
                toSummarize.forEach { messages.remove(it) }
            }
        }
    }
}

/**
 * Composite memory that chains multiple memory strategies.
 *
 * Messages flow through all memory implementations in order.
 *
 * Example:
 * ```kotlin
 * val memory = CompositeMemory(
 *     TokenMemory(maxTokens = 8000),
 *     BufferMemory(maxMessages = 50)
 * )
 * ```
 */
class CompositeMemory(
    private vararg val memories: Memory
) : Memory {

    override suspend fun add(message: Message) {
        memories.forEach { it.add(message) }
    }

    override suspend fun addAll(messages: List<Message>) {
        memories.forEach { it.addAll(messages) }
    }

    override suspend fun getContextMessages(): List<Message> {
        // Use the first memory's context
        return memories.firstOrNull()?.getContextMessages() ?: emptyList()
    }

    override suspend fun getAllMessages(): List<Message> {
        return memories.firstOrNull()?.getAllMessages() ?: emptyList()
    }

    override suspend fun clear() {
        memories.forEach { it.clear() }
    }

    override fun stats(): MemoryStats {
        val primary = memories.firstOrNull()?.stats()
        return primary ?: MemoryStats(0, 0, 0, "CompositeMemory(empty)")
    }
}

// ==================== Utility Functions ====================

/**
 * Estimate tokens for a list of messages.
 */
internal fun estimateTokens(messages: List<Message>): Int {
    return messages.sumOf { estimateMessageTokens(it) }
}

/**
 * Estimate tokens for a single message.
 *
 * Rough approximation: ~4 characters per token + overhead.
 */
internal fun estimateMessageTokens(message: Message): Int {
    val textTokens = message.text.length / 4
    val overhead = 4 // Role, formatting, etc.
    return textTokens + overhead
}
