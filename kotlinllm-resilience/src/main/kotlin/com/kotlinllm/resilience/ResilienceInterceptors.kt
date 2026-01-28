package com.kotlinllm.resilience

import com.kotlinllm.core.*

/**
 * Interceptor that applies rate limiting to LLM requests.
 *
 * Example:
 * ```kotlin
 * // Configure rate limits
 * RateLimiters.configure("openai", RateLimitConfig.OPENAI_TIER_1)
 *
 * // Add interceptor
 * KotlinLLM.addInterceptor(RateLimitingInterceptor())
 * ```
 */
class RateLimitingInterceptor(
    override val priority: Int = 100  // High priority - run early
) : LLMInterceptor {

    override val name: String = "RateLimitingInterceptor"

    override suspend fun intercept(
        context: LLMRequestContext,
        chain: LLMInterceptorChain
    ): LLMResponseContext {
        val providerSlug = context.provider.slug
        val rateLimiter = RateLimiters.forProvider(providerSlug)

        // Estimate tokens for rate limiting
        val estimatedTokens = estimateTokens(context.messages)

        try {
            // Acquire permit (may block or throw)
            rateLimiter.acquire(estimatedTokens)

            // Proceed with request
            val response = chain.proceed(context)

            // Release permit with actual token count
            val actualTokens = response.response?.tokens?.total ?: estimatedTokens
            rateLimiter.release(actualTokens)

            return response
        } catch (e: RateLimitExceededException) {
            // Return as response context error
            return LLMResponseContext.failure(
                context,
                e,
                0
            )
        }
    }

    /**
     * Estimate tokens from messages (rough approximation).
     */
    private fun estimateTokens(messages: List<Message>): Int {
        // Rough estimate: ~4 characters per token
        val totalChars = messages.sumOf { it.text.length }
        return (totalChars / 4) + (messages.size * 4) // Add overhead for message structure
    }
}

/**
 * Interceptor that applies circuit breaker protection to LLM requests.
 *
 * Example:
 * ```kotlin
 * // Configure circuit breaker
 * CircuitBreakers.configure("openai", CircuitBreakerConfig.DEFAULT)
 *
 * // Add interceptor
 * KotlinLLM.addInterceptor(CircuitBreakerInterceptor())
 * ```
 */
class CircuitBreakerInterceptor(
    override val priority: Int = 90  // Run after rate limiting
) : LLMInterceptor {

    override val name: String = "CircuitBreakerInterceptor"

    override suspend fun intercept(
        context: LLMRequestContext,
        chain: LLMInterceptorChain
    ): LLMResponseContext {
        val providerSlug = context.provider.slug
        val circuitBreaker = CircuitBreakers.forProvider(providerSlug)

        return try {
            circuitBreaker.execute {
                val response = chain.proceed(context)

                // If the response is a failure, throw to trigger circuit breaker
                if (response.isFailure) {
                    throw response.error ?: Exception("Unknown error")
                }

                response
            }
        } catch (e: CircuitBreakerOpenException) {
            LLMResponseContext.failure(context, e, 0)
        }
    }
}

/**
 * Combined resilience interceptor that applies both rate limiting and circuit breaker.
 *
 * This is a convenience interceptor that combines rate limiting and circuit breaker
 * functionality in a single interceptor.
 *
 * Example:
 * ```kotlin
 * KotlinLLM.addInterceptor(ResilienceInterceptor())
 * ```
 */
class ResilienceInterceptor(
    override val priority: Int = 100
) : LLMInterceptor {

    override val name: String = "ResilienceInterceptor"

    override suspend fun intercept(
        context: LLMRequestContext,
        chain: LLMInterceptorChain
    ): LLMResponseContext {
        val providerSlug = context.provider.slug
        val rateLimiter = RateLimiters.forProvider(providerSlug)
        val circuitBreaker = CircuitBreakers.forProvider(providerSlug)

        // Estimate tokens
        val estimatedTokens = estimateTokens(context.messages)

        // Check circuit breaker first (fail fast)
        if (!circuitBreaker.canExecute()) {
            return LLMResponseContext.failure(
                context,
                CircuitBreakerOpenException("Circuit breaker for '$providerSlug' is open"),
                0
            )
        }

        // Acquire rate limit permit
        try {
            rateLimiter.acquire(estimatedTokens)
        } catch (e: RateLimitExceededException) {
            return LLMResponseContext.failure(context, e, 0)
        }

        return try {
            circuitBreaker.execute {
                val response = chain.proceed(context)

                // Release rate limit permit
                val actualTokens = response.response?.tokens?.total ?: estimatedTokens
                rateLimiter.release(actualTokens)

                // If failure, throw to trigger circuit breaker
                if (response.isFailure) {
                    throw response.error ?: Exception("Unknown error")
                }

                response
            }
        } catch (e: CircuitBreakerOpenException) {
            rateLimiter.release(0)
            LLMResponseContext.failure(context, e, 0)
        } catch (e: Throwable) {
            rateLimiter.release(0)
            throw e
        }
    }

    private fun estimateTokens(messages: List<Message>): Int {
        val totalChars = messages.sumOf { it.text.length }
        return (totalChars / 4) + (messages.size * 4)
    }
}

/**
 * Retry interceptor with exponential backoff.
 *
 * Automatically retries failed requests with configurable delay and max attempts.
 *
 * Example:
 * ```kotlin
 * KotlinLLM.addInterceptor(RetryInterceptor(maxRetries = 3))
 * ```
 */
class RetryInterceptor(
    private val maxRetries: Int = 3,
    private val initialDelayMs: Long = 1000,
    private val maxDelayMs: Long = 30000,
    private val multiplier: Double = 2.0,
    private val retryableExceptions: Set<Class<out Throwable>> = setOf(
        java.io.IOException::class.java,
        java.net.SocketTimeoutException::class.java
    ),
    override val priority: Int = 80  // Run after rate limiting and circuit breaker
) : LLMInterceptor {

    override val name: String = "RetryInterceptor"

    override suspend fun intercept(
        context: LLMRequestContext,
        chain: LLMInterceptorChain
    ): LLMResponseContext {
        var lastResponse: LLMResponseContext? = null
        var currentDelay = initialDelayMs

        repeat(maxRetries + 1) { attempt ->
            val response = chain.proceed(context)

            if (response.isSuccess) {
                return response
            }

            lastResponse = response

            // Check if we should retry
            val error = response.error
            if (error == null || !shouldRetry(error) || attempt >= maxRetries) {
                return response
            }

            // Wait before retry
            kotlinx.coroutines.delay(currentDelay)
            currentDelay = minOf((currentDelay * multiplier).toLong(), maxDelayMs)
        }

        return lastResponse ?: LLMResponseContext.failure(
            context,
            IllegalStateException("Retry loop completed without result"),
            0
        )
    }

    private fun shouldRetry(error: Throwable): Boolean {
        // Don't retry circuit breaker or rate limit errors
        if (error is CircuitBreakerOpenException || error is RateLimitExceededException) {
            return false
        }

        // Check if error is retryable
        return retryableExceptions.any { it.isInstance(error) } ||
                retryableExceptions.any { it.isInstance(error.cause) }
    }
}
