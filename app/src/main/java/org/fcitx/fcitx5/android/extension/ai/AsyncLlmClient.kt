/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.extension.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.math.min

class AsyncLlmClient(
    private val apiKey: String,
    private val baseUrl: String = "https://api.deepseek.com/anthropic",
    private val model: String = "deepseek-v4-flash",
    private val maxRetries: Int = 2
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun fetchAiResponse(
        systemPrompt: String,
        userText: String,
        onResult: (String) -> Unit
    ): String? {
        val result = runCatching {
            requestChatCompletion(systemPrompt, userText, stream = false)
        }.getOrNull()
        withContext(Dispatchers.Main.immediate) {
            onResult(result.orEmpty())
        }
        return result
    }

    suspend fun fetchAiResponse(systemPrompt: String, userText: String): String? {
        return runCatching {
            requestChatCompletion(systemPrompt, userText, stream = false)
        }.getOrNull()
    }

    suspend fun streamAiResponse(
        systemPrompt: String,
        userText: String,
        onDelta: (String) -> Unit,
        onComplete: (String) -> Unit
    ): String? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext null
        val request = buildRequest(systemPrompt, userText, stream = true)
        val builder = StringBuilder()
        executeStreamingWithRetry(request) { responseBody ->
            responseBody.charStream().buffered().useLines { lines ->
                lines.forEach { line ->
                    coroutineContext.ensureActive()
                    val trimmed = line.trim()
                    if (!trimmed.startsWith("data:")) return@forEach
                    val payload = trimmed.removePrefix("data:").trim()
                    if (payload == "[DONE]") return@forEach
                    val delta = parseStreamingDelta(payload)
                    if (delta.isNotEmpty()) {
                        builder.append(delta)
                        withContext(Dispatchers.Main.immediate) {
                            onDelta(delta)
                        }
                    }
                }
                coroutineContext.ensureActive()
            }
            builder.toString().takeIf { it.isNotBlank() }
        }.also { finalText ->
            withContext(Dispatchers.Main.immediate) {
                onComplete(finalText.orEmpty())
            }
        }
    }

    private suspend fun requestChatCompletion(
        systemPrompt: String,
        userText: String,
        stream: Boolean
    ): String? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext null
        val request = buildRequest(systemPrompt, userText, stream)
        executeWithRetry(request) { responseBody ->
            if (apiFormat == ApiFormat.Anthropic) {
                parseAnthropicMessage(responseBody)
            } else {
                parseOpenAiMessage(responseBody)
            }
        }
    }

    private fun buildRequest(systemPrompt: String, userText: String, stream: Boolean): Request {
        return if (apiFormat == ApiFormat.Anthropic) {
            buildAnthropicRequest(systemPrompt, userText, stream)
        } else {
            buildOpenAiRequest(systemPrompt, userText, stream)
        }
    }

    private fun buildOpenAiRequest(systemPrompt: String, userText: String, stream: Boolean): Request {
        val bodyJson = JSONObject()
            .put("model", model)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", systemPrompt))
                    .put(JSONObject().put("role", "user").put("content", userText))
            )
            .put("stream", stream)
            .put("temperature", 0.2)

        return Request.Builder()
            .url("${normalizedBaseUrl()}/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(bodyJson.toString().toRequestBody(JsonMediaType))
            .build()
    }

    private fun buildAnthropicRequest(systemPrompt: String, userText: String, stream: Boolean): Request {
        val bodyJson = JSONObject()
            .put("model", model)
            .put("system", systemPrompt)
            .put("max_tokens", 2048)
            .put("stream", stream)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "user").put("content", userText))
            )

        return Request.Builder()
            .url("${normalizedBaseUrl()}/v1/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("Content-Type", "application/json")
            .post(bodyJson.toString().toRequestBody(JsonMediaType))
            .build()
    }

    private fun parseOpenAiMessage(responseBody: String): String? {
        return JSONObject(responseBody)
            .optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun parseAnthropicMessage(responseBody: String): String? {
        val content = JSONObject(responseBody).optJSONArray("content") ?: return null
        val builder = StringBuilder()
        for (i in 0 until content.length()) {
            val item = content.optJSONObject(i) ?: continue
            if (item.optString("type") == "text") {
                builder.append(item.optString("text"))
            }
        }
        return builder.toString().trim().takeIf { it.isNotBlank() }
    }

    private fun parseStreamingDelta(payload: String): String {
        val json = JSONObject(payload)
        return if (apiFormat == ApiFormat.Anthropic) {
            when (json.optString("type")) {
                "content_block_delta" -> {
                    val delta = json.optJSONObject("delta")
                    if (delta?.optString("type") == "text_delta") {
                        delta.optString("text")
                    } else {
                        ""
                    }
                }
                "content_block_start" -> {
                    val block = json.optJSONObject("content_block")
                    if (block?.optString("type") == "text") {
                        block.optString("text")
                    } else {
                        ""
                    }
                }
                else -> ""
            }
        } else {
            json.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("delta")
                ?.optString("content")
                .orEmpty()
        }
    }

    private val apiFormat: ApiFormat
        get() = if (baseUrl.trimEnd('/').endsWith("/anthropic", ignoreCase = true)) {
            ApiFormat.Anthropic
        } else {
            ApiFormat.OpenAi
        }

    private fun normalizedBaseUrl(): String = baseUrl.trim().trimEnd('/')

    private suspend fun <T> executeWithRetry(
        request: Request,
        parser: suspend (String) -> T?
    ): T? {
        var lastError: IOException? = null
        repeat(maxRetries + 1) { attempt ->
            coroutineContext.ensureActive()
            try {
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    if (response.isSuccessful) {
                        return parser(responseBody)
                    }
                    if (response.code in 400..499 && response.code != 429) {
                        return null
                    }
                    lastError = IOException("DeepSeek HTTP ${response.code}: ${response.message}")
                }
            } catch (e: IOException) {
                lastError = e
            }
            if (attempt < maxRetries) {
                delay(min(250L * (attempt + 1), 750L))
            }
        }
        throw lastError ?: IOException("DeepSeek request failed")
    }

    private suspend fun <T> executeStreamingWithRetry(
        request: Request,
        parser: suspend (ResponseBody) -> T?
    ): T? {
        var lastError: IOException? = null
        repeat(maxRetries + 1) { attempt ->
            coroutineContext.ensureActive()
            try {
                client.newCall(request).execute().use { response ->
                    val body = response.body
                    if (response.isSuccessful && body != null) {
                        return parser(body)
                    }
                    if (response.code in 400..499 && response.code != 429) {
                        return null
                    }
                    lastError = IOException("DeepSeek HTTP ${response.code}: ${response.message}")
                }
            } catch (e: IOException) {
                lastError = e
            }
            if (attempt < maxRetries) {
                delay(min(250L * (attempt + 1), 750L))
            }
        }
        throw lastError ?: IOException("DeepSeek streaming request failed")
    }

    companion object {
        private val JsonMediaType = "application/json; charset=utf-8".toMediaType()

        const val DefaultSystemPrompt =
            "You are a closed-loop data assistance module. Read OCR_RESULT and return only the final text that should be inserted into the active field. Do not explain. OCR_RESULT: [OCR_RESULT]"
    }

    private enum class ApiFormat {
        OpenAi,
        Anthropic
    }
}
