package com.kotlinllm.observability

import kotlin.test.*

class InMemoryMetricsCollectorTest {

    @Test
    fun `initial state has zero metrics`() {
        val metrics = InMemoryMetricsCollector()
        val summary = metrics.getSummary()

        assertEquals(0, summary.totalRequests)
        assertEquals(0, summary.totalErrors)
        assertEquals(0, summary.totalInputTokens)
        assertEquals(0, summary.totalOutputTokens)
        assertEquals(0, summary.totalLatencyMs)
        assertEquals(0.0, summary.avgLatencyMs)
    }

    @Test
    fun `recordRequest increments counters`() {
        val metrics = InMemoryMetricsCollector()

        metrics.recordRequest("openai", "gpt-4o", false)
        metrics.recordRequest("openai", "gpt-4o", true)
        metrics.recordRequest("anthropic", "claude-3", false)

        val summary = metrics.getSummary()

        assertEquals(3, summary.totalRequests)
        assertEquals(2, summary.requestsByProvider["openai"])
        assertEquals(1, summary.requestsByProvider["anthropic"])
        assertEquals(2, summary.requestsByModel["gpt-4o"])
        assertEquals(1, summary.requestsByModel["claude-3"])
    }

    @Test
    fun `recordLatency tracks latency`() {
        val metrics = InMemoryMetricsCollector()

        metrics.recordLatency("openai", "gpt-4o", "complete", 100)
        metrics.recordLatency("openai", "gpt-4o", "complete", 200)
        metrics.recordLatency("openai", "gpt-4o", "complete", 300)

        // Need to record requests too for avgLatencyMs calculation
        repeat(3) { metrics.recordRequest("openai", "gpt-4o", false) }

        val summary = metrics.getSummary()

        assertEquals(600, summary.totalLatencyMs)
        assertEquals(200.0, summary.avgLatencyMs)
    }

    @Test
    fun `recordTokens accumulates tokens`() {
        val metrics = InMemoryMetricsCollector()

        metrics.recordTokens("openai", "gpt-4o", 100, 50)
        metrics.recordTokens("openai", "gpt-4o", 200, 100)
        metrics.recordTokens("anthropic", "claude-3", 150, 75)

        val summary = metrics.getSummary()

        assertEquals(450, summary.totalInputTokens)
        assertEquals(225, summary.totalOutputTokens)

        val openaiTokens = summary.tokensByProvider["openai"]!!
        assertEquals(300, openaiTokens.inputTokens)
        assertEquals(150, openaiTokens.outputTokens)
        assertEquals(450, openaiTokens.totalTokens)

        val anthropicTokens = summary.tokensByProvider["anthropic"]!!
        assertEquals(150, anthropicTokens.inputTokens)
        assertEquals(75, anthropicTokens.outputTokens)
    }

    @Test
    fun `recordError tracks errors`() {
        val metrics = InMemoryMetricsCollector()

        metrics.recordError("openai", "gpt-4o", "RateLimitError")
        metrics.recordError("openai", "gpt-4o", "RateLimitError")
        metrics.recordError("anthropic", "claude-3", "AuthenticationError")

        val summary = metrics.getSummary()

        assertEquals(3, summary.totalErrors)
        assertEquals(2, summary.errorsByType["RateLimitError"])
        assertEquals(1, summary.errorsByType["AuthenticationError"])
    }

    @Test
    fun `latency percentiles are calculated correctly`() {
        val metrics = InMemoryMetricsCollector()

        // Add 100 samples from 1 to 100
        repeat(100) { i ->
            metrics.recordLatency("openai", "gpt-4o", "complete", (i + 1).toLong())
        }

        val summary = metrics.getSummary()
        val percentiles = summary.latencyPercentiles

        // p50 should be around 50
        assertTrue(percentiles.p50 in 45..55)
        // p90 should be around 90
        assertTrue(percentiles.p90 in 85..95)
        // p99 should be around 99
        assertTrue(percentiles.p99 in 95..100)
        // max should be 100
        assertEquals(100, percentiles.max)
    }

    @Test
    fun `reset clears all metrics`() {
        val metrics = InMemoryMetricsCollector()

        metrics.recordRequest("openai", "gpt-4o", false)
        metrics.recordTokens("openai", "gpt-4o", 100, 50)
        metrics.recordError("openai", "gpt-4o", "Error")
        metrics.recordLatency("openai", "gpt-4o", "complete", 100)

        metrics.reset()

        val summary = metrics.getSummary()

        assertEquals(0, summary.totalRequests)
        assertEquals(0, summary.totalErrors)
        assertEquals(0, summary.totalInputTokens)
        assertEquals(0, summary.totalOutputTokens)
        assertEquals(0, summary.totalLatencyMs)
    }

