package com.kotlinllm.resilience

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Configuration for rate limiting.
 *
 * Example:
 * ```kotlin
 * val config = RateLimitConfig(
 *     requestsPerMinute = 60,
 *     tokensPerMinute = 100000,
 *     concurrentRequests = 10
 * )
 * ```
 */
data class RateLimitConfig(
    /** Maximum requests per minute. Default: 60 */
    val requestsPerMinute: Int = 60,
    /** Maximum tokens per minute. Default: unlimited (0) */
    val tokensPerMinute: Int = 0,
    /** Maximum concurrent requests. Default: 10 */
    val concurrentRequests: Int = 10,
    /** Whether to wait when rate limited, or throw immediately. Default: true */
    val waitOnLimit: Boolean = true,
    /** Maximum time to wait when rate limited (ms). Default: 60000 */
    val maxWaitMs: Long = 60000
) {
    companion object {
        val DEFAULT = RateLimitConfig()

        /** OpenAI tier 1 limits */
        val OPENAI_TIER_1 = RateLimitConfig(
            requestsPerMinute = 500,
            tokensPerMinute = 30000,
            concurrentRequests = 25
        )

        /** OpenAI tier 2 limits */
        val OPENAI_TIER_2 = RateLimitConfig(
            requestsPerMinute = 5000,
            tokensPerMinute = 450000,
            concurrentRequests = 50
        )

        /** Anthropic default limits */
        val ANTHROPIC_DEFAULT = RateLimitConfig(
            requestsPerMinute = 60,
            tokensPerMinute = 100000,
            concurrentRequests = 10
        )

        /** Local Ollama (generous limits) */
        val OLLAMA_LOCAL = RateLimitConfig(
            requestsPerMinute = 1000,
            tokensPerMinute = 0,
            concurrentRequests = 5
        )
    }
}

/**
 * Rate limiter interface for controlling request throughput.
 */
interface RateLimiter {
    /**
     * Acquire a permit for a request. Blocks until a permit is available
     * or throws [RateLimitExceededException] if waiting is disabled.
     *
     * @param estimatedTokens Estimated tokens for this request (for token-based limits)
     */
    suspend fun acquire(estimatedTokens: Int = 0)

    /**
     * Release a permit and report actual token usage.
     *
     * @param actualTokens Actual tokens used by the request
     */
    suspend fun release(actualTokens: Int = 0)

    /**
     * Check if a request can proceed without blocking.
     */
    fun canProceed(estimatedTokens: Int = 0): Boolean

    /**
     * Get current rate limiter statistics.
     */
    fun stats(): RateLimiterStats

    /**
     * Reset the rate limiter.
     */
    fun reset()
}

/**
 * Statistics for a rate limiter.
 */
data class RateLimiterStats(
    val requestsInWindow: Int,
    val tokensInWindow: Int,
    val activeRequests: Int,
    val windowStartTime: Long,
    val totalRequests: Long,
    val totalWaitTimeMs: Long,
    val rejectedRequests: Long
)

/**
 * Token bucket rate limiter implementation.
 *
 * Uses the token bucket algorithm to smooth out request bursts while
 * maintaining an average rate limit.
 */
