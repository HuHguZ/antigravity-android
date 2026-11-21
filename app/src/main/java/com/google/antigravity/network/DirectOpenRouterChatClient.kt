package com.google.antigravity.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Contains only application-owned messages: provider bodies may contain credentials or prompts. */
class OpenRouterChatFailure(val status: Int, message: String) : Exception(message)

internal data class OpenRouterChatReply(val text: String, val reasoning: String, val outputTokens: Int)

/** Direct phone inference. One POST, exact selected model, no redirects or automatic replay. */
internal class DirectOpenRouterChatClient(client: OkHttpClient = OkHttpClient()) {
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(240, TimeUnit.SECONDS)
        .readTimeout(240, TimeUnit.SECONDS).build()

    suspend fun complete(
        model: String,
        key: String,
        messages: JSONArray,
        onReasoning: suspend (String) -> Unit = {}
    ): OpenRouterChatReply = withContext(Dispatchers.IO) {
        val selected = model.trim()
        if (selected.isEmpty()) throw OpenRouterChatFailure(0, "Выберите модель OpenRouter.")
        if (key.isBlank()) throw failure(401)
        val payload = JSONObject().put("model", selected).put("messages", messages)
            .put("stream", true).put("max_tokens", if (selected == "particle-ai/particle-1:free") 8192 else 4096)
            .put("include_reasoning", true)
        val request = Request.Builder().url("https://openrouter.ai/api/v1/chat/completions")
            .header("Authorization", "Bearer ${key.trim()}")
            .header("X-OpenRouter-Title", "Antigravity Android")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        http.newCall(request).useCancellable { response ->
            if (!response.isSuccessful) throw httpFailure(response)
            val body = response.body ?: throw OpenRouterChatFailure(0, "OpenRouter не вернул сообщение модели.")
            if (body.contentType()?.subtype == "event-stream") readStream(body, onReasoning)
            else readJsonReply(body.string(), onReasoning)
        }
    }

    private suspend fun readStream(body: ResponseBody, onReasoning: suspend (String) -> Unit): OpenRouterChatReply {
        val reader = body.charStream().buffered()
        val event = StringBuilder()
        val text = StringBuilder()
        val plain = StringBuilder()
        val detailedReasoning = StringBuilder()
        var finishReason: String? = null
        var outputTokens = 0
        var lastPublishedAt = 0L
        var lastPublished = ""
        fun reasoningText() = plain.ifBlank { detailedReasoning }.toString().trim()
        suspend fun publishReasoning(force: Boolean = false) {
            val now = System.nanoTime()
            if (!force && lastPublishedAt != 0L && now - lastPublishedAt < TimeUnit.MILLISECONDS.toNanos(80)) return
            val snapshot = reasoningText()
            if (snapshot.isNotEmpty() && snapshot != lastPublished) {
                lastPublished = snapshot
                lastPublishedAt = now
                onReasoning(snapshot)
            }
        }

        try {
            while (true) {
                val line = reader.readLine()
                if (line?.startsWith("data:") == true) {
                    if (event.isNotEmpty()) event.append('\n')
                    event.append(line.removePrefix("data:").removePrefix(" "))
                }
                if (line != null && line.isNotEmpty()) continue
                if (event.isNotEmpty()) {
                    val data = event.toString().trim()
                    event.setLength(0)
                    if (data == "[DONE]") break
                    val json = parseJson(data)
                    json.optJSONObject("usage")?.let { outputTokens = it.optInt("completion_tokens", outputTokens) }
                    val choice = checkedChoice(json)
                    (choice?.opt("finish_reason") as? String)?.let { finishReason = it }
                    choice?.optJSONObject("delta")?.let { delta ->
                        text.append(textContent(delta))
                        plain.append(plainReasoning(delta).orEmpty())
                        readableDetails(delta).forEach { detailedReasoning.append(it) }
                        publishReasoning()
                    }
                }
                if (line == null) {
                    if (finishReason == null) throw OpenRouterChatFailure(0, "Поток OpenRouter оборвался. Повторите запрос.")
                    break
                }
            }
        } finally {
            if (currentCoroutineContext().isActive) publishReasoning(force = true)
        }
        return reply(text.toString(), reasoningText(), finishReason, outputTokens)
    }

    private suspend fun readJsonReply(payload: String, onReasoning: suspend (String) -> Unit): OpenRouterChatReply {
        val json = parseJson(payload)
        val choice = checkedChoice(json)
        val message = choice?.optJSONObject("message")
            ?: throw OpenRouterChatFailure(0, "OpenRouter не вернул сообщение модели.")
        val reasoning = plainReasoning(message)?.takeIf { it.isNotBlank() } ?: readableDetails(message).joinToString("\n")
        if (reasoning.isNotBlank()) onReasoning(reasoning)
        return reply(textContent(message), reasoning, choice.optString("finish_reason"),
            json.optJSONObject("usage")?.optInt("completion_tokens", 0) ?: 0)
    }

