package com.kotlinllm.structured

import com.kotlinllm.core.Chat
import com.kotlinllm.core.Message
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.*
import kotlinx.serialization.serializer
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.jvmErasure

/**
 * Result of a structured output request.
 */
sealed class StructuredResult<T> {
    data class Success<T>(val value: T, val rawResponse: Message) : StructuredResult<T>()
    data class Failure<T>(val error: Throwable, val rawResponse: Message?) : StructuredResult<T>()

    fun getOrNull(): T? = (this as? Success)?.value
    fun getOrThrow(): T = when (this) {
        is Success -> value
        is Failure -> throw error
    }

    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Failure
}

/**
 * Configuration for structured output requests.
 */
data class StructuredConfig(
    /** Maximum retry attempts for parsing failures */
    val maxRetries: Int = 3,
    /** Temperature for the request (lower is more deterministic) */
    val temperature: Double = 0.1,
    /** Whether to include JSON schema in the prompt */
    val includeSchema: Boolean = true,
    /** Custom extraction prompt */
    val extractionPrompt: String? = null,
    /** Maximum tokens for the response */
    val maxTokens: Int? = null
)

/**
 * JSON Schema generator for Kotlin data classes.
 *
 * Uses reflection to generate JSON Schema from Kotlin classes.
 */
object SchemaGenerator {

    /**
     * Generate JSON Schema for a Kotlin class.
     */
    fun generateSchema(klass: KClass<*>): JsonObject {
        return buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                klass.memberProperties.forEach { prop ->
                    putJsonObject(prop.name) {
                        putPropertySchema(prop.returnType)
                    }
                }
            }

            // Add required fields (non-nullable properties)
            val requiredFields = klass.memberProperties
                .filter { !it.returnType.isMarkedNullable }
                .map { it.name }

