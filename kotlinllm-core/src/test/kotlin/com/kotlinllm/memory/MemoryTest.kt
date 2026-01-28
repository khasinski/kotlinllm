package com.kotlinllm.memory

import com.kotlinllm.core.Message
import com.kotlinllm.core.Role
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class MemoryTest {

    @Test
    fun `BufferMemory keeps last N messages`() = runTest {
        val memory = BufferMemory(maxMessages = 3)

        memory.add(Message.user("1"))
        memory.add(Message.assistant("2"))
        memory.add(Message.user("3"))
        memory.add(Message.assistant("4"))
        memory.add(Message.user("5"))

        val messages = memory.getContextMessages()
        assertEquals(3, messages.size)
        assertEquals("3", messages[0].text)
        assertEquals("4", messages[1].text)
        assertEquals("5", messages[2].text)
    }

    @Test
    fun `BufferMemory preserves system message`() = runTest {
        val memory = BufferMemory(maxMessages = 3, preserveSystemMessage = true)

        memory.add(Message.system("You are helpful"))
        memory.add(Message.user("1"))
        memory.add(Message.assistant("2"))
        memory.add(Message.user("3"))
        memory.add(Message.assistant("4"))

        val messages = memory.getContextMessages()
        assertEquals(3, messages.size)
        assertEquals(Role.SYSTEM, messages[0].role)
        assertEquals("You are helpful", messages[0].text)
    }

    @Test
    fun `BufferMemory clear works`() = runTest {
        val memory = BufferMemory(maxMessages = 10)

        memory.add(Message.user("1"))
        memory.add(Message.assistant("2"))

        assertEquals(2, memory.getAllMessages().size)

        memory.clear()

        assertEquals(0, memory.getAllMessages().size)
    }

    @Test
    fun `BufferMemory stats are accurate`() = runTest {
        val memory = BufferMemory(maxMessages = 10)

        memory.add(Message.user("Hello"))
        memory.add(Message.assistant("Hi there"))

        val stats = memory.stats()
        assertEquals(2, stats.totalMessages)
        assertEquals(2, stats.contextMessages)
        assertTrue(stats.estimatedTokens > 0)
        assertTrue(stats.memoryType.contains("BufferMemory"))
    }

    @Test
    fun `WindowMemory keeps last N turns`() = runTest {
        val memory = WindowMemory(maxTurns = 2)

        // Turn 1
        memory.add(Message.user("Q1"))
        memory.add(Message.assistant("A1"))
        // Turn 2
        memory.add(Message.user("Q2"))
        memory.add(Message.assistant("A2"))
        // Turn 3
        memory.add(Message.user("Q3"))
        memory.add(Message.assistant("A3"))

        val messages = memory.getContextMessages()
        // Should have turns 2 and 3 (4 messages)
        assertEquals(4, messages.size)
        assertEquals("Q2", messages[0].text)
        assertEquals("A2", messages[1].text)
        assertEquals("Q3", messages[2].text)
        assertEquals("A3", messages[3].text)
    }

    @Test
    fun `TokenMemory respects token limit`() = runTest {
        val memory = TokenMemory(maxTokens = 20) // Very small limit

        // Each message is roughly 5 tokens (20 chars / 4)
        memory.add(Message.user("This is message one"))  // ~5 tokens
        memory.add(Message.assistant("This is reply one")) // ~5 tokens
        memory.add(Message.user("This is message two"))  // ~5 tokens
        memory.add(Message.assistant("This is reply two")) // ~5 tokens

        val messages = memory.getContextMessages()
        // Should have trimmed to fit within 20 tokens
        assertTrue(messages.size < 4)

        val stats = memory.stats()
        assertTrue(stats.estimatedTokens <= 30) // Some tolerance
    }

    @Test
    fun `SummaryMemory summarizes old messages`() = runTest {
        var summarizeCalled = false
        val memory = SummaryMemory(
            maxMessages = 3,
            summaryThreshold = 3,
            summarizer = { messages ->
                summarizeCalled = true
                "Summary of ${messages.size} messages"
            }
        )

        memory.add(Message.user("1"))
        memory.add(Message.assistant("2"))
        memory.add(Message.user("3"))
        memory.add(Message.assistant("4"))
        memory.add(Message.user("5"))

        // Summarizer should have been called
        assertTrue(summarizeCalled)

        val stats = memory.stats()
        assertTrue(stats.memoryType.contains("SummaryMemory"))
    }

    @Test
    fun `CompositeMemory delegates to all memories`() = runTest {
        val buffer = BufferMemory(maxMessages = 10)
        val window = WindowMemory(maxTurns = 5)
        val composite = CompositeMemory(buffer, window)

        composite.add(Message.user("Test"))

        // Both should have the message
        assertEquals(1, buffer.getAllMessages().size)
        assertEquals(1, window.getAllMessages().size)

        // Context comes from first memory
        assertEquals(1, composite.getContextMessages().size)
    }

    @Test
    fun `addAll adds multiple messages`() = runTest {
        val memory = BufferMemory(maxMessages = 10)

        val messages = listOf(
            Message.user("1"),
            Message.assistant("2"),
            Message.user("3")
        )

        memory.addAll(messages)

        assertEquals(3, memory.getAllMessages().size)
    }

    @Test
    fun `estimateTokens provides reasonable estimates`() {
        val messages = listOf(
            Message.user("Hello, how are you?"), // ~5 tokens
            Message.assistant("I am doing well, thank you for asking!") // ~10 tokens
        )

        val estimate = estimateTokens(messages)
        assertTrue(estimate in 10..30) // Reasonable range
    }

    @Test
    fun `estimateMessageTokens works for single message`() {
        val message = Message.user("Hello world") // 11 chars = ~3 tokens + 4 overhead

        val estimate = estimateMessageTokens(message)
        assertTrue(estimate in 5..10)
    }
}
