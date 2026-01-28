package com.kotlinllm.core

/**
 * Interceptor for LLM requests and responses.
 *
 * Interceptors can inspect, modify, or short-circuit requests before they reach
 * the provider, and process responses before they're returned to the caller.
 *
 * Example:
 * ```kotlin
 * class LoggingInterceptor : LLMInterceptor {
 *     override suspend fun intercept(context: LLMRequestContext, chain: LLMInterceptorChain): LLMResponseContext {
 *         println("Request: ${context.model}")
 *         val response = chain.proceed(context)
 *         println("Response: ${response.durationMs}ms")
 *         return response
 *     }
 * }
 * ```
 */
interface LLMInterceptor {
    /**
     * Intercept the request and optionally modify it or the response.
     *
     * Call `chain.proceed(context)` to continue to the next interceptor.
     * You can modify the context before calling proceed, or modify the response after.
     */
    suspend fun intercept(context: LLMRequestContext, chain: LLMInterceptorChain): LLMResponseContext

    /**
     * Priority for ordering interceptors. Higher values run first.
     * Default is 0.
     */
    val priority: Int get() = 0

    /**
     * Name for debugging/logging purposes.
     */
    val name: String get() = this::class.simpleName ?: "Unknown"
}

/**
 * Context for an LLM request before it's sent.
 */
data class LLMRequestContext(
    val provider: Provider,
    val model: String,
    val messages: List<Message>,
    val tools: List<Tool>,
    val temperature: Double?,
    val maxTokens: Int?,
    val streaming: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val metadata: MutableMap<String, Any> = mutableMapOf()
) {
    /**
     * Create a copy with modified fields.
     */
    fun copy(
        provider: Provider = this.provider,
        model: String = this.model,
        messages: List<Message> = this.messages,
        tools: List<Tool> = this.tools,
        temperature: Double? = this.temperature,
        maxTokens: Int? = this.maxTokens,
        streaming: Boolean = this.streaming
    ): LLMRequestContext = LLMRequestContext(
        provider = provider,
        model = model,
        messages = messages,
        tools = tools,
        temperature = temperature,
        maxTokens = maxTokens,
        streaming = streaming,
        timestamp = this.timestamp,
        metadata = this.metadata
    )
}

/**
 * Context for an LLM response after it's received.
 */
data class LLMResponseContext(
    val request: LLMRequestContext,
    val response: Message?,
    val error: Throwable?,
    val durationMs: Long
) {
    /**
     * Whether the request succeeded.
     */
    val isSuccess: Boolean get() = error == null && response != null

    /**
     * Whether the request failed.
     */
    val isFailure: Boolean get() = error != null

    /**
     * Get the response or throw if failed.
     */
    fun getOrThrow(): Message {
        if (error != null) throw error
        return response ?: throw IllegalStateException("No response available")
    }

    companion object {
        fun success(request: LLMRequestContext, response: Message, durationMs: Long) =
            LLMResponseContext(request, response, null, durationMs)

        fun failure(request: LLMRequestContext, error: Throwable, durationMs: Long) =
            LLMResponseContext(request, null, error, durationMs)
    }
}

/**
 * Chain of interceptors that processes requests.
 */
interface LLMInterceptorChain {
    /**
     * Proceed to the next interceptor in the chain.
     */
    suspend fun proceed(context: LLMRequestContext): LLMResponseContext
}

/**
 * Internal implementation of the interceptor chain.
 */
internal class DefaultInterceptorChain(
    private val interceptors: List<LLMInterceptor>,
    private val index: Int,
    private val config: Configuration,
    private val executor: suspend (LLMRequestContext, Configuration) -> Message
) : LLMInterceptorChain {

    override suspend fun proceed(context: LLMRequestContext): LLMResponseContext {
        val startTime = System.currentTimeMillis()

        return if (index < interceptors.size) {
            // Call next interceptor
            val nextChain = DefaultInterceptorChain(interceptors, index + 1, config, executor)
            try {
                interceptors[index].intercept(context, nextChain)
            } catch (e: Throwable) {
                LLMResponseContext.failure(context, e, System.currentTimeMillis() - startTime)
            }
        } else {
            // End of chain - execute the actual request
            try {
                val response = executor(context, config)
                LLMResponseContext.success(context, response, System.currentTimeMillis() - startTime)
            } catch (e: Throwable) {
                LLMResponseContext.failure(context, e, System.currentTimeMillis() - startTime)
            }
        }
    }
}

/**
 * Registry for global interceptors.
 */
object LLMInterceptors {
    private val interceptors = mutableListOf<LLMInterceptor>()

    /**
     * Add an interceptor.
     */
    fun add(interceptor: LLMInterceptor) {
        interceptors.add(interceptor)
        interceptors.sortByDescending { it.priority }
    }

    /**
     * Remove an interceptor.
     */
    fun remove(interceptor: LLMInterceptor) {
        interceptors.remove(interceptor)
    }

    /**
     * Remove an interceptor by name.
     */
    fun remove(name: String) {
        interceptors.removeAll { it.name == name }
    }

    /**
     * Clear all interceptors.
     */
    fun clear() {
        interceptors.clear()
    }

    /**
     * Get all registered interceptors (sorted by priority).
     */
    fun all(): List<LLMInterceptor> = interceptors.toList()

    /**
     * Create an interceptor chain with all registered interceptors.
     */
    internal fun createChain(
        config: Configuration,
        executor: suspend (LLMRequestContext, Configuration) -> Message
    ): LLMInterceptorChain {
        return DefaultInterceptorChain(interceptors.toList(), 0, config, executor)
    }

    /**
     * Execute a request through the interceptor chain.
     */
    internal suspend fun execute(
        context: LLMRequestContext,
        config: Configuration,
        executor: suspend (LLMRequestContext, Configuration) -> Message
    ): LLMResponseContext {
        val chain = createChain(config, executor)
        return chain.proceed(context)
    }
}