            if (requiredFields.isNotEmpty()) {
                putJsonArray("required") {
                    requiredFields.forEach { add(it) }
                }
            }
        }
    }

    /**
     * Generate JSON Schema for a KType.
     */
    fun generateSchema(type: KType): JsonObject {
        return buildJsonObject {
            putPropertySchema(type)
        }
    }

    private fun JsonObjectBuilder.putPropertySchema(type: KType) {
        val klass = type.jvmErasure

        when {
            klass == String::class -> put("type", "string")
            klass == Int::class || klass == Long::class -> put("type", "integer")
            klass == Double::class || klass == Float::class -> put("type", "number")
            klass == Boolean::class -> put("type", "boolean")
            klass == List::class || klass == Set::class -> {
                put("type", "array")
                val elementType = type.arguments.firstOrNull()?.type
                if (elementType != null) {
                    putJsonObject("items") {
                        putPropertySchema(elementType)
                    }
                }
            }
            klass == Map::class -> {
                put("type", "object")
                val valueType = type.arguments.getOrNull(1)?.type
                if (valueType != null) {
                    putJsonObject("additionalProperties") {
                        putPropertySchema(valueType)
                    }
                }
            }
            klass.java.isEnum -> {
                put("type", "string")
                putJsonArray("enum") {
                    klass.java.enumConstants?.forEach {
                        add((it as Enum<*>).name)
                    }
                }
            }
            else -> {
                // Nested object
                put("type", "object")
                putJsonObject("properties") {
                    klass.memberProperties.forEach { prop ->
                        putJsonObject(prop.name) {
                            putPropertySchema(prop.returnType)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Parser for extracting structured data from LLM responses.
 */
object ResponseParser {

    /**
     * Shared JSON instance for parsing.
     */
    val jsonParser: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * Extract JSON from an LLM response text.
     *
     * Handles:
     * - Raw JSON responses
     * - JSON in markdown code blocks
     * - JSON embedded in text
     */
    fun extractJson(text: String): String? {
        // Try direct JSON parse first
        if (text.trim().startsWith("{") || text.trim().startsWith("[")) {
            val trimmed = text.trim()
            if (isValidJson(trimmed)) {
                return trimmed
            }
        }

        // Try extracting from markdown code blocks
        val codeBlockPattern = Regex("```(?:json)?\\s*\\n?([\\s\\S]*?)\\n?```")
        codeBlockPattern.find(text)?.let { match ->
            val jsonContent = match.groupValues[1].trim()
            if (isValidJson(jsonContent)) {
                return jsonContent
            }
        }

        // Try finding JSON object in text
        val jsonObjectPattern = Regex("\\{[\\s\\S]*\\}")
        jsonObjectPattern.find(text)?.let { match ->
            val potentialJson = match.value
            if (isValidJson(potentialJson)) {
                return potentialJson
            }
        }

        // Try finding JSON array in text
        val jsonArrayPattern = Regex("\\[[\\s\\S]*\\]")
        jsonArrayPattern.find(text)?.let { match ->
            val potentialJson = match.value
            if (isValidJson(potentialJson)) {
                return potentialJson
            }
        }

        return null
    }

    /**
     * Parse JSON string to a typed object.
     */
    inline fun <reified T> parse(jsonString: String): T {
        return jsonParser.decodeFromString(jsonString)
    }

    /**
     * Parse JSON string to a typed object, returning null on failure.
     */
    inline fun <reified T> parseOrNull(jsonString: String): T? {
        return try {
            jsonParser.decodeFromString(jsonString)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Check if a string is valid JSON.
     */
    fun isValidJson(text: String): Boolean {
        return try {
            jsonParser.parseToJsonElement(text)
            true
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * Builder for structured output requests.
 */
class StructuredOutputBuilder<T : Any>(
    private val chat: Chat,
    private val serializer: KSerializer<T>,
    private val klass: KClass<T>
) {
    private var prompt: String = ""
    private var config: StructuredConfig = StructuredConfig()

    /**
     * Set the extraction prompt.
     */
    fun prompt(prompt: String) = apply { this.prompt = prompt }

    /**
     * Set maximum retries.
     */
    fun maxRetries(retries: Int) = apply {
        config = config.copy(maxRetries = retries)
    }

    /**
     * Set temperature.
     */
    fun temperature(temp: Double) = apply {
        config = config.copy(temperature = temp)
    }

    /**
     * Include JSON schema in the prompt.
     */
    fun includeSchema(include: Boolean = true) = apply {
        config = config.copy(includeSchema = include)
    }

    /**
     * Execute the structured output request.
     */
    suspend fun execute(): StructuredResult<T> {
        return executeStructuredRequestWithSerializer(chat, serializer, klass, prompt, config)
    }
}

// ==================== Public Functions ====================

/**
 * Execute a structured request with a serializer.
 */
suspend fun <T : Any> executeStructuredRequestWithSerializer(
    chat: Chat,
    serializer: KSerializer<T>,
    klass: KClass<T>,
    userPrompt: String,
    config: StructuredConfig
): StructuredResult<T> {
    val json = ResponseParser.jsonParser

    // Build the full prompt with schema instructions
    val schema = SchemaGenerator.generateSchema(klass)
    val schemaStr = json.encodeToString(JsonElement.serializer(), schema)

    val fullPrompt = buildString {
        append(userPrompt)
        append("\n\n")

        if (config.includeSchema) {
            append("Respond with a JSON object matching this schema:\n")
            append("```json\n")
            append(schemaStr)
            append("\n```\n\n")
        }

        append("IMPORTANT: Respond ONLY with valid JSON. No explanations or additional text.")
    }

    // Set temperature for more deterministic output
    val originalTemp = chat.temperature()
    chat.withTemperature(config.temperature)

    var lastResponse: Message? = null
    var lastError: Throwable? = null

    // Try up to maxRetries times
    repeat(config.maxRetries) { attempt ->
        try {
            val response = if (attempt == 0) {
                chat.ask(fullPrompt)
            } else {
                // On retry, ask for correction
                chat.ask("That response was not valid JSON. Please respond with ONLY valid JSON matching the schema, no other text.")
            }

            lastResponse = response

            // Extract and parse JSON
            val jsonStr = ResponseParser.extractJson(response.text)
                ?: throw StructuredOutputException("Could not extract JSON from response")

            // Parse using serializer
            val result = json.decodeFromString(serializer, jsonStr)

            // Restore original temperature
            originalTemp?.let { chat.withTemperature(it) }

            return StructuredResult.Success(result, response)

        } catch (e: Exception) {
            lastError = e
        }
    }

    // Restore original temperature
    originalTemp?.let { chat.withTemperature(it) }

    return StructuredResult.Failure(
        lastError ?: StructuredOutputException("Failed to get structured output"),
        lastResponse
    )
}

// ==================== Extension Functions ====================

/**
 * Ask for a structured response.
 *
 * Example:
 * ```kotlin
 * @Serializable
 * data class Person(val name: String, val age: Int)
 *
 * val result = chat.askStructured<Person>("Extract the person from: John is 30 years old")
 * val person = result.getOrThrow()
 * ```
 */
suspend inline fun <reified T : Any> Chat.askStructured(
    prompt: String,
    config: StructuredConfig = StructuredConfig()
): StructuredResult<T> {
    return executeStructuredRequestWithSerializer(
        this,
        serializer<T>(),
        T::class,
        prompt,
        config
    )
}

/**
 * Ask for a structured response, throwing on failure.
 *
 * Example:
 * ```kotlin
 * val person: Person = chat.askTyped("Extract the person from: John is 30 years old")
 * ```
 */
suspend inline fun <reified T : Any> Chat.askTyped(prompt: String): T {
    return askStructured<T>(prompt).getOrThrow()
}

/**
 * DSL for structured output requests.
 *
 * Example:
 * ```kotlin
 * val person = chat.structured<Person> {
 *     prompt("Extract person info from: John is 30 years old")
 *     maxRetries(3)
 *     temperature(0.1)
 * }.execute().getOrThrow()
 * ```
 */
inline fun <reified T : Any> Chat.structured(
    block: StructuredOutputBuilder<T>.() -> Unit
): StructuredOutputBuilder<T> {
    val builder = StructuredOutputBuilder(this, serializer<T>(), T::class)
    builder.block()
    return builder
}

// StructuredOutputException is defined in com.kotlinllm.core.Exceptions
// Re-export for convenience
typealias StructuredOutputException = com.kotlinllm.core.StructuredOutputException

/**
 * Java-compatible result class for structured output.
 */
data class JavaCompatibleStructuredResult<T>(
    val value: T?,
    val rawResponse: String,
    val error: String?,
    val isSuccess: Boolean
)

/**
 * Extension function for Java interop - executes structured request with serializer.
 */
suspend fun <T : Any> Chat.executeStructuredRequestWithSerializer(
    prompt: String,
    serializer: KSerializer<T>,
    config: StructuredConfig
): JavaCompatibleStructuredResult<T> {
    // Get KClass from serializer descriptor
    val result = executeStructuredRequestWithSerializer(
        chat = this,
        serializer = serializer,
        klass = serializer.descriptor.serialName.let {
            try {
                @Suppress("UNCHECKED_CAST")
                Class.forName(it).kotlin as KClass<T>
            } catch (e: Exception) {
                // Fallback - use Any
                @Suppress("UNCHECKED_CAST")
                Any::class as KClass<T>
            }
        },
        userPrompt = prompt,
        config = config
    )

    return when (result) {
        is StructuredResult.Success -> JavaCompatibleStructuredResult(
            value = result.value,
            rawResponse = result.rawResponse.text,
            error = null,
            isSuccess = true
        )
        is StructuredResult.Failure -> JavaCompatibleStructuredResult(
            value = null,
            rawResponse = result.rawResponse?.text ?: "",
            error = result.error.message,
            isSuccess = false
        )
    }
}
