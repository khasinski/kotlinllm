package com.kotlinllm.providers

import com.kotlinllm.KotlinLLM
import com.kotlinllm.core.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.*

/**
 * Integration tests using MockWebServer to test provider implementations
 * without making real API calls.
 */
class OpenAIProviderIntegrationTest {

    private lateinit var server: MockWebServer

    @BeforeTest
    fun setup() {
        server = MockWebServer()
        server.start()

        KotlinLLM.configure {
            openaiApiKey = "test-api-key"
            openaiApiBase = server.url("/v1").toString()
        }
    }

    @AfterTest
    fun teardown() {
        server.shutdown()
        // Reset to default
        KotlinLLM.configure {
            openaiApiBase = "https://api.openai.com"
        }
    }

    @Test
    fun `OpenAI provider sends correct request format`() = runTest {
        // Enqueue a mock response
        server.enqueue(MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("""
                {
                    "id": "chatcmpl-123",
                    "object": "chat.completion",
                    "created": 1677652288,
                    "model": "gpt-4o",
                    "choices": [{
                        "index": 0,
                        "message": {
                            "role": "assistant",
                            "content": "Hello! How can I help you today?"
                        },
                        "finish_reason": "stop"
                    }],
                    "usage": {
                        "prompt_tokens": 10,
                        "completion_tokens": 15,
                        "total_tokens": 25
                    }
                }
            """.trimIndent()))

        val chat = KotlinLLM.chat("gpt-4o")
        val response = chat.ask("Hello!")

        // Verify response
        assertEquals("Hello! How can I help you today?", response.text)
        assertEquals(Role.ASSISTANT, response.role)
        assertNotNull(response.tokens)
        assertEquals(25, response.tokens?.total)

        // Verify request
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.path?.contains("/chat/completions") == true)
        assertEquals("Bearer test-api-key", request.getHeader("Authorization"))