    @Test
    fun `concurrent access is safe`() {
        val metrics = InMemoryMetricsCollector()
        val threads = mutableListOf<Thread>()

        repeat(10) { threadId ->
            threads.add(Thread {
                repeat(100) {
                    metrics.recordRequest("provider-$threadId", "model-$threadId", false)
                    metrics.recordTokens("provider-$threadId", "model-$threadId", 10, 5)
                    metrics.recordLatency("provider-$threadId", "model-$threadId", "complete", 10)
                }
            })
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        val summary = metrics.getSummary()

        assertEquals(1000, summary.totalRequests)
        assertEquals(10000, summary.totalInputTokens)
        assertEquals(5000, summary.totalOutputTokens)
    }
}

class NoOpMetricsCollectorTest {

    @Test
    fun `NoOpMetricsCollector does nothing`() {
        NoOpMetricsCollector.recordRequest("openai", "gpt-4o", false)
        NoOpMetricsCollector.recordTokens("openai", "gpt-4o", 100, 50)
        NoOpMetricsCollector.recordError("openai", "gpt-4o", "Error")
        NoOpMetricsCollector.recordLatency("openai", "gpt-4o", "complete", 100)

        val summary = NoOpMetricsCollector.getSummary()

        assertEquals(0, summary.totalRequests)
        assertEquals(0, summary.totalErrors)
        assertEquals(0, summary.totalInputTokens)
    }
}

class CompositeMetricsCollectorTest {

    @Test
    fun `CompositeMetricsCollector delegates to all collectors`() {
        val collector1 = InMemoryMetricsCollector()
        val collector2 = InMemoryMetricsCollector()

        val composite = CompositeMetricsCollector(collector1, collector2)

        composite.recordRequest("openai", "gpt-4o", false)
        composite.recordTokens("openai", "gpt-4o", 100, 50)

        // Both collectors should have recorded
        assertEquals(1, collector1.getSummary().totalRequests)
        assertEquals(1, collector2.getSummary().totalRequests)
        assertEquals(100, collector1.getSummary().totalInputTokens)
        assertEquals(100, collector2.getSummary().totalInputTokens)
    }

    @Test
    fun `CompositeMetricsCollector getSummary returns first collector summary`() {
        val collector1 = InMemoryMetricsCollector()
        val collector2 = InMemoryMetricsCollector()

        collector1.recordRequest("openai", "gpt-4o", false)
        // collector2 has no data

        val composite = CompositeMetricsCollector(collector1, collector2)
        val summary = composite.getSummary()

        assertEquals(1, summary.totalRequests)
    }
}

class TokenStatsTest {

    @Test
    fun `TokenStats totalTokens is sum of input and output`() {
        val stats = TokenStats(inputTokens = 100, outputTokens = 50)

        assertEquals(150, stats.totalTokens)
    }
}

class LogLevelTest {

    @Test
    fun `LogLevel values exist`() {
        assertEquals(5, com.kotlinllm.core.LogLevel.entries.size)
        assertTrue(com.kotlinllm.core.LogLevel.entries.contains(com.kotlinllm.core.LogLevel.ERROR))
        assertTrue(com.kotlinllm.core.LogLevel.entries.contains(com.kotlinllm.core.LogLevel.WARN))
        assertTrue(com.kotlinllm.core.LogLevel.entries.contains(com.kotlinllm.core.LogLevel.INFO))
        assertTrue(com.kotlinllm.core.LogLevel.entries.contains(com.kotlinllm.core.LogLevel.DEBUG))
        assertTrue(com.kotlinllm.core.LogLevel.entries.contains(com.kotlinllm.core.LogLevel.OFF))
    }
}

class ConsoleLoggerTest {

    @Test
    fun `ConsoleLogger does not throw`() {
        val logger = ConsoleLogger()

        // Should not throw
        logger.info("Test message")
        logger.error("Error message", null)
        logger.debug("Debug message")
        logger.warn("Warning message")
    }

    @Test
    fun `ConsoleLogger with debug level logs all levels`() {
        val logger = ConsoleLogger(level = com.kotlinllm.core.LogLevel.DEBUG)

        // Should not throw
        logger.debug("Debug message")
        logger.info("Info message")
        logger.warn("Warning message")
        logger.error("Error message")
    }
}

class Slf4jLoggerTest {

    @Test
    fun `Slf4jLogger does not throw`() {
        val logger = Slf4jLogger()

        // Should not throw (even if SLF4J is not configured)
        logger.info("Test message")
        logger.error("Error message", null)
    }

    @Test
    fun `Slf4jLogger with custom name does not throw`() {
        val logger = Slf4jLogger("custom.logger")

        logger.debug("Custom logger message")
    }
}

class NoOpLoggerTest {

    @Test
    fun `NoOpLogger does nothing`() {
        NoOpLogger.debug("debug")
        NoOpLogger.info("info")
        NoOpLogger.warn("warn")
        NoOpLogger.error("error", RuntimeException("test"))
        NoOpLogger.logRequest("openai", "gpt-4o", emptyList(), false)
        NoOpLogger.logResponse("openai", "gpt-4o", com.kotlinllm.core.Message.assistant("test"), 100)
        NoOpLogger.logError("openai", "gpt-4o", RuntimeException("test"), 100)

        // If we get here, nothing threw
        assertTrue(true)
    }
}
