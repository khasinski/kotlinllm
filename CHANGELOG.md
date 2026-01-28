# Changelog

All notable changes to KotlinLLM will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.9.0] - 2025-01-22

### Added

#### Core Module (`kotlinllm-core`)
- **Multi-provider support**: OpenAI, Anthropic Claude, and Ollama (local)
- **Chat API**: Fluent builder pattern with `KotlinLLM.chat("model")`
- **Streaming**: Real-time token streaming via Kotlin Flow
- **Tool/Function calling**: Define tools with typed parameters, automatic execution
- **Interceptor framework**: `LLMInterceptor` for cross-cutting concerns (logging, metrics, retry)
- **Memory abstractions**: `BufferMemory`, `WindowMemory`, `TokenMemory`, `SummaryMemory`
- **Structured output**: Type-safe responses with `askStructured<T>()` using kotlinx.serialization
- **Java interop**: `JavaLLM`, `JavaChat`, `JavaMemory` wrappers with CompletableFuture support
- **Exception hierarchy**: `LLMException` base class with `ProviderException`, `ResilienceException`, etc.

#### Resilience Module (`kotlinllm-resilience`)
- **Rate limiting**: Token bucket algorithm with per-provider configuration
- **Circuit breaker**: Automatic failure detection with CLOSED/OPEN/HALF_OPEN states
- **Retry interceptor**: Configurable retry with exponential backoff
- **Pre-built configs**: `RateLimitConfig.OPENAI_TIER_1`, `ANTHROPIC_DEFAULT`, etc.

#### Observability Module (`kotlinllm-observability`)
- **Metrics collection**: `InMemoryMetricsCollector` for latency, tokens, errors
- **Logging**: `ConsoleLogger`, `Slf4jLogger` with configurable levels
- **Interceptors**: `MetricsInterceptor`, `LoggingInterceptor`, `TracingInterceptor`

#### Documents Module (`kotlinllm-documents`)
- **Document loaders**: Text, Markdown, HTML (Jsoup), PDF (PDFBox)
- **Chunking strategies**: `CharacterChunker`, `SentenceChunker`, `ParagraphChunker`
- **Chat integration**: `chat.withDocument(path)` DSL
- **Java API**: `JavaDocuments`, `JavaDocumentChat`, `JavaDocumentsBuilder`

#### Persistence Modules
- **JPA adapter** (`kotlinllm-jpa`): Store conversations in any JPA-compatible database
- **Exposed adapter** (`kotlinllm-exposed`): Store conversations using JetBrains Exposed

### Supported Models
- OpenAI: GPT-4o, GPT-4o-mini, GPT-4.1, GPT-5, o1, o3, o3-mini, o4-mini
- Anthropic: Claude Opus 4, Claude Sonnet 4, Claude 3.5 Sonnet/Haiku
- Ollama: llama3, mistral, codellama, phi, gemma, and other local models

## [0.1.0] - 2025-01-15

### Added
- Initial release
- Basic OpenAI and Anthropic provider support
- Simple chat API
- Tool calling support
- Streaming support

[Unreleased]: https://github.com/khasinski/kotlinllm/compare/v0.9.0...HEAD
[0.9.0]: https://github.com/khasinski/kotlinllm/compare/v0.1.0...v0.9.0
[0.1.0]: https://github.com/khasinski/kotlinllm/releases/tag/v0.1.0