        // Verify request body
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("gpt-4o", body["model"]?.jsonPrimitive?.content)
        assertTrue(body["messages"]?.jsonArray?.isNotEmpty() == true)
    }

    @Test
    fun `OpenAI provider handles error responses`() = runTest {
        server.enqueue(MockResponse()
            .setResponseCode(401)
            .setHeader("Content-Type", "application/json")
            .setBody("""
                {
                    "error": {
                        "message": "Invalid API key",
                        "type": "invalid_request_error",
                        "code": "invalid_api_key"
                    }
                }
            """.trimIndent()))

        val chat = KotlinLLM.chat("gpt-4o")

        val exception = assertFailsWith<OpenAIException> {
            chat.ask("Hello!")
        }

        assertEquals(401, exception.statusCode)
        assertTrue(exception.message?.contains("Invalid API key") == true)
    }

    @Test
    fun `OpenAI provider handles rate limit with retry-after`() = runTest {
        server.enqueue(MockResponse()
            .setResponseCode(429)
            .setHeader("Content-Type", "application/json")
            .setHeader("Retry-After", "30")
            .setBody("""
                {
                    "error": {
                        "message": "Rate limit exceeded",
                        "type": "rate_limit_error"
                    }
                }
            """.trimIndent()))

        val chat = KotlinLLM.chat("gpt-4o")

        val exception = assertFailsWith<OpenAIException> {
            chat.ask("Hello!")
        }

        assertEquals(429, exception.statusCode)
        assertTrue(exception.isRetryable)
    }

    @Test
    fun `OpenAI provider sends tools correctly`() = runTest {
        server.enqueue(MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("""
                {
                    "id": "chatcmpl-123",
                    "object": "chat.completion",
                    "created": 1677652288,
                    "model": "gpt-4o",
                    "choices": [{
                        "index": 0,
                        "message": {
                            "role": "assistant",
                            "content": "I'll help you with that calculation.",
                            "tool_calls": [{
                                "id": "call_123",
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
                        "prompt_tokens": 20,
                        "completion_tokens": 30,
                        "total_tokens": 50
                    }
                }
            """.trimIndent()))

        // Enqueue response for after tool execution
        server.enqueue(MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("""
                {
                    "id": "chatcmpl-124",
                    "object": "chat.completion",
                    "created": 1677652289,
                    "model": "gpt-4o",
                    "choices": [{
                        "index": 0,
                        "message": {
                            "role": "assistant",
                            "content": "The result of 2 + 2 is 4."
                        },
                        "finish_reason": "stop"
                    }],
                    "usage": {
                        "prompt_tokens": 30,
                        "completion_tokens": 10,
                        "total_tokens": 40
                    }
                }
            """.trimIndent()))

        val calculator = object : Tool("calculator", "Performs calculations") {
            init {
                registerParameter("expression", ParameterDef("string", "Math expression", true))
            }
            override suspend fun execute(args: Map<String, JsonElement>): Any {
                return "4"
            }
        }

        val chat = KotlinLLM.chat("gpt-4o").withTool(calculator)
        val response = chat.ask("What is 2 + 2?")

        assertEquals("The result of 2 + 2 is 4.", response.text)

        // Verify first request included tools
        val firstRequest = server.takeRequest()
        val firstBody = Json.parseToJsonElement(firstRequest.body.readUtf8()).jsonObject
        assertTrue(firstBody.containsKey("tools"))
    }

    @Test
    fun `OpenAI provider respects temperature setting`() = runTest {
        server.enqueue(MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("""
                {
                    "id": "chatcmpl-123",
                    "choices": [{
                        "message": { "role": "assistant", "content": "Response" },
                        "finish_reason": "stop"
                    }],
                    "usage": { "total_tokens": 10 }
                }
            """.trimIndent()))

        val chat = KotlinLLM.chat("gpt-4o").withTemperature(0.5)
        chat.ask("Hello!")

        val request = server.takeRequest()
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(0.5, body["temperature"]?.jsonPrimitive?.double)
    }

    @Test
    fun `OpenAI provider respects maxTokens setting`() = runTest {
        server.enqueue(MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("""
                {
                    "id": "chatcmpl-123",
                    "choices": [{
                        "message": { "role": "assistant", "content": "Response" },
                        "finish_reason": "stop"
                    }],
                    "usage": { "total_tokens": 10 }
                }
            """.trimIndent()))

        val chat = KotlinLLM.chat("gpt-4o").withMaxTokens(500)
        chat.ask("Hello!")

        val request = server.takeRequest()
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(500, body["max_tokens"]?.jsonPrimitive?.int)
    }
}

class AnthropicProviderIntegrationTest {

    private lateinit var server: MockWebServer

    @BeforeTest
    fun setup() {
        server = MockWebServer()
        server.start()

        KotlinLLM.configure {
            anthropicApiKey = "test-api-key"
            anthropicApiBase = server.url("/v1").toString()
        }
    }

    @AfterTest
    fun teardown() {
        server.shutdown()
        KotlinLLM.configure {
            anthropicApiBase = "https://api.anthropic.com"
        }
    }

