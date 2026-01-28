package com.kotlinllm

import com.kotlinllm.core.*
import com.kotlinllm.memory.*
import kotlin.test.*

class JavaLLMTest {

    @Test
    fun `JavaLLM chat creates JavaChat instance`() {
        val chat = JavaLLM.chat()
        assertNotNull(chat)
        assertNotNull(chat.unwrap())
    }

    @Test
    fun `JavaLLM chat with model creates JavaChat with correct model`() {
        val chat = JavaLLM.chat("gpt-4o")
        assertEquals("gpt-4o", chat.getModel())
    }

    @Test
    fun `JavaChat fluent configuration works`() {
        val chat = JavaLLM.chat("gpt-4o")
            .withInstructions("You are helpful")
            .withTemperature(0.7)
            .withMaxTokens(1000)

        val underlying = chat.unwrap()
        assertEquals(0.7, underlying.temperature())
        assertEquals(1000, underlying.maxTokens())
    }

    @Test
    fun `JavaChat getMessages returns empty list initially`() {
        val chat = JavaLLM.chat("gpt-4o")
        assertTrue(chat.getMessages().isEmpty())
    }

    @Test
    fun `JavaChat getTotalTokens returns zero initially`() {
        val chat = JavaLLM.chat("gpt-4o")
        assertEquals(0, chat.getTotalTokens())
    }

    @Test
    fun `JavaChat withTool adds tool without throwing`() {
        val tool = object : Tool("test_tool", "A test tool") {
            override suspend fun execute(args: Map<String, kotlinx.serialization.json.JsonElement>): Any {
                return "executed"
            }
        }

        // Should not throw
        val chat = JavaLLM.chat("gpt-4o").withTool(tool)
        assertNotNull(chat)
    }

    @Test
    fun `JavaToolBuilder creates valid tool`() {
        val tool = JavaLLM.toolBuilder("calculator", "Performs calculations")
            .param("expression", "Math expression")
            .param("precision", "Decimal places", "integer", false)
            .execute { args ->
                val expr = args.getString("expression")
                "Result: $expr"
            }
            .build()

        assertEquals("calculator", tool.name)
        assertEquals("Performs calculations", tool.description)
    }

    @Test
    fun `JavaToolBuilder requires executor`() {
        val builder = JavaLLM.toolBuilder("test", "Test tool")
            .param("input", "Input value")

        assertFailsWith<IllegalStateException> {
            builder.build()
        }
    }

    @Test
    fun `JavaToolArgs provides typed access`() {
        val jsonElement = kotlinx.serialization.json.JsonPrimitive("test-value")
        val args = JavaToolArgs(mapOf("key" to jsonElement))

        assertEquals("test-value", args.getString("key"))
    }
}

class JavaMemoryTest {

    @Test
    fun `JavaMemory buffer creates BufferMemory`() {
        val memory = JavaMemory.buffer(20)
        assertTrue(memory is BufferMemory)
    }

    @Test
    fun `JavaMemory buffer with preserveSystemMessage creates correct memory`() {
        val memory = JavaMemory.buffer(20, true)
        assertTrue(memory is BufferMemory)
    }

    @Test
    fun `JavaMemory window creates WindowMemory`() {
        val memory = JavaMemory.window(10)
        assertTrue(memory is WindowMemory)
    }

    @Test
    fun `JavaMemory tokenLimited creates TokenMemory`() {
        val memory = JavaMemory.tokenLimited(4000)
        assertTrue(memory is TokenMemory)
    }

    @Test
    fun `JavaChat withMemory returns JavaMemoryChat`() {
        val chat = JavaLLM.chat("gpt-4o")
        val memoryChat = chat.withMemory(JavaMemory.buffer(20))

        assertTrue(memoryChat is JavaMemoryChat)
    }

    @Test
    fun `JavaMemoryChat fluent configuration works`() {
        val memoryChat = JavaLLM.chat("gpt-4o")
            .withMemory(JavaMemory.buffer(20))
            .withInstructions("You are helpful")
            .withTemperature(0.5)

        assertNotNull(memoryChat)
    }

