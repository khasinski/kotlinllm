package com.kotlinllm.core

import kotlinx.coroutines.flow.Flow

/**
 * Base interface for LLM providers.
 *
 * Each provider (OpenAI, Anthropic, etc.) implements this interface
 * to provide a consistent API across different services.
 */
interface Provider {
    /**
     * Provider identifier (e.g., "openai", "anthropic").
     */
    val slug: String

    /**
     * Display name for the provider.
     */
    val name: String

    /**
     * Check if the provider is properly configured.
     */
    fun isConfigured(config: Configuration): Boolean

    /**
     * Send a completion request and get a response.
     */
    suspend fun complete(
        messages: List<Message>,
        model: String,
        tools: List<Tool> = emptyList(),
        temperature: Double? = null,
        maxTokens: Int? = null,
        config: Configuration
    ): Message

    /**
     * Send a completion request and stream the response.
     */
    fun stream(
        messages: List<Message>,
        model: String,
        tools: List<Tool> = emptyList(),
        temperature: Double? = null,
        maxTokens: Int? = null,
        config: Configuration
    ): Flow<Chunk>

    /**
     * List available models from this provider.
     */
    suspend fun listModels(config: Configuration): List<ModelInfo>

    companion object {
        private val providers = mutableMapOf<String, Provider>()

        /**
         * Register a provider.
         */
        fun register(provider: Provider) {
            providers[provider.slug] = provider
        }

        /**
         * Get a provider by slug.
         */
        fun get(slug: String): Provider? = providers[slug]

        /**
         * Get all registered providers.
         */
        fun all(): Collection<Provider> = providers.values

        /**
         * Resolve provider for a model ID.
         *
         * Supports:
         * - OpenAI: gpt-4*, gpt-5*, o1*, o3*, o4*, gpt-image-*
         * - Anthropic: claude-*
         * - Google: gemini-*
         */
        fun forModel(modelId: String): Provider {
            return when {
                // OpenAI models: GPT series, o-series reasoning models, image generation
                modelId.startsWith("gpt-") ||
                modelId.startsWith("o1") ||
                modelId.startsWith("o3") ||
                modelId.startsWith("o4") -> get("openai")

                // Anthropic models
                modelId.startsWith("claude-") -> get("anthropic")

                // Google models
                modelId.startsWith("gemini-") -> get("gemini")

                else -> get("openai") // Default fallback
            } ?: throw IllegalArgumentException("No provider found for model: $modelId")
        }
    }
}

/**
 * HTTP-related utilities for providers.
 */
object ProviderUtils {
    /**
     * Create authorization header value.
     */
    fun bearerAuth(token: String): String = "Bearer $token"
}
