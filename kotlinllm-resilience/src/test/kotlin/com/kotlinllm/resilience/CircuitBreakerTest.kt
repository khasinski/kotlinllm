package com.kotlinllm.resilience

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class CircuitBreakerTest {

    @BeforeTest
    fun setup() {
        CircuitBreakers.clear()
    }

    @Test
    fun `circuit breaker starts closed`() {
        val breaker = CircuitBreaker()
        assertEquals(CircuitState.CLOSED, breaker.currentState())
    }

    @Test
    fun `successful operations keep circuit closed`() = runTest {
        val breaker = CircuitBreaker(CircuitBreakerConfig(failureThreshold = 3))

        repeat(10) {
            breaker.execute { "success" }
        }

        assertEquals(CircuitState.CLOSED, breaker.currentState())

        val stats = breaker.stats()
        assertEquals(10L, stats.totalSuccesses)
        assertEquals(0L, stats.totalFailures)
    }

    @Test
    fun `failures open circuit after threshold`() = runTest {
        val breaker = CircuitBreaker(CircuitBreakerConfig(failureThreshold = 3))

        // Cause 3 failures
        repeat(3) {
            try {
                breaker.execute { throw RuntimeException("error") }
            } catch (e: RuntimeException) {
                // Expected
            }
        }

        assertEquals(CircuitState.OPEN, breaker.currentState())

        val stats = breaker.stats()
        assertEquals(3L, stats.totalFailures)
    }

    @Test
    fun `open circuit rejects requests`() = runTest {
        val breaker = CircuitBreaker(CircuitBreakerConfig(
            failureThreshold = 1,
            timeoutMs = 60000 // Long timeout so it stays open
        ))

        // Open the circuit
        try {
            breaker.execute { throw RuntimeException("error") }
        } catch (e: RuntimeException) {
            // Expected
        }

        assertEquals(CircuitState.OPEN, breaker.currentState())

        // Next request should be rejected
        assertFailsWith<CircuitBreakerOpenException> {
            breaker.execute { "should not execute" }
        }
    }

    @Test
    fun `circuit transitions to half-open after timeout`() = runBlocking {
        val breaker = CircuitBreaker(CircuitBreakerConfig(
            failureThreshold = 1,
            timeoutMs = 50 // Short timeout
        ))

        // Open the circuit
        try {
            breaker.execute { throw RuntimeException("error") }
        } catch (e: RuntimeException) {
            // Expected
        }

        assertEquals(CircuitState.OPEN, breaker.currentState())

        // Wait for timeout (real time)
        Thread.sleep(100)

        // Next check should allow execution (half-open)
        assertTrue(breaker.canExecute())
    }

    @Test
    fun `success in half-open state closes circuit`() = runBlocking {
        val breaker = CircuitBreaker(CircuitBreakerConfig(
            failureThreshold = 1,
            successThreshold = 1,
            timeoutMs = 50
        ))

        // Open the circuit
        try {
            breaker.execute { throw RuntimeException("error") }
        } catch (e: RuntimeException) {
            // Expected
        }

        // Wait for half-open (real time)
        Thread.sleep(100)

        // Successful execution should close
        breaker.execute { "success" }

        assertEquals(CircuitState.CLOSED, breaker.currentState())
    }

    @Test
    fun `failure in half-open state reopens circuit`() = runBlocking {
        val breaker = CircuitBreaker(CircuitBreakerConfig(
            failureThreshold = 1,
            timeoutMs = 50
        ))

        // Open the circuit
        try {
            breaker.execute { throw RuntimeException("error") }
        } catch (e: RuntimeException) {
            // Expected
        }

        // Wait for half-open (real time)
        Thread.sleep(100)

        // Another failure should reopen
        try {
            breaker.execute { throw RuntimeException("error again") }
        } catch (e: RuntimeException) {
            // Expected
        }

        assertEquals(CircuitState.OPEN, breaker.currentState())
    }

    @Test
    fun `manual reset closes circuit`() = runTest {
        val breaker = CircuitBreaker(CircuitBreakerConfig(failureThreshold = 1))

        // Open the circuit
        try {
            breaker.execute { throw RuntimeException("error") }
        } catch (e: RuntimeException) {
            // Expected
        }

        assertEquals(CircuitState.OPEN, breaker.currentState())

        // Manual reset
        breaker.reset()

        assertEquals(CircuitState.CLOSED, breaker.currentState())
    }

    @Test
    fun `manual trip opens circuit`() = runTest {
        val breaker = CircuitBreaker()

        assertEquals(CircuitState.CLOSED, breaker.currentState())

        breaker.trip()

        assertEquals(CircuitState.OPEN, breaker.currentState())
    }

    @Test
    fun `stats track all counters`() = runTest {
        val breaker = CircuitBreaker(CircuitBreakerConfig(
            failureThreshold = 5,
            timeoutMs = 60000
        ))

        // Some successes
        repeat(3) {
            breaker.execute { "success" }
        }

        // Some failures (not enough to open)
        repeat(2) {
            try {
                breaker.execute { throw RuntimeException("error") }
            } catch (e: RuntimeException) {
                // Expected
            }
        }

        val stats = breaker.stats()
        assertEquals(CircuitState.CLOSED, stats.state)
        assertEquals(5L, stats.totalRequests)
        assertEquals(3L, stats.totalSuccesses)
        assertEquals(2L, stats.totalFailures)
        assertEquals(2, stats.failureCount)
    }

    @Test
    fun `CircuitBreakers registry works`() {
        CircuitBreakers.configure("test", CircuitBreakerConfig(failureThreshold = 10))

        val breaker = CircuitBreakers.forProvider("test")
        assertNotNull(breaker)

        assertTrue(CircuitBreakers.providers().contains("test"))
    }

    @Test
    fun `CircuitBreakerConfig presets are valid`() {
        assertTrue(CircuitBreakerConfig.DEFAULT.failureThreshold > 0)
        assertTrue(CircuitBreakerConfig.LENIENT.failureThreshold > CircuitBreakerConfig.DEFAULT.failureThreshold)
        assertTrue(CircuitBreakerConfig.STRICT.failureThreshold < CircuitBreakerConfig.DEFAULT.failureThreshold)
    }
}
