package com.kotlinllm.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach

/**
 * Represents a conversation with an AI model.
 *
 * This is the main interface for interacting with LLMs in KotlinLLM.
 *
 * Example:
 * ```kotlin
 * // Simple usage
 * val response = KotlinLLM.chat().ask("Hello!")
 *
 * // With configuration
 * val chat = KotlinLLM.chat("claude-sonnet-4-20250514")
 *     .withInstructions("You are a helpful assistant")
 *     .withTool(Calculator())
 *
 * val response = chat.ask("What is 2 + 2?")
 * ```
 */
class Chat(
    private var model: String,
    private var config: Configuration
) {
    private val messages = mutableListOf<Message>()
    private val tools = mutableMapOf<String, Tool>()
    private var temperature: Double? = null
    private var maxTokens: Int? = null

    // Callbacks
    private var onNewMessage: ((Message) -> Unit)? = null
    private var onToolCall: ((ToolCall) -> Unit)? = null
    private var onToolResult: ((String, Any) -> Unit)? = null
    private var onChunk: ((Chunk) -> Unit)? = null

    /**
     * Get all messages in the conversation.
     */
    fun messages(): List<Message> = messages.toList()

    /**
     * Get the current model.
     */
    fun model(): String = model

    /**
     * Get the current temperature setting.
     */
    fun temperature(): Double? = temperature

    /**
     * Get the current max tokens setting.
     */
    fun maxTokens(): Int? = maxTokens

    /**
     * Get the names of registered tools.
     */
    fun toolNames(): List<String> = tools.keys.toList()

    // ==================== Builder Methods ====================

    /**
     * Set the model to use.
     */
    fun withModel(model: String): Chat = apply {
        this.model = model
    }

    /**
     * Add system instructions.
     */
    fun withInstructions(instructions: String, replace: Boolean = false): Chat = apply {
        if (replace) {
            messages.removeAll { it.role == Role.SYSTEM }
        }
        messages.add(0, Message.system(instructions))
    }

    /**
     * Add a tool.
     */
    fun withTool(tool: Tool): Chat = apply {
        tools[tool.name] = tool
    }

    /**
     * Add multiple tools.
     */
    fun withTools(vararg tools: Tool, replace: Boolean = false): Chat = apply {
        if (replace) this.tools.clear()
        tools.forEach { this.tools[it.name] = it }
    }

    /**
     * Set the temperature.
     */
    fun withTemperature(temperature: Double): Chat = apply {
        this.temperature = temperature
    }

    /**
     * Set max tokens.
     */
    fun withMaxTokens(maxTokens: Int): Chat = apply {
        this.maxTokens = maxTokens
    }

    /**
     * Set callback for new messages.
     */
    fun onNewMessage(callback: (Message) -> Unit): Chat = apply {
        this.onNewMessage = callback
    }

    /**
     * Set callback for tool calls.
     */
    fun onToolCall(callback: (ToolCall) -> Unit): Chat = apply {
        this.onToolCall = callback
    }

    /**
     * Set callback for tool results.
     */
    fun onToolResult(callback: (String, Any) -> Unit): Chat = apply {
        this.onToolResult = callback
    }

    /**
     * Set callback for streaming chunks.
     */
    fun onChunk(callback: (Chunk) -> Unit): Chat = apply {
        this.onChunk = callback
    }

    // ==================== Core Methods ====================

    /**
     * Send a message and get a response.
     */
    suspend fun ask(message: String): Message {
        return ask(Content.text(message))
    }

    /**
     * Send content (text + attachments) and get a response.
     */
    suspend fun ask(content: Content): Message {
        addMessage(Message(Role.USER, content))
        return complete()
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
    fun askStreaming(content: Content): Flow<Chunk> = flow {
        addMessage(Message(Role.USER, content))

        val provider = Provider.forModel(model)
        val chunks = mutableListOf<Chunk>()

        provider.stream(
            messages = messages.toList(),
            model = model,
            tools = tools.values.toList(),
            temperature = temperature,
            maxTokens = maxTokens,
            config = config
        ).onEach { chunk ->
            chunks.add(chunk)
            onChunk?.invoke(chunk)
        }.collect { chunk ->
            emit(chunk)
        }

        // Reconstruct the full message from chunks
        val fullContent = chunks.mapNotNull { it.content.takeIf { c -> c.isNotEmpty() } }.joinToString("")
        val toolCalls = chunks.flatMap { it.toolCalls ?: emptyList() }.takeIf { it.isNotEmpty() }

        val response = Message(
            role = Role.ASSISTANT,
            content = Content.text(fullContent),
            modelId = model,
            toolCalls = toolCalls
        )

        addMessage(response)
        onNewMessage?.invoke(response)

        // Handle tool calls if present
        if (response.isToolCall()) {
            handleToolCallsStreaming(response).collect { emit(it) }
        }
    }

    /**
     * Complete the conversation (get next assistant response).
     */
    private suspend fun complete(): Message {
        val provider = Provider.forModel(model)

        val response = provider.complete(
            messages = messages.toList(),
            model = model,
            tools = tools.values.toList(),
            temperature = temperature,
            maxTokens = maxTokens,
            config = config
        )

        addMessage(response)
        onNewMessage?.invoke(response)

        return if (response.isToolCall()) {
            handleToolCalls(response)
        } else {
            response
        }
    }

    /**
     * Handle tool calls from the model.
     */
    private suspend fun handleToolCalls(response: Message): Message {
        val toolCalls = response.toolCalls ?: return response

        for (toolCall in toolCalls) {
            onToolCall?.invoke(toolCall)

            val tool = tools[toolCall.name]
                ?: throw IllegalStateException("Tool not found: ${toolCall.name}")

            val result = tool.execute(toolCall.arguments)
            onToolResult?.invoke(toolCall.name, result)

            addMessage(Message.tool(result.toString(), toolCall.id))
        }

        // Continue conversation after tool execution
        return complete()
    }

    /**
     * Handle tool calls in streaming mode.
     */
    private fun handleToolCallsStreaming(response: Message): Flow<Chunk> = flow {
        val toolCalls = response.toolCalls ?: return@flow

        for (toolCall in toolCalls) {
            onToolCall?.invoke(toolCall)

            val tool = tools[toolCall.name]
                ?: throw IllegalStateException("Tool not found: ${toolCall.name}")

            val result = tool.execute(toolCall.arguments)
            onToolResult?.invoke(toolCall.name, result)

            addMessage(Message.tool(result.toString(), toolCall.id))
        }

        // Continue conversation after tool execution
        val provider = Provider.forModel(model)
        provider.stream(
            messages = messages.toList(),
            model = model,
            tools = tools.values.toList(),
            temperature = temperature,
            maxTokens = maxTokens,
            config = config
        ).collect { emit(it) }
    }

    /**
     * Add a message to the conversation.
     */
    fun addMessage(message: Message): Chat = apply {
        messages.add(message)
    }

    /**
     * Clear all messages.
     */
    fun reset(): Chat = apply {
        messages.clear()
    }

    /**
     * Create a copy of this chat.
     */
    fun fork(): Chat = Chat(model, config).also { copy ->
        copy.messages.addAll(this.messages)
        copy.tools.putAll(this.tools)
        copy.temperature = this.temperature
        copy.maxTokens = this.maxTokens
    }
}

/**
 * Alias for ask() - more conversational.
 */
suspend fun Chat.say(message: String): Message = ask(message)
