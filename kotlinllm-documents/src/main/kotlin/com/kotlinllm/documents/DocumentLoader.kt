package com.kotlinllm.documents

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path

/**
 * Interface for loading documents from various sources.
 */
interface DocumentLoader {
    /**
     * Supported file extensions.
     */
    val supportedExtensions: Set<String>

    /**
     * Load a document from a path.
     */
    suspend fun load(path: Path): Document

    /**
     * Load a document from an input stream.
     */
    suspend fun load(inputStream: InputStream, metadata: Map<String, String> = emptyMap()): Document

    /**
     * Check if this loader supports the given path.
     */
    fun supports(path: Path): Boolean {
        val extension = path.fileName?.toString()?.substringAfterLast('.', "")?.lowercase() ?: ""
        return extension in supportedExtensions
    }
}

/**
 * Plain text document loader.
 *
 * Supports: .txt
 */
class TextLoader : DocumentLoader {
    override val supportedExtensions = setOf("txt")

    override suspend fun load(path: Path): Document = withContext(Dispatchers.IO) {
        val content = Files.readString(path)
        Document(
            content = content,
            metadata = mapOf(
                "source" to path.toString(),
                "type" to "text",
                "title" to path.fileName.toString()
            )
        )
    }

    override suspend fun load(inputStream: InputStream, metadata: Map<String, String>): Document =
        withContext(Dispatchers.IO) {
            val content = inputStream.bufferedReader().readText()
            Document(
                content = content,
                metadata = metadata + ("type" to "text")
            )
        }
}

/**
 * Markdown document loader.
 *
 * Supports: .md, .markdown
 */
class MarkdownLoader : DocumentLoader {
    override val supportedExtensions = setOf("md", "markdown")

    override suspend fun load(path: Path): Document = withContext(Dispatchers.IO) {
        val content = Files.readString(path)

        // Extract title from first heading if present
        val title = extractTitle(content) ?: path.fileName.toString()

        Document(
            content = content,
            metadata = mapOf(
                "source" to path.toString(),
                "type" to "markdown",
                "title" to title
            )
        )
    }

    override suspend fun load(inputStream: InputStream, metadata: Map<String, String>): Document =
        withContext(Dispatchers.IO) {
            val content = inputStream.bufferedReader().readText()
            val title = extractTitle(content) ?: metadata["title"] ?: "Untitled"

            Document(
                content = content,
                metadata = metadata + mapOf(
                    "type" to "markdown",
                    "title" to title
                )
            )
        }

    private fun extractTitle(content: String): String? {
        // Look for # heading
        val headingMatch = Regex("^#\\s+(.+)$", RegexOption.MULTILINE).find(content)
        return headingMatch?.groupValues?.get(1)?.trim()
    }
}

/**
 * HTML document loader using Jsoup.
 *
 * Supports: .html, .htm
 */
class HtmlLoader(
    private val includeMetadata: Boolean = true
) : DocumentLoader {
    override val supportedExtensions = setOf("html", "htm")

    override suspend fun load(path: Path): Document = withContext(Dispatchers.IO) {
        val html = Files.readString(path)
        parseHtml(html, mapOf("source" to path.toString()))
    }

    override suspend fun load(inputStream: InputStream, metadata: Map<String, String>): Document =
        withContext(Dispatchers.IO) {
            val html = inputStream.bufferedReader().readText()
            parseHtml(html, metadata)
        }

    /**
     * Load HTML from a URL.
     */
    suspend fun loadUrl(url: String): Document = withContext(Dispatchers.IO) {
        val doc = org.jsoup.Jsoup.connect(url)
            .userAgent("KotlinLLM/1.0")
            .timeout(30000)
            .get()

        val title = doc.title()
        val content = doc.body().text()

        val metadata = mutableMapOf(
            "source" to url,
            "type" to "html",
            "title" to title
        )

        if (includeMetadata) {
            doc.select("meta").forEach { meta ->
                val name = meta.attr("name").ifEmpty { meta.attr("property") }
                val metaContent = meta.attr("content")
                if (name.isNotEmpty() && metaContent.isNotEmpty()) {
                    metadata["meta:$name"] = metaContent
                }
            }
        }

        Document(
            content = content,
            metadata = metadata
        )
    }

    private fun parseHtml(html: String, extraMetadata: Map<String, String>): Document {
        val doc = org.jsoup.Jsoup.parse(html)

        val title = doc.title()
        val content = doc.body().text()

        val metadata = mutableMapOf(
            "type" to "html",
            "title" to title
        )
        metadata.putAll(extraMetadata)

        if (includeMetadata) {
            doc.select("meta").forEach { meta ->
                val name = meta.attr("name").ifEmpty { meta.attr("property") }
                val metaContent = meta.attr("content")
                if (name.isNotEmpty() && metaContent.isNotEmpty()) {
                    metadata["meta:$name"] = metaContent
                }
            }
        }

        return Document(
            content = content,
            metadata = metadata
        )
    }
}

