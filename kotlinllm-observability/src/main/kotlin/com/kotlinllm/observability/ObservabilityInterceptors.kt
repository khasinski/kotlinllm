package com.kotlinllm.observability

import com.kotlinllm.core.*

/**
 * Interceptor that collects metrics for all LLM requests.
 *
 * Example:
 * ```kotlin
 * val metrics = InMemoryMetricsCollector()
 * KotlinLLM.addInterceptor(MetricsInterceptor(metrics))
 *
 * // Later...
 * println(metrics.getSummary())
 * ```
 */
class MetricsInterceptor(
    private val collector: MetricsCollector,
    override val priority: Int = 50  // Run after resilience interceptors
) : LLMInterceptor {

    override val name: String = "MetricsInterceptor"

    override suspend fun intercept(
        context: LLMRequestContext,
        chain: LLMInterceptorChain
    ): LLMResponseContext {
        val provider = context.provider.slug
        val model = context.model
        val operation = if (context.streaming) "stream" else "complete"

        // Record request
        collector.recordRequest(provider, model, context.streaming)

        // Proceed with request
        val response = chain.proceed(context)

        // Record latency
        collector.recordLatency(provider, model, operation, response.durationMs)

        // Record tokens if available
        response.response?.tokens?.let { tokens ->
            collector.recordTokens(provider, model, tokens.input, tokens.output)
        }

        // Record error if failed
        if (response.isFailure) {
            val errorType = response.error?.javaClass?.simpleName ?: "Unknown"
            collector.recordError(provider, model, errorType)
        }

        return response
    }
}

/**
 * Interceptor that logs all LLM requests and responses.
 *
 * Example:
 * ```kotlin
 * val logger = ConsoleLogger(LogLevel.DEBUG)
 * KotlinLLM.addInterceptor(LoggingInterceptor(logger))
 * ```
 */
class LoggingInterceptor(
    private val logger: LLMLogger,
    override val priority: Int = 40  // Run after metrics
) : LLMInterceptor {

    override val name: String = "LoggingInterceptor"

    override suspend fun intercept(
        context: LLMRequestContext,
        chain: LLMInterceptorChain
    ): LLMResponseContext {
        val provider = context.provider.slug
        val model = context.model

        // Log request
        logger.logRequest(provider, model, context.messages, context.streaming)

        // Proceed with request
        val response = chain.proceed(context)

        // Log response or error
        val responseMsg = response.response
        val responseError = response.error
        if (response.isSuccess && responseMsg != null) {
            logger.logResponse(provider, model, responseMsg, response.durationMs)
        } else if (response.isFailure && responseError != null) {
            logger.logError(provider, model, responseError, response.durationMs)
        }

        return response
    }
}

/**
 * Combined observability interceptor that does both metrics and logging.
 *
 * This is a convenience interceptor that combines metrics collection and logging
 * in a single interceptor for easier setup.
 *
 * Example:
 * ```kotlin
 * val interceptor = ObservabilityInterceptor(
 *     metrics = InMemoryMetricsCollector(),
 *     logger = ConsoleLogger(LogLevel.INFO)
 * )
 * KotlinLLM.addInterceptor(interceptor)
 * ```
 */
class ObservabilityInterceptor(
    private val metrics: MetricsCollector = InMemoryMetricsCollector(),
    private val logger: LLMLogger = NoOpLogger,
    override val priority: Int = 50
) : LLMInterceptor {

    override val name: String = "ObservabilityInterceptor"

    /**
     * Get the metrics collector.
     */
    fun metrics(): MetricsCollector = metrics

    override suspend fun intercept(
        context: LLMRequestContext,
        chain: LLMInterceptorChain
    ): LLMResponseContext {
        val provider = context.provider.slug
        val model = context.model
        val operation = if (context.streaming) "stream" else "complete"

        // Record request and log
        metrics.recordRequest(provider, model, context.streaming)
        logger.logRequest(provider, model, context.messages, context.streaming)

        // Proceed with request
        val response = chain.proceed(context)

        // Record latency
        metrics.recordLatency(provider, model, operation, response.durationMs)

        // Handle success or failure
        val responseMsg = response.response
        val responseError = response.error
        if (response.isSuccess && responseMsg != null) {
            // Record tokens
            responseMsg.tokens?.let { tokens ->
                metrics.recordTokens(provider, model, tokens.input, tokens.output)
            }
            // Log response
            logger.logResponse(provider, model, responseMsg, response.durationMs)
        } else if (response.isFailure && responseError != null) {
            // Record error
            val errorType = responseError.javaClass.simpleName
            metrics.recordError(provider, model, errorType)
            // Log error
            logger.logError(provider, model, responseError, response.durationMs)
        }

        return response
    }
}

/**
 * Interceptor that adds tracing/correlation IDs to requests.
 *
 * Useful for distributed tracing and log correlation.
 *
 * Example:
 * ```kotlin
 * KotlinLLM.addInterceptor(TracingInterceptor())
 * ```
 */
class TracingInterceptor(
    private val idGenerator: () -> String = { java.util.UUID.randomUUID().toString() },
    override val priority: Int = 200  // Very high priority - run first
) : LLMInterceptor {

    override val name: String = "TracingInterceptor"

    override suspend fun intercept(
        context: LLMRequestContext,
        chain: LLMInterceptorChain
    ): LLMResponseContext {
        // Generate and add trace ID to metadata
        val traceId = idGenerator()
        context.metadata["traceId"] = traceId
        context.metadata["requestTime"] = context.timestamp

        // Proceed with request
        val response = chain.proceed(context)

        // Add trace ID to response metadata as well
        response.request.metadata["traceId"] = traceId

        return response
    }
}
