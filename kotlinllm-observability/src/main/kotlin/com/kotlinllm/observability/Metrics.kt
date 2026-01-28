package com.kotlinllm.observability

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder

/**
 * Metrics collector interface for recording LLM usage metrics.
 *
 * Implementations can send metrics to various backends (in-memory, Micrometer, etc.)
 */
interface MetricsCollector {
    /**
     * Record request latency.
     *
     * @param provider Provider slug (e.g., "openai", "anthropic")
     * @param model Model ID
     * @param operation Operation type (e.g., "complete", "stream")
     * @param durationMs Duration in milliseconds
     */
    fun recordLatency(provider: String, model: String, operation: String, durationMs: Long)

    /**
     * Record token usage.
     *
     * @param provider Provider slug
     * @param model Model ID
     * @param inputTokens Number of input/prompt tokens
     * @param outputTokens Number of output/completion tokens
     */
    fun recordTokens(provider: String, model: String, inputTokens: Int, outputTokens: Int)

    /**
     * Record an error.
     *
     * @param provider Provider slug
     * @param model Model ID
     * @param errorType Type/class of error
     */
    fun recordError(provider: String, model: String, errorType: String)

    /**
     * Record a request.
     *
     * @param provider Provider slug
     * @param model Model ID
     * @param streaming Whether the request was streaming
     */
    fun recordRequest(provider: String, model: String, streaming: Boolean)

    /**
     * Get a summary of collected metrics.
     */
    fun getSummary(): MetricsSummary
}

/**
 * Summary of collected metrics.
 */
data class MetricsSummary(
    val totalRequests: Long,
    val totalErrors: Long,
    val totalInputTokens: Long,
    val totalOutputTokens: Long,
    val totalLatencyMs: Long,
    val avgLatencyMs: Double,
    val requestsByProvider: Map<String, Long>,
    val requestsByModel: Map<String, Long>,
    val errorsByType: Map<String, Long>,
    val tokensByProvider: Map<String, TokenStats>,
    val latencyPercentiles: LatencyPercentiles
)

/**
 * Token statistics.
 */
data class TokenStats(
    val inputTokens: Long,
    val outputTokens: Long
) {
    val totalTokens: Long get() = inputTokens + outputTokens
}

/**
 * Latency percentiles.
 */
data class LatencyPercentiles(
    val p50: Long,
    val p90: Long,
    val p95: Long,
    val p99: Long,
    val max: Long
)

/**
 * In-memory metrics collector for development and testing.
 *
 * Stores metrics in memory and provides a summary.
 * Not suitable for production with high traffic.
 *
 * Example:
 * ```kotlin
 * val metrics = InMemoryMetricsCollector()
 * KotlinLLM.addInterceptor(MetricsInterceptor(metrics))
 *
 * // Later...
 * val summary = metrics.getSummary()
 * println("Total requests: ${summary.totalRequests}")
 * ```
 */
