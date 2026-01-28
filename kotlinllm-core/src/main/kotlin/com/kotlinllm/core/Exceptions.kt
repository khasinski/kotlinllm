package com.kotlinllm.core

/**
 * Base exception for all KotlinLLM errors.
 *
 * All exceptions thrown by KotlinLLM extend this class, allowing you to catch
 * all library errors with a single catch block:
 *
 * ```kotlin
 * try {
 *     chat.ask("Hello")
 * } catch (e: LLMException) {
 *     when (e) {
 *         is ProviderException -> println("Provider error: ${e.statusCode}")
 *         is ConfigurationException -> println("Config error: ${e.message}")
 *         else -> println("LLM error: ${e.message}")
 *     }
 * }
 * ```
 */
open class LLMException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

// ==================== Provider Exceptions ====================

/**
 * Base exception for provider-related errors (API calls, authentication, rate limits).
 *
 * @property statusCode HTTP status code from the provider (0 if not applicable)
 * @property provider The provider name (e.g., "openai", "anthropic", "ollama")
 */
open class ProviderException(
    val statusCode: Int,
    val provider: String,
    message: String,
    cause: Throwable? = null
) : LLMException("$provider API error ($statusCode): $message", cause) {

    /**
     * Whether this is a client error (4xx status code).
     */
    val isClientError: Boolean get() = statusCode in 400..499

    /**
     * Whether this is a server error (5xx status code).
     */
    val isServerError: Boolean get() = statusCode in 500..599

    /**
     * Whether this error is retryable (server errors, rate limits, timeouts).
     */
    val isRetryable: Boolean get() = isServerError || statusCode == 429 || statusCode == 408
}

/**
 * Exception thrown when an API key is missing or invalid.
 */
class AuthenticationException(
    provider: String,
    message: String = "Invalid or missing API key",
    cause: Throwable? = null
) : ProviderException(401, provider, message, cause)

/**
 * Exception thrown when the provider rate limit is exceeded.
 */
class ProviderRateLimitException(
    provider: String,
    message: String = "Rate limit exceeded",
    val retryAfterSeconds: Int? = null,
    cause: Throwable? = null
) : ProviderException(429, provider, message, cause)

/**
 * Exception thrown when the requested model is not found or not available.
 */
class ModelNotFoundException(
    val model: String,
    provider: String,
    cause: Throwable? = null
) : ProviderException(404, provider, "Model not found: $model", cause)

// ==================== Configuration Exceptions ====================

/**
 * Exception thrown when KotlinLLM configuration is invalid.
 */
class ConfigurationException(
    message: String,
    cause: Throwable? = null
) : LLMException("Configuration error: $message", cause)

// ==================== Tool Exceptions ====================

/**
 * Exception thrown when tool execution fails.
 */
class ToolExecutionException(
    val toolName: String,
    message: String,
    cause: Throwable? = null
) : LLMException("Tool '$toolName' execution failed: $message", cause)

// ==================== Structured Output Exceptions ====================

/**
 * Exception thrown when structured output parsing fails.
 */
class StructuredOutputException(
    message: String,
    cause: Throwable? = null
) : LLMException("Structured output error: $message", cause)

// ==================== Document Exceptions ====================

/**
 * Exception thrown when a document format is not supported.
 */
class UnsupportedDocumentException(
    message: String,
    val extension: String? = null
) : LLMException(message)

/**
 * Exception thrown when document loading fails.
 */
class DocumentLoadException(
    val source: String,
    message: String,
    cause: Throwable? = null
) : LLMException("Failed to load document from '$source': $message", cause)

// ==================== Resilience Exceptions ====================

/**
 * Base exception for resilience-related errors (circuit breaker, rate limiting).
 */
open class ResilienceException(
    message: String,
    cause: Throwable? = null
) : LLMException(message, cause)

/**
 * Exception thrown when the circuit breaker is open.
 */
class CircuitBreakerOpenException(
    val circuitName: String? = null,
    message: String = "Circuit breaker is open"
) : ResilienceException(if (circuitName != null) "Circuit breaker '$circuitName' is open" else message)

/**
 * Exception thrown when rate limit is exceeded (local rate limiter, not provider).
 */
class RateLimitExceededException(
    message: String = "Rate limit exceeded"
) : ResilienceException(message)

// ==================== Memory Exceptions ====================

/**
 * Exception thrown when memory operations fail.
 */
class MemoryException(
    message: String,
    cause: Throwable? = null
) : LLMException("Memory error: $message", cause)
