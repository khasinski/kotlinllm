@file:JvmName("JavaLLM")

package com.kotlinllm

import com.kotlinllm.core.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.function.Consumer

/**
 * Java-friendly API for KotlinLLM.
 *
 * This class provides blocking and CompletableFuture-based methods
 * for easy integration with Java code.
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
 * Chat chat = KotlinLLM.chat();
 * Message response = JavaLLM.ask(chat, "Hello!");
 * System.out.println(response.getText());
 *
 * // Async with CompletableFuture
 * CompletableFuture<Message> future = JavaLLM.askAsync(chat, "Hello!");
 * future.thenAccept(msg -> System.out.println(msg.getText()));
 * ```
 */
object JavaLLM {
    private val executor = Executors.newCachedThreadPool()

    // ==================== Blocking API ====================

    /**
     * Send a message and get a response (blocking).
     */
    @JvmStatic
    fun ask(chat: Chat, message: String): Message = runBlocking {
        chat.ask(message)
    }

    /**
     * Send a message and get a response (blocking).
     */
    @JvmStatic
    fun say(chat: Chat, message: String): Message = ask(chat, message)

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

    // ==================== Async API ====================

    /**
     * Send a message and get a response (async).
     */
    @JvmStatic
    fun askAsync(chat: Chat, message: String): CompletableFuture<Message> {
        return CompletableFuture.supplyAsync({
            runBlocking { chat.ask(message) }
        }, executor)
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

    // ==================== Streaming API ====================

    /**
     * Stream a response with a callback for each chunk.
     */
    @JvmStatic
    fun askStreaming(chat: Chat, message: String, onChunk: Consumer<Chunk>) {
        runBlocking {
            chat.askStreaming(message).collect { chunk ->
                onChunk.accept(chunk)
            }
        }
    }

    /**
     * Stream a response with a callback (async).
     */
    @JvmStatic
    fun askStreamingAsync(chat: Chat, message: String, onChunk: Consumer<Chunk>): CompletableFuture<Void> {
        return CompletableFuture.runAsync({
            runBlocking {
                chat.askStreaming(message).collect { chunk ->
                    onChunk.accept(chunk)
                }
            }
        }, executor)
    }

    /**
     * Collect all streaming chunks into a list (blocking).
     */
    @JvmStatic
    fun collectStream(chat: Chat, message: String): List<Chunk> = runBlocking {
        chat.askStreaming(message).toList()
    }

    // ==================== Builder Helpers ====================

    /**
     * Create a chat builder for Java.
     */
    @JvmStatic
    fun chatBuilder(): JavaChatBuilder = JavaChatBuilder()

    /**
     * Create a tool builder for Java.
     */
    @JvmStatic
    fun toolBuilder(name: String, description: String): JavaToolBuilder = JavaToolBuilder(name, description)
}

/**
 * Java-friendly chat builder.
 */
class JavaChatBuilder {
    private var model: String = KotlinLLM.config().defaultModel
    private var systemPrompt: String? = null
    private val tools = mutableListOf<Tool>()
    private var temperature: Double? = null
    private var maxTokens: Int? = null

    fun model(model: String): JavaChatBuilder = apply { this.model = model }
    fun system(instructions: String): JavaChatBuilder = apply { this.systemPrompt = instructions }
    fun tool(tool: Tool): JavaChatBuilder = apply { this.tools.add(tool) }
    fun temperature(value: Double): JavaChatBuilder = apply { this.temperature = value }
    fun maxTokens(value: Int): JavaChatBuilder = apply { this.maxTokens = value }

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