class InMemoryMetricsCollector(
    private val maxLatencySamples: Int = 10000
) : MetricsCollector {

    // Counters
    private val totalRequests = LongAdder()
    private val totalErrors = LongAdder()
    private val totalInputTokens = LongAdder()
    private val totalOutputTokens = LongAdder()
    private val totalLatencyMs = LongAdder()

    // Per-provider/model breakdowns
    private val requestsByProvider = ConcurrentHashMap<String, LongAdder>()
    private val requestsByModel = ConcurrentHashMap<String, LongAdder>()
    private val errorsByType = ConcurrentHashMap<String, LongAdder>()
    private val tokensByProvider = ConcurrentHashMap<String, Pair<LongAdder, LongAdder>>()

    // Latency samples for percentile calculation
    private val latencySamples = java.util.concurrent.ConcurrentLinkedQueue<Long>()
    private val latencySampleCount = AtomicLong(0)

    override fun recordLatency(provider: String, model: String, operation: String, durationMs: Long) {
        totalLatencyMs.add(durationMs)

        // Store sample for percentile calculation (with bounded size)
        if (latencySampleCount.incrementAndGet() <= maxLatencySamples) {
            latencySamples.add(durationMs)
        } else {
            // Randomly replace samples to maintain a representative sample
            if (Math.random() < 0.1) {
                latencySamples.poll()
                latencySamples.add(durationMs)
            }
        }
    }

    override fun recordTokens(provider: String, model: String, inputTokens: Int, outputTokens: Int) {
        totalInputTokens.add(inputTokens.toLong())
        totalOutputTokens.add(outputTokens.toLong())

        val (input, output) = tokensByProvider.computeIfAbsent(provider) {
            LongAdder() to LongAdder()
        }
        input.add(inputTokens.toLong())
        output.add(outputTokens.toLong())
    }

    override fun recordError(provider: String, model: String, errorType: String) {
        totalErrors.increment()
        errorsByType.computeIfAbsent(errorType) { LongAdder() }.increment()
    }

    override fun recordRequest(provider: String, model: String, streaming: Boolean) {
        totalRequests.increment()
        requestsByProvider.computeIfAbsent(provider) { LongAdder() }.increment()
        requestsByModel.computeIfAbsent(model) { LongAdder() }.increment()
    }

    override fun getSummary(): MetricsSummary {
        val requests = totalRequests.sum()
        val latency = totalLatencyMs.sum()

        return MetricsSummary(
            totalRequests = requests,
            totalErrors = totalErrors.sum(),
            totalInputTokens = totalInputTokens.sum(),
            totalOutputTokens = totalOutputTokens.sum(),
            totalLatencyMs = latency,
            avgLatencyMs = if (requests > 0) latency.toDouble() / requests else 0.0,
            requestsByProvider = requestsByProvider.mapValues { it.value.sum() },
            requestsByModel = requestsByModel.mapValues { it.value.sum() },
            errorsByType = errorsByType.mapValues { it.value.sum() },
            tokensByProvider = tokensByProvider.mapValues {
                TokenStats(it.value.first.sum(), it.value.second.sum())
            },
            latencyPercentiles = calculatePercentiles()
        )
    }

    /**
     * Reset all metrics.
     */
    fun reset() {
        totalRequests.reset()
        totalErrors.reset()
        totalInputTokens.reset()
        totalOutputTokens.reset()
        totalLatencyMs.reset()
        requestsByProvider.clear()
        requestsByModel.clear()
        errorsByType.clear()
        tokensByProvider.clear()
        latencySamples.clear()
        latencySampleCount.set(0)
    }

    private fun calculatePercentiles(): LatencyPercentiles {
        val samples = latencySamples.toList().sorted()
        if (samples.isEmpty()) {
            return LatencyPercentiles(0, 0, 0, 0, 0)
        }

        return LatencyPercentiles(
            p50 = percentile(samples, 0.50),
            p90 = percentile(samples, 0.90),
            p95 = percentile(samples, 0.95),
            p99 = percentile(samples, 0.99),
            max = samples.last()
        )
    }

    private fun percentile(sorted: List<Long>, p: Double): Long {
        if (sorted.isEmpty()) return 0
        val index = (sorted.size * p).toInt().coerceIn(0, sorted.size - 1)
        return sorted[index]
    }
}

/**
 * No-op metrics collector that discards all metrics.
 */
object NoOpMetricsCollector : MetricsCollector {
    override fun recordLatency(provider: String, model: String, operation: String, durationMs: Long) {}
    override fun recordTokens(provider: String, model: String, inputTokens: Int, outputTokens: Int) {}
    override fun recordError(provider: String, model: String, errorType: String) {}
    override fun recordRequest(provider: String, model: String, streaming: Boolean) {}
    override fun getSummary(): MetricsSummary = MetricsSummary(
        totalRequests = 0,
        totalErrors = 0,
        totalInputTokens = 0,
        totalOutputTokens = 0,
        totalLatencyMs = 0,
        avgLatencyMs = 0.0,
        requestsByProvider = emptyMap(),
        requestsByModel = emptyMap(),
        errorsByType = emptyMap(),
        tokensByProvider = emptyMap(),
        latencyPercentiles = LatencyPercentiles(0, 0, 0, 0, 0)
    )
}

/**
 * Composite metrics collector that delegates to multiple collectors.
 */
class CompositeMetricsCollector(
    private val collectors: List<MetricsCollector>
) : MetricsCollector {

    constructor(vararg collectors: MetricsCollector) : this(collectors.toList())

    override fun recordLatency(provider: String, model: String, operation: String, durationMs: Long) {
        collectors.forEach { it.recordLatency(provider, model, operation, durationMs) }
    }

    override fun recordTokens(provider: String, model: String, inputTokens: Int, outputTokens: Int) {
        collectors.forEach { it.recordTokens(provider, model, inputTokens, outputTokens) }
    }

    override fun recordError(provider: String, model: String, errorType: String) {
        collectors.forEach { it.recordError(provider, model, errorType) }
    }

    override fun recordRequest(provider: String, model: String, streaming: Boolean) {
        collectors.forEach { it.recordRequest(provider, model, streaming) }
    }

    override fun getSummary(): MetricsSummary {
        // Return summary from first collector (usually the in-memory one)
        return collectors.firstOrNull()?.getSummary() ?: NoOpMetricsCollector.getSummary()
    }
}
