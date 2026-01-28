@file:JvmName("JavaLLM")

package com.kotlinllm

import com.kotlinllm.core.*
import com.kotlinllm.memory.*
import com.kotlinllm.structured.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.function.Consumer
import kotlin.reflect.KClass

/**
 * Java-friendly API for KotlinLLM.
 *
 * Example:
 * ```java
 * // Configure
 * KotlinLLM.configure(config -> {
 *     config.setOpenaiApiKey(System.getenv("OPENAI_API_KEY"));
 *     return Unit.INSTANCE;
 * });
 *
 * // Simple chat
 * JavaChat chat = JavaLLM.chat("gpt-4o");
 * Message response = chat.ask("Hello!");
 * System.out.println(response.getText());
 *
 * // Fluent building
 * JavaChat chat = JavaLLM.chat("gpt-4o")
 *     .withInstructions("You are a helpful assistant")
 *     .withTemperature(0.7);
 *
 * // Async with CompletableFuture
 * chat.askAsync("Hello!")
 *     .thenAccept(msg -> System.out.println(msg.getText()));
 * ```
 */
object JavaLLM {
    internal val executor = Executors.newCachedThreadPool()

    // ==================== Chat Creation ====================

    /**
     * Create a new chat with the default model.
     */
    @JvmStatic
    fun chat(): JavaChat = JavaChat(KotlinLLM.chat())

    /**
     * Create a new chat with a specific model.
     */
    @JvmStatic
    fun chat(model: String): JavaChat = JavaChat(KotlinLLM.chat(model))

    // ==================== Quick One-Shot API ====================

    /**
     * Quick one-shot chat (blocking).
     */
    @JvmStatic
    fun quickChat(message: String): String = runBlocking {
        KotlinLLM.chat().ask(message).text
    }

    /**
     * Quick one-shot chat with specific model (blocking).
     */
    @JvmStatic
    fun quickChat(message: String, model: String): String = runBlocking {
        KotlinLLM.chat(model).ask(message).text
    }

    /**
     * Quick one-shot chat (async).
     */
    @JvmStatic
    fun quickChatAsync(message: String): CompletableFuture<String> {
        return CompletableFuture.supplyAsync({
            runBlocking { KotlinLLM.chat().ask(message).text }
        }, executor)
    }

    /**
     * Quick one-shot chat with specific model (async).
     */
    @JvmStatic
    fun quickChatAsync(message: String, model: String): CompletableFuture<String> {
        return CompletableFuture.supplyAsync({
            runBlocking { KotlinLLM.chat(model).ask(message).text }
        }, executor)
    }

    // ==================== Builder Helpers ====================

    /**
     * Create a tool builder for Java.
     */
    @JvmStatic
    fun toolBuilder(name: String, description: String): JavaToolBuilder = JavaToolBuilder(name, description)
}

/**
 * Java-friendly chat wrapper.
 *
 * Wraps the Kotlin Chat class and provides blocking/async methods
 * that can be called directly from Java without coroutine handling.
 *
 * Example:
 * ```java
 * JavaChat chat = JavaLLM.chat("gpt-4o")
 *     .withInstructions("You are helpful")
 *     .withTemperature(0.7);
 *
 * Message response = chat.ask("Hello!");
 * System.out.println(response.getText());
 * ```
 */
class JavaChat internal constructor(private val chat: Chat) {

    // ==================== Configuration ====================

    /**
     * Set system instructions.
     */
    fun withInstructions(instructions: String): JavaChat = apply {
        chat.withInstructions(instructions)
    }

    /**
     * Add a tool.
     */
    fun withTool(tool: Tool): JavaChat = apply {
        chat.withTool(tool)
    }

    /**
     * Set temperature.
     */
    fun withTemperature(temperature: Double): JavaChat = apply {
        chat.withTemperature(temperature)
    }

    /**
     * Set max tokens.
     */
    fun withMaxTokens(maxTokens: Int): JavaChat = apply {
        chat.withMaxTokens(maxTokens)
    }

    // ==================== Blocking API ====================

    /**
     * Send a message and get a response (blocking).
     */
    fun ask(message: String): Message = runBlocking {
        chat.ask(message)
    }

    /**
     * Alias for ask().
     */
    fun say(message: String): Message = ask(message)

    /**
     * Stream a response with a callback for each chunk (blocking).
     */
    fun askStreaming(message: String, onChunk: Consumer<Chunk>) {
        runBlocking {
            chat.askStreaming(message).collect { chunk ->
                onChunk.accept(chunk)
            }
        }
    }

    /**
     * Collect all streaming chunks into a list (blocking).
     */
    fun collectStream(message: String): List<Chunk> = runBlocking {
        chat.askStreaming(message).toList()
    }