/**
 * PDF document loader using Apache PDFBox.
 *
 * Supports: .pdf
 */
class PdfLoader(
    private val extractImages: Boolean = false
) : DocumentLoader {
    override val supportedExtensions = setOf("pdf")

    override suspend fun load(path: Path): Document = withContext(Dispatchers.IO) {
        org.apache.pdfbox.Loader.loadPDF(path.toFile()).use { pdf ->
            parsePdf(pdf, mapOf("source" to path.toString()))
        }
    }

    override suspend fun load(inputStream: InputStream, metadata: Map<String, String>): Document =
        withContext(Dispatchers.IO) {
            org.apache.pdfbox.Loader.loadPDF(inputStream.readBytes()).use { pdf ->
                parsePdf(pdf, metadata)
            }
        }

    private fun parsePdf(
        pdf: org.apache.pdfbox.pdmodel.PDDocument,
        extraMetadata: Map<String, String>
    ): Document {
        val stripper = org.apache.pdfbox.text.PDFTextStripper()
        val content = stripper.getText(pdf)

        val info = pdf.documentInformation
        val metadata = mutableMapOf(
            "type" to "pdf",
            "pages" to pdf.numberOfPages.toString()
        )
        metadata.putAll(extraMetadata)

        info.title?.let { metadata["title"] = it }
        info.author?.let { metadata["author"] = it }
        info.subject?.let { metadata["subject"] = it }
        info.keywords?.let { metadata["keywords"] = it }
        info.creationDate?.let { metadata["created"] = it.time.toString() }

        return Document(
            content = content,
            metadata = metadata
        )
    }
}

/**
 * Registry and factory for document loaders.
 */
object DocumentLoaders {
    private val loaders = mutableListOf<DocumentLoader>(
        TextLoader(),
        MarkdownLoader(),
        HtmlLoader(),
        PdfLoader()
    )

    /**
     * Register a custom document loader.
     */
    fun register(loader: DocumentLoader) {
        loaders.add(0, loader) // Add at front so custom loaders take precedence
    }

    /**
     * Load a document from a path, auto-detecting the loader.
     */
    suspend fun load(path: Path): Document {
        val loader = loaders.find { it.supports(path) }
            ?: throw UnsupportedDocumentException(
                "No loader found for: ${path.fileName}. " +
                "Supported extensions: ${loaders.flatMap { it.supportedExtensions }.toSet()}"
            )

        return loader.load(path)
    }

    /**
     * Load a document from a URL.
     */
    suspend fun loadUrl(url: String): Document {
        val htmlLoader = loaders.filterIsInstance<HtmlLoader>().firstOrNull()
            ?: HtmlLoader()

        return htmlLoader.loadUrl(url)
    }

    /**
     * Load multiple documents from a directory.
     */
    suspend fun loadDirectory(
        path: Path,
        recursive: Boolean = false,
        filter: (Path) -> Boolean = { true }
    ): List<Document> = withContext(Dispatchers.IO) {
        val stream = if (recursive) {
            Files.walk(path)
        } else {
            Files.list(path)
        }

        stream.use { paths ->
            paths
                .filter { Files.isRegularFile(it) }
                .filter { path -> loaders.any { it.supports(path) } }
                .filter { filter(it) }
                .toList()
                .map { load(it) }
        }
    }

    /**
     * Get loader for a specific extension.
     */
    fun forExtension(extension: String): DocumentLoader? {
        return loaders.find { extension.lowercase() in it.supportedExtensions }
    }

    /**
     * Get all supported extensions.
     */
    fun supportedExtensions(): Set<String> {
        return loaders.flatMap { it.supportedExtensions }.toSet()
    }
}

// UnsupportedDocumentException is defined in com.kotlinllm.core.Exceptions
// Re-export for convenience
typealias UnsupportedDocumentException = com.kotlinllm.core.UnsupportedDocumentException
