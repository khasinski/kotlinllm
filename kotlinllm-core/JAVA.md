# KotlinLLM for Java Developers

This guide shows how to use KotlinLLM from Java code. While KotlinLLM is written in Kotlin, it provides a Java-friendly API through the `JavaLLM` and `JavaChat` classes.

## Installation

### Gradle (Groovy)

```groovy
dependencies {
    implementation 'com.kotlinllm:kotlinllm:0.9.0'
}
```

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    implementation("com.kotlinllm:kotlinllm:0.9.0")
}
```

### Maven

```xml
<dependency>
    <groupId>com.kotlinllm</groupId>
    <artifactId>kotlinllm</artifactId>
    <version>0.9.0</version>
</dependency>
```

## Quick Start

### Configuration

```java
import com.kotlinllm.KotlinLLM;
import kotlin.Unit;

public class Main {
    public static void main(String[] args) {
        // Configure from environment variables (recommended)
        // Set OPENAI_API_KEY and/or ANTHROPIC_API_KEY

        // Or configure programmatically
        KotlinLLM.configure(config -> {
            config.setOpenaiApiKey(System.getenv("OPENAI_API_KEY"));
            config.setAnthropicApiKey(System.getenv("ANTHROPIC_API_KEY"));
            config.setDefaultModel("gpt-4o-mini");
            return Unit.INSTANCE;
        });
    }
}
```

### Simple Chat (Blocking)

```java
import com.kotlinllm.JavaLLM;
import com.kotlinllm.JavaChat;
import com.kotlinllm.core.Message;

public class SimpleChat {
    public static void main(String[] args) {
        // Quick one-liner
        String response = JavaLLM.quickChat("What is the capital of France?");
        System.out.println(response);

        // With specific model
        String claudeResponse = JavaLLM.quickChat(
            "Explain quantum computing",
            "claude-sonnet-4-20250514"
        );
        System.out.println(claudeResponse);
    }
}
```

### Chat with Conversation History

```java
import com.kotlinllm.JavaLLM;
import com.kotlinllm.JavaChat;
import com.kotlinllm.core.Message;

public class ConversationExample {
    public static void main(String[] args) {
        // Create a chat instance with fluent configuration
        JavaChat chat = JavaLLM.chat("gpt-4o")
            .withInstructions("You are a helpful math tutor.")
            .withTemperature(0.7);

        // First question
        Message response1 = chat.ask("What is 2 + 2?");
        System.out.println("Assistant: " + response1.getText());

        // Follow-up (maintains conversation history)
        Message response2 = chat.ask("Now multiply that by 10");
        System.out.println("Assistant: " + response2.getText());
    }
}
```

### Fluent API

```java
import com.kotlinllm.JavaLLM;
import com.kotlinllm.JavaChat;
import com.kotlinllm.core.Message;

public class FluentExample {
    public static void main(String[] args) {
        JavaChat chat = JavaLLM.chat("claude-sonnet-4-20250514")
            .withInstructions("You are a helpful coding assistant.")
            .withTemperature(0.5)
            .withMaxTokens(1000);

        Message response = chat.ask("Write a hello world in Java");
        System.out.println(response.getText());
    }
}
```

## Async Operations with CompletableFuture

```java
import com.kotlinllm.JavaLLM;
import com.kotlinllm.JavaChat;
import com.kotlinllm.core.Message;
import java.util.concurrent.CompletableFuture;

public class AsyncExample {
    public static void main(String[] args) {
        JavaChat chat = JavaLLM.chat("gpt-4o");

        // Async call
        CompletableFuture<Message> future = chat.askAsync("Hello!");

        // Do other work while waiting...
        System.out.println("Request sent, doing other work...");

        // Handle result
        future.thenAccept(response -> {
            System.out.println("Response: " + response.getText());
        }).exceptionally(e -> {
            System.err.println("Error: " + e.getMessage());
            return null;
        });

        // Wait for completion (in real app, you might not need this)
        future.join();
    }
}
```

## Streaming Responses

```java
import com.kotlinllm.JavaLLM;
import com.kotlinllm.JavaChat;

public class StreamingExample {
    public static void main(String[] args) {
        JavaChat chat = JavaLLM.chat("gpt-4o");

        // Streaming with callback
        chat.askStreaming("Write a poem about coding", chunk -> {
            // Print each chunk as it arrives
            System.out.print(chunk.getContent());
        });

        System.out.println(); // New line after streaming completes
    }
}
```

### Async Streaming

```java
import com.kotlinllm.JavaLLM;
import com.kotlinllm.JavaChat;
import java.util.concurrent.CompletableFuture;

