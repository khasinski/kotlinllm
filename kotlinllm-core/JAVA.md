# KotlinLLM for Java Developers

This guide shows how to use KotlinLLM from Java code. While KotlinLLM is written in Kotlin, it provides a Java-friendly API through the `JavaLLM` class.

## Installation

### Gradle (Groovy)

```groovy
dependencies {
    implementation 'com.kotlinllm:kotlinllm:0.1.0'
}
```

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
import com.kotlinllm.KotlinLLM;
import com.kotlinllm.JavaLLM;
import com.kotlinllm.core.Chat;
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
import com.kotlinllm.KotlinLLM;
import com.kotlinllm.JavaLLM;
import com.kotlinllm.core.Chat;
import com.kotlinllm.core.Message;

public class ConversationExample {
    public static void main(String[] args) {
        // Create a chat instance
        Chat chat = KotlinLLM.chat("gpt-4o")
            .withInstructions("You are a helpful math tutor.")
            .withTemperature(0.7);

        // First question
        Message response1 = JavaLLM.ask(chat, "What is 2 + 2?");
        System.out.println("Assistant: " + response1.getText());

        // Follow-up (maintains conversation history)
        Message response2 = JavaLLM.ask(chat, "Now multiply that by 10");
        System.out.println("Assistant: " + response2.getText());
    }
}
```

### Using the Builder Pattern

```java
import com.kotlinllm.JavaLLM;
import com.kotlinllm.JavaChatBuilder;
import com.kotlinllm.core.Chat;
import com.kotlinllm.core.Message;

public class BuilderExample {
    public static void main(String[] args) {
        Chat chat = JavaLLM.chatBuilder()
            .model("claude-sonnet-4-20250514")
            .system("You are a helpful coding assistant.")
            .temperature(0.5)
            .maxTokens(1000)
            .build();

        Message response = JavaLLM.ask(chat, "Write a hello world in Java");
        System.out.println(response.getText());
    }
}
```

## Async Operations with CompletableFuture

```java
import com.kotlinllm.KotlinLLM;
import com.kotlinllm.JavaLLM;
import com.kotlinllm.core.Chat;
import com.kotlinllm.core.Message;
import java.util.concurrent.CompletableFuture;

public class AsyncExample {
    public static void main(String[] args) {
        Chat chat = KotlinLLM.chat();

        // Async call
        CompletableFuture<Message> future = JavaLLM.askAsync(chat, "Hello!");

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
import com.kotlinllm.KotlinLLM;
import com.kotlinllm.JavaLLM;
import com.kotlinllm.core.Chat;
import com.kotlinllm.core.Chunk;

public class StreamingExample {
    public static void main(String[] args) {
        Chat chat = KotlinLLM.chat("gpt-4o");

        // Streaming with callback
        JavaLLM.askStreaming(chat, "Write a poem about coding", chunk -> {
            // Print each chunk as it arrives
            System.out.print(chunk.getContent());
        });

        System.out.println(); // New line after streaming completes
    }
}
```

### Async Streaming

```java
import com.kotlinllm.KotlinLLM;
import com.kotlinllm.JavaLLM;
import com.kotlinllm.core.Chat;
import java.util.concurrent.CompletableFuture;

public class AsyncStreamingExample {
    public static void main(String[] args) {
        Chat chat = KotlinLLM.chat();

        CompletableFuture<Void> future = JavaLLM.askStreamingAsync(
            chat,
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
import com.kotlinllm.KotlinLLM;
import com.kotlinllm.JavaLLM;
import com.kotlinllm.JavaToolBuilder;
import com.kotlinllm.core.Chat;
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
                    // This is simplified - use javax.script or similar in production
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
        Chat chat = KotlinLLM.chat("gpt-4o")
            .withTool(calculator)
            .withTool(weather);

        Message response = JavaLLM.ask(chat, "What's 15 * 7? Also, what's the weather in Tokyo?");
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
import com.kotlinllm.KotlinLLM;
import com.kotlinllm.JavaLLM;
import com.kotlinllm.core.Chat;
import com.kotlinllm.core.Message;

public class ProvidersExample {
    public static void main(String[] args) {
        // OpenAI
        Chat openaiChat = KotlinLLM.chat("gpt-4o");
        Message openaiResponse = JavaLLM.ask(openaiChat, "Hello from OpenAI!");

        // Anthropic Claude
        Chat claudeChat = KotlinLLM.chat("claude-sonnet-4-20250514");
        Message claudeResponse = JavaLLM.ask(claudeChat, "Hello from Claude!");

        // The model is auto-detected from the model name
        // gpt-* -> OpenAI
        // claude-* -> Anthropic
        // gemini-* -> Google (when implemented)
    }
}
```

## Error Handling

```java
import com.kotlinllm.KotlinLLM;
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
import com.kotlinllm.core.Chat;
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
        Chat chat = KotlinLLM.chat(model);
        Message response = JavaLLM.ask(chat, message);
        return response.getText();
    }
}
```

## Complete Example

```java
import com.kotlinllm.KotlinLLM;
import com.kotlinllm.JavaLLM;
import com.kotlinllm.core.Chat;
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
        Chat chat = JavaLLM.chatBuilder()
            .model("gpt-4o")
            .system("You are a helpful research assistant. Use the search tool when needed.")
            .tool(searchTool)
            .temperature(0.7)
            .build();

        // 4. Have a conversation
        System.out.println("User: What are the latest developments in AI?");
        Message response = JavaLLM.ask(chat, "What are the latest developments in AI?");
        System.out.println("Assistant: " + response.getText());

        System.out.println("\nUser: Tell me more about LLMs specifically");
        response = JavaLLM.ask(chat, "Tell me more about LLMs specifically");
        System.out.println("Assistant: " + response.getText());

        // 5. Check token usage
        if (response.getTokens() != null) {
            System.out.println("\nTokens used: " + response.getTokens().getTotal());
        }
    }
}
```

## Tips for Java Developers

1. **Null Safety**: KotlinLLM uses Kotlin's null safety. In Java, use `@Nullable` annotations or check for null values.

2. **Unit Type**: When using lambdas that don't return a value (like `configure`), return `Unit.INSTANCE`.

3. **Blocking vs Async**: Use `JavaLLM` methods ending in `Async` for non-blocking operations.

4. **Streaming**: Use `askStreaming` for real-time response display.

5. **Thread Safety**: Chat instances are not thread-safe. Create separate instances for concurrent use.

## Comparison with Pure Kotlin

| Feature | Java | Kotlin |
|---------|------|--------|
| Simple chat | `JavaLLM.quickChat("Hi")` | `chat("Hi")` |
| Configure | `KotlinLLM.configure(c -> { ... })` | `KotlinLLM.configure { ... }` |
| Builder | `chatBuilder().model("x").build()` | `chat("x") { ... }` |
| Streaming | Callback-based | Flow-based |
| Async | CompletableFuture | Coroutines |

The Kotlin API is more concise, but the Java API provides full functionality with familiar patterns.