class TokenBucketRateLimiter(
    private val config: RateLimitConfig
) : RateLimiter {

    private val mutex = Mutex()
    private val concurrencySemaphore = Semaphore(config.concurrentRequests)

    // Request rate tracking
    private var requestTokens = config.requestsPerMinute.toDouble()
    private var lastRequestRefill = System.currentTimeMillis()

    // Token rate tracking (for models with token limits)
    private var tokenBucket = config.tokensPerMinute.toDouble()
    private var lastTokenRefill = System.currentTimeMillis()

    // Statistics
    private val totalRequests = AtomicLong(0)
    private val totalWaitTimeMs = AtomicLong(0)
    private val rejectedRequests = AtomicLong(0)
    private var activeRequests = 0
    private var requestsInCurrentWindow = 0
    private var tokensInCurrentWindow = 0
    private var windowStartTime = System.currentTimeMillis()

    override suspend fun acquire(estimatedTokens: Int) {
        val startWait = System.currentTimeMillis()

        // Acquire concurrency permit
        if (!config.waitOnLimit && !concurrencySemaphore.tryAcquire()) {
            rejectedRequests.incrementAndGet()
            throw RateLimitExceededException("Concurrent request limit exceeded")
        }

        if (config.waitOnLimit) {
            concurrencySemaphore.acquire()
        }

        mutex.withLock {
            // Refill buckets based on elapsed time
            refillBuckets()

            // Check and wait for request rate limit
            while (requestTokens < 1.0) {
                if (!config.waitOnLimit) {
                    concurrencySemaphore.release()
                    rejectedRequests.incrementAndGet()
                    throw RateLimitExceededException("Request rate limit exceeded")
                }

                val waitTime = calculateWaitTime(requestTokens, config.requestsPerMinute)
                if (System.currentTimeMillis() - startWait + waitTime > config.maxWaitMs) {
                    concurrencySemaphore.release()
                    rejectedRequests.incrementAndGet()
                    throw RateLimitExceededException("Rate limit wait timeout exceeded")
                }

                // Release mutex while waiting
                mutex.unlock()
                delay(waitTime)
                mutex.lock()
                refillBuckets()
            }

            // Check and wait for token rate limit
            if (config.tokensPerMinute > 0 && estimatedTokens > 0) {
                while (tokenBucket < estimatedTokens) {
                    if (!config.waitOnLimit) {
                        concurrencySemaphore.release()
                        rejectedRequests.incrementAndGet()
                        throw RateLimitExceededException("Token rate limit exceeded")
                    }

                    val waitTime = calculateWaitTime(tokenBucket - estimatedTokens, config.tokensPerMinute)
                    if (System.currentTimeMillis() - startWait + waitTime > config.maxWaitMs) {
                        concurrencySemaphore.release()
                        rejectedRequests.incrementAndGet()
                        throw RateLimitExceededException("Token rate limit wait timeout exceeded")
                    }

                    mutex.unlock()
                    delay(waitTime)
                    mutex.lock()
                    refillBuckets()
                }

                tokenBucket -= estimatedTokens
                tokensInCurrentWindow += estimatedTokens
            }

            // Consume request token
            requestTokens -= 1.0
            activeRequests++
            requestsInCurrentWindow++
            totalRequests.incrementAndGet()
        }

        val waitTime = System.currentTimeMillis() - startWait
        if (waitTime > 0) {
            totalWaitTimeMs.addAndGet(waitTime)
        }
    }

    override suspend fun release(actualTokens: Int) {
        mutex.withLock {
            activeRequests--

            // Adjust token bucket if actual differs from estimated
            if (config.tokensPerMinute > 0 && actualTokens > 0) {
                // Note: We don't refund tokens, but we track actual usage
                val diff = actualTokens - tokensInCurrentWindow
                if (diff > 0) {
                    tokenBucket = maxOf(0.0, tokenBucket - diff)
                }
            }
        }

        concurrencySemaphore.release()
    }

    override fun canProceed(estimatedTokens: Int): Boolean {
        // Check concurrency first (non-blocking)
        if (concurrencySemaphore.availablePermits == 0) return false

        // Check rate limits
        val now = System.currentTimeMillis()
        val requestRefillAmount = (now - lastRequestRefill) / 60000.0 * config.requestsPerMinute
        val currentRequestTokens = minOf(config.requestsPerMinute.toDouble(), requestTokens + requestRefillAmount)

        if (currentRequestTokens < 1.0) return false

        if (config.tokensPerMinute > 0 && estimatedTokens > 0) {
            val tokenRefillAmount = (now - lastTokenRefill) / 60000.0 * config.tokensPerMinute
            val currentTokenBucket = minOf(config.tokensPerMinute.toDouble(), tokenBucket + tokenRefillAmount)
            if (currentTokenBucket < estimatedTokens) return false
        }

        return true
    }

    override fun stats(): RateLimiterStats {
        return RateLimiterStats(
            requestsInWindow = requestsInCurrentWindow,
            tokensInWindow = tokensInCurrentWindow,
            activeRequests = activeRequests,
            windowStartTime = windowStartTime,
            totalRequests = totalRequests.get(),
            totalWaitTimeMs = totalWaitTimeMs.get(),
            rejectedRequests = rejectedRequests.get()
        )
    }

    override fun reset() {
        requestTokens = config.requestsPerMinute.toDouble()
        tokenBucket = config.tokensPerMinute.toDouble()
        lastRequestRefill = System.currentTimeMillis()
        lastTokenRefill = System.currentTimeMillis()
        activeRequests = 0
        requestsInCurrentWindow = 0
        tokensInCurrentWindow = 0
        windowStartTime = System.currentTimeMillis()
    }

    private fun refillBuckets() {
        val now = System.currentTimeMillis()

        // Refill request tokens
        val requestElapsed = now - lastRequestRefill
        if (requestElapsed > 0) {
            val refillAmount = requestElapsed / 60000.0 * config.requestsPerMinute
            requestTokens = minOf(config.requestsPerMinute.toDouble(), requestTokens + refillAmount)
            lastRequestRefill = now
        }

        // Refill token bucket
        if (config.tokensPerMinute > 0) {
            val tokenElapsed = now - lastTokenRefill
            if (tokenElapsed > 0) {
                val refillAmount = tokenElapsed / 60000.0 * config.tokensPerMinute
                tokenBucket = minOf(config.tokensPerMinute.toDouble(), tokenBucket + refillAmount)
                lastTokenRefill = now
            }
        }

        // Reset window stats if needed (every minute)
        if (now - windowStartTime > 60000) {
            requestsInCurrentWindow = 0
            tokensInCurrentWindow = 0
            windowStartTime = now
        }
    }

    private fun calculateWaitTime(deficit: Double, ratePerMinute: Int): Long {
        if (ratePerMinute == 0) return 0
        val msNeeded = ((-deficit) / ratePerMinute * 60000).toLong()
        return maxOf(10, minOf(msNeeded, 1000)) // Wait between 10ms and 1s
    }
}