public class AsyncStreamingExample {
    public static void main(String[] args) {
        JavaChat chat = JavaLLM.chat("gpt-4o");

        CompletableFuture<Void> future = chat.askStreamingAsync(
            "Explain REST APIs",
            chunk -> System.out.print(chunk.getContent())
        );

        // Do other work while streaming...

        future.join(); // Wait for completion
    }
}
```

## Creating Tools (Function Calling)

```java
import com.kotlinllm.JavaLLM;
import com.kotlinllm.JavaChat;
import com.kotlinllm.core.Tool;
import com.kotlinllm.core.Message;

public class ToolsExample {
    public static void main(String[] args) {
        // Create a calculator tool
        Tool calculator = JavaLLM.toolBuilder("calculator", "Performs arithmetic calculations")
            .param("expression", "The mathematical expression to evaluate")
            .execute(args -> {
                String expression = args.getString("expression");
                // Simple evaluation (in production, use a proper expression parser)
                try {
                    double result = evaluateExpression(expression);
                    return "Result: " + result;
                } catch (Exception e) {
                    return "Error: " + e.getMessage();
                }
            })
            .build();

        // Create a weather tool
        Tool weather = JavaLLM.toolBuilder("get_weather", "Gets the current weather")
            .param("city", "The city name")
            .param("unit", "Temperature unit (celsius/fahrenheit)", "string", false)
            .execute(args -> {
                String city = args.getString("city");
                String unit = args.getStringOrNull("unit");
                // In production, call a real weather API
                return "Weather in " + city + ": 22°C, Sunny";
            })
            .build();

        // Use tools in chat
        JavaChat chat = JavaLLM.chat("gpt-4o")
            .withTool(calculator)
            .withTool(weather);

        Message response = chat.ask("What's 15 * 7? Also, what's the weather in Tokyo?");
        System.out.println(response.getText());
    }

    private static double evaluateExpression(String expr) {
        // Simplified - use a proper library in production
        javax.script.ScriptEngineManager mgr = new javax.script.ScriptEngineManager();
        javax.script.ScriptEngine engine = mgr.getEngineByName("JavaScript");
        try {
            return ((Number) engine.eval(expr)).doubleValue();
        } catch (Exception e) {
            throw new RuntimeException("Failed to evaluate: " + expr, e);
        }
    }
}
```

## Working with Different Providers

```java
import com.kotlinllm.JavaLLM;
import com.kotlinllm.JavaChat;
import com.kotlinllm.core.Message;

public class ProvidersExample {
    public static void main(String[] args) {
        // OpenAI
        JavaChat openaiChat = JavaLLM.chat("gpt-4o");
        Message openaiResponse = openaiChat.ask("Hello from OpenAI!");

        // Anthropic Claude
        JavaChat claudeChat = JavaLLM.chat("claude-sonnet-4-20250514");
        Message claudeResponse = claudeChat.ask("Hello from Claude!");

        // The model is auto-detected from the model name
        // gpt-* -> OpenAI
        // claude-* -> Anthropic
        // gemini-* -> Google (when implemented)
    }
}
```

## Error Handling

```java
import com.kotlinllm.JavaLLM;
import com.kotlinllm.providers.OpenAIException;
import com.kotlinllm.providers.AnthropicException;

public class ErrorHandlingExample {
    public static void main(String[] args) {
        try {
            String response = JavaLLM.quickChat("Hello!");
            System.out.println(response);
        } catch (OpenAIException e) {
            System.err.println("OpenAI error (" + e.getStatusCode() + "): " + e.getMessage());
        } catch (AnthropicException e) {
            System.err.println("Anthropic error (" + e.getStatusCode() + "): " + e.getMessage());
        } catch (Exception e) {
            System.err.println("Unexpected error: " + e.getMessage());
        }
    }
}
```

## Spring Boot Integration

```java
import com.kotlinllm.KotlinLLM;
import com.kotlinllm.JavaLLM;
import com.kotlinllm.JavaChat;
import com.kotlinllm.core.Message;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;
import kotlin.Unit;

@Configuration
public class KotlinLLMConfig {

    @Value("${openai.api.key}")
    private String openaiApiKey;

    @Value("${anthropic.api.key:}")
    private String anthropicApiKey;

    @Bean
    public void configureKotlinLLM() {
        KotlinLLM.configure(config -> {
            config.setOpenaiApiKey(openaiApiKey);
            if (!anthropicApiKey.isEmpty()) {
                config.setAnthropicApiKey(anthropicApiKey);
            }
            return Unit.INSTANCE;
        });
    }
}

