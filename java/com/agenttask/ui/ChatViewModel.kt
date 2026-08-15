package com.agenttask.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agenttask.App
import com.agenttask.agent.AgentEvent
import com.agenttask.data.*
import com.agenttask.files.Attachment
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.UUID

data class UiMessage(
    val id: Long,
    val role: String,
    val content: String,
    val images: List<String> = emptyList(),
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null
)

class ChatViewModel(private val app: App) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true }
    private val dao = app.db.dao()

    val chats = dao.chats().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val providers = app.settings.providers.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val theme = app.settings.theme.stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)
    val agentEnabled = app.settings.agentEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private val _chatId = MutableStateFlow<String?>(null)
    val chatId: StateFlow<String?> = _chatId

    val messages: StateFlow<List<UiMessage>> = _chatId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else dao.messages(id) }
        .map { list -> list.map { it.toUi() } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _streaming = MutableStateFlow(false)
    val streaming: StateFlow<Boolean> = _streaming

    private val _liveText = MutableStateFlow("")
    val liveText: StateFlow<String> = _liveText

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _attachments = MutableStateFlow<List<Attachment>>(emptyList())
    val attachments: StateFlow<List<Attachment>> = _attachments

    val activeProvider = combine(providers, app.settings.activeProviderId) { list, id ->
        list.firstOrNull { it.id == id } ?: list.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val activeModel = combine(activeProvider, app.settings.activeModel) { p, m ->
        m ?: p?.models?.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var job: Job? = null

    init {
        viewModelScope.launch {
            app.settings.workspace.collect { app.workspace = it ?: app.defaultWorkspace() }
        }
    }

    private fun MsgEntity.toUi() = UiMessage(
        id, role, content,
        runCatching { json.decodeFromString<List<String>>(imagesJson) }.getOrDefault(emptyList()),
        runCatching { json.decodeFromString<List<ToolCall>>(toolCallsJson) }.getOrDefault(emptyList()),
        toolCallId
    )

    fun openChat(id: String?) { _chatId.value = id; _liveText.value = ""; _status.value = null }

    fun newChat() = openChat(null)

    fun deleteChat(id: String) = viewModelScope.launch {
        dao.delete(id)
        if (_chatId.value == id) openChat(null)
    }

    fun renameChat(id: String, title: String) = viewModelScope.launch { dao.rename(id, title) }

    fun addAttachment(a: Attachment) { _attachments.value = _attachments.value + a }
    fun removeAttachment(a: Attachment) { _attachments.value = _attachments.value - a }
    fun clearError() { _error.value = null }

    fun setTheme(m: ThemeMode) = viewModelScope.launch { app.settings.setTheme(m) }
    fun setActive(p: String?, m: String?) = viewModelScope.launch { app.settings.setActive(p, m) }
    fun saveProviders(list: List<Provider>) = viewModelScope.launch { app.settings.saveProviders(list) }
    fun setAgent(v: Boolean) = viewModelScope.launch { app.settings.setAgent(v) }
    fun setWorkspace(path: String?) = viewModelScope.launch { app.settings.setWorkspace(path) }

    suspend fun fetchModels(p: Provider): Result<List<String>> =
        runCatching { app.client.listModels(p.baseUrl, p.apiKey) }

    fun stop() { job?.cancel(); job = null; finishStream() }

    private fun finishStream() {
        _streaming.value = false
        _status.value = null
    }

    fun send(input: String) {
        val provider = activeProvider.value
        val model = activeModel.value
        if (provider == null || provider.apiKey.isBlank() || model.isNullOrBlank()) {
            _error.value = "Сначала добавь Base URL, API-ключ и Model ID в настройках."
            return
        }
        val atts = _attachments.value
        if (input.isBlank() && atts.isEmpty()) return

        job = viewModelScope.launch {
            _streaming.value = true
            _liveText.value = ""

            val id = _chatId.value ?: UUID.randomUUID().toString().also { newId ->
                dao.upsert(
                    ChatEntity(
                        id = newId,
                        title = input.take(48).ifBlank { atts.firstOrNull()?.name ?: "Новый чат" },
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis(),
                        providerId = provider.id, model = model
                    )
                )
                _chatId.value = newId
            }

            // текст пользователя + вложения
            val sb = StringBuilder(input)
            atts.filter { it.kind != Attachment.Kind.IMAGE }.forEach { a ->
                sb.append("\n\n=== Вложение: ${a.name} (${a.bytes} байт) ===\n").append(a.text)
            }
            val images = atts.filter { it.kind == Attachment.Kind.IMAGE }.map { it.dataUrl }

            dao.insert(
                MsgEntity(
                    chatId = id, role = "user", content = sb.toString(),
                    imagesJson = json.encodeToString(images)
                )
            )
            _attachments.value = emptyList()
            dao.touch(id)

            val system = app.settings.systemPrompt.first() +
                    "\n\nРабочая папка (workspace): ${app.workspace}" +
                    "\nПолный доступ к хранилищу: ${if (app.hasAllFilesAccess()) "да" else "нет (только workspace и Downloads)"}"

            val history = buildList {
                add(ChatMsg("system", system))
                dao.messagesOnce(id).forEach {
                    val ui = it.toUi()
                    add(ChatMsg(ui.role, ui.content, ui.images, ui.toolCalls, ui.toolCallId))
                }
            }

            var assistantRow: Long? = null
            val buffer = StringBuilder()

            try {
                app.agent.run(
                    baseUrl = provider.baseUrl,
                    apiKey = provider.apiKey,
                    model = model,
                    history = history,
                    useTools = agentEnabled.value,
                    temperature = app.settings.temperature.first()
                ).collect { ev ->
                    when (ev) {
                        is AgentEvent.Text -> {
                            buffer.append(ev.delta)
                            _liveText.value = buffer.toString()
                        }
                        is AgentEvent.AssistantToolCalls -> {
                            dao.insert(
                                MsgEntity(
                                    chatId = id, role = "assistant",
                                    content = ev.partialText,
                                    toolCallsJson = json.encodeToString(ev.calls)
                                )
                            )
                            buffer.clear(); _liveText.value = ""
                        }
                        is AgentEvent.ToolStart -> _status.value = "⚙️ ${ev.call.name}…"
                        is AgentEvent.ToolResult -> {
                            _status.value = null
                            dao.insert(
                                MsgEntity(
                                    chatId = id, role = "tool",
                                    content = ev.result.take(20_000),
                                    toolCallId = ev.call.id
                                )
                            )
                        }
                        is AgentEvent.Failure -> _error.value = ev.message
                        AgentEvent.Turn -> {
                            if (buffer.isNotBlank()) {
                                assistantRow = dao.insert(
                                    MsgEntity(chatId = id, role = "assistant", content = buffer.toString())
                                )
                                buffer.clear(); _liveText.value = ""
                            }
                        }
                        AgentEvent.Finished -> Unit
                    }
                }
            } catch (e: Throwable) {
                _error.value = e.message ?: "Прервано"
                if (buffer.isNotBlank()) {
                    dao.insert(MsgEntity(chatId = id, role = "assistant", content = buffer.toString()))
                }
            } finally {
                if (buffer.isNotBlank() && assistantRow == null) {
                    dao.insert(MsgEntity(chatId = id, role = "assistant", content = buffer.toString()))
                }
                _liveText.value = ""
                dao.touch(id)
                finishStream()
            }
        }
    }

    fun saveCodeToDownloads(code: String, filename: String): String =
        app.tools.saveDownload(filename, code)
}