    // ==================== Async API ====================

    /**
     * Send a message and get a response (async).
     */
    fun askAsync(message: String): CompletableFuture<Message> {
        return CompletableFuture.supplyAsync({
            runBlocking { chat.ask(message) }
        }, JavaLLM.executor)
    }

    /**
     * Stream a response with a callback (async).
     */
    fun askStreamingAsync(message: String, onChunk: Consumer<Chunk>): CompletableFuture<Void> {
        return CompletableFuture.runAsync({
            runBlocking {
                chat.askStreaming(message).collect { chunk ->
                    onChunk.accept(chunk)
                }
            }
        }, JavaLLM.executor)
    }

    // ==================== Accessors ====================

    /**
     * Get the conversation history.
     */
    fun getMessages(): List<Message> = chat.messages()

    /**
     * Get total tokens used (sum of all message tokens).
     */
    fun getTotalTokens(): Int = chat.messages().sumOf {
        (it.tokens?.total ?: 0)
    }

    /**
     * Get the underlying Kotlin Chat (for advanced use).
     */
    fun unwrap(): Chat = chat

    // ==================== Memory Integration ====================

    /**
     * Add memory to this chat.
     *
     * Example:
     * ```java
     * JavaMemoryChat memoryChat = JavaLLM.chat("gpt-4o")
     *     .withMemory(JavaMemory.buffer(20));
     * ```
     */
    fun withMemory(memory: Memory): JavaMemoryChat = JavaMemoryChat(chat, memory)

    // ==================== Structured Output ====================

    /**
     * Request a structured response using a Kotlin serializer.
     *
     * This is the low-level method that accepts a KSerializer directly.
     * For Java, prefer using askStructured with a Class parameter.
     */
    fun <T : Any> askStructured(
        prompt: String,
        serializer: KSerializer<T>,
        config: JavaStructuredConfig = JavaStructuredConfig()
    ): JavaStructuredResult<T> = runBlocking {
        try {
            val structuredConfig = StructuredConfig(
                maxRetries = config.maxRetries,
                temperature = config.temperature,
                maxTokens = config.maxTokens
            )

            val result = chat.executeStructuredRequestWithSerializer(prompt, serializer, structuredConfig)

            if (result.isSuccess && result.value != null) {
                JavaStructuredResult.success(result.value, result.rawResponse)
            } else {
                JavaStructuredResult.failure(result.error ?: "Unknown error", result.rawResponse)
            }
        } catch (e: Exception) {
            JavaStructuredResult.failure(e.message ?: "Exception during structured request", null)
        }
    }

    /**
     * Request a structured response, throwing on failure.
     *
     * @throws IllegalStateException if parsing fails
     */
    fun <T : Any> askTyped(
        prompt: String,
        serializer: KSerializer<T>,
        config: JavaStructuredConfig = JavaStructuredConfig()
    ): T {
        return askStructured(prompt, serializer, config).getValueOrThrow()
    }

    /**
     * Async version of askStructured.
     */
    fun <T : Any> askTypedAsync(
        prompt: String,
        serializer: KSerializer<T>,
        config: JavaStructuredConfig = JavaStructuredConfig()
    ): CompletableFuture<T> {
        return CompletableFuture.supplyAsync({ askTyped(prompt, serializer, config) }, JavaLLM.executor)
    }

    /**
     * Request a structured response (async).
     */
    fun <T : Any> askStructuredAsync(
        prompt: String,
        serializer: KSerializer<T>,
        config: JavaStructuredConfig = JavaStructuredConfig()
    ): CompletableFuture<JavaStructuredResult<T>> {
        return CompletableFuture.supplyAsync({ askStructured(prompt, serializer, config) }, JavaLLM.executor)
    }

    /**
     * Get the model used by this chat.
     */
    fun getModel(): String = chat.model()

    /**
     * Add document content as context.
     *
     * Example:
     * ```java
     * JavaChat chat = JavaLLM.chat("gpt-4o")
     *     .withDocumentContext("Title", "This is the document content...");
     * chat.ask("Summarize this document");
     * ```
     */
    fun withDocumentContext(title: String?, content: String): JavaChat = apply {
        val context = buildString {
            appendLine("[Document Context]")
            title?.let { appendLine("Title: $it") }
            appendLine()
            appendLine("Content:")
            appendLine(content)
            appendLine("[End Document Context]")
        }
        chat.withInstructions(context, replace = false)
    }