@Service
public class ChatService {

    public String chat(String message) {
        return JavaLLM.quickChat(message);
    }

    public String chatWithModel(String message, String model) {
        JavaChat chat = JavaLLM.chat(model);
        Message response = chat.ask(message);
        return response.getText();
    }
}
```

## Complete Example

```java
import com.kotlinllm.KotlinLLM;
import com.kotlinllm.JavaLLM;
import com.kotlinllm.JavaChat;
import com.kotlinllm.core.Message;
import com.kotlinllm.core.Tool;
import kotlin.Unit;

public class CompleteExample {
    public static void main(String[] args) {
        // 1. Configure
        KotlinLLM.configure(config -> {
            config.setOpenaiApiKey(System.getenv("OPENAI_API_KEY"));
            config.setDefaultModel("gpt-4o-mini");
            return Unit.INSTANCE;
        });

        // 2. Create a tool
        Tool searchTool = JavaLLM.toolBuilder("search", "Search for information")
            .param("query", "The search query")
            .execute(args -> {
                String query = args.getString("query");
                // Simulated search result
                return "Search results for '" + query + "': Found 3 relevant articles...";
            })
            .build();

        // 3. Create chat with tools and instructions
        JavaChat chat = JavaLLM.chat("gpt-4o")
            .withInstructions("You are a helpful research assistant. Use the search tool when needed.")
            .withTool(searchTool)
            .withTemperature(0.7);

        // 4. Have a conversation
        System.out.println("User: What are the latest developments in AI?");
        Message response = chat.ask("What are the latest developments in AI?");
        System.out.println("Assistant: " + response.getText());

        System.out.println("\nUser: Tell me more about LLMs specifically");
        response = chat.ask("Tell me more about LLMs specifically");
        System.out.println("Assistant: " + response.getText());

        // 5. Check token usage
        System.out.println("\nTotal tokens used: " + chat.getTotalTokens());
    }
}
```

## Memory Management

KotlinLLM provides several memory strategies to manage conversation history automatically.

### Buffer Memory (Last N Messages)

```java
import com.kotlinllm.JavaLLM;
import com.kotlinllm.JavaMemory;
import com.kotlinllm.JavaMemoryChat;
import com.kotlinllm.memory.Memory;

public class MemoryExample {
    public static void main(String[] args) {
        // Create a buffer memory that keeps the last 20 messages
        Memory memory = JavaMemory.buffer(20);

        // Or with system message preservation
        Memory memoryWithSystem = JavaMemory.buffer(20, true);

        // Create chat with memory
        JavaMemoryChat chat = JavaLLM.chat("gpt-4o")
            .withMemory(memory);

        // Messages are automatically tracked
        chat.ask("My name is Alice");
        chat.ask("What's my name?"); // Will remember "Alice"

        // Access memory stats
        var stats = chat.getMemoryStats();
        System.out.println("Messages in memory: " + stats.getTotalMessages());
        System.out.println("Estimated tokens: " + stats.getEstimatedTokens());
    }
}
```

### Window Memory (Last N Turns)

```java
import com.kotlinllm.JavaMemory;
import com.kotlinllm.memory.Memory;

// Keep last 10 conversation turns (user + assistant pairs)
Memory memory = JavaMemory.window(10);

// With system message preservation
Memory memory = JavaMemory.window(10, true);
```

### Token-Limited Memory

```java
import com.kotlinllm.JavaMemory;
import com.kotlinllm.memory.Memory;

// Keep messages that fit within 4000 tokens
Memory memory = JavaMemory.tokenLimited(4000);

// With system message preservation
Memory memory = JavaMemory.tokenLimited(4000, true);
```

### Memory Chat Operations

```java
JavaMemoryChat chat = JavaLLM.chat("gpt-4o")
    .withMemory(JavaMemory.buffer(20))
    .withInstructions("You are a helpful assistant")
    .withTemperature(0.7);

// Ask questions (automatically manages memory)
chat.ask("Hello!");
chat.askAsync("How are you?").join();

// Access memory
List<Message> allMessages = chat.getAllMessages();
List<Message> contextMessages = chat.getContextMessages();

// Clear memory
chat.clearMemory();

// Get underlying objects for advanced use
Memory memory = chat.unwrapMemory();
Chat kotlinChat = chat.unwrapChat();
```

## Structured Output (Typed Responses)

Request responses in a specific format using kotlinx.serialization.

### Basic Usage

```java
import com.kotlinllm.JavaLLM;
import com.kotlinllm.JavaChat;
import com.kotlinllm.JavaStructuredResult;
import com.kotlinllm.JavaStructuredConfig;
import kotlinx.serialization.Serializable;

