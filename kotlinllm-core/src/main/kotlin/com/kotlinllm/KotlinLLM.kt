package com.kotlinllm

import com.kotlinllm.core.Chat
import com.kotlinllm.core.Configuration
import com.kotlinllm.core.LLMInterceptor
import com.kotlinllm.core.LLMInterceptors
import com.kotlinllm.core.Provider
import com.kotlinllm.providers.AnthropicProvider
import com.kotlinllm.providers.OllamaProvider
import com.kotlinllm.providers.OpenAIProvider

/**
 * KotlinLLM - A beautiful Kotlin API for Large Language Models.
 *
 * Inspired by RubyLLM, KotlinLLM provides a simple, elegant interface
 * for working with various LLM providers.
 *
 * ## Quick Start
 *
 * ```kotlin
 * // Configure
 * KotlinLLM.configure {
 *     openaiApiKey = System.getenv("OPENAI_API_KEY")
 * }
 *
 * // Simple chat
 * val response = KotlinLLM.chat().ask("Hello!")
 * println(response.text)
 *
 * // With model and tools
 * val chat = KotlinLLM.chat("claude-sonnet-4-20250514")
 *     .withInstructions("You are a helpful assistant")
 *     .withTool(Calculator())
 *
 * val response = chat.ask("What is 42 * 17?")
 * ```
 *
 * ## Java Usage
 *
 * ```java
 * // Configure
 * KotlinLLM.configure(config -> {
 *     config.setOpenaiApiKey(System.getenv("OPENAI_API_KEY"));
 *     return Unit.INSTANCE;
 * });
 *
 * // Chat
 * Chat chat = KotlinLLM.chat();
 * Message response = KotlinLLMJava.ask(chat, "Hello!");
 * System.out.println(response.getText());
 * ```
 */
object KotlinLLM {
    private var config = Configuration.fromEnvironment()

    init {
        // Register built-in providers
        Provider.register(OpenAIProvider())
        Provider.register(AnthropicProvider())
        Provider.register(OllamaProvider())
    }

    // ==================== Interceptors ====================

    /**
     * Add a global interceptor that will be applied to all requests.
     *
     * ```kotlin
     * KotlinLLM.addInterceptor(LoggingInterceptor())
     * KotlinLLM.addInterceptor(RateLimitingInterceptor())
     * ```
     */
    @JvmStatic
    fun addInterceptor(interceptor: LLMInterceptor) {
        LLMInterceptors.add(interceptor)
    }

    /**
     * Remove an interceptor.
     */
    @JvmStatic
    fun removeInterceptor(interceptor: LLMInterceptor) {
        LLMInterceptors.remove(interceptor)
    }

    /**
     * Remove an interceptor by name.
     */
    @JvmStatic
    fun removeInterceptor(name: String) {
        LLMInterceptors.remove(name)
    }

    /**
     * Clear all interceptors.
     */
    @JvmStatic
    fun clearInterceptors() {
        LLMInterceptors.clear()
    }

    /**
     * Get all registered interceptors.
     */
    @JvmStatic
    fun interceptors(): List<LLMInterceptor> = LLMInterceptors.all()

    /**
     * Configure KotlinLLM.
     *
     * ```kotlin
     * KotlinLLM.configure {
     *     openaiApiKey = "sk-..."
     *     anthropicApiKey = "sk-ant-..."
     *     defaultModel = "gpt-4o"
     * }
     * ```
     */
    @JvmStatic
    fun configure(block: Configuration.() -> Unit) {
        config.block()
    }

    /**
     * Get current configuration.
     */
    @JvmStatic
    fun config(): Configuration = config

    /**
     * Create a new chat with the default model.
     *
     * ```kotlin
     * val response = KotlinLLM.chat().ask("Hello!")
     * ```
     */
    @JvmStatic
    fun chat(): Chat = Chat(config.defaultModel, config)

    /**
     * Create a new chat with a specific model.
     *
     * ```kotlin
     * val chat = KotlinLLM.chat("claude-sonnet-4-20250514")
     * ```
     */
    @JvmStatic
    fun chat(model: String): Chat = Chat(model, config)

    /**
     * Create a new chat with custom configuration.
     *
     * ```kotlin
     * val chat = KotlinLLM.chat("gpt-4o") {
     *     openaiApiKey = "different-key"
     * }
     * ```
     */
    fun chat(model: String, configBlock: Configuration.() -> Unit): Chat {
        val customConfig = config.copy(configBlock)
        return Chat(model, customConfig)
    }

    /**
     * List all available providers.
     */
    @JvmStatic
    fun providers(): Collection<Provider> = Provider.all()

    /**
     * Get a specific provider.
     */
    @JvmStatic
    fun provider(slug: String): Provider? = Provider.get(slug)
}

// ==================== Convenience Functions ====================

/**
 * Quick chat function for one-off questions.
 *
 * ```kotlin
 * val answer = chat("What is the capital of France?")
 * ```
 */
suspend fun chat(message: String, model: String = KotlinLLM.config().defaultModel): String {
    return KotlinLLM.chat(model).ask(message).text
}

/**
 * Quick chat with custom configuration.
 */
suspend fun chat(
    message: String,
    model: String = KotlinLLM.config().defaultModel,
    configBlock: Configuration.() -> Unit
): String {
    return KotlinLLM.chat(model, configBlock).ask(message).text
}
