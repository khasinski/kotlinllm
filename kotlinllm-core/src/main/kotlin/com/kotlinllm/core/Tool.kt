package com.kotlinllm.core

import kotlinx.serialization.json.*

/**
 * Base class for creating tools that AI models can use.
 *
 * Example:
 * ```kotlin
 * class Calculator : Tool(
 *     name = "calculator",
 *     description = "Performs basic arithmetic"
 * ) {
 *     val expression by param<String>("Mathematical expression to evaluate")
 *
 *     override suspend fun execute(args: Map<String, JsonElement>): String {
 *         val expr = args.string("expression")
 *         // ... evaluate expression
 *         return result
 *     }
 * }
 * ```
 */
abstract class Tool(
    val name: String,
    val description: String
) {
    private val parameters = mutableMapOf<String, ParameterDef>()

    /**
     * Define a required string parameter.
     */
    protected fun param(description: String, required: Boolean = true): ParameterDelegate<String> {
        return ParameterDelegate("string", description, required) { it.jsonPrimitive.content }
    }

    /**
     * Define a required parameter with custom type.
     */
    protected inline fun <reified T> param(
        description: String,
        required: Boolean = true,
        noinline converter: (JsonElement) -> T = { defaultConverter(it) }
    ): ParameterDelegate<T> {
        val type = when (T::class) {
            String::class -> "string"
            Int::class -> "integer"
            Long::class -> "integer"
            Double::class -> "number"
            Float::class -> "number"
            Boolean::class -> "boolean"
            List::class -> "array"
            else -> "object"
        }
        return ParameterDelegate(type, description, required, converter)
    }

    /**
     * Register a parameter definition.
     */
    internal fun registerParameter(name: String, def: ParameterDef) {
        parameters[name] = def
    }

    /**
     * Execute the tool with given arguments.
     */
    abstract suspend fun execute(args: Map<String, JsonElement>): Any

    /**
     * Get the JSON schema for this tool's parameters.
     */
    fun parametersSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            parameters.forEach { (name, param) ->
                putJsonObject(name) {
                    put("type", param.type)
                    put("description", param.description)
                }
            }
        }
        putJsonArray("required") {
            parameters.filter { it.value.required }.keys.forEach { add(it) }
        }
    }

    /**
     * Convert tool to provider-agnostic format.
     */
    fun toSchema(): ToolSchema = ToolSchema(
        name = name,
        description = description,
        parameters = parametersSchema()
    )

    companion object {
        inline fun <reified T> defaultConverter(element: JsonElement): T {
            return when (T::class) {
                String::class -> element.jsonPrimitive.content as T
                Int::class -> element.jsonPrimitive.int as T
                Long::class -> element.jsonPrimitive.long as T
                Double::class -> element.jsonPrimitive.double as T
                Float::class -> element.jsonPrimitive.float as T
                Boolean::class -> element.jsonPrimitive.boolean as T
                else -> throw IllegalArgumentException("Unsupported type: ${T::class}")
            }
        }
    }
}

/**
 * Parameter definition.
 */
data class ParameterDef(
    val type: String,
    val description: String,
    val required: Boolean
)

/**
 * Delegate for tool parameters.
 */
class ParameterDelegate<T>(
    private val type: String,
    private val description: String,
    private val required: Boolean,
    private val converter: (JsonElement) -> T
) {
    private var name: String? = null

    operator fun provideDelegate(thisRef: Tool, property: kotlin.reflect.KProperty<*>): ParameterDelegate<T> {
        name = property.name
        thisRef.registerParameter(property.name, ParameterDef(type, description, required))
        return this
    }

    operator fun getValue(thisRef: Tool, property: kotlin.reflect.KProperty<*>): (Map<String, JsonElement>) -> T {
        return { args ->
            val element = args[name] ?: if (required) {
                throw IllegalArgumentException("Missing required parameter: $name")
            } else {
                null
            }
            element?.let { converter(it) } ?: throw IllegalArgumentException("Missing required parameter: $name")
        }
    }
}

/**
 * Tool schema for API requests.
 */
data class ToolSchema(
    val name: String,
    val description: String,
    val parameters: JsonObject
)

// Extension functions for convenient argument access
fun Map<String, JsonElement>.string(key: String): String = this[key]?.jsonPrimitive?.content
    ?: throw IllegalArgumentException("Missing argument: $key")

fun Map<String, JsonElement>.stringOrNull(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

fun Map<String, JsonElement>.int(key: String): Int = this[key]?.jsonPrimitive?.int
    ?: throw IllegalArgumentException("Missing argument: $key")

fun Map<String, JsonElement>.intOrNull(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

fun Map<String, JsonElement>.double(key: String): Double = this[key]?.jsonPrimitive?.double
    ?: throw IllegalArgumentException("Missing argument: $key")

fun Map<String, JsonElement>.doubleOrNull(key: String): Double? = this[key]?.jsonPrimitive?.doubleOrNull

fun Map<String, JsonElement>.boolean(key: String): Boolean = this[key]?.jsonPrimitive?.boolean
    ?: throw IllegalArgumentException("Missing argument: $key")

fun Map<String, JsonElement>.booleanOrNull(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull
