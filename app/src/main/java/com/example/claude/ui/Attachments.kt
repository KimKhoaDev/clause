package com.example.claude.ui

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.example.claude.data.Attachment
import com.example.claude.data.Repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

object Attachments {
    private const val MAX_BYTES = 10 * 1024 * 1024
    private const val MAX_SIDE = 1568
    private val textExt = setOf("txt", "md", "csv", "json", "xml", "html", "css", "js", "ts", "kt", "java", "py", "c", "cpp", "h", "rs", "go", "sh", "yml", "yaml", "toml", "sql", "log")

    private fun name(cr: ContentResolver, uri: Uri): String =
        runCatching {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() ?: "file"

    /** Copies a picked file into app storage (images are downscaled). Returns null if unsupported/too big. */
    suspend fun import(ctx: Context, uri: Uri): Attachment? = withContext(Dispatchers.IO) {
        runCatching {
            val cr = ctx.contentResolver
            val mime = cr.getType(uri) ?: "application/octet-stream"
            val nm = name(cr, uri)
            val ext = nm.substringAfterLast('.', "").lowercase()
            val out = File(Repo.attDir(), UUID.randomUUID().toString())
            when {
                mime.startsWith("image/") -> {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE * 2) sample *= 2
                    var bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return@runCatching null
                    val scale = MAX_SIDE.toFloat() / maxOf(bmp.width, bmp.height)
                    if (scale < 1f) bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
                    out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                    Attachment(nm, "image/jpeg", out.path)
                }
                mime == "application/pdf" || ext == "pdf" -> copy(cr, uri, out)?.let { Attachment(nm, "application/pdf", out.path) }
                mime.startsWith("text/") || mime == "application/json" || mime == "application/xml" || ext in textExt ->
                    copy(cr, uri, out)?.let { Attachment(nm, "text/plain", out.path) }
                else -> null
            }
        }.getOrNull()
    }

    private fun copy(cr: ContentResolver, uri: Uri, out: File): File? {
        cr.openInputStream(uri)?.use { i -> out.outputStream().use { o -> i.copyTo(o) } } ?: return null
        if (out.length() > MAX_BYTES) { out.delete(); return null }
        return out
    }
}
