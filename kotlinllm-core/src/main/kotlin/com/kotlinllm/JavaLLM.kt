@file:JvmName("JavaLLM")

package com.kotlinllm

import com.kotlinllm.core.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.function.Consumer

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
