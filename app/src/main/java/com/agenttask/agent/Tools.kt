package com.agenttask.agent

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import kotlinx.serialization.json.*
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class Tools(private val ctx: Context, private val rootPath: () -> String) {

    private val maxRead = 200_000

    private fun root(): File = File(rootPath()).also { if (!it.exists()) it.mkdirs() }

    private fun resolve(path: String): File {
        val p = path.trim()
        return if (p.startsWith("/")) File(p) else File(root(), p)
    }

    fun schema(): JsonArray = buildJsonArray {
        fun tool(name: String, desc: String, props: JsonObjectBuilder.() -> Unit, required: List<String>) {
            add(buildJsonObject {
                put("type", "function")
                putJsonObject("function") {
                    put("name", name)
                    put("description", desc)
                    putJsonObject("parameters") {
                        put("type", "object")
                        putJsonObject("properties") { props() }
                        putJsonArray("required") { required.forEach { add(it) } }
                    }
                }
            })
        }
        fun JsonObjectBuilder.str(name: String, d: String) =
            putJsonObject(name) { put("type", "string"); put("description", d) }
        fun JsonObjectBuilder.strArr(name: String, d: String) = putJsonObject(name) {
            put("type", "array"); put("description", d)
            putJsonObject("items") { put("type", "string") }
        }

        tool("list_dir", "Список файлов и папок по пути (относительно workspace).",
            { str("path", "Путь, по умолчанию '.'") }, emptyList())
        tool("read_file", "Прочитать текстовый файл.",
            { str("path", "Путь к файлу") }, listOf("path"))
        tool("write_file", "Создать/перезаписать файл текстом.",
            { str("path", "Путь"); str("content", "Содержимое") }, listOf("path", "content"))
        tool("append_file", "Дописать текст в конец файла.",
            { str("path", "Путь"); str("content", "Что дописать") }, listOf("path", "content"))
        tool("make_dir", "Создать папку (рекурсивно).",
            { str("path", "Путь") }, listOf("path"))
        tool("delete_path", "Удалить файл или папку рекурсивно.",
            { str("path", "Путь") }, listOf("path"))
        tool("move_path", "Переместить/переименовать.",
            { str("from", "Откуда"); str("to", "Куда") }, listOf("from", "to"))
        tool("find_files", "Поиск файлов по подстроке имени и/или по содержимому.",
            { str("query", "Подстрока имени"); str("path", "Где искать"); str("contains", "Подстрока в содержимом") },
            listOf("query"))
        tool("zip_create", "Упаковать файлы/папки в zip.",
            { strArr("paths", "Что упаковать"); str("output", "Путь итогового .zip") },
            listOf("paths", "output"))
        tool("zip_list", "Список содержимого архива.",
            { str("path", "Путь к .zip") }, listOf("path"))
        tool("zip_extract", "Распаковать архив.",
            { str("path", "Путь к .zip"); str("output_dir", "Куда распаковать") }, listOf("path"))
        tool("save_download", "Сохранить файл в папку Download/AgentTask, чтобы пользователь мог его скачать/открыть.",
            { str("filename", "Имя файла с расширением"); str("content", "Содержимое") },
            listOf("filename", "content"))
    }

    suspend fun call(name: String, argsRaw: String): String = try {
        val args = runCatching { Json.parseToJsonElement(argsRaw).jsonObject }.getOrDefault(JsonObject(emptyMap()))
        fun s(k: String, def: String? = null): String =
            (args[k] as? JsonPrimitive)?.contentOrNull ?: def ?: error("Отсутствует параметр '$k'")

        when (name) {
            "list_dir" -> listDir(s("path", "."))
            "read_file" -> readFile(s("path"))
            "write_file" -> {
                val f = resolve(s("path")); f.parentFile?.mkdirs()
                f.writeText(s("content"))
                "OK: записано ${f.length()} байт в ${rel(f)}"
            }
            "append_file" -> {
                val f = resolve(s("path")); f.parentFile?.mkdirs()
                f.appendText(s("content")); "OK: дописано в ${rel(f)}"
            }
            "make_dir" -> if (resolve(s("path")).mkdirs()) "OK: папка создана" else "Папка уже существует или ошибка"
            "delete_path" -> {
                val f = resolve(s("path"))
                if (!f.exists()) "Не найдено: ${rel(f)}"
                else if (f.deleteRecursively()) "OK: удалено ${rel(f)}" else "Не удалось удалить"
            }
            "move_path" -> {
                val a = resolve(s("from")); val b = resolve(s("to"))
                b.parentFile?.mkdirs()
                if (a.renameTo(b)) "OK: ${rel(a)} -> ${rel(b)}"
                else { a.copyRecursively(b, true); a.deleteRecursively(); "OK (копированием): ${rel(b)}" }
            }
            "find_files" -> find(s("query", ""), s("path", "."), (args["contains"] as? JsonPrimitive)?.contentOrNull)
            "zip_create" -> zipCreate(
                args["paths"]?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
                s("output")
            )
            "zip_list" -> zipList(s("path"))
            "zip_extract" -> zipExtract(s("path"), (args["output_dir"] as? JsonPrimitive)?.contentOrNull)
            "save_download" -> saveDownload(s("filename"), s("content"))
            else -> "Неизвестный инструмент: $name"
        }
    } catch (e: Throwable) {
        "ОШИБКА: ${e.message ?: e.toString()}"
    }

    private fun rel(f: File): String =
        f.absolutePath.removePrefix(root().absolutePath).ifEmpty { "." }.trimStart('/')

    private fun listDir(path: String): String {
        val dir = resolve(path)
        if (!dir.exists()) return "Не существует: ${rel(dir)}"
        if (!dir.isDirectory) return "Это файл: ${rel(dir)} (${dir.length()} байт)"
        val items = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
        if (items.isEmpty()) return "Папка пуста: ${rel(dir)}"
        return buildString {
            appendLine("Содержимое ${dir.absolutePath}:")
            items.forEach {
                appendLine(if (it.isDirectory) "  [dir]  ${it.name}/" else "  [file] ${it.name}  ${it.length()}B")
            }
        }
    }

    private fun readFile(path: String): String {
        val f = resolve(path)
        if (!f.exists()) return "Файл не найден: ${f.absolutePath}"
        if (f.isDirectory) return listDir(path)
        if (f.name.endsWith(".zip", true)) return zipList(path)
        val bytes = f.readBytes()
        if (bytes.take(1024).any { it == 0.toByte() }) return "Бинарный файл (${bytes.size} байт), текст не читается."
        val text = String(bytes)
        return if (text.length > maxRead) text.take(maxRead) + "\n…[обрезано, всего ${text.length} символов]"
        else text
    }

    private fun find(query: String, path: String, contains: String?): String {
        val base = resolve(path)
        val hits = base.walkTopDown().maxDepth(12)
            .filter { it.isFile }
            .filter { query.isBlank() || it.name.contains(query, true) }
            .filter { c ->
                contains.isNullOrBlank() || runCatching {
                    c.length() < 2_000_000 && c.readText().contains(contains, true)
                }.getOrDefault(false)
            }
            .take(200).map { it.absolutePath }.toList()
        return if (hits.isEmpty()) "Ничего не найдено" else hits.joinToString("\n")
    }

    private fun zipCreate(paths: List<String>, output: String): String {
        val out = resolve(output).apply { parentFile?.mkdirs() }
        ZipOutputStream(FileOutputStream(out).buffered()).use { zos ->
            paths.map { resolve(it) }.filter { it.exists() }.forEach { src ->
                if (src.isDirectory) {
                    src.walkTopDown().filter { it.isFile }.forEach { f ->
                        zos.putNextEntry(ZipEntry("${src.name}/${f.relativeTo(src).path}"))
                        f.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                } else {
                    zos.putNextEntry(ZipEntry(src.name))
                    src.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
        return "OK: архив ${out.absolutePath} (${out.length()} байт)"
    }

    private fun zipList(path: String): String {
        val f = resolve(path)
        if (!f.exists()) return "Архив не найден: ${f.absolutePath}"
        ZipFile(f).use { z ->
            val entries = z.entries().toList()
            return buildString {
                appendLine("Архив ${f.name}, записей: ${entries.size}")
                entries.take(300).forEach { appendLine("  ${it.name}  ${it.size}B") }
            }
        }
    }

    private fun zipExtract(path: String, outDir: String?): String {
        val f = resolve(path)
        if (!f.exists()) return "Архив не найден: ${f.absolutePath}"
        val dest = resolve(outDir ?: f.nameWithoutExtension).apply { mkdirs() }
        var n = 0
        ZipFile(f).use { z ->
            z.entries().asSequence().forEach { e ->
                val target = File(dest, e.name).canonicalFile
                if (!target.canonicalPath.startsWith(dest.canonicalPath)) return@forEach // zip slip
                if (e.isDirectory) target.mkdirs() else {
                    target.parentFile?.mkdirs()
                    z.getInputStream(e).use { i -> target.outputStream().use { o -> i.copyTo(o) } }
                    n++
                }
            }
        }
        return "OK: распаковано $n файлов в ${dest.absolutePath}"
    }

    fun saveDownload(filename: String, content: String): String =
        saveDownloadBytes(filename, content.toByteArray())

    fun saveDownloadBytes(filename: String, bytes: ByteArray): String {
        val safe = filename.substringAfterLast('/').ifBlank { "file.txt" }
        val mime = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(safe.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, safe)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AgentTask")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                ?: return "ОШИБКА: не удалось создать файл в Downloads"
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            "OK: сохранено в Download/AgentTask/$safe (${bytes.size} байт)"
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "AgentTask")
            dir.mkdirs()
            val out = File(dir, safe)
            out.writeBytes(bytes)
            "OK: сохранено в ${out.absolutePath}"
        }
    }
}
