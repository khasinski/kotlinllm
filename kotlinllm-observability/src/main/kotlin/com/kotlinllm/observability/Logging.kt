package com.kotlinllm.observability

import com.kotlinllm.core.LogLevel
import com.kotlinllm.core.Message

/**
 * Logger interface for LLM operations.
 */
interface LLMLogger {
    fun debug(message: String)
    fun info(message: String)
    fun warn(message: String)
    fun error(message: String, throwable: Throwable? = null)

    /**
     * Log a request being sent.
     */
    fun logRequest(provider: String, model: String, messages: List<Message>, streaming: Boolean)

    /**
     * Log a response received.
     */
    fun logResponse(provider: String, model: String, response: Message, durationMs: Long)

    /**
     * Log an error.
     */
    fun logError(provider: String, model: String, error: Throwable, durationMs: Long)
}

/**
 * Console logger implementation.
 *
 * Prints logs to stdout/stderr with timestamps and log levels.
 *
 * Example:
 * ```kotlin
 * val logger = ConsoleLogger(LogLevel.DEBUG)
 * KotlinLLM.addInterceptor(LoggingInterceptor(logger))
 * ```
 */
class ConsoleLogger(
    private val level: LogLevel = LogLevel.INFO,
    private val includeMessageContent: Boolean = false,
    private val maxContentLength: Int = 200
) : LLMLogger {

    override fun debug(message: String) {
        if (level <= LogLevel.DEBUG) {
            println("[${timestamp()}] DEBUG: $message")
        }
    }

    override fun info(message: String) {
        if (level <= LogLevel.INFO) {
            println("[${timestamp()}] INFO: $message")
        }
    }

    override fun warn(message: String) {
        if (level <= LogLevel.WARN) {
            println("[${timestamp()}] WARN: $message")
        }
    }

    override fun error(message: String, throwable: Throwable?) {
        if (level <= LogLevel.ERROR) {
            System.err.println("[${timestamp()}] ERROR: $message")
            throwable?.printStackTrace(System.err)
        }
    }

    override fun logRequest(provider: String, model: String, messages: List<Message>, streaming: Boolean) {
        if (level <= LogLevel.DEBUG) {
            val streamingStr = if (streaming) " (streaming)" else ""
            debug("LLM Request: provider=$provider, model=$model, messages=${messages.size}$streamingStr")

            if (includeMessageContent) {
                messages.forEach { msg ->
                    val content = truncate(msg.text)
                    debug("  [${msg.role}]: $content")
                }
            }
        }
    }

    override fun logResponse(provider: String, model: String, response: Message, durationMs: Long) {
        if (level <= LogLevel.DEBUG) {
            val tokens = response.tokens?.let { " (${it.total} tokens)" } ?: ""
            debug("LLM Response: provider=$provider, model=$model, duration=${durationMs}ms$tokens")

            if (includeMessageContent) {
                val content = truncate(response.text)
                debug("  Response: $content")
            }
        } else if (level <= LogLevel.INFO) {
            val tokens = response.tokens?.total ?: 0
            info("LLM: $provider/$model - ${durationMs}ms, ${tokens} tokens")
        }
    }

    override fun logError(provider: String, model: String, error: Throwable, durationMs: Long) {
        error("LLM Error: provider=$provider, model=$model, duration=${durationMs}ms, error=${error.message}", error)
    }

    private fun timestamp(): String {
        return java.time.LocalDateTime.now().toString()
    }

    private fun truncate(text: String): String {
        return if (text.length > maxContentLength) {
            text.take(maxContentLength) + "..."
        } else {
            text
        }
    }
}

/**
 * SLF4J logger implementation.
 *
 * Uses SLF4J for logging, allowing integration with various logging backends
 * (Logback, Log4j2, etc.)
 *
 * Note: Requires SLF4J to be on the classpath.
 *
 * Example:
 * ```kotlin
 * val logger = Slf4jLogger()
 * KotlinLLM.addInterceptor(LoggingInterceptor(logger))
 * ```
 */
class Slf4jLogger(
    private val loggerName: String = "com.kotlinllm",
    private val includeMessageContent: Boolean = false,
    private val maxContentLength: Int = 200
) : LLMLogger {

    private val logger: org.slf4j.Logger by lazy {
        org.slf4j.LoggerFactory.getLogger(loggerName)
    }

    override fun debug(message: String) {
        logger.debug(message)
    }

    override fun info(message: String) {
        logger.info(message)
    }

    override fun warn(message: String) {
        logger.warn(message)
    }

    override fun error(message: String, throwable: Throwable?) {
        if (throwable != null) {
            logger.error(message, throwable)
        } else {
            logger.error(message)
        }
    }

    override fun logRequest(provider: String, model: String, messages: List<Message>, streaming: Boolean) {
        if (logger.isDebugEnabled) {
            val streamingStr = if (streaming) " (streaming)" else ""
            debug("LLM Request: provider=$provider, model=$model, messages=${messages.size}$streamingStr")

            if (includeMessageContent) {
                messages.forEach { msg ->
                    val content = truncate(msg.text)
                    debug("  [${msg.role}]: $content")
                }
            }
        }
    }

    override fun logResponse(provider: String, model: String, response: Message, durationMs: Long) {
        if (logger.isDebugEnabled) {
            val tokens = response.tokens?.let { " (${it.total} tokens)" } ?: ""
            debug("LLM Response: provider=$provider, model=$model, duration=${durationMs}ms$tokens")

            if (includeMessageContent) {
                val content = truncate(response.text)
                debug("  Response: $content")
            }
        } else if (logger.isInfoEnabled) {
            val tokens = response.tokens?.total ?: 0
            info("LLM: $provider/$model - ${durationMs}ms, ${tokens} tokens")
        }
    }

    override fun logError(provider: String, model: String, error: Throwable, durationMs: Long) {
        error("LLM Error: provider=$provider, model=$model, duration=${durationMs}ms, error=${error.message}", error)
    }

    private fun truncate(text: String): String {
        return if (text.length > maxContentLength) {
            text.take(maxContentLength) + "..."
        } else {
            text
        }
    }
}

/**
 * No-op logger that discards all logs.
 */
object NoOpLogger : LLMLogger {
    override fun debug(message: String) {}
    override fun info(message: String) {}
    override fun warn(message: String) {}
    override fun error(message: String, throwable: Throwable?) {}
    override fun logRequest(provider: String, model: String, messages: List<Message>, streaming: Boolean) {}
    override fun logResponse(provider: String, model: String, response: Message, durationMs: Long) {}
    override fun logError(provider: String, model: String, error: Throwable, durationMs: Long) {}
}
