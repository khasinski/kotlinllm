# KotlinLLM Observability

Metrics collection, logging, and tracing for LLM applications.

## Installation

```kotlin
dependencies {
    implementation("com.kotlinllm:kotlinllm-core:0.9.0")
    implementation("com.kotlinllm:kotlinllm-observability:0.9.0")
}
```

## Features

- **Metrics Collection**: Track latency, token usage, errors, and costs
- **Logging**: Configurable logging with SLF4J or console output
- **Tracing**: Request/response tracing with correlation IDs
- **Interceptors**: Plug into the LLM request pipeline

## Metrics Collection

### In-Memory Collector

```kotlin
import com.kotlinllm.observability.*

// Create collector
val metrics = InMemoryMetricsCollector()

// Add as interceptor
LLMInterceptors.add(MetricsInterceptor(metrics))

// Use chat normally
val chat = KotlinLLM.chat("gpt-4o")
chat.ask("Hello")
chat.ask("How are you?")

// Get metrics
val summary = metrics.getSummary()
println("Total requests: ${summary.totalRequests}")
println("Total tokens: ${summary.totalTokens}")
println("Average latency: ${summary.averageLatencyMs}ms")
println("Error rate: ${summary.errorRate}")

// Get per-model metrics
val gpt4Metrics = metrics.getMetrics("openai", "gpt-4o")
println("GPT-4o requests: ${gpt4Metrics.requestCount}")
```

### Metrics Recorded

| Metric | Description |
|--------|-------------|
| `requestCount` | Total number of requests |
| `successCount` | Successful requests |
| `errorCount` | Failed requests |
| `totalLatencyMs` | Cumulative latency |
| `totalInputTokens` | Input tokens consumed |
| `totalOutputTokens` | Output tokens generated |
| `streamingRequests` | Requests using streaming |

### Custom Metrics Collector

```kotlin
interface MetricsCollector {
    fun recordLatency(provider: String, model: String, operation: String, durationMs: Long)
    fun recordTokens(provider: String, model: String, inputTokens: Int, outputTokens: Int)
    fun recordError(provider: String, model: String, errorType: String)
    fun recordRequest(provider: String, model: String, streaming: Boolean)
}

// Implement for your monitoring system
class DataDogMetricsCollector : MetricsCollector {
    override fun recordLatency(provider: String, model: String, operation: String, durationMs: Long) {
        statsd.recordHistogram("llm.latency", durationMs, tags(provider, model))
    }
    // ... other methods
}
```

### Micrometer Integration

```kotlin
import io.micrometer.core.instrument.MeterRegistry

class MicrometerMetricsCollector(private val registry: MeterRegistry) : MetricsCollector {

    override fun recordLatency(provider: String, model: String, operation: String, durationMs: Long) {
        registry.timer("llm.request.duration", "provider", provider, "model", model)
            .record(durationMs, TimeUnit.MILLISECONDS)
    }

    override fun recordTokens(provider: String, model: String, inputTokens: Int, outputTokens: Int) {
        registry.counter("llm.tokens.input", "provider", provider, "model", model)
            .increment(inputTokens.toDouble())
        registry.counter("llm.tokens.output", "provider", provider, "model", model)
            .increment(outputTokens.toDouble())
    }

    override fun recordError(provider: String, model: String, errorType: String) {
        registry.counter("llm.errors", "provider", provider, "model", model, "type", errorType)
            .increment()
    }

    override fun recordRequest(provider: String, model: String, streaming: Boolean) {
        registry.counter("llm.requests", "provider", provider, "model", model, "streaming", streaming.toString())
            .increment()
    }
}
```

## Logging

### Console Logger

```kotlin
import com.kotlinllm.observability.*

// Add logging interceptor
LLMInterceptors.add(LoggingInterceptor(
    logger = ConsoleLogger(),
    level = LogLevel.DEBUG,
    logRequestBody = true,
    logResponseBody = true
))

// Logs will show:
// [DEBUG] LLM Request: provider=openai, model=gpt-4o, messages=1
// [DEBUG] LLM Response: provider=openai, model=gpt-4o, tokens=150, latency=1234ms
```

### SLF4J Logger

