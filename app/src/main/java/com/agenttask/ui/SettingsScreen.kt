package com.agenttask.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.agenttask.App
import com.agenttask.data.Provider
import com.agenttask.data.ThemeMode
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: ChatViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val app = App.instance
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val providers by vm.providers.collectAsState()
    val theme by vm.theme.collectAsState()
    val agent by vm.agentEnabled.collectAsState()
    var editing by remember { mutableStateOf<Provider?>(null) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Назад") }
                }
            )
        }
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            item { SectionTitle("Тема") }
            item {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    ThemeMode.entries.forEach { m ->
                        FilterChip(
                            selected = theme == m,
                            onClick = { vm.setTheme(m) },
                            label = {
                                Text(
                                    when (m) {
                                        ThemeMode.SYSTEM -> "Как в системе"
                                        ThemeMode.LIGHT -> "Светлая"
                                        ThemeMode.DARK -> "Тёмная"
                                    }
                                )
                            },
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                }
            }

            item { SectionTitle("Агент") }
            item {
                ListItem(
                    headlineContent = { Text("Инструменты (файлы, архивы, скачивание)") },
                    supportingContent = { Text("Позволяет модели самой читать/писать файлы") },
                    trailingContent = { Switch(agent, onCheckedChange = { vm.setAgent(it) }) }
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Рабочая папка") },
                    supportingContent = { Text(app.workspace) },
                    trailingContent = {
                        TextButton(onClick = {
                            vm.setWorkspace(Environment.getExternalStorageDirectory().absolutePath)
                            scope.launch { snackbar.showSnackbar("Workspace = /storage/emulated/0 (нужен полный доступ)") }
                        }) { Text("В корень") }
                    }
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Полный доступ к хранилищу") },
                    supportingContent = {
                        Text(if (app.hasAllFilesAccess()) "Разрешён" else "Не разрешён — агент видит только workspace и Downloads")
                    },
                    trailingContent = {
                        if (!app.hasAllFilesAccess() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            TextButton(onClick = {
                                ctx.startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                        Uri.parse("package:${ctx.packageName}")
                                    )
                                )
                            }) { Text("Выдать") }
                        }
                    }
                )
            }

            item {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Провайдеры (Base URL + API key)", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { editing = Provider() }) { Icon(Icons.Default.Add, "Добавить") }
                }
            }

            items(providers, key = { it.id }) { p ->
                ListItem(
                    headlineContent = { Text(p.name) },
                    supportingContent = {
                        Text("${p.baseUrl}\nмоделей: ${p.models.size} · ключ: ${if (p.apiKey.isBlank()) "нет" else "•••" + p.apiKey.takeLast(4)}")
                    },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { editing = p }) { Icon(Icons.Default.Edit, "Изменить") }
                            IconButton(onClick = { vm.saveProviders(providers - p) }) {
                                Icon(Icons.Default.Delete, "Удалить")
                            }
                        }
                    }
                )
                HorizontalDivider()
            }

            item {
                Text(
                    "APK собирается GitHub Actions. Ключи хранятся только на устройстве.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }

    editing?.let { p ->
        ProviderDialog(
            initial = p,
            vm = vm,
            onDismiss = { editing = null },
            onSave = { updated ->
                val list = if (providers.any { it.id == updated.id })
                    providers.map { if (it.id == updated.id) updated else it }
                else providers + updated
                vm.saveProviders(list)
                if (providers.isEmpty()) vm.setActive(updated.id, updated.models.firstOrNull())
                editing = null
            }
        )
    }
}

@Composable
private fun SectionTitle(text: String) =
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
    )

@Composable
private fun ProviderDialog(
    initial: Provider,
    vm: ChatViewModel,
    onDismiss: () -> Unit,
    onSave: (Provider) -> Unit
) {
    var name by remember { mutableStateOf(initial.name) }
    var url by remember { mutableStateOf(initial.baseUrl) }
    var key by remember { mutableStateOf(initial.apiKey) }
    var modelsText by remember { mutableStateOf(initial.models.joinToString("\n")) }
    var loading by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Провайдер") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Название") }, singleLine = true)
                OutlinedTextField(
                    url, { url = it },
                    label = { Text("Base URL") },
                    placeholder = { Text("https://api.openai.com/v1") },
                    singleLine = true
                )
                OutlinedTextField(
                    key, { key = it },
                    label = { Text("API Key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                OutlinedTextField(
                    modelsText, { modelsText = it },
                    label = { Text("Model ID (по одному в строке)") },
                    minLines = 3
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        enabled = !loading,
                        onClick = {
                            loading = true
                            scope.launch {
                                val res = vm.fetchModels(Provider(name = name, baseUrl = url, apiKey = key))
                                loading = false
                                res.onSuccess { list ->
                                    if (list.isEmpty()) info = "Список моделей пуст — введи Model ID вручную"
                                    else { modelsText = list.joinToString("\n"); info = "Загружено ${list.size} моделей" }
                                }.onFailure { info = "Ошибка: ${it.message}" }
                            }
                        }
                    ) { Text("Загрузить список моделей") }
                    if (loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                }
                info?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    initial.copy(
                        name = name.ifBlank { "Provider" },
                        baseUrl = url.trim(),
                        apiKey = key.trim(),
                        models = modelsText.lines().map { it.trim() }.filter { it.isNotEmpty() }
                    )
                )
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}
