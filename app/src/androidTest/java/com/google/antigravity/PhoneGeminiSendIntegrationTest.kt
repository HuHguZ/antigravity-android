package com.google.antigravity

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.antigravity.device.PhoneResourceController
import com.google.antigravity.model.AgentExecutionTarget
import com.google.antigravity.network.AntigravityBackendClient
import com.google.antigravity.network.GeminiChatFailure
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneGeminiSendIntegrationTest {
    private suspend fun send(selected: String, expected: String, code: Int = 200): AntigravityBackendClient.AgentTurnResult {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val transport = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("https://generativelanguage.googleapis.com/v1beta/models/$expected:generateContent", request.url.toString())
            assertEquals("qa-gemini-key", request.header("x-goog-api-key"))
            val body = if (code == 200) """{"candidates":[{"content":{"parts":[{"text":"Готово"}]}}]}"""
                else """{"error":{"message":"private error body"}}"""
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("QA")
                .body(body.toResponseBody()).build()
        }.build()
        return AntigravityBackendClient(PhoneResourceController(context), transport).executeAgentTurn(
            "QA", false, AgentExecutionTarget.PHONE_LOCAL_RESOURCES, selected, "qa-gemini", "QA",
            emptyList(), emptyList(), "http://127.0.0.1:1", "qa-gemini-key"
        )
    }

    @Test fun publicGemini4AndFlashUseDirectInferenceWithoutPc() = runBlocking {
        for (model in listOf("gemini-4-hydrogen", "gemini-4-argon", "gemini-4-neon", "gemini-4-carbon", "gemini-3.8-flash")) {
            val result = send(model, model)
            assertFalse(result.isError)
            assertEquals("Готово", result.responseMarkdown)
        }
    }

    @Test fun savedGemini25SelectionUsesCurrentReplacement() = runBlocking {
        assertEquals("Готово", send("gemini-2.5-pro", "gemini-3.8-flash").responseMarkdown)
    }

    @Test fun unavailableModelRetainsStatusWithoutExposingProviderBody() = runBlocking {
        try { send("gemini-4-neon", "gemini-4-neon", 404); fail("Expected HTTP 404") }
        catch (error: GeminiChatFailure) {
            assertEquals(404, error.status)
            assertTrue(error.message.orEmpty().contains("gemini-4-neon"))
            assertFalse(error.message.orEmpty().contains("private error body"))
        }
    }
}
