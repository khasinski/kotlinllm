package com.kotlinllm.documents

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.*

class DocumentTest {

    @Test
    fun `Document truncated works correctly`() {
        val doc = Document(
            content = "This is a long piece of text that should be truncated.",
            metadata = mapOf("title" to "Test")
        )

        val truncated = doc.truncated(20)
        assertEquals(20 + "...[truncated]".length, truncated.content.length)
        assertTrue(truncated.content.endsWith("[truncated]"))
    }

    @Test
    fun `Document truncated does not modify short content`() {
        val doc = Document(content = "Short")
        val truncated = doc.truncated(100)
        assertEquals("Short", truncated.content)
    }

    @Test
    fun `Document estimateTokens provides estimate`() {
        val doc = Document(content = "Hello world, this is a test document.") // 38 chars

        val tokens = doc.estimateTokens()
        assertTrue(tokens in 5..15) // 38/4 = ~9.5
    }

    @Test
    fun `Document metadata getters work`() {
        val doc = Document(
            content = "Content",
            metadata = mapOf(
                "source" to "/path/to/file.txt",
                "title" to "My Document",
                "type" to "text"
            )
        )

        assertEquals("/path/to/file.txt", doc.source)
        assertEquals("My Document", doc.title)
        assertEquals("text", doc.type)
    }

    @Test
    fun `Document chunked creates chunks`() {
        val doc = Document(content = "Line 1. Line 2. Line 3. Line 4. Line 5.")

        val chunked = doc.chunked(CharacterChunker(chunkSize = 20, overlap = 5))

        assertTrue(chunked.chunks.isNotEmpty())
        assertTrue(chunked.chunks.all { it.content.isNotEmpty() })
    }

    @Test
    fun `CharacterChunker splits content correctly`() {
        val content = "0123456789" + "0123456789" + "0123456789" // 30 chars
        val chunker = CharacterChunker(chunkSize = 10, overlap = 2)

        val chunks = chunker.chunk(content)

        assertTrue(chunks.size >= 3)
        assertEquals(0, chunks[0].index)
        assertEquals(0, chunks[0].startOffset)
    }

    @Test
    fun `CharacterChunker handles small content`() {
        val content = "Small"
        val chunker = CharacterChunker(chunkSize = 100, overlap = 10)

        val chunks = chunker.chunk(content)

        assertEquals(1, chunks.size)
        assertEquals("Small", chunks[0].content)
    }

    @Test
    fun `SentenceChunker splits at sentence boundaries`() {
        val content = "First sentence. Second sentence. Third sentence."
        val chunker = SentenceChunker(maxChunkSize = 100, minChunkSize = 10)

        val chunks = chunker.chunk(content)

        assertTrue(chunks.isNotEmpty())
        chunks.forEach { chunk ->
            assertTrue(chunk.content.isNotBlank())
        }
    }

    @Test
    fun `ParagraphChunker splits at paragraph boundaries`() {
        val content = """
            First paragraph with some content.

            Second paragraph with more content.

            Third paragraph to finish.
        """.trimIndent()

        val chunker = ParagraphChunker(maxChunkSize = 100)
        val chunks = chunker.chunk(content)

        assertTrue(chunks.isNotEmpty())
    }

    @Test
    fun `DocumentChunk has correct metadata`() {
        val chunker = CharacterChunker(chunkSize = 10, overlap = 2)
        val chunks = chunker.chunk("0123456789ABCDEFGHIJ", mapOf("source" to "test"))

        assertTrue(chunks.all { it.metadata.containsKey("source") })
        assertTrue(chunks.all { it.metadata.containsKey("chunkIndex") })
    }

    @Test
    fun `DocumentChunk estimateTokens works`() {
        val chunk = DocumentChunk(
            content = "Hello world",
            index = 0,
            startOffset = 0,
            endOffset = 11
        )

        assertTrue(chunk.estimateTokens() > 0)
    }
}

class DocumentLoaderTest {

    @Test
    fun `TextLoader loads text files`() = runTest {
        val tempFile = Files.createTempFile("test", ".txt")
        try {
            Files.writeString(tempFile, "Hello, World!")

            val loader = TextLoader()
            assertTrue(loader.supports(tempFile))

            val doc = loader.load(tempFile)
            assertEquals("Hello, World!", doc.content)
            assertEquals("text", doc.type)
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    @Test
    fun `MarkdownLoader loads markdown files`() = runTest {
        val tempFile = Files.createTempFile("test", ".md")
        try {
            Files.writeString(tempFile, "# Title\n\nSome content.")

            val loader = MarkdownLoader()
            assertTrue(loader.supports(tempFile))

            val doc = loader.load(tempFile)
            assertTrue(doc.content.contains("Title"))
            assertEquals("markdown", doc.type)
            assertEquals("Title", doc.title)
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    @Test
    fun `DocumentLoaders auto-detects loader`() = runTest {
        val tempFile = Files.createTempFile("test", ".txt")
        try {
            Files.writeString(tempFile, "Auto-detected content")

            val doc = DocumentLoaders.load(tempFile)
            assertEquals("Auto-detected content", doc.content)
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    @Test
    fun `DocumentLoaders throws for unsupported extension`() = runTest {
        val tempFile = Files.createTempFile("test", ".xyz")
        try {
            assertFailsWith<UnsupportedDocumentException> {
                DocumentLoaders.load(tempFile)
            }
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    @Test
    fun `DocumentLoaders supportedExtensions returns all extensions`() {
        val extensions = DocumentLoaders.supportedExtensions()

        assertTrue(extensions.contains("txt"))
        assertTrue(extensions.contains("md"))
        assertTrue(extensions.contains("html"))
        assertTrue(extensions.contains("pdf"))
    }

    @Test
    fun `DocumentLoaders forExtension finds correct loader`() {
        val txtLoader = DocumentLoaders.forExtension("txt")
        val mdLoader = DocumentLoaders.forExtension("md")
        val unknownLoader = DocumentLoaders.forExtension("xyz")

        assertNotNull(txtLoader)
        assertTrue(txtLoader is TextLoader)

        assertNotNull(mdLoader)
        assertTrue(mdLoader is MarkdownLoader)

        assertNull(unknownLoader)
    }
}

class DocumentsBuilderTest {

    @Test
    fun `DocumentsBuilder builds list of documents`() = runTest {
        val builder = DocumentsBuilder()

        builder.document(Document(content = "Doc 1"))
        builder.document(Document(content = "Doc 2"))

        val docs = builder.build()
        assertEquals(2, docs.size)
    }

    @Test
    fun `DocumentsBuilder applies maxChars`() = runTest {
        val builder = DocumentsBuilder()

        builder.document(Document(content = "A".repeat(1000)))
        builder.document(Document(content = "B".repeat(1000)))
        builder.maxChars(100)

        val docs = builder.build()
        assertTrue(docs.sumOf { it.content.length } <= 200) // Some tolerance for truncation suffix
    }

    @Test
    fun `DocumentsBuilder applies chunking`() = runTest {
        val builder = DocumentsBuilder()

        builder.document(Document(content = "This is a longer document that should be chunked."))
        builder.characterChunking(chunkSize = 20, overlap = 5)

        val docs = builder.build()
        assertTrue(docs.first().chunks.isNotEmpty())
    }
}
