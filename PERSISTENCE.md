# KotlinLLM Persistence

KotlinLLM provides a flexible persistence layer for saving chat conversations. Choose the storage backend that fits your needs.

## Architecture

```
┌─────────────────────────────────────────┐
│         PersistedChat (wrapper)          │
├─────────────────────────────────────────┤
│         ChatStore (interface)            │
├──────────┬──────────┬──────────┬────────┤
│ InMemory │   File   │   JPA    │Exposed │
│ (core)   │  (core)  │ (module) │(module)│
└──────────┴──────────┴──────────┴────────┘
```

## Quick Start

### In-Memory (Testing/Prototyping)

```kotlin
val store = InMemoryChatStore()
val chat = PersistedChat.create(store, model = "gpt-4o")

chat.ask("Hello!")  // Automatically saved
chat.ask("How are you?")

// Load later
val loaded = PersistedChat.load(store, chat.id)
```

### File-Based (JSON)

```kotlin
val store = FileChatStore(Path.of("./data"), prettyPrint = true)
val chat = PersistedChat.create(store, model = "claude-sonnet-4-20250514")

chat.ask("Remember my name is Alice")
// Saved to ./data/chats/{chat-id}.json

// Later, in another session:
val chatId = "..." // from previous session
val chat = PersistedChat.load(store, chatId)
chat.ask("What's my name?")  // "Your name is Alice"
```

### JPA (Hibernate, Spring Data)

Add dependency:
```kotlin
implementation("com.kotlinllm:kotlinllm-jpa:0.9.0")
```

```kotlin
// With plain JPA
val emf = Persistence.createEntityManagerFactory("my-unit")
val store = JpaChatStore(emf)

// With Spring
@Bean
fun chatStore(emf: EntityManagerFactory) = JpaChatStore(emf)
```

persistence.xml entities:
```xml
<class>com.kotlinllm.persistence.jpa.ChatEntity</class>
<class>com.kotlinllm.persistence.jpa.MessageEntity</class>
```

### Exposed (JetBrains)

Add dependency:
```kotlin
implementation("com.kotlinllm:kotlinllm-exposed:0.9.0")
```

```kotlin
// Setup
Database.connect("jdbc:postgresql://localhost/mydb", driver = "org.postgresql.Driver")
transaction { createKotlinLLMTables() }

// Use
val store = ExposedChatStore()
val chat = PersistedChat.create(store, model = "gpt-4o")
```

## API Reference

### ChatStore Interface

```kotlin
interface ChatStore {
    suspend fun save(chat: ChatRecord): String
    suspend fun load(id: String): ChatRecord?
    suspend fun delete(id: String): Boolean
    suspend fun list(limit: Int = 100, offset: Int = 0): List<String>
    suspend fun appendMessage(chatId: String, message: MessageRecord)
    suspend fun loadMessages(chatId: String, limit: Int = 100, offset: Int = 0): List<MessageRecord>
}
```

### PersistedChat

```kotlin
// Create new
val chat = PersistedChat.create(store, model = "gpt-4o")

// Load existing
val chat = PersistedChat.load(store, "chat-id")

// Load or create
val chat = PersistedChat.loadOrCreate(store, "chat-id", model = "gpt-4o")

// With metadata
val chat = PersistedChat.create(store, model = "gpt-4o")
    .withMetadata("userId", "user-123")
    .withMetadata("topic", "support")

// List all
val chatIds = PersistedChat.list(store)
```

### Data Classes

```kotlin
data class ChatRecord(
    val id: String,
    val model: String,
    val instructions: String?,
    val temperature: Double?,
    val maxTokens: Int?,
    val toolNames: List<String>,
    val messages: List<MessageRecord>,
    val metadata: Map<String, String>,
    val createdAt: Long,
    val updatedAt: Long
)

data class MessageRecord(
    val id: String,
    val role: String,
    val content: String,
    val modelId: String?,
    val toolCalls: List<ToolCallRecord>?,
    val toolCallId: String?,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val createdAt: Long
)
```

## Implementing Custom Storage

