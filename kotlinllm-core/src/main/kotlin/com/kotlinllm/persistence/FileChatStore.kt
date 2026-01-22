package com.kotlinllm.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * File-based implementation of ChatStore using JSON.
 *
 * Each chat is stored as a separate JSON file in the specified directory.
 * Thread-safe and suitable for single-instance applications.
 *
 * File structure:
 * ```
 * baseDir/
 *   chats/
 *     {chat-id-1}.json
 *     {chat-id-2}.json
 *   index.json  (optional, for faster listing)
 * ```
 *
 * Example:
 * ```kotlin
 * val store = FileChatStore(Path.of("./data"))
 * val chat = KotlinLLM.chat().withStore(store)
 * ```
 */
class FileChatStore(
    private val baseDir: Path,
    private val prettyPrint: Boolean = false
) : ChatStore {

    private val chatsDir: Path = baseDir.resolve("chats")
    private val mutex = Mutex()

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = this@FileChatStore.prettyPrint
        encodeDefaults = true
    }

    init {
        // Create directories if they don't exist
        Files.createDirectories(chatsDir)
    }

    override suspend fun save(chat: ChatRecord): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val updated = chat.copy(updatedAt = System.currentTimeMillis())
            val file = chatFile(chat.id)
            file.writeText(json.encodeToString(updated))
            chat.id
        }
    }

    override suspend fun load(id: String): ChatRecord? = withContext(Dispatchers.IO) {
        val file = chatFile(id)
        if (!file.exists()) return@withContext null

        try {
            json.decodeFromString<ChatRecord>(file.readText())
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = chatFile(id)
            file.exists() && file.delete()
        }
    }

    override suspend fun list(limit: Int, offset: Int): List<String> = withContext(Dispatchers.IO) {
        chatsDir.toFile()
            .listFiles { f -> f.extension == "json" }
            ?.map { file ->
                val updatedAt = try {
                    json.decodeFromString<ChatRecord>(file.readText()).updatedAt
                } catch (e: Exception) {
                    0L
                }
                file.nameWithoutExtension to updatedAt
            }
            ?.sortedByDescending { it.second }
            ?.map { it.first }
            ?.drop(offset)
            ?.take(limit)
            ?: emptyList()
    }

    override suspend fun appendMessage(chatId: String, message: MessageRecord) {
        mutex.withLock {
            val chat = load(chatId) ?: return
            val updated = chat.copy(
                messages = chat.messages + message,
                updatedAt = System.currentTimeMillis()
            )
            save(updated)
        }
    }

    override suspend fun loadMessages(chatId: String, limit: Int, offset: Int): List<MessageRecord> {
        return load(chatId)?.messages
            ?.drop(offset)
            ?.take(limit)
            ?: emptyList()
    }

    /**
     * Get the file path for a chat.
     */
    private fun chatFile(id: String): File {
        // Sanitize ID to prevent directory traversal
        val safeId = id.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return chatsDir.resolve("$safeId.json").toFile()
    }

    /**
     * Get all chat records (loads all from disk).
     */
    suspend fun loadAll(): List<ChatRecord> = withContext(Dispatchers.IO) {
        chatsDir.toFile()
            .listFiles { f -> f.extension == "json" }
            ?.mapNotNull { file ->
                try {
                    json.decodeFromString<ChatRecord>(file.readText())
                } catch (e: Exception) {
                    null
                }
            }
            ?.sortedByDescending { it.updatedAt }
            ?: emptyList()
    }

    /**
     * Export all chats to a single JSON file.
     */
    suspend fun exportAll(outputFile: Path) = withContext(Dispatchers.IO) {
        val allChats = loadAll()
        outputFile.toFile().writeText(json.encodeToString(allChats))
    }

    /**
     * Import chats from a JSON file.
     */
    suspend fun importAll(inputFile: Path) = withContext(Dispatchers.IO) {
        val chats = json.decodeFromString<List<ChatRecord>>(inputFile.toFile().readText())
        chats.forEach { save(it) }
    }

    /**
     * Get storage statistics.
     */
    fun stats(): StorageStats {
        val files = chatsDir.toFile().listFiles { f -> f.extension == "json" } ?: emptyArray()
        return StorageStats(
            chatCount = files.size,
            totalSizeBytes = files.sumOf { it.length() },
            directory = baseDir.toString()
        )
    }

    data class StorageStats(
        val chatCount: Int,
        val totalSizeBytes: Long,
        val directory: String
    ) {
        val totalSizeKb: Double get() = totalSizeBytes / 1024.0
        val totalSizeMb: Double get() = totalSizeKb / 1024.0
    }
}
