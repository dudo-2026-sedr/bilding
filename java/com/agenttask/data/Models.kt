package com.agenttask.data

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class Provider(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "New provider",
    val baseUrl: String = "https://api.openai.com/v1",
    val apiKey: String = "",
    val models: List<String> = emptyList()
)

@Serializable
data class ToolCall(val id: String, val name: String, val args: String)

/** Универсальное сообщение для API */
@Serializable
data class ChatMsg(
    val role: String,
    val content: String = "",
    val images: List<String> = emptyList(),      // data:image/...;base64,...
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null
)

enum class ThemeMode { SYSTEM, LIGHT, DARK }
