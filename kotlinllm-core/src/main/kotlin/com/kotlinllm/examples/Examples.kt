package com.kotlinllm.examples

import com.kotlinllm.KotlinLLM
import com.kotlinllm.core.*
import com.kotlinllm.dsl.*
import kotlinx.serialization.json.JsonElement

/**
 * Example tools for demonstration.
 */

// Calculator tool
class Calculator : Tool(
    name = "calculator",
    description = "Performs basic arithmetic calculations. Supports +, -, *, /"
) {
    init {
        registerParameter("expression", ParameterDef("string", "The mathematical expression to evaluate", true))
    }

    override suspend fun execute(args: Map<String, JsonElement>): Any {
        val expression = args.string("expression")
        return try {
            val result = evaluateSimpleExpression(expression)
            "Result: $result"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    private fun evaluateSimpleExpression(expr: String): Double {
        // Very simple expression evaluator for demo purposes
        val cleaned = expr.replace(" ", "")

        return when {
            cleaned.contains("+") -> {
                val parts = cleaned.split("+")
                parts.sumOf { it.toDouble() }
            }
            cleaned.contains("*") -> {
                val parts = cleaned.split("*")
                parts.map { it.toDouble() }.reduce { a, b -> a * b }
            }
            cleaned.contains("-") && cleaned.indexOf("-") > 0 -> {
                val parts = cleaned.split("-")
                parts.map { it.toDouble() }.reduce { a, b -> a - b }
            }
            cleaned.contains("/") -> {
                val parts = cleaned.split("/")
                parts.map { it.toDouble() }.reduce { a, b -> a / b }
            }
            else -> cleaned.toDouble()
        }
    }
}

// Weather tool (mock)
class WeatherTool : Tool(
    name = "get_weather",
    description = "Gets the current weather for a city"
) {
    init {
        registerParameter("city", ParameterDef("string", "The city name", true))
        registerParameter("unit", ParameterDef("string", "Temperature unit: celsius or fahrenheit", false))
    }

    override suspend fun execute(args: Map<String, JsonElement>): Any {
        val city = args.string("city")
        val unit = args.stringOrNull("unit") ?: "celsius"

        // Mock weather data
        val temp = when (city.lowercase()) {
            "tokyo" -> 22
            "london" -> 15
            "new york" -> 18
            "sydney" -> 25
            else -> (15..30).random()
        }

        val conditions = listOf("Sunny", "Partly Cloudy", "Cloudy", "Rainy").random()

        return if (unit == "fahrenheit") {
            "Weather in $city: ${temp * 9 / 5 + 32}°F, $conditions"
        } else {
            "Weather in $city: $temp°C, $conditions"
        }
    }
}

/**
 * Usage examples.
 */
object Examples {

    /**
     * Basic chat example.
     */
    suspend fun simpleChat() {
        println("=== Simple Chat ===")

        val response = KotlinLLM.chat().ask("What is Kotlin?")
        println("Response: ${response.text}")
        println()
    }

    /**
     * Chat with specific model.
     */
    suspend fun chatWithModel() {
        println("=== Chat with Specific Model ===")

        val chat = KotlinLLM.chat("gpt-4o-mini")
            .withInstructions("You are a helpful assistant. Be concise.")
            .withTemperature(0.7)

        val response = chat.ask("Explain REST APIs in one sentence")
        println("Response: ${response.text}")
        println()
    }

    /**
     * Conversation with history.
     */
    suspend fun conversationWithHistory() {
        println("=== Conversation with History ===")

        val chat = KotlinLLM.chat()
            .withInstructions("You are a math tutor. Be concise and clear.")

        println("User: What is 5 + 3?")
        val r1 = chat.ask("What is 5 + 3?")
        println("Assistant: ${r1.text}")

        println("\nUser: Now multiply that by 2")
        val r2 = chat.ask("Now multiply that by 2")
        println("Assistant: ${r2.text}")

        println("\nUser: Subtract 6")
        val r3 = chat.ask("Subtract 6")
        println("Assistant: ${r3.text}")
        println()
    }

    /**
     * Using tools.
     */
    suspend fun usingTools() {
        println("=== Using Tools ===")

        val chat = KotlinLLM.chat("gpt-4o")
            .withInstructions("You are a helpful assistant. Use tools when needed.")
            .withTool(Calculator())
            .withTool(WeatherTool())

        println("User: What is 42 * 17?")
        val r1 = chat.ask("What is 42 * 17?")
        println("Assistant: ${r1.text}")

        println("\nUser: What's the weather in Tokyo?")
        val r2 = chat.ask("What's the weather in Tokyo?")
        println("Assistant: ${r2.text}")
        println()
    }

    /**
     * DSL syntax.
     */
    suspend fun dslSyntax() {
        println("=== DSL Syntax ===")

        val myChat = chat("gpt-4o-mini") {
            system("You are a pirate. Respond in pirate speak.")
            temperature(0.9)
        }

        val response = myChat.ask("Hello, how are you?")
        println("Response: ${response.text}")
        println()
    }

    /**
     * DSL tool creation.
     */
    suspend fun dslToolCreation() {
        println("=== DSL Tool Creation ===")

        val timeTool = tool("get_time", "Gets the current time") {
            param("timezone", "The timezone (e.g., UTC, EST)")

            execute { args ->
                val tz = args.string("timezone")
                val now = java.time.ZonedDateTime.now(
                    java.time.ZoneId.of(
                        when (tz.uppercase()) {
                            "UTC" -> "UTC"
                            "EST" -> "America/New_York"
                            "PST" -> "America/Los_Angeles"
                            "JST" -> "Asia/Tokyo"
                            else -> "UTC"
                        }
                    )
                )
                "Current time in $tz: ${now.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))}"
            }
        }

        val myChat = chat("gpt-4o") {
            system("You are a helpful assistant.")
            tool(timeTool)
        }

        val response = myChat.ask("What time is it in Tokyo?")
        println("Response: ${response.text}")
        println()
    }

    /**
     * Streaming example.
     */
    suspend fun streaming() {
        println("=== Streaming ===")

        val myChat = KotlinLLM.chat("gpt-4o-mini")

        print("Response: ")
        myChat.askStreaming("Write a haiku about programming")
            .collect { chunk ->
                print(chunk.content)
            }
        println("\n")
    }

    /**
     * Quick chat shortcut.
     */
    suspend fun quickChat() {
        println("=== Quick Chat ===")

        // Using the global function
        val answer = chat("What is 2 + 2?")
        println("Answer: $answer")
        println()
    }

    /**
     * Token usage tracking.
     */
    suspend fun tokenUsage() {
        println("=== Token Usage ===")

        val chat = KotlinLLM.chat()
        val response = chat.ask("Explain quantum computing in brief")

        println("Response: ${response.text}")
        response.tokens?.let { tokens ->
            println("\nToken usage:")
            println("  Input: ${tokens.input}")
            println("  Output: ${tokens.output}")
            println("  Total: ${tokens.total}")
        }
        println()
    }

    /**
     * Run all examples.
     */
    suspend fun runAll() {
        println("KotlinLLM Examples")
        println("==================\n")

        simpleChat()
        chatWithModel()
        conversationWithHistory()
        usingTools()
        dslSyntax()
        dslToolCreation()
        streaming()
        quickChat()
        tokenUsage()

        println("All examples completed!")
    }
}

/**
 * Main entry point for running examples.
 */
suspend fun main() {
    // Configure (uses environment variables by default)
    KotlinLLM.configure {
        // Uncomment and set your API keys if not using env vars
        // openaiApiKey = "sk-..."
        // anthropicApiKey = "sk-ant-..."
    }

    Examples.runAll()
}
