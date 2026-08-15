package com.agenttask.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.agenttask.files.Attachment
import com.agenttask.files.Attachments
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel, onOpenSettings: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val snackbar = remember { SnackbarHostState() }

    val chats by vm.chats.collectAsState()
    val messages by vm.messages.collectAsState()
    val live by vm.liveText.collectAsState()
    val streaming by vm.streaming.collectAsState()
    val status by vm.status.collectAsState()
    val error by vm.error.collectAsState()
    val atts by vm.attachments.collectAsState()
    val provider by vm.activeProvider.collectAsState()
    val model by vm.activeModel.collectAsState()
    val chatId by vm.chatId.collectAsState()

    var input by remember { mutableStateOf("") }
    var modelMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris?.forEach { uri: Uri ->
            scope.launch {
                val a = withContext(Dispatchers.IO) { runCatching { Attachments.load(ctx, uri) }.getOrNull() }
                if (a != null) vm.addAttachment(a) else snackbar.showSnackbar("Не удалось прочитать файл")
            }
        }
    }
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(5)
    ) { uris ->
        uris.forEach { uri ->
            scope.launch {
                val a = withContext(Dispatchers.IO) { runCatching { Attachments.load(ctx, uri) }.getOrNull() }
                if (a != null) vm.addAttachment(a)
            }
        }
    }

    LaunchedEffect(error) { error?.let { snackbar.showSnackbar(it); vm.clearError() } }
    LaunchedEffect(messages.size, live) {
        val last = messages.size + if (live.isNotBlank()) 1 else 0
        if (last > 0) listState.animateScrollToItem(maxOf(0, last - 1))
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Agent Task", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { vm.newChat(); scope.launch { drawer.close() } }) {
                        Icon(Icons.Default.Add, "Новый чат")
                    }
                }
                HorizontalDivider()
                LazyColumn(Modifier.weight(1f)) {
                    items(chats, key = { it.id }) { c ->
                        NavigationDrawerItem(
                            label = { Text(c.title, maxLines = 1) },
                            selected = c.id == chatId,
                            icon = { Icon(Icons.Default.ChatBubbleOutline, null) },
                            badge = {
                                IconButton(onClick = { vm.deleteChat(c.id) }) {
                                    Icon(Icons.Default.DeleteOutline, "Удалить", Modifier.size(18.dp))
                                }
                            },
                            onClick = { vm.openChat(c.id); scope.launch { drawer.close() } },
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
                HorizontalDivider()
                NavigationDrawerItem(
                    label = { Text("Настройки") },
                    icon = { Icon(Icons.Default.Settings, null) },
                    selected = false,
                    onClick = { scope.launch { drawer.close() }; onOpenSettings() },
                    modifier = Modifier.padding(8.dp)
                )
            }
        }
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawer.open() } }) {
                            Icon(Icons.Default.Menu, "Чаты")
                        }
                    },
                    title = {
                        Box {
                            TextButton(onClick = { modelMenu = true }) {
                                Column(horizontalAlignment = Alignment.Start) {
                                    Text(model ?: "Выбери модель", maxLines = 1)
                                    Text(
                                        provider?.name ?: "нет провайдера",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Icon(Icons.Default.ArrowDropDown, null)
                            }
                            DropdownMenu(modelMenu, onDismissRequest = { modelMenu = false }) {
                                val providers by vm.providers.collectAsState()
                                providers.forEach { p ->
                                    Text(
                                        p.name,
                                        style = MaterialTheme.typography.labelMedium,
                                        modifier = Modifier.padding(start = 12.dp, top = 8.dp)
                                    )
                                    if (p.models.isEmpty()) {
                                        DropdownMenuItem(
                                            text = { Text("— нет моделей, добавь в настройках") },
                                            onClick = { modelMenu = false; onOpenSettings() })
                                    }
                                    p.models.forEach { m ->
                                        DropdownMenuItem(
                                            text = { Text(m) },
                                            trailingIcon = {
                                                if (m == model && p.id == provider?.id)
                                                    Icon(Icons.Default.Check, null)
                                            },
                                            onClick = { vm.setActive(p.id, m); modelMenu = false }
                                        )
                                    }
                                    HorizontalDivider()
                                }
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { vm.newChat() }) { Icon(Icons.Default.AddComment, "Новый чат") }
                    }
                )
            },
            bottomBar = {
                Surface(tonalElevation = 3.dp) {
                    Column(Modifier.navigationBarsPadding().imePadding().padding(8.dp)) {
                        if (atts.isNotEmpty()) {
                            LazyColumn(Modifier.heightIn(max = 120.dp)) {
                                items(atts) { a -> AttachmentChip(a) { vm.removeAttachment(a) } }
                            }
                        }
                        status?.let {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(it, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        Row(verticalAlignment = Alignment.Bottom) {
                            IconButton(onClick = { pickFile.launch(arrayOf("*/*")) }) {
                                Icon(Icons.Default.AttachFile, "Файл")
                            }
                            IconButton(onClick = {
                                pickImage.launch(
                                    androidx.activity.result.PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly
                                    )
                                )
                            }) { Icon(Icons.Default.Image, "Фото") }

                            OutlinedTextField(
                                value = input,
                                onValueChange = { input = it },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("Сообщение Agent Task…") },
                                maxLines = 6,
                                shape = RoundedCornerShape(24.dp),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default)
                            )
                            Spacer(Modifier.width(4.dp))
                            FilledIconButton(
                                onClick = {
                                    if (streaming) vm.stop()
                                    else { vm.send(input); input = "" }
                                }
                            ) {
                                Icon(
                                    if (streaming) Icons.Default.Stop else Icons.Default.ArrowUpward,
                                    if (streaming) "Стоп" else "Отправить"
                                )
                            }
                        }
                    }
                }
            }
        ) { pad ->
            if (messages.isEmpty() && live.isBlank()) {
                EmptyState(Modifier.padding(pad))
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(pad),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(messages, key = { it.id }) { m ->
                        MessageItem(m) { code, lang ->
                            val res = vm.saveCodeToDownloads(code, "agenttask_${System.currentTimeMillis()}.${extForLang(lang)}")
                            scope.launch { snackbar.showSnackbar(res) }
                        }
                    }
                    if (live.isNotBlank()) {
                        item { AssistantBubble(live, onSaveCode = { _, _ -> }) }
                    }
                    if (streaming && live.isBlank()) {
                        item {
                            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("думает…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.Terminal, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text("Agent Task", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Агент с доступом к файлам устройства: читает и пишет файлы, распаковывает архивы, собирает zip, отдаёт код файлом в Downloads.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AttachmentChip(a: Attachment, onRemove: () -> Unit) {
    ListItem(
        headlineContent = { Text(a.name, maxLines = 1) },
        supportingContent = { Text("${a.kind.name.lowercase()} · ${a.bytes} B") },
        leadingContent = {
            when (a.kind) {
                Attachment.Kind.IMAGE -> AsyncImage(
                    a.dataUrl, null,
                    Modifier.size(36.dp), contentScale = ContentScale.Crop
                )
                Attachment.Kind.ARCHIVE -> Icon(Icons.Default.FolderZip, null)
                else -> Icon(Icons.Default.Description, null)
            }
        },
        trailingContent = {
            IconButton(onClick = onRemove) { Icon(Icons.Default.Close, "Убрать") }
        }
    )
}

@Composable
private fun MessageItem(m: UiMessage, onSaveCode: (String, String) -> Unit) {
    when (m.role) {
        "user" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.widthIn(max = 320.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    m.images.forEach {
                        AsyncImage(
                            it, null,
                            Modifier.fillMaxWidth().heightIn(max = 240.dp).padding(bottom = 6.dp),
                            contentScale = ContentScale.Fit
                        )
                    }
                    Text(m.content.substringBefore("\n\n=== Вложение:"))
                    if (m.content.contains("=== Вложение:")) {
                        Text(
                            "📎 вложения включены в запрос",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        "assistant" -> {
            Column {
                if (m.content.isNotBlank()) AssistantBubble(m.content, onSaveCode)
                m.toolCalls.forEach { tc -> ToolChip("→ ${tc.name}", tc.args) }
            }
        }

        "tool" -> ToolChip("← результат", m.content)

        else -> Unit
    }
}

@Composable
private fun AssistantBubble(text: String, onSaveCode: (String, String) -> Unit) {
    MarkdownText(text, onSaveCode = onSaveCode, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun ToolChip(title: String, body: String) {
    var open by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Build, null, Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { open = !open }) {
                    Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                }
            }
            AnimatedVisibility(open) {
                Text(
                    body.take(8000),
                    style = com.agenttask.ui.theme.CodeStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
