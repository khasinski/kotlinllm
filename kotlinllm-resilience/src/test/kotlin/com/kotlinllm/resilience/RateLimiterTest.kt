package com.kotlinllm.resilience

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class RateLimiterTest {

    @BeforeTest
    fun setup() {
        RateLimiters.clear()
    }

    @Test
    fun `TokenBucketRateLimiter allows requests within limit`() = runTest {
        val limiter = TokenBucketRateLimiter(RateLimitConfig(
            requestsPerMinute = 60,
            concurrentRequests = 10
        ))

        // Should be able to acquire immediately
        limiter.acquire()
        limiter.release()

        val stats = limiter.stats()
        assertEquals(1, stats.totalRequests)
        assertEquals(0, stats.rejectedRequests)
    }

    @Test
    fun `TokenBucketRateLimiter rejects when waitOnLimit is false`() = runTest {
        val limiter = TokenBucketRateLimiter(RateLimitConfig(
            requestsPerMinute = 1,
            concurrentRequests = 1,
            waitOnLimit = false
        ))

        // First request should succeed
        limiter.acquire()

        // Second request should fail immediately (concurrent limit)
        assertFailsWith<RateLimitExceededException> {
            limiter.acquire()
        }

        limiter.release()

        val stats = limiter.stats()
        assertEquals(1, stats.totalRequests)
        assertEquals(1, stats.rejectedRequests)
    }

    @Test
    fun `TokenBucketRateLimiter canProceed returns correct status`() = runTest {
        val limiter = TokenBucketRateLimiter(RateLimitConfig(
            requestsPerMinute = 60,
            concurrentRequests = 1
        ))

        assertTrue(limiter.canProceed())

        limiter.acquire()
        assertFalse(limiter.canProceed()) // Concurrent limit reached

        limiter.release()
        assertTrue(limiter.canProceed())
    }

    @Test
    fun `TokenBucketRateLimiter reset clears state`() = runTest {
        val limiter = TokenBucketRateLimiter(RateLimitConfig(
            requestsPerMinute = 60,
            concurrentRequests = 10
        ))

        limiter.acquire()
        limiter.release()

        val statsBefore = limiter.stats()
        assertEquals(1, statsBefore.totalRequests)

        limiter.reset()

        val statsAfter = limiter.stats()
        assertEquals(0, statsAfter.requestsInWindow)
    }

    @Test
    fun `RateLimitConfig presets are valid`() {
        // Verify presets have reasonable values
        assertTrue(RateLimitConfig.OPENAI_TIER_1.requestsPerMinute > 0)
        assertTrue(RateLimitConfig.OPENAI_TIER_2.requestsPerMinute > RateLimitConfig.OPENAI_TIER_1.requestsPerMinute)
        assertTrue(RateLimitConfig.ANTHROPIC_DEFAULT.requestsPerMinute > 0)
        assertTrue(RateLimitConfig.OLLAMA_LOCAL.requestsPerMinute > 0)
    }

    @Test
    fun `RateLimiters registry works correctly`() {
        RateLimiters.configure("test", RateLimitConfig(requestsPerMinute = 100))

        val limiter = RateLimiters.forProvider("test")
        assertNotNull(limiter)

        assertTrue(RateLimiters.providers().contains("test"))
    }

    @Test
    fun `RateLimiters provides default configs per provider`() {
        val openai = RateLimiters.forProvider("openai")
        val anthropic = RateLimiters.forProvider("anthropic")
        val ollama = RateLimiters.forProvider("ollama")

        assertNotNull(openai)
        assertNotNull(anthropic)
        assertNotNull(ollama)
    }

    @Test
    fun `RateLimiters allStats returns stats for all providers`() = runTest {
        RateLimiters.forProvider("provider1")
        RateLimiters.forProvider("provider2")

        val stats = RateLimiters.allStats()
        assertEquals(2, stats.size)
        assertTrue(stats.containsKey("provider1"))
        assertTrue(stats.containsKey("provider2"))
    }

    @Test
    fun `RateLimiterStats contains expected fields`() = runTest {
        val limiter = TokenBucketRateLimiter(RateLimitConfig())

        limiter.acquire()
        limiter.release()

        val stats = limiter.stats()
        assertTrue(stats.totalRequests >= 0)
        assertTrue(stats.windowStartTime > 0)
        assertTrue(stats.activeRequests >= 0)
    }
}
