package com.agenttask.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.agenttask.ui.theme.CodeStyle

private data class Block(val code: Boolean, val lang: String, val text: String)

private fun parse(md: String): List<Block> {
    val out = mutableListOf<Block>()
    val lines = md.lines()
    var i = 0
    val buf = StringBuilder()
    while (i < lines.size) {
        val line = lines[i]
        if (line.trimStart().startsWith("```")) {
            if (buf.isNotEmpty()) { out += Block(false, "", buf.toString().trimEnd()); buf.clear() }
            val lang = line.trimStart().removePrefix("```").trim()
            i++
            val code = StringBuilder()
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                code.appendLine(lines[i]); i++
            }
            i++
            out += Block(true, lang, code.toString().trimEnd())
        } else {
            buf.appendLine(line); i++
        }
    }
    if (buf.isNotEmpty()) out += Block(false, "", buf.toString().trimEnd())
    return out
}

private val inlineCode = Regex("`([^`\\n]+)`")
private val bold = Regex("\\*\\*([^*\\n]+)\\*\\*")
private val heading = Regex("^(#{1,6})\\s+(.*)$", RegexOption.MULTILINE)

@Composable
private fun inlineAnnotated(text: String): AnnotatedString {
    val cs = MaterialTheme.colorScheme
    return remember(text, cs) {
        buildAnnotatedString {
            var rest = heading.replace(text) { m -> m.groupValues[2] }
            var idx = 0
            val marks = mutableListOf<Triple<IntRange, String, SpanStyle>>()
            inlineCode.findAll(rest).forEach {
                marks += Triple(it.range, it.groupValues[1],
                    SpanStyle(fontFamily = CodeStyle.fontFamily, background = cs.surfaceVariant))
            }
            bold.findAll(rest).forEach {
                marks += Triple(it.range, it.groupValues[1], SpanStyle(fontWeight = FontWeight.Bold))
            }
            marks.sortBy { it.first.first }
            for ((range, content, style) in marks) {
                if (range.first < idx) continue
                append(rest.substring(idx, range.first))
                withStyle(style) { append(content) }
                idx = range.last + 1
            }
            if (idx < rest.length) append(rest.substring(idx))
        }
    }
}

@Composable
fun MarkdownText(
    md: String,
    onSaveCode: (code: String, lang: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboardManager.current
    val blocks = remember(md) { parse(md) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { b ->
            if (!b.code) {
                if (b.text.isNotBlank()) {
                    Text(inlineAnnotated(b.text), style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                b.lang.ifBlank { "code" },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = { clipboard.setText(AnnotatedString(b.text)) }) {
                                Icon(Icons.Default.ContentCopy, "Копировать", Modifier.size(18.dp))
                            }
                            IconButton(onClick = { onSaveCode(b.text, b.lang) }) {
                                Icon(Icons.Default.Download, "Сохранить как файл", Modifier.size(18.dp))
                            }
                        }
                        Text(
                            b.text,
                            style = CodeStyle,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 12.dp)
                                .padding(bottom = 12.dp)
                        )
                    }
                }
            }
        }
    }
}

fun extForLang(lang: String): String = when (lang.lowercase()) {
    "kotlin", "kt" -> "kt"; "java" -> "java"; "python", "py" -> "py"
    "javascript", "js" -> "js"; "typescript", "ts" -> "ts"
    "json" -> "json"; "xml" -> "xml"; "html" -> "html"; "css" -> "css"
    "bash", "sh", "shell" -> "sh"; "yaml", "yml" -> "yml"
    "c" -> "c"; "cpp", "c++" -> "cpp"; "go" -> "go"; "rust", "rs" -> "rs"
    "sql" -> "sql"; "md", "markdown" -> "md"
    else -> "txt"
}
