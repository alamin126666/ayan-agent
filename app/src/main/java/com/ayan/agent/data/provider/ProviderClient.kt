package com.ayan.agent.data.provider

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class ChatMessage(val role: String, val content: String)

data class ProviderConfig(
    val id: String,
    val name: String,
    val baseUrl: String,
    val defaultModel: String,
    val needsAnthropicApi: Boolean = false
)

/** Built-in providers (PRD v0.3). Users can also add a custom provider. */
object BuiltInProviders {
    val list = listOf(
        ProviderConfig("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "openrouter/auto"),
        ProviderConfig("openai", "OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
        ProviderConfig("anthropic", "Anthropic", "https://api.anthropic.com", "claude-3-haiku-20240307", needsAnthropicApi = true),
        ProviderConfig("gemini", "Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-1.5-flash")
    )

    fun byId(id: String): ProviderConfig? = list.find { it.id == id }
}

/**
 * Multi-key pool (PRD v0.3): many API keys for the same provider.
 * On a limit/rate-limit error the caller rotates to the next key.
 * Session/memory stays in the app, so the agent continues the same task.
 */
class ApiKeyPool(keys: List<String>) {
    private val cleanKeys = keys.filter { it.isNotBlank() }
    private var index = 0

    val size: Int get() = cleanKeys.size
    fun current(): String? = cleanKeys.getOrNull(index)
    fun hasKeys(): Boolean = cleanKeys.isNotEmpty()

    /** Move to the next key. Returns false when no more keys are left. */
    fun rotate(): Boolean {
        if (index + 1 < cleanKeys.size) {
            index++
            return true
        }
        return false
    }
}

private val gson = Gson()

/** Minimal chat-completions client (OpenAI-compatible: OpenRouter, OpenAI, Gemini, custom). */
class OpenAiCompatibleClient(private val baseUrl: String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun chat(apiKey: String, model: String, messages: List<ChatMessage>): String =
        withContext(Dispatchers.IO) {
            val bodyJson = JsonObject().apply {
                addProperty("model", model)
                add("messages", gson.toJsonTree(messages.map {
                    mapOf("role" to it.role, "content" to it.content)
                }))
            }
            val request = Request.Builder()
                .url("$baseUrl/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw ApiLimitException(response.code, text)
                val obj = gson.fromJson(text, JsonObject::class.java)
                obj.getAsJsonArray("choices")
                    .get(0).asJsonObject
                    .getAsJsonObject("message")
                    .get("content").asString
            }
        }
}

/** Anthropic Messages API (different shape from OpenAI-compatible providers). */
class AnthropicClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun chat(apiKey: String, model: String, messages: List<ChatMessage>): String =
        withContext(Dispatchers.IO) {
            val bodyJson = JsonObject().apply {
                addProperty("model", model)
                addProperty("max_tokens", 4096)
                add("messages", gson.toJsonTree(messages.map {
                    mapOf("role" to it.role, "content" to it.content)
                }))
            }
            val request = Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("Content-Type", "application/json")
                .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw ApiLimitException(response.code, text)
                val obj = gson.fromJson(text, JsonObject::class.java)
                obj.getAsJsonArray("content")
                    .get(0).asJsonObject
                    .get("text").asString
            }
        }
}

/** Thrown on HTTP errors; code 429 / 402 means "limit hit — rotate to next key". */
class ApiLimitException(val code: Int, val body: String) :
    Exception("Provider error $code: $body")

fun isLimitError(e: Exception): Boolean =
    (e as? ApiLimitException)?.code in listOf(429, 402, 403)