    private fun parseJson(payload: String): JSONObject = try {
        JSONObject(payload)
    } catch (_: org.json.JSONException) {
        throw OpenRouterChatFailure(0, "OpenRouter вернул некорректный ответ. Повторите запрос вручную.")
    }

    private fun checkedChoice(json: JSONObject): JSONObject? {
        if (json.has("error") && !json.isNull("error")) throw failure(json.optJSONObject("error")?.optInt("code", 0) ?: 0)
        val choice = json.optJSONArray("choices")?.optJSONObject(0)
        val providerError = choice?.optJSONObject("error")
        if (providerError != null || choice?.optString("finish_reason") == "error") {
            throw failure(providerError?.optInt("code", 502) ?: 502)
        }
        return choice
    }

    private fun textContent(message: JSONObject): String = when (val content = message.opt("content")) {
        is String -> content
        is JSONArray -> (0 until content.length()).mapNotNull { content.optJSONObject(it) }
            .filter { it.optString("type") == "text" }.joinToString("") { it.optString("text") }
        else -> ""
    }

    private fun plainReasoning(message: JSONObject): String? = (message.opt("reasoning") as? String)?.takeUnless { it == "null" }

    private fun readableDetails(message: JSONObject): List<String> {
        val parts = message.optJSONArray("reasoning_details") ?: return emptyList()
        return (0 until parts.length()).mapNotNull { index ->
            val part = parts.optJSONObject(index) ?: return@mapNotNull null
            when (part.optString("type")) {
                "reasoning.text" -> part.opt("text") as? String
                "reasoning.summary" -> part.opt("summary") as? String
                else -> null
            }
        }
    }

    private fun reply(text: String, reasoning: String, finishReason: String?, outputTokens: Int): OpenRouterChatReply {
        if (text.isBlank()) throw OpenRouterChatFailure(0, if (finishReason == "length")
            "Модель исчерпала лимит ответа до готового текста. Сократите запрос или выберите другую модель."
            else "Модель не вернула текст. Проверьте, что выбрана текстовая модель; для фото и видео используйте генерацию медиа.")
        return OpenRouterChatReply(text.trim(), reasoning, outputTokens.coerceAtLeast(0))
    }

    private fun httpFailure(response: Response): OpenRouterChatFailure {
        if (response.code != 429) return failure(response.code)
        val now = System.currentTimeMillis()
        val retrySeconds = response.header("Retry-After")?.toLongOrNull()?.takeIf { it > 0 }
        val reset = response.headers.getDate("Retry-After")?.time?.takeIf { it > now }
            ?: response.header("X-RateLimit-Reset")?.toLongOrNull()?.takeIf { it > now }
        val seconds = retrySeconds ?: reset?.let { 1 + (it - now - 1) / 1000 }
        return if (seconds == null) failure(429) else OpenRouterChatFailure(429,
            "Достигнут лимит OpenRouter (HTTP 429). Повторите запрос через $seconds сек.")
    }

    private fun failure(status: Int) = OpenRouterChatFailure(status, when (status) {
        400, 422 -> "OpenRouter отклонил параметры запроса (HTTP $status). Проверьте выбранную модель."
        401 -> "Ключ OpenRouter отсутствует, недействителен или отозван (HTTP 401). Сохраните действующий ключ на телефоне."
        402 -> "Недостаточно средств OpenRouter для запроса (HTTP 402). Проверьте баланс и лимит ключа."
        403 -> "OpenRouter запретил запрос (HTTP 403). Проверьте доступ ключа и ограничения провайдера."
        404 -> "Выбранная модель OpenRouter недоступна (HTTP 404). Обновите список и выберите доступную модель."
        408, 504 -> "OpenRouter не успел завершить запрос (HTTP $status). Автоматический повтор не отправлялся."
        429 -> "Достигнут лимит OpenRouter (HTTP 429). Время сброса не указано. Повторите позже."
        502, 503 -> "Провайдер модели временно недоступен (HTTP $status). Повторите позже или выберите другую модель."
        in 300..399 -> "OpenRouter вернул перенаправление. Ключ не передавался на другой адрес."
        else -> "OpenRouter не смог завершить запрос${if (status > 0) " (HTTP $status)" else ""}. Повторите позже."
    })
}