/**
 * Registry for per-provider rate limiters.
 */
object RateLimiters {
    private val limiters = ConcurrentHashMap<String, RateLimiter>()
    private val configs = ConcurrentHashMap<String, RateLimitConfig>()

    /**
     * Configure rate limiting for a provider.
     */
    fun configure(provider: String, config: RateLimitConfig) {
        configs[provider] = config
        limiters[provider] = TokenBucketRateLimiter(config)
    }

    /**
     * Get or create a rate limiter for a provider.
     */
    fun forProvider(provider: String): RateLimiter {
        return limiters.computeIfAbsent(provider) {
            val config = configs[provider] ?: when (provider) {
                "openai" -> RateLimitConfig.OPENAI_TIER_1
                "anthropic" -> RateLimitConfig.ANTHROPIC_DEFAULT
                "ollama" -> RateLimitConfig.OLLAMA_LOCAL
                else -> RateLimitConfig.DEFAULT
            }
            TokenBucketRateLimiter(config)
        }
    }

    /**
     * Get all configured provider names.
     */
    fun providers(): Set<String> = limiters.keys

    /**
     * Get statistics for all rate limiters.
     */
    fun allStats(): Map<String, RateLimiterStats> =
        limiters.mapValues { it.value.stats() }

    /**
     * Reset all rate limiters.
     */
    fun resetAll() {
        limiters.values.forEach { it.reset() }
    }

    /**
     * Clear all rate limiters.
     */
    fun clear() {
        limiters.clear()
        configs.clear()
    }
}

// RateLimitExceededException is defined in com.kotlinllm.core.Exceptions
// Re-export for convenience
typealias RateLimitExceededException = com.kotlinllm.core.RateLimitExceededException
