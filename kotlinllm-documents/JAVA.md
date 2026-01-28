# KotlinLLM Documents - Java API

This module provides document loading, chunking, and chat integration for Java developers.

## Installation

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    implementation("com.kotlinllm:kotlinllm-core:0.9.0")
    implementation("com.kotlinllm:kotlinllm-documents:0.9.0")
}
```

### Gradle (Groovy)

```groovy
dependencies {
    implementation 'com.kotlinllm:kotlinllm-core:0.9.0'
    implementation 'com.kotlinllm:kotlinllm-documents:0.9.0'
}
```

### Maven

```xml
<dependencies>
    <dependency>
        <groupId>com.kotlinllm</groupId>
        <artifactId>kotlinllm-core</artifactId>
        <version>0.9.0</version>
    </dependency>
    <dependency>
        <groupId>com.kotlinllm</groupId>
        <artifactId>kotlinllm-documents</artifactId>
        <version>0.9.0</version>
    </dependency>
</dependencies>
```

## Supported Formats

| Format | Extension | Library |
|--------|-----------|---------|
| Plain Text | `.txt` | Built-in |
| Markdown | `.md`, `.markdown` | Built-in |
| HTML | `.html`, `.htm` | Jsoup |
| PDF | `.pdf` | Apache PDFBox |

## Quick Start

### Loading Documents

```java
import com.kotlinllm.documents.JavaDocuments;
import com.kotlinllm.documents.Document;
import java.nio.file.Path;

public class DocumentLoadingExample {
    public static void main(String[] args) {
        // Load from file
        Document doc = JavaDocuments.load(Path.of("readme.md"));
        System.out.println("Title: " + doc.getTitle());
        System.out.println("Content length: " + doc.getContent().length());

        // Load from URL
        Document webDoc = JavaDocuments.loadUrl("https://example.com/page.html");

        // Create from raw content
        Document rawDoc = JavaDocuments.fromContent(
            "This is my document content",
            "My Document",  // title
            "manual"        // source
        );

        // Async loading
        CompletableFuture<Document> future = JavaDocuments.loadAsync(Path.of("large.pdf"));
        future.thenAccept(d -> System.out.println("Loaded: " + d.getTitle()));
    }
}
```

### Using Documents with Chat

```java
import com.kotlinllm.JavaLLM;
import com.kotlinllm.JavaChat;
import com.kotlinllm.documents.JavaDocuments;
import com.kotlinllm.documents.JavaDocumentChat;
import com.kotlinllm.documents.Document;
import java.nio.file.Path;

public class DocumentChatExample {
    public static void main(String[] args) {
        // Method 1: Load document and create document chat
        JavaDocumentChat chat = JavaDocuments.withDocument(
            JavaLLM.chat("gpt-4o"),
            Path.of("specification.pdf")
        );

        Message response = chat.ask("Summarize this document");
        System.out.println(response.getText());

        // Method 2: Add document to existing chat
        Document doc = JavaDocuments.load(Path.of("readme.md"));
        JavaDocumentChat chat2 = JavaDocuments.withDocument(
            JavaLLM.chat("gpt-4o"),
            doc
        );

        // Add more documents to the same chat
        chat2.withDocument(Path.of("api-docs.md"))
             .withDocument(JavaDocuments.fromContent("Additional context..."));

        chat2.ask("How do these documents relate?");
    }
}
```

## Document Chunking

For large documents, use chunking to split content into manageable pieces.

### Character-Based Chunking

```java
import com.kotlinllm.documents.JavaDocuments;
import com.kotlinllm.documents.Document;
import com.kotlinllm.documents.ChunkingStrategy;

Document doc = JavaDocuments.load(Path.of("large-document.txt"));

// Create chunker: 1000 chars per chunk, 200 char overlap
ChunkingStrategy chunker = JavaDocuments.characterChunker(1000, 200);

// Apply chunking
Document chunkedDoc = JavaDocuments.chunk(doc, chunker);

// Access chunks
System.out.println("Number of chunks: " + chunkedDoc.getChunks().size());
for (var chunk : chunkedDoc.getChunks()) {
    System.out.println("Chunk " + chunk.getIndex() + ": " + chunk.getContent().length() + " chars");
}
```

### Sentence-Based Chunking

```java
// Chunks at sentence boundaries, max 1000 chars per chunk
ChunkingStrategy chunker = JavaDocuments.sentenceChunker(1000);
Document chunkedDoc = JavaDocuments.chunk(doc, chunker);
```

### Paragraph-Based Chunking

```java
// Chunks at paragraph boundaries
ChunkingStrategy chunker = JavaDocuments.paragraphChunker(2000);
Document chunkedDoc = JavaDocuments.chunk(doc, chunker);
```

## Building Multiple Documents

Use `JavaDocumentsBuilder` to load and configure multiple documents.

```java
import com.kotlinllm.documents.JavaDocumentsBuilder;
import com.kotlinllm.documents.Document;
import java.util.List;

