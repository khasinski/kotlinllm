@file:JvmName("JavaDocuments")

package com.kotlinllm.documents

import com.kotlinllm.JavaChat
import com.kotlinllm.core.Chat
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

/**
 * Java-friendly document API.
 *
 * Example:
 * ```java
 * // Load a document
 * Document doc = JavaDocuments.load(Path.of("readme.md"));
 *
 * // Load from URL
 * Document doc = JavaDocuments.loadUrl("https://example.com/page.html");
 *
 * // Add to chat
 * JavaChat chat = JavaLLM.chat("gpt-4o");
 * JavaDocuments.addToChat(chat, doc);
 *
 * // Or use fluent API
 * JavaDocumentChat docChat = JavaDocuments.withDocument(chat, doc);
 * docChat.ask("Summarize this document");
 * ```
 */
object JavaDocuments {
    private val executor = Executors.newCachedThreadPool()

    // ==================== Document Loading ====================

    /**
     * Load a document from a file path (blocking).
     */
    @JvmStatic
    fun load(path: Path): Document = runBlocking {
        DocumentLoaders.load(path)
    }

    /**
     * Load a document from a file path (async).
     */
    @JvmStatic
    fun loadAsync(path: Path): CompletableFuture<Document> {
        return CompletableFuture.supplyAsync({ load(path) }, executor)
    }

    /**
     * Load a document from a URL (blocking).
     */
    @JvmStatic
    fun loadUrl(url: String): Document = runBlocking {
        DocumentLoaders.loadUrl(url)
    }

    /**
     * Load a document from a URL (async).
     */
    @JvmStatic
    fun loadUrlAsync(url: String): CompletableFuture<Document> {
        return CompletableFuture.supplyAsync({ loadUrl(url) }, executor)
    }

    /**
     * Create a document from raw content.
     */
    @JvmStatic
    fun fromContent(content: String): Document = Document(content = content)

    /**
     * Create a document from raw content with metadata.
     */
    @JvmStatic
    fun fromContent(content: String, title: String?, source: String?): Document {
        val metadata = mutableMapOf<String, String>()
        title?.let { metadata["title"] = it }
        source?.let { metadata["source"] = it }
        return Document(content = content, metadata = metadata)
    }

    // ==================== Supported Formats ====================

    /**
     * Get list of supported file extensions.
     */
    @JvmStatic
    fun supportedExtensions(): List<String> = DocumentLoaders.supportedExtensions().toList()

    /**
     * Check if a file extension is supported.
     */
    @JvmStatic
    fun isSupported(extension: String): Boolean =
        DocumentLoaders.forExtension(extension) != null

    /**
     * Check if a path is supported.
     */
    @JvmStatic
    fun isSupported(path: Path): Boolean {
        val extension = path.toString().substringAfterLast('.', "")
        return isSupported(extension)
    }

    // ==================== Chunking ====================

    /**
     * Create a character-based chunker.
     */
    @JvmStatic
    fun characterChunker(chunkSize: Int, overlap: Int): ChunkingStrategy =
        CharacterChunker(chunkSize, overlap)

    /**
     * Create a sentence-based chunker.
     */
    @JvmStatic
    fun sentenceChunker(maxChunkSize: Int): ChunkingStrategy =
        SentenceChunker(maxChunkSize)

    /**
     * Create a paragraph-based chunker.
     */
    @JvmStatic
    fun paragraphChunker(maxChunkSize: Int): ChunkingStrategy =
        ParagraphChunker(maxChunkSize)

    /**
     * Chunk a document.
     */
    @JvmStatic
    fun chunk(document: Document, strategy: ChunkingStrategy): Document =
        document.chunked(strategy)

    // ==================== Chat Integration ====================

    /**
     * Add a document to a chat's context.
     *
     * Returns the same chat for chaining.
     */
    @JvmStatic
    fun addToChat(chat: Chat, document: Document): Chat =
        chat.withDocument(document)

    /**
     * Add multiple documents to a chat's context.
     */
    @JvmStatic
    fun addToChat(chat: Chat, documents: List<Document>): Chat =
        chat.withDocuments(documents)

    /**
     * Add a document from a path to a chat (blocking).
     */
    @JvmStatic
    fun addToChat(chat: Chat, path: Path): Chat = runBlocking {
        chat.withDocument(path)
    }

    /**
     * Create a document-enabled JavaChat wrapper.
     */
    @JvmStatic
    fun withDocument(javaChat: JavaChat, document: Document): JavaDocumentChat {
        val chat = javaChat.unwrap().withDocument(document)
        return JavaDocumentChat(chat)
    }

    /**
     * Create a document-enabled JavaChat wrapper from a path.
     */
    @JvmStatic
    fun withDocument(javaChat: JavaChat, path: Path): JavaDocumentChat = runBlocking {
        val chat = javaChat.unwrap().withDocument(path)
        JavaDocumentChat(chat)
    }
}

