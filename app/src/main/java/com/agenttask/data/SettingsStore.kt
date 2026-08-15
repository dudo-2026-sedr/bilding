package com.agenttask.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.ds by preferencesDataStore("settings")

class SettingsStore(private val ctx: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    private object K {
        val providers = stringPreferencesKey("providers")
        val activeProvider = stringPreferencesKey("active_provider")
        val activeModel = stringPreferencesKey("active_model")
        val theme = stringPreferencesKey("theme")
        val systemPrompt = stringPreferencesKey("system_prompt")
        val agent = booleanPreferencesKey("agent_enabled")
        val temp = doublePreferencesKey("temperature")
        val workspace = stringPreferencesKey("workspace")
    }

    val providers: Flow<List<Provider>> = ctx.ds.data.map { p ->
        p[K.providers]?.let { runCatching { json.decodeFromString<List<Provider>>(it) }.getOrNull() } ?: emptyList()
    }
    val activeProviderId: Flow<String?> = ctx.ds.data.map { it[K.activeProvider] }
    val activeModel: Flow<String?> = ctx.ds.data.map { it[K.activeModel] }
    val theme: Flow<ThemeMode> = ctx.ds.data.map {
        runCatching { ThemeMode.valueOf(it[K.theme] ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM)
    }
    val systemPrompt: Flow<String> = ctx.ds.data.map { it[K.systemPrompt] ?: DEFAULT_PROMPT }
    val agentEnabled: Flow<Boolean> = ctx.ds.data.map { it[K.agent] ?: true }
    val temperature: Flow<Double> = ctx.ds.data.map { it[K.temp] ?: 0.6 }
    val workspace: Flow<String?> = ctx.ds.data.map { it[K.workspace] }

    suspend fun saveProviders(list: List<Provider>) =
        ctx.ds.edit { it[K.providers] = json.encodeToString(list) }

    suspend fun setActive(providerId: String?, model: String?) = ctx.ds.edit {
        if (providerId == null) it.remove(K.activeProvider) else it[K.activeProvider] = providerId
        if (model == null) it.remove(K.activeModel) else it[K.activeModel] = model
    }

    suspend fun setTheme(mode: ThemeMode) = ctx.ds.edit { it[K.theme] = mode.name }
    suspend fun setSystemPrompt(v: String) = ctx.ds.edit { it[K.systemPrompt] = v }
    suspend fun setAgent(v: Boolean) = ctx.ds.edit { it[K.agent] = v }
    suspend fun setTemperature(v: Double) = ctx.ds.edit { it[K.temp] = v }
    suspend fun setWorkspace(v: String?) = ctx.ds.edit {
        if (v == null) it.remove(K.workspace) else it[K.workspace] = v
    }

    companion object {
        val DEFAULT_PROMPT = """
            Ты — Agent Task, автономный агент-инженер на Android-устройстве пользователя.
            Работай короткими шагами: планируй, вызывай инструменты, проверяй результат.
            Правила:
            - Для любых операций с файлами используй инструменты, не выдумывай содержимое файлов.
            - Перед изменением файла сначала прочитай его (read_file).
            - Пути указывай относительно рабочей папки (workspace), либо абсолютные, если доступ разрешён.
            - Если пользователь просит «дай файл» / «скачать» — используй save_download, затем сообщи путь.
            - Отвечай на языке пользователя. Код оформляй в markdown-блоках с указанием языка.
            - Не вызывай инструменты без необходимости: на обычные вопросы отвечай текстом.
        """.trimIndent()
    }
}
