package com.agenttask.net

import com.agenttask.data.ChatMsg
import com.agenttask.data.ToolCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed interface StreamEvent {
    data class Text(val delta: String) : StreamEvent
    data class Reasoning(val delta: String) : StreamEvent
    data class Tools(val calls: List<ToolCall>) : StreamEvent
    data object Done : StreamEvent
}

private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { !it.isString || true }?.contentOrNull

class OpenAiClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)   // стрим держим открытым
        .retryOnConnectionFailure(true)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun endpoint(baseUrl: String, path: String): String {
        var b = baseUrl.trim().trimEnd('/')
        if (!b.startsWith("http")) b = "https://$b"
        if (!b.endsWith("/v1") && !b.contains("/v1/") && !b.endsWith("/openai")) b = "$b/v1"
        return "$b/$path"
    }

    private fun msgJson(m: ChatMsg): JsonObject = buildJsonObject {
        put("role", m.role)
        m.toolCallId?.let { put("tool_call_id", it) }
        if (m.images.isNotEmpty()) {
            putJsonArray("content") {
                if (m.content.isNotBlank()) add(buildJsonObject {
                    put("type", "text"); put("text", m.content)
                })
                m.images.forEach { url ->
                    add(buildJsonObject {
                        put("type", "image_url")
                        putJsonObject("image_url") { put("url", url) }
                    })
                }
            }
        } else {
            put("content", m.content)
        }
        if (m.toolCalls.isNotEmpty()) putJsonArray("tool_calls") {
            m.toolCalls.forEach { tc ->
                add(buildJsonObject {
                    put("id", tc.id); put("type", "function")
                    putJsonObject("function") {
                        put("name", tc.name)
                        put("arguments", tc.args.ifBlank { "{}" })
                    }
                })
            }
        }
    }

    /** Список моделей у провайдера (GET /models) */
    suspend fun listModels(baseUrl: String, apiKey: String): List<String> {
        val req = Request.Builder().url(endpoint(baseUrl, "models"))
            .addHeader("Authorization", "Bearer $apiKey")
            .get().build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}: ${r.body?.string()?.take(300)}")
            val body = r.body?.string() ?: return emptyList()
            val arr = json.parseToJsonElement(body).jsonObject["data"]?.jsonArray ?: return emptyList()
            return arr.mapNotNull { it.jsonObject["id"].str() }.sorted()
        }
    }

    fun stream(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMsg>,
        tools: JsonArray?,
        temperature: Double
    ): Flow<StreamEvent> = flow {
        val payload = buildJsonObject {
            put("model", model)
            put("stream", true)
            put("temperature", temperature)
            putJsonArray("messages") { messages.forEach { add(msgJson(it)) } }
            if (!tools.isNullOrEmpty()) {
                put("tools", tools)
                put("tool_choice", "auto")
            }
        }

        val req = Request.Builder()
            .url(endpoint(baseUrl, "chat/completions"))
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Accept", "text/event-stream")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("HTTP ${resp.code}: ${resp.body?.string()?.take(600)}")
            }
            val source = resp.body?.source() ?: throw IOException("Пустой ответ")

            // index -> (id, name, argsBuffer)
            val acc = LinkedHashMap<Int, Triple<StringBuilder, StringBuilder, StringBuilder>>()

            while (true) {
                val line = source.readUtf8Line() ?: break
                if (line.isBlank()) continue
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break

                val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
                obj["error"]?.let { throw IOException("API error: $it") }

                val choice = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: continue
                val delta = choice["delta"]?.jsonObject ?: continue

                delta["content"].str()?.takeIf { it.isNotEmpty() }?.let { emit(StreamEvent.Text(it)) }
                (delta["reasoning_content"] ?: delta["reasoning"]).str()
                    ?.takeIf { it.isNotEmpty() }?.let { emit(StreamEvent.Reasoning(it)) }

                delta["tool_calls"]?.jsonArray?.forEach { el ->
                    val tc = el.jsonObject
                    val idx = (tc["index"] as? JsonPrimitive)?.intOrNull ?: 0
                    val slot = acc.getOrPut(idx) { Triple(StringBuilder(), StringBuilder(), StringBuilder()) }
                    tc["id"].str()?.let { if (slot.first.isEmpty()) slot.first.append(it) }
                    tc["function"]?.jsonObject?.let { f ->
                        f["name"].str()?.let { if (slot.second.isEmpty()) slot.second.append(it) }
                        f["arguments"].str()?.let { slot.third.append(it) }
                    }
                }
            }

            if (acc.isNotEmpty()) {
                val calls = acc.entries.map { (i, t) ->
                    ToolCall(
                        id = t.first.toString().ifBlank { "call_$i" },
                        name = t.second.toString(),
                        args = t.third.toString().ifBlank { "{}" }
                    )
                }.filter { it.name.isNotBlank() }
                if (calls.isNotEmpty()) emit(StreamEvent.Tools(calls))
            }
            emit(StreamEvent.Done)
        }
    }.flowOn(Dispatchers.IO)
}
