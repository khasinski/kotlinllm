package com.kotlinllm.resilience

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Circuit breaker state.
 */
enum class CircuitState {
    /** Circuit is closed - requests flow normally */
    CLOSED,
    /** Circuit is open - requests fail fast */
    OPEN,
    /** Circuit is testing - limited requests allowed */
    HALF_OPEN
}

/**
 * Configuration for circuit breaker.
 *
 * Example:
 * ```kotlin
 * val config = CircuitBreakerConfig(
 *     failureThreshold = 5,
 *     successThreshold = 3,
 *     timeout = Duration.ofSeconds(30)
 * )
 * ```
 */
data class CircuitBreakerConfig(
    /** Number of failures before opening circuit. Default: 5 */
    val failureThreshold: Int = 5,
    /** Number of successes in half-open state to close circuit. Default: 3 */
    val successThreshold: Int = 3,
    /** Time in milliseconds before attempting to half-open. Default: 30000 (30s) */
    val timeoutMs: Long = 30000,
    /** Time window for counting failures (ms). Default: 60000 (1 min) */
    val failureWindowMs: Long = 60000,
    /** Maximum concurrent requests in half-open state. Default: 1 */
    val halfOpenRequests: Int = 1,
    /** Exceptions that should not count as failures */
    val ignoredExceptions: Set<Class<out Throwable>> = emptySet()
) {
    companion object {
        val DEFAULT = CircuitBreakerConfig()

        /** More lenient config for local/dev */
        val LENIENT = CircuitBreakerConfig(
            failureThreshold = 10,
            successThreshold = 2,
            timeoutMs = 10000
        )

        /** Strict config for production */
        val STRICT = CircuitBreakerConfig(
            failureThreshold = 3,
            successThreshold = 5,
            timeoutMs = 60000
        )
    }
}

/**
 * Circuit breaker statistics.
 */
data class CircuitBreakerStats(
    val state: CircuitState,
    val failureCount: Int,
    val successCount: Int,
    val consecutiveSuccesses: Int,
    val lastFailureTime: Long?,
    val lastSuccessTime: Long?,
    val openedAt: Long?,
    val totalRequests: Long,
    val totalFailures: Long,
    val totalSuccesses: Long,
    val totalRejected: Long
)

/**
 * Circuit breaker implementation for fault tolerance.
 *
 * The circuit breaker pattern prevents cascading failures by failing fast
 * when a downstream service is unhealthy.
 *
 * States:
 * - CLOSED: Normal operation. Failures are counted.
 * - OPEN: Requests fail immediately. After timeout, transitions to HALF_OPEN.
 * - HALF_OPEN: Limited requests allowed to test recovery. Success closes, failure reopens.
 *
 * Example:
 * ```kotlin
 * val breaker = CircuitBreaker(CircuitBreakerConfig(
 *     failureThreshold = 5,
 *     timeout = Duration.ofSeconds(30)
 * ))
 *
 * breaker.execute {
 *     llmClient.complete(messages)
 * }
 * ```
 */
