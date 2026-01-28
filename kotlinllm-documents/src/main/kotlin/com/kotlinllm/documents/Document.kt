package com.kotlinllm.documents

/**
 * Represents a loaded document with its content and metadata.
 *
 * Example:
 * ```kotlin
 * val doc = DocumentLoaders.load(Path.of("spec.pdf"))
 * println(doc.content)
 * println(doc.metadata)
 * ```
 */
data class Document(
    /** The full text content of the document */
    val content: String,

    /** Document metadata (source, title, etc.) */
    val metadata: Map<String, String> = emptyMap(),

    /** Pre-chunked content (empty if not chunked) */
    val chunks: List<DocumentChunk> = emptyList()
) {
    /**
     * Get the document with content chunked.
     */
    fun chunked(strategy: ChunkingStrategy): Document {
        return copy(chunks = strategy.chunk(content, metadata))
    }

    /**
     * Get a truncated version of the document.
     */
    fun truncated(maxChars: Int): Document {
        return if (content.length <= maxChars) {
            this
        } else {
            copy(content = content.take(maxChars) + "...[truncated]")
        }
    }

    /**
     * Estimate token count.
     */
    fun estimateTokens(): Int {
        return content.length / 4 // Rough estimate: 4 chars per token
    }

    /**
     * Get the source path/URL.
     */
    val source: String? get() = metadata["source"]

    /**
     * Get the document title.
     */
    val title: String? get() = metadata["title"]

    /**
     * Get the document type.
     */
    val type: String? get() = metadata["type"]
}

/**
 * A chunk of a document.
 */
data class DocumentChunk(
    /** The chunk content */
    val content: String,

    /** Chunk index (0-based) */
    val index: Int,

    /** Start character position in original document */
    val startOffset: Int,

    /** End character position in original document */
    val endOffset: Int,

    /** Metadata inherited from parent document plus chunk-specific data */
    val metadata: Map<String, String> = emptyMap()
) {
    /**
     * Estimate token count.
     */
    fun estimateTokens(): Int {
        return content.length / 4
    }
}

/**
 * Strategy for chunking document content.
 */
interface ChunkingStrategy {
    /**
     * Chunk the content into smaller pieces.
     */
    fun chunk(content: String, metadata: Map<String, String> = emptyMap()): List<DocumentChunk>
}

/**
 * Character-based chunking strategy.
 *
 * Splits content into chunks of approximately `chunkSize` characters,
 * with `overlap` characters of overlap between chunks.
 *
 * Example:
 * ```kotlin
 * val chunker = CharacterChunker(chunkSize = 1000, overlap = 200)
 * val doc = document.chunked(chunker)
 * ```
 */
class CharacterChunker(
    private val chunkSize: Int = 1000,
    private val overlap: Int = 200
) : ChunkingStrategy {

    init {
        require(chunkSize > 0) { "chunkSize must be positive" }
        require(overlap >= 0) { "overlap must be non-negative" }
        require(overlap < chunkSize) { "overlap must be less than chunkSize" }
    }

    override fun chunk(content: String, metadata: Map<String, String>): List<DocumentChunk> {
        if (content.length <= chunkSize) {
            return listOf(
                DocumentChunk(
                    content = content,
                    index = 0,
                    startOffset = 0,
                    endOffset = content.length,
                    metadata = metadata + ("chunkIndex" to "0")
                )
            )
        }

        val chunks = mutableListOf<DocumentChunk>()
        var start = 0
        var index = 0

        while (start < content.length) {
            val end = minOf(start + chunkSize, content.length)
            val chunkContent = content.substring(start, end)

            chunks.add(
                DocumentChunk(
                    content = chunkContent,
                    index = index,
                    startOffset = start,
                    endOffset = end,
                    metadata = metadata + ("chunkIndex" to index.toString())
                )
            )

            index++
            start = if (end == content.length) {
                content.length // Exit loop
            } else {
                end - overlap
            }
        }

        return chunks
    }
}

/**
 * Sentence-based chunking strategy.
 *
 * Splits content at sentence boundaries, keeping chunks under `maxChunkSize`.
 *
 * Example:
 * ```kotlin
 * val chunker = SentenceChunker(maxChunkSize = 1000)
 * val doc = document.chunked(chunker)
 * ```
 */
