# KotlinLLM Exposed

Persist chat conversations using JetBrains Exposed SQL framework.

## Installation

```kotlin
dependencies {
    implementation("com.kotlinllm:kotlinllm-core:0.9.0")
    implementation("com.kotlinllm:kotlinllm-exposed:0.9.0")

    // Exposed
    implementation("org.jetbrains.exposed:exposed-core:0.46.0")
    implementation("org.jetbrains.exposed:exposed-dao:0.46.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.46.0")
    implementation("org.jetbrains.exposed:exposed-kotlin-datetime:0.46.0")

    // Database driver
    runtimeOnly("org.postgresql:postgresql:42.7.0")
    // or
    runtimeOnly("com.h2database:h2:2.2.224")
}
```

## Quick Start

### 1. Configure Database

```kotlin
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import com.kotlinllm.persistence.exposed.*

// Connect to database
Database.connect(
    url = "jdbc:postgresql://localhost:5432/mydb",
    driver = "org.postgresql.Driver",
    user = "user",
    password = "password"
)

// Create tables
transaction {
    SchemaUtils.create(Conversations, Messages)
}
```

### 2. Create Persisted Chat

```kotlin
import com.kotlinllm.KotlinLLM
import com.kotlinllm.persistence.exposed.ExposedConversationStorage

// Create storage adapter
val storage = ExposedConversationStorage()

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

## Table Schema

### Conversations Table

```kotlin
object Conversations : Table("conversations") {
    val id = varchar("id", 255)
    val model = varchar("model", 255)
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")
    val metadata = text("metadata").nullable()

    override val primaryKey = PrimaryKey(id)
}
```

### Messages Table

```kotlin
object Messages : Table("messages") {
    val id = long("id").autoIncrement()
    val conversationId = varchar("conversation_id", 255)
        .references(Conversations.id, onDelete = ReferenceOption.CASCADE)
    val role = varchar("role", 50)
    val content = text("content")
    val toolCalls = text("tool_calls").nullable()
    val toolCallId = varchar("tool_call_id", 255).nullable()
    val tokensInput = integer("tokens_input").nullable()
    val tokensOutput = integer("tokens_output").nullable()
    val createdAt = datetime("created_at")

    override val primaryKey = PrimaryKey(id)
}
```

## Ktor Integration

### Configuration

```kotlin
fun Application.configurePersistence() {
    Database.connect(
        url = environment.config.property("database.url").getString(),
        driver = environment.config.property("database.driver").getString(),
        user = environment.config.property("database.user").getString(),
        password = environment.config.property("database.password").getString()
    )

    transaction {
        SchemaUtils.create(Conversations, Messages)
    }
}
```

### Routes

```kotlin
fun Route.chatRoutes() {
    val storage = ExposedConversationStorage()

    route("/chat") {
        post("/{sessionId}") {
            val sessionId = call.parameters["sessionId"]!!
            val userId = call.principal<UserPrincipal>()!!.id
            val request = call.receive<ChatRequest>()

            val conversationId = "$userId-$sessionId"
            val chat = KotlinLLM.chat("gpt-4o")
                .persisted(storage, conversationId)

            val response = chat.ask(request.message)

            call.respond(ChatResponse(response.text))
        }

        get("/{sessionId}/history") {
            val sessionId = call.parameters["sessionId"]!!
            val userId = call.principal<UserPrincipal>()!!.id
            val conversationId = "$userId-$sessionId"

            val messages = storage.loadMessages(conversationId)

            call.respond(messages.map { MessageDto(it.role.name, it.text) })
        }

        delete("/{sessionId}") {
            val sessionId = call.parameters["sessionId"]!!
            val userId = call.principal<UserPrincipal>()!!.id
            val conversationId = "$userId-$sessionId"

            storage.delete(conversationId)

            call.respond(HttpStatusCode.NoContent)
        }
    }
}
```

## Storage Operations

```kotlin
val storage = ExposedConversationStorage()

// Save a conversation
storage.save(conversationId, messages, metadata)

// Load messages
val messages = storage.loadMessages(conversationId)

// Check if exists
val exists = storage.exists(conversationId)

// Delete
storage.delete(conversationId)

// List all conversations
val conversations = storage.list(limit = 20, offset = 0)

// Search by metadata
val filtered = storage.findByMetadata("userId", "123")
```

## Custom Queries with Exposed DSL

Access the underlying Exposed tables for custom queries:

```kotlin
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import com.kotlinllm.persistence.exposed.Conversations
import com.kotlinllm.persistence.exposed.Messages

// Find conversations with most messages
val activeConversations = transaction {
    Messages
        .slice(Messages.conversationId, Messages.id.count())
        .selectAll()
        .groupBy(Messages.conversationId)
        .orderBy(Messages.id.count(), SortOrder.DESC)
        .limit(10)
        .map { it[Messages.conversationId] }
}

// Find conversations updated in last 24 hours
val recentConversations = transaction {
    Conversations
        .select { Conversations.updatedAt greater LocalDateTime.now().minusDays(1) }
        .map { it[Conversations.id] }
}

// Count tokens per user (from metadata)
val tokenUsage = transaction {
    Messages
        .innerJoin(Conversations)
        .slice(
            Conversations.metadata,
            Messages.tokensInput.sum(),
            Messages.tokensOutput.sum()
        )
        .selectAll()
        .groupBy(Conversations.metadata)
        .toList()
}
```

## Connection Pooling

For production, use HikariCP:

```kotlin
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource

val config = HikariConfig().apply {
    jdbcUrl = "jdbc:postgresql://localhost:5432/mydb"
    username = "user"
    password = "password"
    maximumPoolSize = 10
    isAutoCommit = false
    transactionIsolation = "TRANSACTION_REPEATABLE_READ"
}

val dataSource = HikariDataSource(config)
Database.connect(dataSource)
```

## Migration

Use Flyway or Liquibase for production migrations:

```sql
-- V1__Create_kotlinllm_tables.sql
CREATE TABLE conversations (
    id VARCHAR(255) PRIMARY KEY,
    model VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    metadata TEXT
);

CREATE TABLE messages (
    id BIGSERIAL PRIMARY KEY,
    conversation_id VARCHAR(255) NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    role VARCHAR(50) NOT NULL,
    content TEXT NOT NULL,
    tool_calls TEXT,
    tool_call_id VARCHAR(255),
    tokens_input INT,
    tokens_output INT,
    created_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_messages_conversation ON messages(conversation_id);
CREATE INDEX idx_conversations_updated ON conversations(updated_at);
```

## Error Handling

```kotlin
import com.kotlinllm.core.LLMException
import org.jetbrains.exposed.exceptions.ExposedSQLException

try {
    val chat = KotlinLLM.chat("gpt-4o")
        .persisted(storage, conversationId)
    chat.ask("Hello")
} catch (e: ExposedSQLException) {
    // Database error
    logger.error("Database operation failed", e)
} catch (e: LLMException) {
    // LLM error
    logger.error("LLM request failed", e)
}
```

## Comparison with JPA

| Feature | Exposed | JPA |
|---------|---------|-----|
| DSL | Kotlin-native | Annotation-based |
| Learning curve | Lower for Kotlin devs | Standard Java knowledge |
| Type safety | Compile-time | Runtime |
| Framework | Standalone | Hibernate/EclipseLink |
| Best for | Kotlin projects, Ktor | Spring Boot, existing JPA apps |

Choose Exposed for pure Kotlin projects, JPA for Spring Boot or mixed Java/Kotlin codebases.