    @Test
    fun `Anthropic provider sends correct request format`() = runTest {
        server.enqueue(MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("""
                {
                    "id": "msg_123",
                    "type": "message",
                    "role": "assistant",
                    "content": [{
                        "type": "text",
                        "text": "Hello! I'm Claude."
                    }],
                    "model": "claude-sonnet-4-20250514",
                    "stop_reason": "end_turn",
                    "usage": {
                        "input_tokens": 10,
                        "output_tokens": 15
                    }
                }
            """.trimIndent()))

        val chat = KotlinLLM.chat("claude-sonnet-4-20250514")
        val response = chat.ask("Hello!")

        assertEquals("Hello! I'm Claude.", response.text)
        assertEquals(Role.ASSISTANT, response.role)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.path?.contains("/messages") == true)
        assertEquals("test-api-key", request.getHeader("x-api-key"))
        assertTrue(request.getHeader("anthropic-version")?.isNotEmpty() == true)
    }

    @Test
    fun `Anthropic provider handles error responses`() = runTest {
        server.enqueue(MockResponse()
            .setResponseCode(401)
            .setHeader("Content-Type", "application/json")
            .setBody("""
                {
                    "type": "error",
                    "error": {
                        "type": "authentication_error",
                        "message": "Invalid API key"
                    }
                }
            """.trimIndent()))

        val chat = KotlinLLM.chat("claude-sonnet-4-20250514")

        val exception = assertFailsWith<AnthropicException> {
            chat.ask("Hello!")
        }

        assertEquals(401, exception.statusCode)
    }

    @Test
    fun `Anthropic provider sends system message correctly`() = runTest {
        server.enqueue(MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("""
                {
                    "id": "msg_123",
                    "type": "message",
                    "role": "assistant",
                    "content": [{ "type": "text", "text": "I am helpful!" }],
                    "usage": { "input_tokens": 20, "output_tokens": 5 }
                }
            """.trimIndent()))

        val chat = KotlinLLM.chat("claude-sonnet-4-20250514")
            .withInstructions("You are a helpful assistant")
        chat.ask("Hello!")

        val request = server.takeRequest()
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject

        // Anthropic uses top-level "system" field
        assertTrue(body.containsKey("system"))
        assertTrue(body["system"]?.jsonPrimitive?.content?.contains("helpful") == true)
    }
}

class OllamaProviderIntegrationTest {

    private lateinit var server: MockWebServer

    @BeforeTest
    fun setup() {
        server = MockWebServer()
        server.start()

        KotlinLLM.configure {
            ollamaApiBase = server.url("").toString().removeSuffix("/")
        }
    }

    @AfterTest
    fun teardown() {
        server.shutdown()
    }

    @Test
    fun `Ollama provider sends correct request format`() = runTest {
        server.enqueue(MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("""
                {
                    "model": "llama3",
                    "created_at": "2024-01-01T00:00:00Z",
                    "message": {
                        "role": "assistant",
                        "content": "Hello from Ollama!"
                    },
                    "done": true,
                    "total_duration": 1000000000,
                    "prompt_eval_count": 10,
                    "eval_count": 15
                }
            """.trimIndent()))

        val chat = KotlinLLM.chat("ollama:llama3")
        val response = chat.ask("Hello!")

        assertEquals("Hello from Ollama!", response.text)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.path?.contains("/api/chat") == true)

        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("llama3", body["model"]?.jsonPrimitive?.content)
        assertEquals(false, body["stream"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun `Ollama provider handles connection errors gracefully`() = runTest {
        server.shutdown() // Simulate server being down

        val chat = KotlinLLM.chat("ollama:llama3")

        assertFailsWith<Exception> {
            chat.ask("Hello!")
        }
    }
}

class ProviderRoutingTest {

    @Test
    fun `Provider forModel routes gpt models to OpenAI`() {
        val provider = Provider.forModel("gpt-4o")
        assertTrue(provider is OpenAIProvider)
    }

    @Test
    fun `Provider forModel routes claude models to Anthropic`() {
        val provider = Provider.forModel("claude-sonnet-4-20250514")
        assertTrue(provider is AnthropicProvider)
    }

    @Test
    fun `Provider forModel routes ollama prefix to Ollama`() {
        val provider = Provider.forModel("ollama:llama3")
        assertTrue(provider is OllamaProvider)
    }

    @Test
    fun `Provider forModel routes o1 models to OpenAI`() {
        val provider = Provider.forModel("o1")
        assertTrue(provider is OpenAIProvider)
    }

    @Test
    fun `Provider forModel routes o3 models to OpenAI`() {
        val provider = Provider.forModel("o3-mini")
        assertTrue(provider is OpenAIProvider)
    }
}