/**
 * Java-friendly chat with document context.
 *
 * Example:
 * ```java
 * JavaDocumentChat chat = JavaDocuments.withDocument(
 *     JavaLLM.chat("gpt-4o"),
 *     Path.of("document.pdf")
 * );
 *
 * Message response = chat.ask("What is this document about?");
 * System.out.println(response.getText());
 * ```
 */
class JavaDocumentChat internal constructor(private val chat: Chat) {
    private val executor = Executors.newCachedThreadPool()

    /**
     * Add another document to the context.
     */
    fun withDocument(document: Document): JavaDocumentChat = apply {
        chat.withDocument(document)
    }

    /**
     * Add another document from a path to the context.
     */
    fun withDocument(path: Path): JavaDocumentChat = apply {
        runBlocking {
            chat.withDocument(path)
        }
    }

    /**
     * Set system instructions.
     */
    fun withInstructions(instructions: String): JavaDocumentChat = apply {
        chat.withInstructions(instructions)
    }

    /**
     * Set temperature.
     */
    fun withTemperature(temperature: Double): JavaDocumentChat = apply {
        chat.withTemperature(temperature)
    }

    /**
     * Set max tokens.
     */
    fun withMaxTokens(maxTokens: Int): JavaDocumentChat = apply {
        chat.withMaxTokens(maxTokens)
    }

    /**
     * Send a message and get a response (blocking).
     */
    fun ask(message: String): com.kotlinllm.core.Message = runBlocking {
        chat.ask(message)
    }

    /**
     * Send a message and get a response (async).
     */
    fun askAsync(message: String): CompletableFuture<com.kotlinllm.core.Message> {
        return CompletableFuture.supplyAsync({ ask(message) }, executor)
    }

    /**
     * Get the underlying Kotlin Chat.
     */
    fun unwrap(): Chat = chat
}

/**
 * Builder for loading and configuring multiple documents.
 *
 * Example:
 * ```java
 * List<Document> docs = JavaDocumentsBuilder.create()
 *     .addPath(Path.of("doc1.pdf"))
 *     .addPath(Path.of("doc2.md"))
 *     .addUrl("https://example.com/page")
 *     .maxTotalChars(50000)
 *     .characterChunking(1000, 200)
 *     .build();
 * ```
 */
class JavaDocumentsBuilder private constructor() {
    private val documents = mutableListOf<Document>()
    private var maxTotalChars: Int = 50000
    private var chunkingStrategy: ChunkingStrategy? = null

    /**
     * Add a document from a path.
     */
    fun addPath(path: Path): JavaDocumentsBuilder = apply {
        documents.add(JavaDocuments.load(path))
    }

    /**
     * Add a document from a URL.
     */
    fun addUrl(url: String): JavaDocumentsBuilder = apply {
        documents.add(JavaDocuments.loadUrl(url))
    }

    /**
     * Add a document directly.
     */
    fun addDocument(document: Document): JavaDocumentsBuilder = apply {
        documents.add(document)
    }

    /**
     * Add raw content as a document.
     */
    fun addContent(content: String): JavaDocumentsBuilder = apply {
        documents.add(Document(content = content))
    }

    /**
     * Add raw content as a document with title.
     */
    fun addContent(content: String, title: String): JavaDocumentsBuilder = apply {
        documents.add(Document(content = content, metadata = mapOf("title" to title)))
    }

    /**
     * Set maximum total characters across all documents.
     */
    fun maxTotalChars(chars: Int): JavaDocumentsBuilder = apply {
        maxTotalChars = chars
    }

    /**
     * Use character-based chunking.
     */
    fun characterChunking(chunkSize: Int, overlap: Int): JavaDocumentsBuilder = apply {
        chunkingStrategy = CharacterChunker(chunkSize, overlap)
    }

    /**
     * Use sentence-based chunking.
     */
    fun sentenceChunking(maxChunkSize: Int): JavaDocumentsBuilder = apply {
        chunkingStrategy = SentenceChunker(maxChunkSize)
    }

    /**
     * Use paragraph-based chunking.
     */
    fun paragraphChunking(maxChunkSize: Int): JavaDocumentsBuilder = apply {
        chunkingStrategy = ParagraphChunker(maxChunkSize)
    }

    /**
     * Build the list of processed documents.
     */
    fun build(): List<Document> {
        var result = documents.toList()

        // Apply chunking if set
        chunkingStrategy?.let { strategy ->
            result = result.map { it.chunked(strategy) }
        }

        // Truncate if needed
        val totalChars = result.sumOf { it.content.length }
        if (totalChars > maxTotalChars) {
            val charsPerDoc = maxTotalChars / result.size
            result = result.map { it.truncated(charsPerDoc) }
        }

        return result
    }

    companion object {
        @JvmStatic
        fun create(): JavaDocumentsBuilder = JavaDocumentsBuilder()
    }
}
