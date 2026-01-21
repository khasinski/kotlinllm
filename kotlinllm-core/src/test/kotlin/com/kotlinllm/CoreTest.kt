package com.kotlinllm

import com.kotlinllm.core.*
import kotlinx.serialization.json.*
import kotlin.test.*

class CoreTest {

    @Test
    fun `Message creation with text`() {
        val msg = Message.user("Hello")
        assertEquals(Role.USER, msg.role)
        assertEquals("Hello", msg.text)
    }

    @Test
    fun `Message creation with different roles`() {
        val system = Message.system("Be helpful")
        val user = Message.user("Hi")
        val assistant = Message.assistant("Hello!")
        val tool = Message.tool("result", "call-123")

        assertEquals(Role.SYSTEM, system.role)
        assertEquals(Role.USER, user.role)
        assertEquals(Role.ASSISTANT, assistant.role)
        assertEquals(Role.TOOL, tool.role)
        assertEquals("call-123", tool.toolCallId)
    }

    @Test
    fun `Role conversion to API string`() {
        assertEquals("system", Role.SYSTEM.toApiString())
        assertEquals("user", Role.USER.toApiString())
        assertEquals("assistant", Role.ASSISTANT.toApiString())
        assertEquals("tool", Role.TOOL.toApiString())
    }

    @Test
    fun `Role parsing from string`() {
        assertEquals(Role.SYSTEM, Role.fromString("system"))
        assertEquals(Role.USER, Role.fromString("USER"))
        assertEquals(Role.ASSISTANT, Role.fromString("Assistant"))
    }

    @Test
    fun `Content text creation`() {
        val content = Content.text("Hello world")
        assertEquals("Hello world", content.text)
        assertTrue(content.attachments.isEmpty())
    }

    @Test
    fun `TokenUsage addition`() {
        val t1 = TokenUsage(input = 10, output = 20)
        val t2 = TokenUsage(input = 5, output = 15)
        val total = t1 + t2

        assertEquals(15, total.input)
        assertEquals(35, total.output)
        assertEquals(50, total.total)
    }

    @Test
    fun `ToolCall data class`() {
        val args = mapOf(
            "expression" to JsonPrimitive("2 + 2")
        )
        val toolCall = ToolCall(
            id = "call-123",
            name = "calculator",
            arguments = args
        )

        assertEquals("call-123", toolCall.id)
        assertEquals("calculator", toolCall.name)
        assertEquals("2 + 2", toolCall.arguments["expression"]?.jsonPrimitive?.content)
    }

    @Test
    fun `Message isToolCall detection`() {
        val normalMsg = Message.assistant("Hello")
        val toolCallMsg = Message(
            role = Role.ASSISTANT,
            content = Content.text(""),
            toolCalls = listOf(
                ToolCall("id", "name", emptyMap())
            )
        )

        assertFalse(normalMsg.isToolCall())
        assertTrue(toolCallMsg.isToolCall())
    }

    @Test
    fun `Chunk data class`() {
        val chunk = Chunk(
            content = "Hello",
            finishReason = null
        )

        assertEquals("Hello", chunk.content)
        assertNull(chunk.finishReason)
    }

    @Test
    fun `ModelInfo data class`() {
        val model = ModelInfo(
            id = "gpt-4o",
            provider = "openai",
            displayName = "GPT-4o",
            contextWindow = 128000,
            maxOutputTokens = 4096,
            supportsTools = true,
            supportsVision = true
        )

        assertEquals("gpt-4o", model.id)
        assertEquals("openai", model.provider)
        assertTrue(model.supportsTools)
        assertTrue(model.supportsVision)
    }
}

class ConfigurationTest {

    @Test
    fun `Default configuration values`() {
        val config = Configuration()

        assertEquals("gpt-4o-mini", config.defaultModel)
        assertEquals("https://api.openai.com/v1", config.openaiApiBase)
        assertEquals("https://api.anthropic.com", config.anthropicApiBase)
        assertEquals(3, config.maxRetries)
    }

    @Test
    fun `Configuration copy with modifications`() {
        val original = Configuration().apply {
            openaiApiKey = "original-key"
            defaultModel = "gpt-4o"
        }

        val copy = original.copy {
            defaultModel = "claude-sonnet-4-20250514"
        }

        assertEquals("original-key", copy.openaiApiKey)
        assertEquals("claude-sonnet-4-20250514", copy.defaultModel)
        assertEquals("gpt-4o", original.defaultModel) // Original unchanged
    }
}

class ToolArgumentsTest {

    @Test
    fun `String argument extraction`() {
        val args = mapOf<String, JsonElement>(
            "name" to JsonPrimitive("John")
        )

        assertEquals("John", args.string("name"))
    }

    @Test
    fun `Int argument extraction`() {
        val args = mapOf<String, JsonElement>(
            "count" to JsonPrimitive(42)
        )

        assertEquals(42, args.int("count"))
    }

    @Test
    fun `Double argument extraction`() {
        val args = mapOf<String, JsonElement>(
            "price" to JsonPrimitive(19.99)
        )

        assertEquals(19.99, args.double("price"), 0.001)
    }

    @Test
    fun `Boolean argument extraction`() {
        val args = mapOf<String, JsonElement>(
            "enabled" to JsonPrimitive(true)
        )

        assertTrue(args.boolean("enabled"))
    }

    @Test
    fun `Null-safe argument extraction`() {
        val args = mapOf<String, JsonElement>(
            "name" to JsonPrimitive("John")
        )

        assertEquals("John", args.stringOrNull("name"))
        assertNull(args.stringOrNull("missing"))
    }

    @Test
    fun `Missing required argument throws`() {
        val args = emptyMap<String, JsonElement>()

        assertFailsWith<IllegalArgumentException> {
            args.string("missing")
        }
    }
}