// Define your data class (must be @Serializable in Kotlin)
// For Java, you'll typically define this in a Kotlin file:
// @Serializable
// data class Person(val name: String, val age: Int, val occupation: String?)

public class StructuredOutputExample {
    public static void main(String[] args) {
        JavaChat chat = JavaLLM.chat("gpt-4o");

        // Get the serializer for your class (from Kotlin)
        var serializer = Person.serializer();

        // Request structured output
        JavaStructuredResult<Person> result = chat.askStructured(
            "Extract person info from: John is a 30 year old software engineer",
            serializer
        );

        if (result.isSuccess()) {
            Person person = result.getValue();
            System.out.println("Name: " + person.getName());
            System.out.println("Age: " + person.getAge());
        } else {
            System.out.println("Error: " + result.getError());
            System.out.println("Raw response: " + result.getRawResponse());
        }
    }
}
```

### With Configuration

```java
import com.kotlinllm.JavaStructuredConfig;

// Configure retry behavior and temperature
JavaStructuredConfig config = JavaStructuredConfig.defaults()
    .maxRetries(5)
    .temperature(0.1)
    .maxTokens(500);

JavaStructuredResult<Person> result = chat.askStructured(
    "Extract person info from the text...",
    Person.serializer(),
    config
);
```

### Throwing Version

```java
// Throws IllegalStateException on failure
Person person = chat.askTyped(
    "Extract: Alice is 25",
    Person.serializer()
);
```

### Async Structured Output

```java
CompletableFuture<JavaStructuredResult<Person>> future = chat.askStructuredAsync(
    "Extract person info...",
    Person.serializer()
);

future.thenAccept(result -> {
    if (result.isSuccess()) {
        System.out.println(result.getValue());
    }
});
```

## Document Context

Add document content as context for your chat.

### Simple Document Context

```java
import com.kotlinllm.JavaLLM;
import com.kotlinllm.JavaChat;

public class DocumentContextExample {
    public static void main(String[] args) {
        JavaChat chat = JavaLLM.chat("gpt-4o")
            .withDocumentContext("API Documentation", apiDocContent)
            .withInstructions("Answer questions about the API");

        Message response = chat.ask("How do I authenticate?");
    }
}
```

### Multiple Documents

```java
import java.util.List;
import kotlin.Pair;

List<Pair<String, String>> documents = List.of(
    new Pair<>("README", readmeContent),
    new Pair<>("API Docs", apiContent),
    new Pair<>(null, additionalContent)  // No title
);

JavaChat chat = JavaLLM.chat("gpt-4o")
    .withDocumentsContext(documents);
```

> **Note:** For advanced document loading (PDF, HTML, URLs), see the `kotlinllm-documents` module documentation.

## Tips for Java Developers

1. **Null Safety**: KotlinLLM uses Kotlin's null safety. In Java, use `@Nullable` annotations or check for null values.

2. **Unit Type**: When using lambdas that don't return a value (like `configure`), return `Unit.INSTANCE`.

3. **Blocking vs Async**: Use methods ending in `Async` for non-blocking operations.

4. **Streaming**: Use `askStreaming` for real-time response display.

5. **Thread Safety**: Chat instances are not thread-safe. Create separate instances for concurrent use.

6. **Unwrapping**: If you need access to the underlying Kotlin objects, use `unwrap()`, `unwrapMemory()`, etc.

7. **Serializers**: For structured output, define your data classes in Kotlin with `@Serializable` annotation.

## Comparison with Pure Kotlin

| Feature | Java | Kotlin |
|---------|------|--------|
| Create chat | `JavaLLM.chat("gpt-4o")` | `KotlinLLM.chat("gpt-4o")` |
| Send message | `chat.ask("Hi")` | `chat.ask("Hi")` |
| Quick chat | `JavaLLM.quickChat("Hi")` | `chat("Hi")` |
| Configure | `KotlinLLM.configure(c -> { ... })` | `KotlinLLM.configure { ... }` |
| Fluent config | `chat.withInstructions(...)` | `chat.withInstructions(...)` |
| Memory | `chat.withMemory(JavaMemory.buffer(20))` | `chat.withMemory { buffer(20) }` |
| Structured | `chat.askStructured(prompt, serializer)` | `chat.askStructured<T>(prompt)` |
| Streaming | Callback-based | Flow-based |
| Async | CompletableFuture | Coroutines |

The Kotlin API is more concise due to DSL support and reified generics, but the Java API provides full functionality with familiar patterns.
