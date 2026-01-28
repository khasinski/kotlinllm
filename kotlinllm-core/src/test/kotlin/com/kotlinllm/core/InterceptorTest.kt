package com.kotlinllm.core

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class InterceptorTest {

    @BeforeTest
    fun setup() {
        LLMInterceptors.clear()
    }

    @Test
    fun `interceptors are called in priority order`() = runTest {
        val callOrder = mutableListOf<String>()

        val lowPriority = object : LLMInterceptor {
            override val priority = 10
            override val name = "Low"
            override suspend fun intercept(context: LLMRequestContext, chain: LLMInterceptorChain): LLMResponseContext {
                callOrder.add("low-before")
                val result = chain.proceed(context)
                callOrder.add("low-after")
                return result
            }
        }

        val highPriority = object : LLMInterceptor {
            override val priority = 100
            override val name = "High"
            override suspend fun intercept(context: LLMRequestContext, chain: LLMInterceptorChain): LLMResponseContext {
                callOrder.add("high-before")
                val result = chain.proceed(context)
                callOrder.add("high-after")
                return result
            }
        }

        // Add in reverse order
        LLMInterceptors.add(lowPriority)
        LLMInterceptors.add(highPriority)

        // Verify order
        val all = LLMInterceptors.all()
        assertEquals(2, all.size)
        assertEquals("High", all[0].name)
        assertEquals("Low", all[1].name)
    }

    @Test
    fun `interceptor can modify context`() = runTest {
        val modifyingInterceptor = object : LLMInterceptor {
            override val name = "Modifier"
            override suspend fun intercept(context: LLMRequestContext, chain: LLMInterceptorChain): LLMResponseContext {
                // Add metadata
                context.metadata["modified"] = true
                return chain.proceed(context)
            }
        }

        LLMInterceptors.add(modifyingInterceptor)

        // Create a mock context
        val context = createTestContext()

        // Execute through chain
        var modifiedContext: LLMRequestContext? = null
        val result = LLMInterceptors.execute(context, Configuration()) { ctx, _ ->
            modifiedContext = ctx
            Message.assistant("response")
        }

        assertTrue(result.isSuccess)
        assertEquals(true, modifiedContext?.metadata?.get("modified"))
    }

    @Test
    fun `interceptor can short-circuit request`() = runTest {
        val shortCircuitInterceptor = object : LLMInterceptor {
            override val name = "ShortCircuit"
            override suspend fun intercept(context: LLMRequestContext, chain: LLMInterceptorChain): LLMResponseContext {
                // Return early without calling chain.proceed()
                return LLMResponseContext.success(
                    context,
                    Message.assistant("short-circuited"),
                    10
                )
            }
        }

        LLMInterceptors.add(shortCircuitInterceptor)

        var executorCalled = false
        val result = LLMInterceptors.execute(createTestContext(), Configuration()) { _, _ ->
            executorCalled = true
            Message.assistant("should not reach here")
        }

        assertTrue(result.isSuccess)
        assertEquals("short-circuited", result.response?.text)
        assertFalse(executorCalled)
    }

    @Test
    fun `LLMResponseContext tracks duration`() {
        val context = createTestContext()

        val success = LLMResponseContext.success(context, Message.assistant("test"), 150)
        assertEquals(150, success.durationMs)
        assertTrue(success.isSuccess)
        assertFalse(success.isFailure)

        val failure = LLMResponseContext.failure(context, RuntimeException("error"), 50)
        assertEquals(50, failure.durationMs)
        assertFalse(failure.isSuccess)
        assertTrue(failure.isFailure)
    }

    @Test
    fun `LLMResponseContext getOrThrow works correctly`() {
        val context = createTestContext()

        val success = LLMResponseContext.success(context, Message.assistant("test"), 10)
        assertEquals("test", success.getOrThrow().text)

        val error = RuntimeException("test error")
        val failure = LLMResponseContext.failure(context, error, 10)
        assertFailsWith<RuntimeException> {
            failure.getOrThrow()
        }
    }

    @Test
    fun `remove interceptor by name`() {
        val interceptor1 = createNamedInterceptor("First")
        val interceptor2 = createNamedInterceptor("Second")

        LLMInterceptors.add(interceptor1)
        LLMInterceptors.add(interceptor2)
        assertEquals(2, LLMInterceptors.all().size)

        LLMInterceptors.remove("First")
        assertEquals(1, LLMInterceptors.all().size)
        assertEquals("Second", LLMInterceptors.all()[0].name)
    }

    @Test
    fun `remove interceptor by reference`() {
        val interceptor1 = createNamedInterceptor("First")
        val interceptor2 = createNamedInterceptor("Second")

        LLMInterceptors.add(interceptor1)
        LLMInterceptors.add(interceptor2)

        LLMInterceptors.remove(interceptor1)
        assertEquals(1, LLMInterceptors.all().size)
        assertEquals("Second", LLMInterceptors.all()[0].name)
    }

    private fun createTestContext(): LLMRequestContext {
        return LLMRequestContext(
            provider = object : Provider {
                override val slug = "test"
                override val name = "Test Provider"
                override fun isConfigured(config: Configuration) = true
                override suspend fun complete(
                    messages: List<Message>,
                    model: String,
                    tools: List<Tool>,
                    temperature: Double?,
                    maxTokens: Int?,
                    config: Configuration
                ) = Message.assistant("test")
                override fun stream(
                    messages: List<Message>,
                    model: String,
                    tools: List<Tool>,
                    temperature: Double?,
                    maxTokens: Int?,
                    config: Configuration
                ) = kotlinx.coroutines.flow.flowOf(Chunk(content = "test"))
                override suspend fun listModels(config: Configuration) = emptyList<ModelInfo>()
            },
            model = "test-model",
            messages = listOf(Message.user("Hello")),
            tools = emptyList(),
            temperature = null,
            maxTokens = null,
            streaming = false
        )
    }

    private fun createNamedInterceptor(interceptorName: String): LLMInterceptor {
        return object : LLMInterceptor {
            override val name = interceptorName
            override suspend fun intercept(context: LLMRequestContext, chain: LLMInterceptorChain) = chain.proceed(context)
        }
    }
}
