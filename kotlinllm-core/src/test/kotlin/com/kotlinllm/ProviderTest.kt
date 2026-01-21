package com.kotlinllm

import com.kotlinllm.core.*
import com.kotlinllm.providers.OpenAIProvider
import com.kotlinllm.providers.AnthropicProvider
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.*

class ProviderTest {

    @Test
    fun `OpenAI provider is configured when API key is set`() {
        val provider = OpenAIProvider()
        val config = Configuration().apply {
            openaiApiKey = "sk-test"
        }

        assertTrue(provider.isConfigured(config))
    }

    @Test
    fun `OpenAI provider is not configured when API key is missing`() {
        val provider = OpenAIProvider()
        val config = Configuration()

        assertFalse(provider.isConfigured(config))
    }

    @Test
    fun `Anthropic provider is configured when API key is set`() {
        val provider = AnthropicProvider()
        val config = Configuration().apply {
            anthropicApiKey = "sk-ant-test"
        }

        assertTrue(provider.isConfigured(config))
    }

    @Test
    fun `Anthropic provider is not configured when API key is missing`() {
        val provider = AnthropicProvider()
        val config = Configuration()

        assertFalse(provider.isConfigured(config))
    }

    @Test
    fun `Provider resolution by model name`() {
        // Register providers
        Provider.register(OpenAIProvider())
        Provider.register(AnthropicProvider())

        val openaiProvider = Provider.forModel("gpt-4o")
        assertEquals("openai", openaiProvider.slug)

        val anthropicProvider = Provider.forModel("claude-sonnet-4-20250514")
        assertEquals("anthropic", anthropicProvider.slug)
    }

    @Test
    fun `Provider slug and name`() {
        val openai = OpenAIProvider()
        assertEquals("openai", openai.slug)
        assertEquals("OpenAI", openai.name)

        val anthropic = AnthropicProvider()
        assertEquals("anthropic", anthropic.slug)
        assertEquals("Anthropic", anthropic.name)
    }
}

class OpenAIProviderIntegrationTest {
    private lateinit var server: MockWebServer
    private lateinit var provider: OpenAIProvider
    private lateinit var config: Configuration

    @BeforeTest
    fun setup() {
        server = MockWebServer()
        server.start()

        provider = OpenAIProvider()
        config = Configuration().apply {
            openaiApiKey = "sk-test"
            openaiApiBase = server.url("/v1").toString().removeSuffix("/v1")
        }
    }

    @AfterTest
    fun teardown() {
        server.shutdown()
    }

    @Test
    fun `Complete sends correct request format`() = runTest {
        val mockResponse = """
            {
                "id": "chatcmpl-123",
                "object": "chat.completion",
                "created": 1677652288,
                "model": "gpt-4o",
                "choices": [{
                    "index": 0,
                    "message": {
                        "role": "assistant",
                        "content": "Hello! How can I help you?"
                    },
                    "finish_reason": "stop"
                }],
                "usage": {
                    "prompt_tokens": 10,
                    "completion_tokens": 8,
                    "total_tokens": 18
                }
            }
        """.trimIndent()

        server.enqueue(MockResponse().setBody(mockResponse))

        val messages = listOf(
            Message.user("Hello")
        )

        val response = provider.complete(
            messages = messages,
            model = "gpt-4o",
            tools = emptyList(),
            config = config
        )

        assertEquals("Hello! How can I help you?", response.text)
        assertEquals(Role.ASSISTANT, response.role)
        assertEquals(10, response.tokens?.input)
        assertEquals(8, response.tokens?.output)

        // Verify request
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.path!!.contains("/chat/completions"))
        assertTrue(request.getHeader("Authorization")!!.contains("Bearer sk-test"))
    }

    @Test
    fun `Complete with tool calls`() = runTest {
        val mockResponse = """
            {
                "id": "chatcmpl-123",
                "object": "chat.completion",
                "model": "gpt-4o",
                "choices": [{
                    "index": 0,
                    "message": {
                        "role": "assistant",
                        "content": null,
                        "tool_calls": [{
                            "id": "call_abc123",
                            "type": "function",
                            "function": {
                                "name": "calculator",
                                "arguments": "{\"expression\": \"2 + 2\"}"
                            }
                        }]
                    },
                    "finish_reason": "tool_calls"
                }],
                "usage": {
                    "prompt_tokens": 15,
                    "completion_tokens": 10,
                    "total_tokens": 25
                }
            }
        """.trimIndent()

        server.enqueue(MockResponse().setBody(mockResponse))

        val messages = listOf(
            Message.user("What is 2 + 2?")
        )

        val response = provider.complete(
            messages = messages,
            model = "gpt-4o",
            tools = emptyList(),
            config = config
        )

        assertTrue(response.isToolCall())
        assertEquals(1, response.toolCalls?.size)
        assertEquals("calculator", response.toolCalls?.first()?.name)
        assertEquals("call_abc123", response.toolCalls?.first()?.id)
    }
}

class AnthropicProviderIntegrationTest {
    private lateinit var server: MockWebServer
    private lateinit var provider: AnthropicProvider
    private lateinit var config: Configuration

    @BeforeTest
    fun setup() {
        server = MockWebServer()
        server.start()

        provider = AnthropicProvider()
        config = Configuration().apply {
            anthropicApiKey = "sk-ant-test"
            anthropicApiBase = server.url("").toString().removeSuffix("/")
        }
    }

    @AfterTest
    fun teardown() {
        server.shutdown()
    }

    @Test
    fun `Complete sends correct request format`() = runTest {
        val mockResponse = """
            {
                "id": "msg_123",
                "type": "message",
                "role": "assistant",
                "content": [{
                    "type": "text",
                    "text": "Hello! How can I assist you today?"
                }],
                "model": "claude-sonnet-4-20250514",
                "stop_reason": "end_turn",
                "usage": {
                    "input_tokens": 12,
                    "output_tokens": 10
                }
            }
        """.trimIndent()

        server.enqueue(MockResponse().setBody(mockResponse))

        val messages = listOf(
            Message.user("Hello")
        )

        val response = provider.complete(
            messages = messages,
            model = "claude-sonnet-4-20250514",
            tools = emptyList(),
            config = config
        )

        assertEquals("Hello! How can I assist you today?", response.text)
        assertEquals(Role.ASSISTANT, response.role)
        assertEquals(12, response.tokens?.input)
        assertEquals(10, response.tokens?.output)

        // Verify request
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.path!!.contains("/v1/messages"))
        assertEquals("sk-ant-test", request.getHeader("x-api-key"))
        assertEquals("2023-06-01", request.getHeader("anthropic-version"))
    }

    @Test
    fun `List models returns known models`() = runTest {
        val models = provider.listModels(config)

        assertTrue(models.isNotEmpty())
        assertTrue(models.any { it.id.contains("claude") })
        assertTrue(models.all { it.provider == "anthropic" })
    }
}
