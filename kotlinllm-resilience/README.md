# KotlinLLM Resilience

Production-ready resilience patterns for LLM applications: rate limiting, circuit breakers, and retry logic.

## Installation

```kotlin
dependencies {
    implementation("com.kotlinllm:kotlinllm-core:0.9.0")
    implementation("com.kotlinllm:kotlinllm-resilience:0.9.0")
}
```

## Features

- **Rate Limiting**: Token bucket algorithm with per-provider configuration
- **Circuit Breaker**: Automatic failure detection with configurable thresholds
- **Retry Logic**: Exponential backoff with jitter
- **Interceptors**: Plug into the LLM request pipeline

## Rate Limiting

Prevent exceeding provider API limits.

### Basic Usage

```kotlin
import com.kotlinllm.resilience.*

// Create a rate limiter
val limiter = TokenBucketRateLimiter(RateLimitConfig(
    requestsPerMinute = 60,
    tokensPerMinute = 100_000,
    concurrentRequests = 10
))

// Use manually
limiter.acquire()  // Blocks until permit available
try {
    // Make API call
} finally {
    limiter.release()
}

// Or use execute helper
limiter.execute {
    chat.ask("Hello")
}
```

### Pre-built Configurations

```kotlin
// OpenAI tiers
RateLimitConfig.OPENAI_TIER_1    // 500 RPM, 30K TPM
RateLimitConfig.OPENAI_TIER_2    // 5000 RPM, 450K TPM
RateLimitConfig.OPENAI_TIER_3    // 10000 RPM

// Anthropic
RateLimitConfig.ANTHROPIC_DEFAULT  // 60 RPM, 100K TPM

// Local models
RateLimitConfig.OLLAMA_LOCAL       // 1000 RPM, unlimited tokens
```

### Per-Provider Registry

```kotlin
// Configure rate limits per provider
RateLimiters.configure("openai", RateLimitConfig.OPENAI_TIER_2)
RateLimiters.configure("anthropic", RateLimitConfig.ANTHROPIC_DEFAULT)

// Get limiter for a provider
val limiter = RateLimiters.forProvider("openai")

// Check stats
val stats = limiter.stats()
println("Requests in window: ${stats.requestsInWindow}")
println("Active requests: ${stats.activeRequests}")
```

### As Interceptor

```kotlin
import com.kotlinllm.resilience.RateLimitingInterceptor
import com.kotlinllm.core.LLMInterceptors

// Add to global interceptor chain
LLMInterceptors.add(RateLimitingInterceptor())

// All chat requests will now be rate-limited
val chat = KotlinLLM.chat("gpt-4o")
chat.ask("Hello")  // Automatically rate-limited
```

## Circuit Breaker

Fail fast when a provider is having issues.

### Basic Usage

```kotlin
import com.kotlinllm.resilience.*

val breaker = CircuitBreaker(CircuitBreakerConfig(
    failureThreshold = 5,      // Open after 5 failures
    successThreshold = 2,      // Close after 2 successes in half-open
    timeoutMs = 30_000         // Try again after 30 seconds
))

// Use with execute
try {
    breaker.execute {
        chat.ask("Hello")
    }
} catch (e: CircuitBreakerOpenException) {
    println("Circuit is open, failing fast")
}

// Check state
when (breaker.currentState()) {
    CircuitState.CLOSED -> println("Normal operation")
    CircuitState.OPEN -> println("Failing fast")
    CircuitState.HALF_OPEN -> println("Testing recovery")
}
```

### Pre-built Configurations

```kotlin
CircuitBreakerConfig.DEFAULT   // 5 failures, 30s timeout
CircuitBreakerConfig.STRICT    // 3 failures, 60s timeout
CircuitBreakerConfig.LENIENT   // 10 failures, 15s timeout
```

### Per-Provider Registry

```kotlin
// Configure per provider
CircuitBreakers.configure("openai", CircuitBreakerConfig.DEFAULT)
CircuitBreakers.configure("anthropic", CircuitBreakerConfig.STRICT)

// Get breaker
val breaker = CircuitBreakers.forProvider("openai")

// Manual controls
breaker.trip()   // Force open
breaker.reset()  // Force closed

// Stats
val stats = breaker.stats()
println("State: ${stats.state}")
println("Failures: ${stats.totalFailures}")
```

### As Interceptor

```kotlin
import com.kotlinllm.resilience.CircuitBreakerInterceptor
import com.kotlinllm.core.LLMInterceptors

LLMInterceptors.add(CircuitBreakerInterceptor())

// Requests will fail fast if circuit is open
```

## Retry Logic

Automatically retry failed requests.

```kotlin
import com.kotlinllm.resilience.RetryInterceptor
import com.kotlinllm.resilience.RetryConfig
import com.kotlinllm.core.LLMInterceptors

val retryInterceptor = RetryInterceptor(RetryConfig(
    maxRetries = 3,
    initialDelayMs = 1000,
    maxDelayMs = 30_000,
    multiplier = 2.0,
    retryOn = { e -> e is ProviderException && e.isRetryable }
))

LLMInterceptors.add(retryInterceptor)
```

## Combining Patterns

Use all resilience patterns together:

```kotlin
import com.kotlinllm.resilience.*
import com.kotlinllm.core.LLMInterceptors

// Order matters: retry -> circuit breaker -> rate limit
LLMInterceptors.add(RetryInterceptor())           // Outermost
LLMInterceptors.add(CircuitBreakerInterceptor())  // Middle
LLMInterceptors.add(RateLimitingInterceptor())    // Innermost (closest to API)

// Now all requests have full resilience
val chat = KotlinLLM.chat("gpt-4o")
val response = chat.ask("Hello")  // Retried, circuit-broken, rate-limited
```

## Java Usage

```java
import com.kotlinllm.resilience.*;

// Rate limiting
RateLimiter limiter = new TokenBucketRateLimiter(
    new RateLimitConfig(60, 100000, 10, true)
);

limiter.acquire();
try {
    // Make API call
} finally {
    limiter.release();
}

// Circuit breaker
CircuitBreaker breaker = new CircuitBreaker(CircuitBreakerConfig.getDEFAULT());

try {
    breaker.execute(() -> {
        return chat.ask("Hello");
    });
} catch (CircuitBreakerOpenException e) {
    System.out.println("Circuit open");
}
```

## Exception Handling

```kotlin
import com.kotlinllm.core.*

try {
    breaker.execute { chat.ask("Hello") }
} catch (e: CircuitBreakerOpenException) {
    // Circuit is open, fail fast
} catch (e: RateLimitExceededException) {
    // Local rate limit exceeded (waitOnLimit = false)
} catch (e: ProviderRateLimitException) {
    // Provider returned 429
    println("Retry after: ${e.retryAfterSeconds}")
}
```