```kotlin
import com.kotlinllm.observability.*

// Uses SLF4J under the hood
LLMInterceptors.add(LoggingInterceptor(
    logger = Slf4jLogger(),
    level = LogLevel.INFO
))
```

### Log Levels

| Level | What's Logged |
|-------|---------------|
| `ERROR` | Only errors and exceptions |
| `WARN` | Errors + retries, rate limits |
| `INFO` | Errors + request summaries (provider, model, latency) |
| `DEBUG` | Everything + message counts, token usage |
| `TRACE` | Everything + full request/response bodies |

### Custom Logger

```kotlin
interface LLMLogger {
    fun log(level: LogLevel, message: String, metadata: Map<String, Any>)
}

class JsonLogger : LLMLogger {
    override fun log(level: LogLevel, message: String, metadata: Map<String, Any>) {
        val json = buildJsonObject {
            put("level", level.name)
            put("message", message)
            put("timestamp", System.currentTimeMillis())
            metadata.forEach { (k, v) -> put(k, v.toString()) }
        }
        println(json)
    }
}
```

## Tracing

Track requests with correlation IDs for distributed tracing.

```kotlin
import com.kotlinllm.observability.*

LLMInterceptors.add(TracingInterceptor(
    generateTraceId = { UUID.randomUUID().toString() },
    onTrace = { traceId, event, metadata ->
        // Send to your tracing system (Jaeger, Zipkin, etc.)
        println("[$traceId] $event: $metadata")
    }
))
```

### Trace Events

| Event | When |
|-------|------|
| `request.start` | Before sending request |
| `request.end` | After receiving response |
| `request.error` | On error |
| `tool.start` | Before tool execution |
| `tool.end` | After tool execution |

## Complete Setup

```kotlin
import com.kotlinllm.observability.*
import com.kotlinllm.resilience.*
import com.kotlinllm.core.LLMInterceptors

// Create collectors
val metrics = InMemoryMetricsCollector()

// Add interceptors (order: outer to inner)
LLMInterceptors.add(TracingInterceptor())                    // Tracing first
LLMInterceptors.add(LoggingInterceptor(level = LogLevel.INFO))
LLMInterceptors.add(MetricsInterceptor(metrics))
LLMInterceptors.add(RetryInterceptor())                      // Resilience
LLMInterceptors.add(CircuitBreakerInterceptor())
LLMInterceptors.add(RateLimitingInterceptor())               // Rate limit last

// Use normally
val chat = KotlinLLM.chat("gpt-4o")
chat.ask("Hello")

// Check metrics
println(metrics.getSummary())
```

## Java Usage

```java
import com.kotlinllm.observability.*;
import com.kotlinllm.core.LLMInterceptors;

// Create metrics collector
InMemoryMetricsCollector metrics = new InMemoryMetricsCollector();

// Add interceptors
LLMInterceptors.add(new LoggingInterceptor(
    new ConsoleLogger(),
    LogLevel.INFO,
    false,  // logRequestBody
    false   // logResponseBody
));
LLMInterceptors.add(new MetricsInterceptor(metrics));

// Use chat
JavaChat chat = JavaLLM.chat("gpt-4o");
chat.ask("Hello");

// Get metrics
MetricsSummary summary = metrics.getSummary();
System.out.println("Requests: " + summary.getTotalRequests());
System.out.println("Latency: " + summary.getAverageLatencyMs() + "ms");
```

## Dashboard Example

Build a simple metrics dashboard:

```kotlin
fun printDashboard(metrics: InMemoryMetricsCollector) {
    val summary = metrics.getSummary()

    println("""
        ╔════════════════════════════════════════╗
        ║         LLM Metrics Dashboard          ║
        ╠════════════════════════════════════════╣
        ║ Total Requests:    ${summary.totalRequests.toString().padStart(18)} ║
        ║ Success Rate:      ${String.format("%.1f%%", (1 - summary.errorRate) * 100).padStart(18)} ║
        ║ Avg Latency:       ${String.format("%.0fms", summary.averageLatencyMs).padStart(18)} ║
        ║ Total Tokens:      ${summary.totalTokens.toString().padStart(18)} ║
        ╚════════════════════════════════════════╝
    """.trimIndent())
}
```