    /**
     * Add multiple document contents as context.
     */
    fun withDocumentsContext(documents: List<Pair<String?, String>>): JavaChat = apply {
        if (documents.isEmpty()) return@apply

        val context = buildString {
            appendLine("[Documents Context - ${documents.size} document(s)]")
            appendLine()

            documents.forEachIndexed { index, (title, content) ->
                appendLine("--- Document ${index + 1} ---")
                title?.let { appendLine("Title: $it") }
                appendLine()
                appendLine(content)
                appendLine()
            }

            appendLine("[End Documents Context]")
        }
        chat.withInstructions(context, replace = false)
    }
}

/**
 * Java-friendly tool builder.
 */
class JavaToolBuilder(private val name: String, private val description: String) {
    private val parameters = mutableMapOf<String, ParameterDef>()
    private var executor: JavaToolExecutor? = null

    fun param(name: String, description: String): JavaToolBuilder = apply {
        parameters[name] = ParameterDef("string", description, true)
    }

    fun param(name: String, description: String, type: String): JavaToolBuilder = apply {
        parameters[name] = ParameterDef(type, description, true)
    }

    fun param(name: String, description: String, type: String, required: Boolean): JavaToolBuilder = apply {
        parameters[name] = ParameterDef(type, description, required)
    }

    fun execute(executor: JavaToolExecutor): JavaToolBuilder = apply {
        this.executor = executor
    }

    fun build(): Tool {
        val params = parameters.toMap()
        val exec = executor ?: throw IllegalStateException("Executor is required")

        return object : Tool(name, description) {
            init {
                params.forEach { (name, def) ->
                    registerParameter(name, def)
                }
            }

            override suspend fun execute(args: Map<String, kotlinx.serialization.json.JsonElement>): Any {
                return exec.execute(JavaToolArgs(args))
            }
        }
    }
}

/**
 * Functional interface for tool execution in Java.
 */
fun interface JavaToolExecutor {
    fun execute(args: JavaToolArgs): Any
}

/**
 * Java-friendly wrapper for tool arguments.
 */
class JavaToolArgs(private val args: Map<String, kotlinx.serialization.json.JsonElement>) {
    fun getString(key: String): String = args.string(key)
    fun getStringOrNull(key: String): String? = args.stringOrNull(key)
    fun getInt(key: String): Int = args.int(key)
    fun getIntOrNull(key: String): Int? = args.intOrNull(key)
    fun getDouble(key: String): Double = args.double(key)
    fun getDoubleOrNull(key: String): Double? = args.doubleOrNull(key)
    fun getBoolean(key: String): Boolean = args.boolean(key)
    fun getBooleanOrNull(key: String): Boolean? = args.booleanOrNull(key)
}

// ==================== Memory Support ====================

/**
 * Java-friendly memory wrapper.
 *
 * Example:
 * ```java
 * // Buffer memory (keeps last N messages)
 * Memory memory = JavaMemory.buffer(20);
 *
 * // Window memory (keeps last N turns)
 * Memory memory = JavaMemory.window(10);
 *
 * // Token memory (keeps messages within token budget)
 * Memory memory = JavaMemory.tokenLimited(4000);
 *
 * // Use with chat
 * JavaChat chat = JavaLLM.chat("gpt-4o")
 *     .withMemory(memory);
 * ```
 */
object JavaMemory {
    /**
     * Create a buffer memory that keeps the last N messages.
     */
    @JvmStatic
    fun buffer(maxMessages: Int): Memory = BufferMemory(maxMessages)

    /**
     * Create a buffer memory with system message preservation.
     */
    @JvmStatic
    fun buffer(maxMessages: Int, preserveSystemMessage: Boolean): Memory =
        BufferMemory(maxMessages, preserveSystemMessage)

    /**
     * Create a window memory that keeps the last N conversation turns.
     */
    @JvmStatic
    fun window(maxTurns: Int): Memory = WindowMemory(maxTurns)

    /**
     * Create a window memory with system message preservation.
     */
    @JvmStatic
    fun window(maxTurns: Int, preserveSystemMessage: Boolean): Memory =
        WindowMemory(maxTurns, preserveSystemMessage)

    /**
     * Create a token-limited memory.
     */
    @JvmStatic
    fun tokenLimited(maxTokens: Int): Memory = TokenMemory(maxTokens)

    /**
     * Create a token-limited memory with system message preservation.
     */
    @JvmStatic
    fun tokenLimited(maxTokens: Int, preserveSystemMessage: Boolean): Memory =
        TokenMemory(maxTokens, preserveSystemMessage)
}

/**
 * Java-friendly chat with memory support.
 *
 * Example:
 * ```java
 * JavaMemoryChat chat = JavaLLM.chat("gpt-4o")
 *     .withMemory(JavaMemory.buffer(20));
 *
 * // Messages are automatically tracked
 * chat.ask("Hello!");
 * chat.ask("What did I just say?"); // Memory provides context
 *
 * // Access memory stats
 * MemoryStats stats = chat.getMemoryStats();
 * System.out.println("Messages: " + stats.getTotalMessages());
 * ```
 */