```kotlin
class RedisChatStore(private val redis: RedisClient) : ChatStore {
    override suspend fun save(chat: ChatRecord): String {
        val json = Json.encodeToString(chat)
        redis.set("chat:${chat.id}", json)
        return chat.id
    }

    override suspend fun load(id: String): ChatRecord? {
        val json = redis.get("chat:$id") ?: return null
        return Json.decodeFromString(json)
    }

    override suspend fun delete(id: String): Boolean {
        return redis.del("chat:$id") > 0
    }

    override suspend fun list(limit: Int, offset: Int): List<String> {
        return redis.keys("chat:*")
            .map { it.removePrefix("chat:") }
            .drop(offset)
            .take(limit)
    }

    override suspend fun appendMessage(chatId: String, message: MessageRecord) {
        val chat = load(chatId) ?: return
        save(chat.copy(messages = chat.messages + message))
    }

    override suspend fun loadMessages(chatId: String, limit: Int, offset: Int): List<MessageRecord> {
        return load(chatId)?.messages?.drop(offset)?.take(limit) ?: emptyList()
    }
}
```

## Spring Boot Integration

```kotlin
@Configuration
class KotlinLLMConfig {

    @Bean
    @ConditionalOnProperty("kotlinllm.store", havingValue = "jpa")
    fun jpaChatStore(emf: EntityManagerFactory) = JpaChatStore(emf)

    @Bean
    @ConditionalOnProperty("kotlinllm.store", havingValue = "file")
    fun fileChatStore(
        @Value("\${kotlinllm.store.path:./data}") path: String
    ) = FileChatStore(Path.of(path))

    @Bean
    @ConditionalOnMissingBean
    fun inMemoryChatStore() = InMemoryChatStore()
}

@Service
class ChatService(private val store: ChatStore) {

    suspend fun startChat(userId: String): PersistedChat {
        return PersistedChat.create(store, model = "gpt-4o")
            .withMetadata("userId", userId)
            .withInstructions("You are a helpful assistant")
    }

    suspend fun continueChat(chatId: String, message: String): Message {
        val chat = PersistedChat.load(store, chatId)
            ?: throw NotFoundException("Chat not found")
        return chat.ask(message)
    }

    suspend fun getUserChats(userId: String): List<ChatRecord> {
        // With JPA store
        return (store as? JpaChatStore)?.findByMetadata("userId", userId)
            ?: emptyList()
    }
}
```

## File Store Features

```kotlin
val store = FileChatStore(Path.of("./data"), prettyPrint = true)

// Export all chats
store.exportAll(Path.of("./backup.json"))

// Import chats
store.importAll(Path.of("./backup.json"))

// Get statistics
val stats = store.stats()
println("Chats: ${stats.chatCount}, Size: ${stats.totalSizeMb} MB")
```

## JPA Schema

The JPA module creates these tables:

```sql
CREATE TABLE kotlinllm_chats (
    id VARCHAR(36) PRIMARY KEY,
    model VARCHAR(100) NOT NULL,
    instructions TEXT,
    temperature DOUBLE,
    max_tokens INT,
    tool_names TEXT,  -- JSON array
    metadata TEXT,    -- JSON object
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE kotlinllm_messages (
    id VARCHAR(36) PRIMARY KEY,
    chat_id VARCHAR(36) NOT NULL REFERENCES kotlinllm_chats(id) ON DELETE CASCADE,
    role VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    model_id VARCHAR(100),
    tool_calls TEXT,  -- JSON array
    tool_call_id VARCHAR(100),
    input_tokens INT,
    output_tokens INT,
    created_at TIMESTAMP NOT NULL
);
```

## Best Practices

1. **Use InMemoryChatStore for tests** - Fast, no setup required
2. **Use FileChatStore for development** - Easy to inspect, no database needed
3. **Use JPA/Exposed for production** - Proper ACID transactions, scalability
4. **Add metadata** - User IDs, session IDs, topics for easy querying
5. **Implement cleanup** - Delete old chats to manage storage

```kotlin
// Cleanup example
val thirtyDaysAgo = Instant.now().minus(30, ChronoUnit.DAYS)
(store as? JpaChatStore)?.deleteOlderThan(thirtyDaysAgo)
```
