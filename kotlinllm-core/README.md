# KotlinLLM

A beautiful Kotlin API for Large Language Models. Inspired by [RubyLLM](https://rubyllm.com/).

```kotlin
// That's it. Seriously.
val response = KotlinLLM.chat().ask("Hello!")
```

## Features

- **Simple & Elegant** - One beautiful API for all providers
- **Multiple Providers** - OpenAI, Anthropic, and more
- **Kotlin-First** - DSL, coroutines, Flow-based streaming
- **Java-Friendly** - Full Java API with CompletableFuture support
- **Minimal Dependencies** - Just OkHttp, kotlinx.serialization, and coroutines
- **Tool Support** - Function calling with type-safe parameters
- **Streaming** - Real-time responses with Kotlin Flow

## Installation

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    implementation("com.kotlinllm:kotlinllm:0.1.0")
}
```

### Maven

```xml
<dependency>
    <groupId>com.kotlinllm</groupId>
    <artifactId>kotlinllm</artifactId>
    <version>0.1.0</version>
</dependency>
```

## Quick Start

### Configuration

```kotlin
// Option 1: Environment variables (recommended)
// Set OPENAI_API_KEY and/or ANTHROPIC_API_KEY

// Option 2: Programmatic
KotlinLLM.configure {
    openaiApiKey = "sk-..."
    anthropicApiKey = "sk-ant-..."
    defaultModel = "gpt-4o-mini"
}
```

### Simple Chat

```kotlin
// One-liner
val response = KotlinLLM.chat().ask("What is the capital of France?")
println(response.text) // "The capital of France is Paris."

// With specific model
val claudeResponse = KotlinLLM.chat("claude-sonnet-4-20250514").ask("Hello!")
```

### DSL Syntax

```kotlin
import com.kotlinllm.dsl.*

// Elegant DSL
val chat = chat("gpt-4o") {
    system("You are a helpful coding assistant")
    temperature(0.7)
}

val response = chat.ask("Explain Kotlin coroutines")
```

### Conversation with History

```kotlin
val chat = KotlinLLM.chat("claude-sonnet-4-20250514")
    .withInstructions("You are a math tutor")

chat.ask("What is 2 + 2?")     // "2 + 2 equals 4"
chat.ask("Multiply that by 10") // "4 × 10 equals 40"
chat.ask("Now divide by 2")     // "40 ÷ 2 equals 20"
```

### Streaming

```kotlin
// Collect chunks as they arrive
chat.askStreaming("Write a poem about Kotlin")
    .collect { chunk ->
        print(chunk.content) // Print each piece as it streams
    }
```

### Tools (Function Calling)

```kotlin
// Define a tool
class Calculator : Tool(
    name = "calculator",
    description = "Performs arithmetic calculations"
) {
    override suspend fun execute(args: Map<String, JsonElement>): Any {
        val expression = args.string("expression")
        // ... evaluate expression
        return "Result: $result"
    }
}

// Use it
val chat = KotlinLLM.chat("gpt-4o")
    .withTool(Calculator())

chat.ask("What is 42 * 17?") // Tool is called automatically
```

### DSL Tool Creation

```kotlin
import com.kotlinllm.dsl.*

val weatherTool = tool("get_weather", "Gets current weather") {
    param("city", "The city name")
    param("unit", "Temperature unit", required = false)

    execute { args ->
        val city = args.string("city")
        // Call weather API...
        "Weather in $city: 22°C, Sunny"
    }
}
```

## Supported Providers

| Provider | Models | Status |
|----------|--------|--------|
| OpenAI | GPT-4o, GPT-4, GPT-3.5, o1, o3 | ✅ |
| Anthropic | Claude 4, Claude 3.5, Claude 3 | ✅ |
| Google | Gemini | 🚧 Coming soon |
| Ollama | Local models | 🚧 Coming soon |

## Java Support

KotlinLLM works great from Java too! See [JAVA.md](JAVA.md) for full documentation.

```java
// Configure
KotlinLLM.configure(config -> {
    config.setOpenaiApiKey(System.getenv("OPENAI_API_KEY"));
    return Unit.INSTANCE;
});

// Chat
String response = JavaLLM.quickChat("Hello!");

// Or with more control
Chat chat = KotlinLLM.chat("gpt-4o");
Message msg = JavaLLM.ask(chat, "Hello!");
System.out.println(msg.getText());
```

## API Reference

### KotlinLLM Object

```kotlin
KotlinLLM.configure { }     // Configure settings
KotlinLLM.config()          // Get current config
KotlinLLM.chat()            // Create chat with default model
KotlinLLM.chat(model)       // Create chat with specific model
KotlinLLM.providers()       // List available providers
```

### Chat Class

```kotlin
chat.ask(message)              // Send message, get response
chat.askStreaming(message)     // Stream response
chat.withModel(model)          // Change model
chat.withInstructions(text)    // Set system prompt
chat.withTool(tool)            // Add a tool
chat.withTools(t1, t2, ...)    // Add multiple tools
chat.withTemperature(0.7)      // Set temperature
chat.withMaxTokens(1000)       // Set max tokens
chat.messages()                // Get conversation history
chat.reset()                   // Clear history
chat.fork()                    // Create a copy
```

### Message Class

```kotlin
message.text        // Get text content
message.role        // SYSTEM, USER, ASSISTANT, TOOL
message.toolCalls   // List of tool calls (if any)
message.tokens      // Token usage statistics
message.isToolCall()
message.isToolResult()
```

## Comparison with Alternatives

| Feature | KotlinLLM | Spring AI | LangChain4j |
|---------|-----------|-----------|-------------|
| Min dependencies | 3 | Many | Many |
| Kotlin DSL | ✅ | ❌ | 🟡 |
| Learning curve | Low | Medium | High |
| Framework required | No | Spring | No |
| Line count for hello world | 1 | ~20 | ~10 |

## Philosophy

KotlinLLM follows the same philosophy as RubyLLM:

> **Simple things should be simple, complex things should be possible.**

- Minimal dependencies (just 3!)
- Sensible defaults
- Progressive disclosure of complexity
- Provider-agnostic API
- Kotlin-idiomatic design

## Contributing

Contributions are welcome! Areas where help is needed:

- Additional providers (Gemini, Ollama, Mistral, etc.)
- More examples and documentation
- Performance optimizations
- Test coverage

## License

MIT License - see [LICENSE](LICENSE) for details.

## Acknowledgments

- [RubyLLM](https://rubyllm.com/) - The inspiration for this project
- [OkHttp](https://square.github.io/okhttp/) - HTTP client
- [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) - JSON handling