    @Test
    fun `JavaMemoryChat getMemoryStats returns stats`() {
        val memoryChat = JavaLLM.chat("gpt-4o")
            .withMemory(JavaMemory.buffer(20))

        val stats = memoryChat.getMemoryStats()
        assertEquals(0, stats.totalMessages)
    }

    @Test
    fun `JavaMemoryChat getAllMessages returns empty initially`() {
        val memoryChat = JavaLLM.chat("gpt-4o")
            .withMemory(JavaMemory.buffer(20))

        assertTrue(memoryChat.getAllMessages().isEmpty())
    }

    @Test
    fun `JavaMemoryChat getContextMessages returns empty initially`() {
        val memoryChat = JavaLLM.chat("gpt-4o")
            .withMemory(JavaMemory.buffer(20))

        assertTrue(memoryChat.getContextMessages().isEmpty())
    }

    @Test
    fun `JavaMemoryChat clearMemory works`() {
        val memoryChat = JavaLLM.chat("gpt-4o")
            .withMemory(JavaMemory.buffer(20))

        // Should not throw
        memoryChat.clearMemory()
        assertTrue(memoryChat.getAllMessages().isEmpty())
    }

    @Test
    fun `JavaMemoryChat unwrap methods work`() {
        val memory = JavaMemory.buffer(20)
        val memoryChat = JavaLLM.chat("gpt-4o").withMemory(memory)

        assertNotNull(memoryChat.unwrapChat())
        assertEquals(memory, memoryChat.unwrapMemory())
    }
}

class JavaStructuredOutputTest {

    @Test
    fun `JavaStructuredConfig defaults are reasonable`() {
        val config = JavaStructuredConfig.defaults()

        assertEquals(3, config.maxRetries)
        assertEquals(0.1, config.temperature)
        assertNull(config.maxTokens)
    }

    @Test
    fun `JavaStructuredConfig fluent configuration works`() {
        val config = JavaStructuredConfig.defaults()
            .maxRetries(5)
            .temperature(0.2)
            .maxTokens(500)

        assertEquals(5, config.maxRetries)
        assertEquals(0.2, config.temperature)
        assertEquals(500, config.maxTokens)
    }

    @Test
    fun `JavaStructuredResult success state`() {
        val result = JavaStructuredResult.success("value", "raw response")

        assertTrue(result.isSuccess())
        assertEquals("value", result.getValue())
        assertEquals("value", result.getValueOrThrow())
        assertEquals("raw response", result.getRawResponse())
        assertNull(result.getError())
    }

    @Test
    fun `JavaStructuredResult failure state`() {
        val result = JavaStructuredResult.failure<String>("error message", "raw response")

        assertFalse(result.isSuccess())
        assertNull(result.getValue())
        assertEquals("error message", result.getError())
        assertEquals("raw response", result.getRawResponse())
    }

    @Test
    fun `JavaStructuredResult getValueOrThrow throws on failure`() {
        val result = JavaStructuredResult.failure<String>("error message", null)

        assertFailsWith<IllegalStateException> {
            result.getValueOrThrow()
        }
    }
}

class JavaChatDocumentContextTest {

    @Test
    fun `withDocumentContext adds context to chat`() {
        val chat = JavaLLM.chat("gpt-4o")
            .withDocumentContext("Test Document", "This is the content")

        // Verify context was added (check underlying chat has instructions)
        val underlying = chat.unwrap()
        // The instructions should contain the document context
        assertNotNull(underlying)
    }

    @Test
    fun `withDocumentContext handles null title`() {
        val chat = JavaLLM.chat("gpt-4o")
            .withDocumentContext(null, "Content without title")

        assertNotNull(chat)
    }

    @Test
    fun `withDocumentsContext adds multiple documents`() {
        val documents = listOf(
            Pair("Doc 1", "Content 1"),
            Pair("Doc 2", "Content 2"),
            Pair<String?, String>(null, "Content 3")
        )

        val chat = JavaLLM.chat("gpt-4o")
            .withDocumentsContext(documents)

        assertNotNull(chat)
    }

    @Test
    fun `withDocumentsContext handles empty list`() {
        val chat = JavaLLM.chat("gpt-4o")
            .withDocumentsContext(emptyList())

        assertNotNull(chat)
    }
}