class CircuitBreaker(
    private val config: CircuitBreakerConfig = CircuitBreakerConfig.DEFAULT,
    private val name: String = "default"
) {
    private val mutex = Mutex()

    @Volatile
    private var state: CircuitState = CircuitState.CLOSED

    // Failure tracking
    private val failures = mutableListOf<Long>() // Timestamps of recent failures
    private var consecutiveSuccesses = 0

    // Timing
    private var openedAt: Long? = null
    private var lastFailureTime: Long? = null
    private var lastSuccessTime: Long? = null

    // Statistics
    private val totalRequests = AtomicLong(0)
    private val totalFailures = AtomicLong(0)
    private val totalSuccesses = AtomicLong(0)
    private val totalRejected = AtomicLong(0)

    // Half-open state tracking
    private val halfOpenAttempts = AtomicInteger(0)

    /**
     * Execute an operation with circuit breaker protection.
     *
     * @throws CircuitBreakerOpenException if circuit is open
     */
    suspend fun <T> execute(operation: suspend () -> T): T {
        // Check if we can proceed
        if (!canExecute()) {
            totalRejected.incrementAndGet()
            throw CircuitBreakerOpenException(
                "Circuit breaker '$name' is ${state.name}. " +
                "Wait ${remainingTimeoutMs()}ms before retry."
            )
        }

        totalRequests.incrementAndGet()

        return try {
            val result = operation()
            recordSuccess()
            result
        } catch (e: Throwable) {
            if (shouldRecordFailure(e)) {
                recordFailure()
            }
            throw e
        }
    }

    /**
     * Check if the circuit breaker allows execution.
     */
    suspend fun canExecute(): Boolean {
        return mutex.withLock {
            when (state) {
                CircuitState.CLOSED -> true

                CircuitState.OPEN -> {
                    // Check if timeout has passed
                    val openTime = openedAt ?: return@withLock false
                    if (System.currentTimeMillis() - openTime >= config.timeoutMs) {
                        // Transition to half-open
                        state = CircuitState.HALF_OPEN
                        halfOpenAttempts.set(0)
                        consecutiveSuccesses = 0
                        true
                    } else {
                        false
                    }
                }

                CircuitState.HALF_OPEN -> {
                    // Allow limited requests in half-open state
                    halfOpenAttempts.incrementAndGet() <= config.halfOpenRequests
                }
            }
        }
    }

    /**
     * Record a successful operation.
     */
    private suspend fun recordSuccess() {
        mutex.withLock {
            lastSuccessTime = System.currentTimeMillis()
            totalSuccesses.incrementAndGet()

            when (state) {
                CircuitState.HALF_OPEN -> {
                    consecutiveSuccesses++
                    if (consecutiveSuccesses >= config.successThreshold) {
                        // Close the circuit
                        state = CircuitState.CLOSED
                        failures.clear()
                        openedAt = null
                        halfOpenAttempts.set(0)
                    }
                }
                CircuitState.CLOSED -> {
                    // Clear old failures
                    pruneOldFailures()
                }
                CircuitState.OPEN -> {
                    // Shouldn't happen, but reset if it does
                    state = CircuitState.HALF_OPEN
                }
            }
        }
    }

    /**
     * Record a failed operation.
     */
    private suspend fun recordFailure() {
        mutex.withLock {
            val now = System.currentTimeMillis()
            lastFailureTime = now
            totalFailures.incrementAndGet()

            when (state) {
                CircuitState.CLOSED -> {
                    failures.add(now)
                    pruneOldFailures()

                    if (failures.size >= config.failureThreshold) {
                        // Open the circuit
                        state = CircuitState.OPEN
                        openedAt = now
                    }
                }
                CircuitState.HALF_OPEN -> {
                    // Immediately reopen on failure in half-open state
                    state = CircuitState.OPEN
                    openedAt = now
                    consecutiveSuccesses = 0
                }
                CircuitState.OPEN -> {
                    // Already open, just update timestamp
                    openedAt = now
                }
            }
        }
    }

    /**
     * Check if an exception should be recorded as a failure.
     */
    private fun shouldRecordFailure(e: Throwable): Boolean {
        return !config.ignoredExceptions.any { it.isInstance(e) }
    }

    /**
     * Remove failures outside the failure window.
     */
    private fun pruneOldFailures() {
        val cutoff = System.currentTimeMillis() - config.failureWindowMs
        failures.removeAll { it < cutoff }
    }

    /**
     * Get remaining timeout in milliseconds.
     */
    private fun remainingTimeoutMs(): Long {
        val opened = openedAt ?: return 0
        val elapsed = System.currentTimeMillis() - opened
        return maxOf(0, config.timeoutMs - elapsed)
    }

    /**
     * Get current circuit breaker state.
     */
    fun currentState(): CircuitState = state

    /**
     * Get circuit breaker statistics.
     */
    fun stats(): CircuitBreakerStats {
        return CircuitBreakerStats(
            state = state,
            failureCount = failures.size,
            successCount = consecutiveSuccesses,
            consecutiveSuccesses = consecutiveSuccesses,
            lastFailureTime = lastFailureTime,
            lastSuccessTime = lastSuccessTime,
            openedAt = openedAt,
            totalRequests = totalRequests.get(),
            totalFailures = totalFailures.get(),
            totalSuccesses = totalSuccesses.get(),
            totalRejected = totalRejected.get()
        )
    }

    /**
     * Manually reset the circuit breaker to closed state.
     */
    suspend fun reset() {
        mutex.withLock {
            state = CircuitState.CLOSED
            failures.clear()
            consecutiveSuccesses = 0
            openedAt = null
            halfOpenAttempts.set(0)
        }
    }

    /**
     * Manually trip the circuit breaker to open state.
     */
    suspend fun trip() {
        mutex.withLock {
            state = CircuitState.OPEN
            openedAt = System.currentTimeMillis()
        }
    }
}

/**
 * Registry for per-provider circuit breakers.
 */
object CircuitBreakers {
    private val breakers = ConcurrentHashMap<String, CircuitBreaker>()
    private val configs = ConcurrentHashMap<String, CircuitBreakerConfig>()

    /**
     * Configure circuit breaker for a provider.
     */
    fun configure(provider: String, config: CircuitBreakerConfig) {
        configs[provider] = config
        breakers[provider] = CircuitBreaker(config, provider)
    }

    /**
     * Get or create a circuit breaker for a provider.
     */
    fun forProvider(provider: String): CircuitBreaker {
        return breakers.computeIfAbsent(provider) {
            val config = configs[provider] ?: CircuitBreakerConfig.DEFAULT
            CircuitBreaker(config, provider)
        }
    }

    /**
     * Get all configured provider names.
     */
    fun providers(): Set<String> = breakers.keys

    /**
     * Get statistics for all circuit breakers.
     */
    fun allStats(): Map<String, CircuitBreakerStats> =
        breakers.mapValues { it.value.stats() }

    /**
     * Reset all circuit breakers.
     */
    suspend fun resetAll() {
        breakers.values.forEach { it.reset() }
    }

    /**
     * Clear all circuit breakers.
     */
    fun clear() {
        breakers.clear()
        configs.clear()
    }
}

// CircuitBreakerOpenException is defined in com.kotlinllm.core.Exceptions
// Re-export for convenience
typealias CircuitBreakerOpenException = com.kotlinllm.core.CircuitBreakerOpenException
