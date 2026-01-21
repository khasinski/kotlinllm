package com.kotlinllm.dsl

import com.kotlinllm.KotlinLLM
import com.kotlinllm.core.*
import kotlinx.coroutines.flow.Flow

/**
 * Kotlin DSL for elegant chat interactions.
 *
 * Example:
 * ```kotlin
 * val response = chat("claude-sonnet-4-20250514") {
 *     system("You are a helpful assistant")
 *     tools(Calculator(), WebSearch())
 *     temperature(0.7)
 * }.ask("Hello!")
 * ```
 */
@DslMarker
annotation class ChatDsl

/**
 * Chat builder for DSL usage.
 */
@ChatDsl
class ChatBuilder(private val model: String) {
    private val config = KotlinLLM.config()
    private var systemPrompt: String? = null
    private val tools = mutableListOf<Tool>()
    private var temperature: Double? = null
    private var maxTokens: Int? = null

    /**
     * Set system instructions.
     */
    fun system(instructions: String) {
        systemPrompt = instructions
    }

    /**
     * Add a tool.
     */
    fun tool(tool: Tool) {
        tools.add(tool)
    }

    /**
     * Add multiple tools.
     */
    fun tools(vararg tools: Tool) {
        this.tools.addAll(tools)
    }

    /**
     * Set temperature.
     */
    fun temperature(value: Double) {
        temperature = value
    }

    /**
     * Set max tokens.
     */
    fun maxTokens(value: Int) {
        maxTokens = value
    }

    /**
     * Build the chat instance.
     */
    fun build(): Chat {
        return KotlinLLM.chat(model).apply {
            systemPrompt?.let { withInstructions(it) }
            tools.forEach { withTool(it) }
            temperature?.let { withTemperature(it) }
            maxTokens?.let { withMaxTokens(it) }
        }
    }
}

/**
 * Create a chat using DSL syntax.
 *
 * ```kotlin
 * val chat = chat("gpt-4o") {
 *     system("You are helpful")
 *     temperature(0.7)
 * }
 * ```
 */
fun chat(model: String = KotlinLLM.config().defaultModel, block: ChatBuilder.() -> Unit = {}): Chat {
    return ChatBuilder(model).apply(block).build()
}

/**
 * Quick one-shot chat using DSL.
 *
 * ```kotlin
 * val response = ask("What is 2+2?") {
 *     model("gpt-4o")
 *     system("You are a math tutor")
 * }
 * ```
 */
@ChatDsl
class QuickChatBuilder {
    var model: String = KotlinLLM.config().defaultModel
    private var systemPrompt: String? = null
    private val tools = mutableListOf<Tool>()
    private var temperature: Double? = null

    fun model(value: String) {
        model = value
    }

    fun system(instructions: String) {
        systemPrompt = instructions
    }

    fun tool(tool: Tool) {
        tools.add(tool)
    }

    fun tools(vararg tools: Tool) {
        this.tools.addAll(tools)
    }

    fun temperature(value: Double) {
        temperature = value
    }

    internal fun build(): Chat {
        return KotlinLLM.chat(model).apply {
            systemPrompt?.let { withInstructions(it) }
            tools.forEach { withTool(it) }
            temperature?.let { withTemperature(it) }
        }
    }
}

/**
 * Quick one-shot ask with DSL configuration.
 *
 * ```kotlin
 * val answer = ask("What is the capital of France?") {
 *     model("claude-sonnet-4-20250514")
 * }
 * ```
 */
suspend fun ask(message: String, block: QuickChatBuilder.() -> Unit = {}): Message {
    return QuickChatBuilder().apply(block).build().ask(message)
}

/**
 * Quick streaming ask.
 */
fun askStreaming(message: String, block: QuickChatBuilder.() -> Unit = {}): Flow<Chunk> {
    return QuickChatBuilder().apply(block).build().askStreaming(message)
}

// ==================== Tool DSL ====================

/**
 * Create a simple tool using DSL.
 *
 * ```kotlin
 * val calculator = tool("calculator", "Performs arithmetic") {
 *     param("expression", "Mathematical expression")
 *
 *     execute { args ->
 *         val expr = args.string("expression")
 *         // ... calculate
 *         "Result: $result"
 *     }
 * }
 * ```
 */
@ChatDsl
class ToolBuilder(private val name: String, private val description: String) {
    private val parameters = mutableMapOf<String, ParameterDef>()
    private var executeBlock: (suspend (Map<String, kotlinx.serialization.json.JsonElement>) -> Any)? = null

    fun param(name: String, description: String, type: String = "string", required: Boolean = true) {
        parameters[name] = ParameterDef(type, description, required)
    }

    fun execute(block: suspend (Map<String, kotlinx.serialization.json.JsonElement>) -> Any) {
        executeBlock = block
    }

    fun build(): Tool {
        val params = parameters.toMap()
        val executor = executeBlock ?: throw IllegalStateException("execute block is required")

        return object : Tool(name, description) {
            init {
                params.forEach { (name, def) ->
                    registerParameter(name, def)
                }
            }

            override suspend fun execute(args: Map<String, kotlinx.serialization.json.JsonElement>): Any {
                return executor(args)
            }
        }
    }
}

/**
 * Create a tool using DSL syntax.
 */
fun tool(name: String, description: String, block: ToolBuilder.() -> Unit): Tool {
    return ToolBuilder(name, description).apply(block).build()
}

// ==================== Conversation DSL ====================

/**
 * Run a conversation using DSL.
 *
 * ```kotlin
 * conversation("gpt-4o") {
 *     system("You are helpful")
 *
 *     user("Hello!")
 *     val response = assistant() // Gets AI response
 *     println(response)
 *
 *     user("Tell me more")
 *     val more = assistant()
 * }
 * ```
 */
@ChatDsl
class ConversationScope(private val chat: Chat) {
    /**
     * Add a user message.
     */
    fun user(message: String) {
        chat.addMessage(Message.user(message))
    }

    /**
     * Get assistant response (calls the API).
     */
    suspend fun assistant(): Message {
        val provider = Provider.forModel(chat.model())
        val response = provider.complete(
            messages = chat.messages(),
            model = chat.model(),
            tools = emptyList(),
            config = KotlinLLM.config()
        )
        chat.addMessage(response)
        return response
    }

    /**
     * Stream assistant response.
     */
    fun assistantStreaming(): Flow<Chunk> {
        return chat.askStreaming("")
    }

    /**
     * Add system instructions.
     */
    fun system(instructions: String) {
        chat.withInstructions(instructions)
    }
}

/**
 * Run a conversation with DSL.
 */
suspend fun conversation(
    model: String = KotlinLLM.config().defaultModel,
    block: suspend ConversationScope.() -> Unit
) {
    val chat = KotlinLLM.chat(model)
    ConversationScope(chat).block()
}