public class MultiDocumentExample {
    public static void main(String[] args) {
        List<Document> docs = JavaDocumentsBuilder.create()
            // Add from various sources
            .addPath(Path.of("spec.pdf"))
            .addPath(Path.of("readme.md"))
            .addUrl("https://example.com/docs")
            .addContent("Additional inline content", "Inline Doc")

            // Configure processing
            .maxTotalChars(50000)           // Limit total content
            .characterChunking(1000, 200)   // Apply chunking

            .build();

        System.out.println("Loaded " + docs.size() + " documents");

        // Add all to chat
        Chat chat = JavaDocuments.addToChat(
            KotlinLLM.chat("gpt-4o").unwrap(),
            docs
        );
    }
}
```

## Checking Supported Formats

```java
import com.kotlinllm.documents.JavaDocuments;

// List all supported extensions
List<String> extensions = JavaDocuments.supportedExtensions();
System.out.println("Supported: " + extensions);
// Output: [txt, md, markdown, html, htm, pdf]

// Check if specific format is supported
boolean isPdfSupported = JavaDocuments.isSupported("pdf");      // true
boolean isDocxSupported = JavaDocuments.isSupported("docx");    // false

// Check if file is supported
boolean canLoad = JavaDocuments.isSupported(Path.of("file.pdf")); // true
```

## Document Properties

```java
Document doc = JavaDocuments.load(Path.of("document.md"));

// Basic properties
String content = doc.getContent();
Map<String, String> metadata = doc.getMetadata();

// Convenience getters (from metadata)
String title = doc.getTitle();      // metadata["title"]
String source = doc.getSource();    // metadata["source"]
String type = doc.getType();        // metadata["type"]

// Estimates
int estimatedTokens = doc.estimateTokens();

// Truncation
Document truncated = doc.truncated(5000);  // Max 5000 chars
```

## Complete Example

```java
import com.kotlinllm.KotlinLLM;
import com.kotlinllm.JavaLLM;
import com.kotlinllm.documents.*;
import com.kotlinllm.core.Message;
import java.nio.file.Path;
import kotlin.Unit;

public class CompleteDocumentExample {
    public static void main(String[] args) {
        // Configure
        KotlinLLM.configure(config -> {
            config.setOpenaiApiKey(System.getenv("OPENAI_API_KEY"));
            return Unit.INSTANCE;
        });

        // Load and process documents
        var docs = JavaDocumentsBuilder.create()
            .addPath(Path.of("project/README.md"))
            .addPath(Path.of("project/docs/api.md"))
            .addUrl("https://example.com/changelog")
            .maxTotalChars(30000)
            .sentenceChunking(1500)
            .build();

        System.out.println("Loaded " + docs.size() + " documents");
        for (Document doc : docs) {
            System.out.println("- " + doc.getTitle() + " (" + doc.estimateTokens() + " tokens)");
        }

        // Create chat with documents
        var kotlinChat = KotlinLLM.chat("gpt-4o");
        var chatWithDocs = JavaDocuments.addToChat(kotlinChat, docs);
        var javaChat = new JavaDocumentChat(chatWithDocs);

        // Ask questions about the documents
        javaChat.withInstructions("You are a helpful documentation assistant.")
                .withTemperature(0.3);

        Message response = javaChat.ask("What are the main features described in these documents?");
        System.out.println("\nAssistant: " + response.getText());

        response = javaChat.ask("How do I get started?");
        System.out.println("\nAssistant: " + response.getText());
    }
}
```

## Error Handling

```java
import com.kotlinllm.documents.UnsupportedDocumentException;

try {
    Document doc = JavaDocuments.load(Path.of("file.docx"));
} catch (UnsupportedDocumentException e) {
    System.err.println("Format not supported: " + e.getMessage());
    System.err.println("Supported formats: " + JavaDocuments.supportedExtensions());
} catch (Exception e) {
    System.err.println("Failed to load document: " + e.getMessage());
}
```

## Performance Tips

1. **Large PDFs**: PDFBox can be memory-intensive. Consider chunking large PDFs.

2. **Async Loading**: Use `loadAsync()` for multiple documents to parallelize I/O.

3. **Token Limits**: Use `maxTotalChars()` to stay within model context limits.

4. **Caching**: Document loading is not cached. Cache documents yourself for repeated use.

## Comparison with Kotlin API

| Feature | Java | Kotlin |
|---------|------|--------|
| Load file | `JavaDocuments.load(path)` | `DocumentLoaders.load(path)` |
| Load URL | `JavaDocuments.loadUrl(url)` | `DocumentLoaders.loadUrl(url)` |
| Add to chat | `JavaDocuments.addToChat(chat, doc)` | `chat.withDocument(doc)` |
| Chunking | `JavaDocuments.characterChunker(...)` | `CharacterChunker(...)` |
| Builder | `JavaDocumentsBuilder.create()...` | `chat.withDocuments { ... }` |
| Async | `CompletableFuture` | Coroutines (suspend) |

The Kotlin API provides a more fluent DSL experience, but the Java API offers full functionality.
