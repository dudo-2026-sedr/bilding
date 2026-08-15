package com.agenttask.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

data class Attachment(
    val name: String,
    val kind: Kind,
    val text: String = "",
    val dataUrl: String = "",
    val bytes: Int = 0
) {
    enum class Kind { TEXT, IMAGE, ARCHIVE, BINARY }
}

object Attachments {

    private const val MAX_TEXT = 120_000

    fun load(ctx: Context, uri: Uri): Attachment {
        val name = displayName(ctx, uri)
        val lower = name.lowercase()
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)

        return when {
            lower.endsWith(".zip") || lower.endsWith(".apk") || lower.endsWith(".jar") ->
                Attachment(name, Attachment.Kind.ARCHIVE, text = readZip(bytes), bytes = bytes.size)

            isImage(lower) || isImageMime(ctx, uri) ->
                Attachment(name, Attachment.Kind.IMAGE, dataUrl = toDataUrl(bytes), bytes = bytes.size)

            looksBinary(bytes) ->
                Attachment(name, Attachment.Kind.BINARY,
                    text = "[бинарный файл, ${bytes.size} байт]", bytes = bytes.size)

            else -> Attachment(name, Attachment.Kind.TEXT, text = trim(String(bytes)), bytes = bytes.size)
        }
    }

    private fun isImage(n: String) = listOf(".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp").any { n.endsWith(it) }
    private fun isImageMime(ctx: Context, uri: Uri) =
        ctx.contentResolver.getType(uri)?.startsWith("image/") == true

    private fun looksBinary(b: ByteArray) = b.take(1024).any { it == 0.toByte() }

    private fun trim(s: String) =
        if (s.length > MAX_TEXT) s.take(MAX_TEXT) + "\n…[обрезано, всего ${s.length} символов]" else s

    private fun readZip(bytes: ByteArray): String = buildString {
        appendLine("Содержимое архива:")
        var total = 0
        runCatching {
            ZipInputStream(bytes.inputStream()).use { zis ->
                var e = zis.nextEntry
                while (e != null && total < MAX_TEXT) {
                    if (!e.isDirectory) {
                        val data = zis.readBytes()
                        appendLine("\n--- ${e.name} (${data.size} B) ---")
                        if (!looksBinary(data) && data.size < 60_000) {
                            val t = String(data)
                            total += t.length
                            appendLine(t)
                        } else appendLine("[бинарный/большой файл пропущен]")
                    }
                    zis.closeEntry()
                    e = zis.nextEntry
                }
            }
        }.onFailure { appendLine("Ошибка чтения архива: ${it.message}") }
    }.let(::trim)

    /** Сжимаем картинку, чтобы не раздувать запрос */
    private fun toDataUrl(bytes: ByteArray): String {
        val opts = BitmapFactory.Options()
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            ?: return "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        val max = 1400
        val scale = minOf(1f, max.toFloat() / maxOf(bmp.width, bmp.height))
        val scaled = if (scale < 1f)
            Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
        else bmp
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    fun displayName(ctx: Context, uri: Uri): String {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) return c.getString(i)
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }
}
