package com.kotlinllm.core

import java.time.Duration

/**
 * Global configuration for KotlinLLM.
 *
 * Example:
 * ```kotlin
 * KotlinLLM.configure {
 *     openaiApiKey = System.getenv("OPENAI_API_KEY")
 *     anthropicApiKey = System.getenv("ANTHROPIC_API_KEY")
 *     defaultModel = "gpt-4o"
 * }
 * ```
 */
class Configuration {
    // API Keys
    var openaiApiKey: String? = null
    var openaiApiBase: String = "https://api.openai.com/v1"
    var openaiOrganization: String? = null

    var anthropicApiKey: String? = null
    var anthropicApiBase: String = "https://api.anthropic.com"

    var geminiApiKey: String? = null
    var ollamaApiBase: String = "http://localhost:11434"

    // Default models
    var defaultModel: String = "gpt-4o-mini"
    var defaultEmbeddingModel: String = "text-embedding-3-small"

    // Connection settings
    var requestTimeout: Duration = Duration.ofSeconds(300)
    var connectTimeout: Duration = Duration.ofSeconds(30)
    var maxRetries: Int = 3
    var retryDelay: Duration = Duration.ofMillis(100)

    // Logging
    var logLevel: LogLevel = LogLevel.INFO
    var logRequests: Boolean = false
    var logResponses: Boolean = false

    /**
     * Create a copy with modifications.
     */
    fun copy(block: Configuration.() -> Unit): Configuration {
        return Configuration().also {
            it.openaiApiKey = this.openaiApiKey
            it.openaiApiBase = this.openaiApiBase
            it.openaiOrganization = this.openaiOrganization
            it.anthropicApiKey = this.anthropicApiKey
            it.anthropicApiBase = this.anthropicApiBase
            it.geminiApiKey = this.geminiApiKey
            it.ollamaApiBase = this.ollamaApiBase
            it.defaultModel = this.defaultModel
            it.defaultEmbeddingModel = this.defaultEmbeddingModel
            it.requestTimeout = this.requestTimeout
            it.connectTimeout = this.connectTimeout
            it.maxRetries = this.maxRetries
            it.retryDelay = this.retryDelay
            it.logLevel = this.logLevel
            it.logRequests = this.logRequests
            it.logResponses = this.logResponses
            it.block()
        }
    }

    companion object {
        /**
         * Create configuration from environment variables.
         */
        fun fromEnvironment(): Configuration = Configuration().apply {
            openaiApiKey = System.getenv("OPENAI_API_KEY")
            anthropicApiKey = System.getenv("ANTHROPIC_API_KEY")
            geminiApiKey = System.getenv("GEMINI_API_KEY")

            System.getenv("OPENAI_API_BASE")?.let { openaiApiBase = it }
            System.getenv("ANTHROPIC_API_BASE")?.let { anthropicApiBase = it }
            System.getenv("OLLAMA_API_BASE")?.let { ollamaApiBase = it }
        }
    }
}

enum class LogLevel {
    DEBUG, INFO, WARN, ERROR, OFF
}
