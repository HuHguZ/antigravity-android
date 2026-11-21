package com.google.antigravity

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.antigravity.device.PhoneResourceController
import com.google.antigravity.model.AgentExecutionTarget
import com.google.antigravity.network.AntigravityBackendClient
import com.google.antigravity.network.OpenRouterChatFailure
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Android engine + real backend routing; fixture transport avoids paid requests and private credentials. */
@RunWith(AndroidJUnit4::class)
class PhoneOpenRouterSendIntegrationTest {
    private suspend fun send(
        code: Int,
        key: String = "qa-phone-key",
        model: String = "qa/text",
        providerFailure: Boolean = false,
        headers: Headers = Headers.Builder().build()
    ): AntigravityBackendClient.AgentTurnResult {
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Owned QA emulator only" }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val client = AntigravityBackendClient(PhoneResourceController(context), OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("https://openrouter.ai/api/v1/chat/completions", chain.request().url.toString())
            assertEquals("Bearer qa-phone-key", chain.request().header("Authorization"))
            val payload = okio.Buffer(); chain.request().body!!.writeTo(payload)
            assertEquals(model, org.json.JSONObject(payload.readUtf8()).getString("model"))
            val error = if (providerFailure) ""","finish_reason":"error","error":{"code":502}""" else ""
            val body = """{"choices":[{"message":{"content":"Телефон получил ответ без ПК.","reasoning":"QA `reasoning`"}$error}]}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("QA").headers(headers)
                .body(body.toResponseBody()).build()
        }.build())
        return client.executeAgentTurn("Проверка", false, AgentExecutionTarget.PHONE_LOCAL_RESOURCES,
            model, "qa-send", "QA", emptyList(), emptyList(), "http://127.0.0.1:1", "", key)
    }

    @Test fun phoneTextTurnWorksWithUnavailablePc() = runBlocking {
        val result = send(200)
        assertFalse(result.isError)
        assertEquals("Телефон получил ответ без ПК.", result.responseMarkdown)
        assertTrue(result.thinkingTrace.contains("QA `reasoning`"))
    }

    @Test fun billingFailurePreservesActionableReason() = runBlocking {
        try { send(402); fail("Expected billing failure") }
        catch (failure: OpenRouterChatFailure) {
            assertEquals(402, failure.status)
            assertTrue(failure.message!!.contains("Недостаточно средств"))
        }
    }

    @Test fun providerFailureAfterHeadersIsNotReportedAsSuccess() = runBlocking {
        try { send(200, providerFailure = true); fail("Expected provider failure") }
        catch (failure: OpenRouterChatFailure) {
            assertEquals(502, failure.status)
            assertTrue(failure.message.orEmpty().contains("HTTP 502"))
        }
    }

    @Test fun rateLimitReportsTheServerWaitAndHidesUnrecognizedValues() = runBlocking {
        val fixtures = listOf(
            Headers.Builder().add("Retry-After", "27").build() to "27 сек.",
            Headers.Builder().add("X-RateLimit-Reset", (System.currentTimeMillis() + 60_000).toString()).build() to "сек.",
            Headers.Builder().add("Retry-After", "bad-header").build() to "Время сброса не указано"
        )
        for ((headers, expected) in fixtures) {
            try { send(429, headers = headers); fail("Expected rate limit") }
            catch (failure: OpenRouterChatFailure) {
                assertEquals(429, failure.status)
                assertTrue(failure.message, failure.message.orEmpty().contains(expected))
                assertFalse(failure.message.orEmpty().contains("bad-header"))
            }
        }
    }

    @Test fun missingPhoneKeyDoesNotTryPc() = runBlocking {
        val result = send(200, "")
        assertTrue(result.isError)
        assertTrue(result.responseMarkdown.contains("ключ OpenRouter"))
    }

    @Test fun autoRouterKeepsItsRealProviderPrefix() = runBlocking {
        assertFalse(send(200, model = "openrouter/auto").isError)
    }
}