class SentenceChunker(
    private val maxChunkSize: Int = 1000,
    private val minChunkSize: Int = 100
) : ChunkingStrategy {

    init {
        require(maxChunkSize > 0) { "maxChunkSize must be positive" }
        require(minChunkSize >= 0) { "minChunkSize must be non-negative" }
        require(minChunkSize < maxChunkSize) { "minChunkSize must be less than maxChunkSize" }
    }

    // Simple sentence boundary detection
    private val sentencePattern = Regex("[.!?]+\\s+|\\n\\n+")

    override fun chunk(content: String, metadata: Map<String, String>): List<DocumentChunk> {
        val sentences = splitIntoSentences(content)

        if (sentences.isEmpty()) {
            return emptyList()
        }

        val chunks = mutableListOf<DocumentChunk>()
        var currentChunk = StringBuilder()
        var currentStart = 0
        var chunkStart = 0
        var index = 0

        for (sentence in sentences) {
            val potentialLength = currentChunk.length + sentence.length

            if (potentialLength > maxChunkSize && currentChunk.isNotEmpty()) {
                // Save current chunk
                chunks.add(
                    DocumentChunk(
                        content = currentChunk.toString(),
                        index = index,
                        startOffset = chunkStart,
                        endOffset = currentStart,
                        metadata = metadata + ("chunkIndex" to index.toString())
                    )
                )
                index++
                currentChunk = StringBuilder()
                chunkStart = currentStart
            }

            currentChunk.append(sentence)
            currentStart += sentence.length
        }

        // Add remaining content
        if (currentChunk.isNotEmpty()) {
            chunks.add(
                DocumentChunk(
                    content = currentChunk.toString(),
                    index = index,
                    startOffset = chunkStart,
                    endOffset = currentStart,
                    metadata = metadata + ("chunkIndex" to index.toString())
                )
            )
        }

        return chunks
    }

    private fun splitIntoSentences(text: String): List<String> {
        val sentences = mutableListOf<String>()
        var lastEnd = 0

        sentencePattern.findAll(text).forEach { match ->
            val sentence = text.substring(lastEnd, match.range.last + 1)
            if (sentence.isNotBlank()) {
                sentences.add(sentence)
            }
            lastEnd = match.range.last + 1
        }

        // Add remaining text
        if (lastEnd < text.length) {
            val remaining = text.substring(lastEnd)
            if (remaining.isNotBlank()) {
                sentences.add(remaining)
            }
        }

        return sentences
    }
}

/**
 * Paragraph-based chunking strategy.
 *
 * Splits content at paragraph boundaries (double newlines).
 */
class ParagraphChunker(
    private val maxChunkSize: Int = 2000
) : ChunkingStrategy {

    override fun chunk(content: String, metadata: Map<String, String>): List<DocumentChunk> {
        val paragraphs = content.split(Regex("\\n\\s*\\n"))
            .filter { it.isNotBlank() }
            .map { it.trim() }

        if (paragraphs.isEmpty()) {
            return emptyList()
        }

        val chunks = mutableListOf<DocumentChunk>()
        var currentChunk = StringBuilder()
        var currentOffset = 0
        var chunkStart = 0
        var index = 0

        for (para in paragraphs) {
            val potentialLength = currentChunk.length + para.length + 2 // +2 for newlines

            if (potentialLength > maxChunkSize && currentChunk.isNotEmpty()) {
                chunks.add(
                    DocumentChunk(
                        content = currentChunk.toString().trim(),
                        index = index,
                        startOffset = chunkStart,
                        endOffset = currentOffset,
                        metadata = metadata + ("chunkIndex" to index.toString())
                    )
                )
                index++
                currentChunk = StringBuilder()
                chunkStart = currentOffset
            }

            if (currentChunk.isNotEmpty()) {
                currentChunk.append("\n\n")
            }
            currentChunk.append(para)
            currentOffset += para.length + 2
        }

        // Add remaining content
        if (currentChunk.isNotEmpty()) {
            chunks.add(
                DocumentChunk(
                    content = currentChunk.toString().trim(),
                    index = index,
                    startOffset = chunkStart,
                    endOffset = currentOffset,
                    metadata = metadata + ("chunkIndex" to index.toString())
                )
            )
        }

        return chunks
    }
}
