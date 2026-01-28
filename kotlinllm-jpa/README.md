# KotlinLLM JPA

Persist chat conversations to any JPA-compatible database.

## Installation

```kotlin
dependencies {
    implementation("com.kotlinllm:kotlinllm-core:0.9.0")
    implementation("com.kotlinllm:kotlinllm-jpa:0.9.0")

    // Your JPA provider
    implementation("org.hibernate.orm:hibernate-core:6.4.0.Final")

    // Your database driver
    runtimeOnly("org.postgresql:postgresql:42.7.0")
    // or
    runtimeOnly("com.h2database:h2:2.2.224")
}
```

## Quick Start

### 1. Configure JPA

Create `persistence.xml` in `src/main/resources/META-INF/`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<persistence xmlns="https://jakarta.ee/xml/ns/persistence"
             version="3.0">
    <persistence-unit name="kotlinllm" transaction-type="RESOURCE_LOCAL">
        <class>com.kotlinllm.persistence.jpa.ConversationEntity</class>
        <class>com.kotlinllm.persistence.jpa.MessageEntity</class>
        <properties>
            <property name="jakarta.persistence.jdbc.url"
                      value="jdbc:postgresql://localhost:5432/mydb"/>
            <property name="jakarta.persistence.jdbc.user" value="user"/>
            <property name="jakarta.persistence.jdbc.password" value="password"/>
            <property name="hibernate.hbm2ddl.auto" value="update"/>
        </properties>
    </persistence-unit>
</persistence>
```

### 2. Create Persisted Chat

```kotlin
import com.kotlinllm.KotlinLLM
import com.kotlinllm.persistence.jpa.JpaConversationStorage
import jakarta.persistence.Persistence

// Create EntityManagerFactory
val emf = Persistence.createEntityManagerFactory("kotlinllm")

// Create storage adapter
val storage = JpaConversationStorage(emf)

// Create chat with persistence
val chat = KotlinLLM.chat("gpt-4o")
    .persisted(storage, conversationId = "user-123-session-1")

// Use normally - messages are automatically saved
chat.ask("Hello!")
chat.ask("How are you?")

// Later, resume the conversation
val resumedChat = KotlinLLM.chat("gpt-4o")
    .persisted(storage, conversationId = "user-123-session-1")

// Previous messages are automatically loaded
val response = resumedChat.ask("What did I say earlier?")
```

## Database Schema

The adapter creates two tables:

### conversations

| Column | Type | Description |
|--------|------|-------------|
| id | VARCHAR(255) | Primary key (conversation ID) |
| model | VARCHAR(255) | Model used |
| created_at | TIMESTAMP | Creation time |
| updated_at | TIMESTAMP | Last update time |
| metadata | TEXT | JSON metadata |

### messages

| Column | Type | Description |
|--------|------|-------------|
| id | BIGINT | Auto-generated ID |
| conversation_id | VARCHAR(255) | Foreign key |
| role | VARCHAR(50) | user, assistant, system, tool |
| content | TEXT | Message content |
| tool_calls | TEXT | JSON tool calls (if any) |
| tool_call_id | VARCHAR(255) | Tool call ID (for tool responses) |
| tokens_input | INT | Input tokens |
| tokens_output | INT | Output tokens |
| created_at | TIMESTAMP | Message timestamp |

## Spring Boot Integration

### Configuration

```kotlin
@Configuration
class KotlinLLMConfig {

    @Bean
    fun conversationStorage(entityManagerFactory: EntityManagerFactory): JpaConversationStorage {
        return JpaConversationStorage(entityManagerFactory)
    }
}
```

### Service

```kotlin
@Service
class ChatService(
    private val storage: JpaConversationStorage
) {
    fun chat(userId: String, sessionId: String, message: String): String {
        val conversationId = "$userId-$sessionId"

        val chat = KotlinLLM.chat("gpt-4o")
            .persisted(storage, conversationId)

        return runBlocking {
            chat.ask(message).text
        }
    }

    fun getHistory(userId: String, sessionId: String): List<Message> {
        val conversationId = "$userId-$sessionId"
        return runBlocking {
            storage.loadMessages(conversationId)
        }
    }

    fun deleteConversation(userId: String, sessionId: String) {
        val conversationId = "$userId-$sessionId"
        runBlocking {
            storage.delete(conversationId)
        }
    }
}
```

### REST Controller

```kotlin
@RestController
@RequestMapping("/api/chat")
class ChatController(private val chatService: ChatService) {

    @PostMapping("/{sessionId}")
    fun chat(
        @PathVariable sessionId: String,
        @RequestBody request: ChatRequest,
        principal: Principal
    ): ChatResponse {
        val response = chatService.chat(principal.name, sessionId, request.message)
        return ChatResponse(response)
    }

    @GetMapping("/{sessionId}/history")
    fun history(
        @PathVariable sessionId: String,
        principal: Principal
    ): List<MessageDto> {
        return chatService.getHistory(principal.name, sessionId)
            .map { MessageDto(it.role.name, it.text) }
    }

    @DeleteMapping("/{sessionId}")
    fun delete(
        @PathVariable sessionId: String,
        principal: Principal
    ) {
        chatService.deleteConversation(principal.name, sessionId)
    }
}
```

## Storage Operations

```kotlin
val storage = JpaConversationStorage(emf)

// Save a conversation
storage.save(conversationId, messages, metadata)

// Load messages
val messages = storage.loadMessages(conversationId)

// Check if exists
val exists = storage.exists(conversationId)

// Delete
storage.delete(conversationId)

// List all conversations (with pagination)
val conversations = storage.list(limit = 20, offset = 0)

// Search by metadata
val filtered = storage.findByMetadata("userId", "123")
```

## Custom Entity Extension

Extend the entities for additional fields:

```kotlin
@Entity
@Table(name = "custom_conversations")
class CustomConversationEntity : ConversationEntity() {

    @Column(name = "user_id")
    var userId: String? = null

    @Column(name = "title")
    var title: String? = null

    @Enumerated(EnumType.STRING)
    var status: ConversationStatus = ConversationStatus.ACTIVE
}

enum class ConversationStatus {
    ACTIVE, ARCHIVED, DELETED
}
```

## Java Usage

```java
import com.kotlinllm.persistence.jpa.JpaConversationStorage;
import jakarta.persistence.Persistence;

// Setup
EntityManagerFactory emf = Persistence.createEntityManagerFactory("kotlinllm");
JpaConversationStorage storage = new JpaConversationStorage(emf);

// Create persisted chat
PersistedChat chat = KotlinLLM.chat("gpt-4o")
    .persisted(storage, "conversation-123");

// Use in Java
Message response = runBlocking(() -> chat.ask("Hello!"));
System.out.println(response.getText());
```

## Error Handling

```kotlin
import com.kotlinllm.core.LLMException

try {
    val chat = KotlinLLM.chat("gpt-4o")
        .persisted(storage, conversationId)
    chat.ask("Hello")
} catch (e: PersistenceException) {
    // Database error
    logger.error("Failed to persist conversation", e)
} catch (e: LLMException) {
    // LLM error (still persists partial conversation)
    logger.error("LLM request failed", e)
}
```