class JavaMemoryChat internal constructor(
    private val chat: Chat,
    private val memory: Memory
) {
    // ==================== Configuration ====================

    /**
     * Set system instructions.
     */
    fun withInstructions(instructions: String): JavaMemoryChat = apply {
        chat.withInstructions(instructions)
    }

    /**
     * Add a tool.
     */
    fun withTool(tool: Tool): JavaMemoryChat = apply {
        chat.withTool(tool)
    }

    /**
     * Set temperature.
     */
    fun withTemperature(temperature: Double): JavaMemoryChat = apply {
        chat.withTemperature(temperature)
    }

    /**
     * Set max tokens.
     */
    fun withMaxTokens(maxTokens: Int): JavaMemoryChat = apply {
        chat.withMaxTokens(maxTokens)
    }

    // ==================== Blocking API ====================

    /**
     * Send a message and get a response (blocking).
     * Messages are automatically added to memory.
     */
    fun ask(message: String): Message = runBlocking {
        // Add user message to memory
        memory.add(Message.user(message))

        // Get context from memory
        val contextMessages = memory.getContextMessages()

        // Create a new chat with context
        val contextChat = KotlinLLM.chat(chat.model())
        contextMessages.forEach { contextChat.addMessage(it) }

        // Get response
        val response = contextChat.ask(message)

        // Add assistant response to memory
        memory.add(response)

        response
    }

    /**
     * Alias for ask().
     */
    fun say(message: String): Message = ask(message)

    // ==================== Async API ====================

    /**
     * Send a message and get a response (async).
     */
    fun askAsync(message: String): CompletableFuture<Message> {
        return CompletableFuture.supplyAsync({ ask(message) }, JavaLLM.executor)
    }

    // ==================== Memory Access ====================

    /**
     * Get memory statistics.
     */
    fun getMemoryStats(): MemoryStats = memory.stats()

    /**
     * Get all messages from memory.
     */
    fun getAllMessages(): List<Message> = runBlocking {
        memory.getAllMessages()
    }

    /**
     * Get context messages (what would be sent to LLM).
     */
    fun getContextMessages(): List<Message> = runBlocking {
        memory.getContextMessages()
    }

    /**
     * Clear memory.
     */
    fun clearMemory() = runBlocking {
        memory.clear()
    }

    /**
     * Get the underlying memory instance.
     */
    fun unwrapMemory(): Memory = memory

    /**
     * Get the underlying Kotlin Chat.
     */
    fun unwrapChat(): Chat = chat
}

// ==================== Structured Output Support ====================

/**
 * Result of a structured output request.
 *
 * Example:
 * ```java
 * JavaStructuredResult<Person> result = chat.askStructured(Person.class, "Extract person info...");
 *
 * if (result.isSuccess()) {
 *     Person person = result.getValue();
 *     System.out.println(person.getName());
 * } else {
 *     System.out.println("Error: " + result.getError());
 * }
 * ```
 */
class JavaStructuredResult<T> private constructor(
    private val value: T?,
    private val rawResponse: String?,
    private val error: String?
) {
    /**
     * Whether the structured output was successfully parsed.
     */
    fun isSuccess(): Boolean = value != null

    /**
     * Get the parsed value, or null if parsing failed.
     */
    fun getValue(): T? = value

    /**
     * Get the parsed value, throwing if parsing failed.
     */
    fun getValueOrThrow(): T = value ?: throw IllegalStateException(error ?: "No value")

    /**
     * Get the raw response from the LLM.
     */
    fun getRawResponse(): String? = rawResponse

    /**
     * Get the error message if parsing failed.
     */
    fun getError(): String? = error

    companion object {
        internal fun <T> success(value: T, rawResponse: String): JavaStructuredResult<T> =
            JavaStructuredResult(value, rawResponse, null)

        internal fun <T> failure(error: String, rawResponse: String?): JavaStructuredResult<T> =
            JavaStructuredResult(null, rawResponse, error)
    }
}

/**
 * Configuration for structured output requests.
 */
class JavaStructuredConfig {
    internal var maxRetries: Int = 3
    internal var temperature: Double = 0.1
    internal var maxTokens: Int? = null

    fun maxRetries(retries: Int): JavaStructuredConfig = apply { this.maxRetries = retries }
    fun temperature(temp: Double): JavaStructuredConfig = apply { this.temperature = temp }
    fun maxTokens(tokens: Int): JavaStructuredConfig = apply { this.maxTokens = tokens }

    companion object {
        @JvmStatic
        fun defaults(): JavaStructuredConfig = JavaStructuredConfig()
    }
}
