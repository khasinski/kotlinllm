package com.kotlinllm.documents

import com.kotlinllm.core.Chat
import java.nio.file.Path

/**
 * Add document content to the chat as context.
 *
 * Example:
 * ```kotlin
 * val chat = KotlinLLM.chat("gpt-4o")
 *     .withDocument(document)
 *     .ask("What is this document about?")
 * ```
 */
fun Chat.withDocument(document: Document): Chat {
    val contextMessage = buildDocumentContext(document)
    return withInstructions(contextMessage, replace = false)
}

/**
 * Add multiple documents to the chat as context.
 */
fun Chat.withDocuments(documents: List<Document>): Chat {
    val contextMessage = buildDocumentsContext(documents)
    return withInstructions(contextMessage, replace = false)
}

/**
 * Load and add a document from a path.
 *
 * Example:
 * ```kotlin
 * val chat = KotlinLLM.chat("gpt-4o")
 *     .withDocument(Path.of("spec.pdf"))
 *     .ask("Summarize this document")
 * ```
 */
suspend fun Chat.withDocument(path: Path): Chat {
    val document = DocumentLoaders.load(path)
    return withDocument(document)
}

/**
 * Load and add a document from a URL.
 */
suspend fun Chat.withDocumentUrl(url: String): Chat {
    val document = DocumentLoaders.loadUrl(url)
    return withDocument(document)
}

/**
 * DSL for adding documents to a chat.
 *
 * Example:
 * ```kotlin
 * val chat = KotlinLLM.chat("gpt-4o").withDocuments {
 *     document(Path.of("spec.pdf"))
 *     document(Path.of("readme.md"))
 *     url("https://example.com/page")
 *     maxChars(30000)
 * }
 * ```
 */
suspend fun Chat.withDocuments(block: suspend DocumentsBuilder.() -> Unit): Chat {
    val builder = DocumentsBuilder()
    builder.block()
    return withDocuments(builder.build())
}

/**
 * Builder for documents DSL.
 */
class DocumentsBuilder {
    private val documents = mutableListOf<Document>()
    private var maxTotalChars: Int = 50000
    private var chunkingStrategy: ChunkingStrategy? = null

    /**
     * Add a document from a path.
     */
    suspend fun document(path: Path) {
        documents.add(DocumentLoaders.load(path))
    }

    /**
     * Add a document from a URL.
     */
    suspend fun url(url: String) {
        documents.add(DocumentLoaders.loadUrl(url))
    }

    /**
     * Add an existing document.
     */
    fun document(doc: Document) {
        documents.add(doc)
    }

    /**
     * Set maximum total characters across all documents.
     */
    fun maxChars(chars: Int) {
        maxTotalChars = chars
    }

    /**
     * Set chunking strategy.
     */
    fun chunking(strategy: ChunkingStrategy) {
        chunkingStrategy = strategy
    }

    /**
     * Use character-based chunking.
     */
    fun characterChunking(chunkSize: Int = 1000, overlap: Int = 200) {
        chunkingStrategy = CharacterChunker(chunkSize, overlap)
    }

    /**
     * Use sentence-based chunking.
     */
    fun sentenceChunking(maxChunkSize: Int = 1000) {
        chunkingStrategy = SentenceChunker(maxChunkSize)
    }

    internal fun build(): List<Document> {
        var result = documents.toList()

        // Apply chunking if set
        chunkingStrategy?.let { strategy ->
            result = result.map { it.chunked(strategy) }
        }

        // Truncate if needed to fit within maxTotalChars
        val totalChars = result.sumOf { it.content.length }
        if (totalChars > maxTotalChars) {
            val charsPerDoc = maxTotalChars / result.size
            result = result.map { it.truncated(charsPerDoc) }
        }

        return result
    }
}

// ==================== Internal Helpers ====================

private fun buildDocumentContext(document: Document): String {
    return buildString {
        appendLine("[Document Context]")

        document.title?.let { appendLine("Title: $it") }
        document.source?.let { appendLine("Source: $it") }
        document.type?.let { appendLine("Type: $it") }

        appendLine()
        appendLine("Content:")
        appendLine(document.content)
        appendLine("[End Document Context]")
    }
}

private fun buildDocumentsContext(documents: List<Document>): String {
    if (documents.isEmpty()) return ""

    return buildString {
        appendLine("[Documents Context - ${documents.size} document(s)]")
        appendLine()

        documents.forEachIndexed { index, doc ->
            appendLine("--- Document ${index + 1} ---")
            doc.title?.let { appendLine("Title: $it") }
            doc.source?.let { appendLine("Source: $it") }
            appendLine()
            appendLine(doc.content)
            appendLine()
        }

        appendLine("[End Documents Context]")
    }
}
