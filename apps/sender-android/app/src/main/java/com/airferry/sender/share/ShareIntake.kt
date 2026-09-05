package com.airferry.sender.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.airferry.sender.encode.BundleWriter
import com.airferry.sender.encode.Filenames
import com.airferry.sender.encode.TextPayload
import java.io.File
import java.util.UUID

data class StagedItem(
    val displayName: String,
    val size: Long,
    val lastModifiedMs: Long,
    val file: File,
    val isText: Boolean
)

/**
 * Copy Share-sheet URIs into app-private storage immediately.
 *
 * Many OEM `ACTION_SEND` grants are revoked as soon as `onCreate` returns
 * (or as soon as the sending app is backgrounded). Reading later — even
 * later in the same Activity — can throw `SecurityException`.
 */
object ShareIntake {
    const val MAX_ORIGINAL_BYTES = 256L * 1024 * 1024
    const val MAX_FILES = BundleWriter.MAX_FILES
    private const val DIR = "share-intake"
    private const val STALE_MS = 24L * 60 * 60 * 1000

    fun purgeStale(context: Context) {
        val root = File(context.filesDir, DIR)
        if (!root.isDirectory) return
        val now = System.currentTimeMillis()
        root.listFiles()?.forEach { child ->
            if (now - child.lastModified() > STALE_MS) {
                child.deleteRecursively()
            }
        }
    }

    fun copyFromIntent(context: Context, intent: Intent): List<StagedItem> {
        val items = ArrayList<StagedItem>()
        val uris = collectUris(intent)
        for (uri in uris) {
            items += copyUri(context, uri)
            if (items.size > MAX_FILES) {
                throw IllegalArgumentException("一次最多发送 $MAX_FILES 个文件，请分批发送")
            }
        }
        if (items.isEmpty()) {
            val text = extraText(intent)
            if (!text.isNullOrEmpty()) {
                items += writeText(context, text)
            }
        }
        if (items.isEmpty()) {
            throw IllegalArgumentException("没有收到可发送的文件或文字")
        }
        val total = items.sumOf { it.size }
        if (total > MAX_ORIGINAL_BYTES) {
            throw IllegalArgumentException(
                "合计 ${formatBytes(total)}，超过发送端内存上限 ${formatBytes(MAX_ORIGINAL_BYTES)}"
            )
        }
        return items
    }

    fun copyUris(context: Context, uris: List<Uri>): List<StagedItem> {
        val fake = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        return copyFromIntent(context, fake)
    }

    private fun extraText(intent: Intent): String? {
        return intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotEmpty() }
    }

    private fun collectUris(intent: Intent): List<Uri> {
        val out = ArrayList<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> {
                streamUri(intent)?.let { out += it }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val list = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                list?.forEach { if (it != null) out += it }
            }
        }
        val clip = intent.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) {
                val uri = clip.getItemAt(i).uri
                if (uri != null && out.none { it == uri }) out += uri
            }
        }
        return out
    }

    private fun streamUri(intent: Intent): Uri? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }

    private fun copyUri(context: Context, uri: Uri): StagedItem {
        val meta = queryMeta(context, uri)
        val name = Filenames.sanitize(meta.displayName)
        val destDir = File(context.filesDir, "$DIR/${UUID.randomUUID()}").apply { mkdirs() }
        val dest = File(destDir, name)
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // Temporary Share grants are not persistable; copy still proceeds.
        }
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalArgumentException("无法读取文件: $name")
        val size = dest.length()
        if (size == 0L) {
            destDir.deleteRecursively()
            throw IllegalArgumentException("暂不支持发送空文件（0 B）: $name")
        }
        if (size > MAX_ORIGINAL_BYTES) {
            destDir.deleteRecursively()
            throw IllegalArgumentException(
                "文件过大（${formatBytes(size)}），上限 ${formatBytes(MAX_ORIGINAL_BYTES)}"
            )
        }
        val mtime = meta.lastModifiedMs.takeIf { it > 0 } ?: dest.lastModified()
        dest.setLastModified(mtime)
        return StagedItem(
            displayName = name,
            size = size,
            lastModifiedMs = mtime,
            file = dest,
            isText = false
        )
    }

    private fun writeText(context: Context, text: String): StagedItem {
        val destDir = File(context.filesDir, "$DIR/${UUID.randomUUID()}").apply { mkdirs() }
        val name = TextPayload.DEFAULT_NAME
        val dest = File(destDir, name)
        dest.writeText(text, Charsets.UTF_8)
        val size = dest.length()
        if (size == 0L) {
            destDir.deleteRecursively()
            throw IllegalArgumentException("文字内容为空")
        }
        return StagedItem(
            displayName = name,
            size = size,
            lastModifiedMs = dest.lastModified(),
            file = dest,
            isText = true
        )
    }

    private data class Meta(
        val displayName: String,
        val lastModifiedMs: Long
    )

    private fun queryMeta(context: Context, uri: Uri): Meta {
        var display = uri.lastPathSegment?.substringAfterLast('/') ?: "shared.bin"
        var mtime = 0L
        val projection = arrayOf(
            OpenableColumns.DISPLAY_NAME,
            OpenableColumns.SIZE,
            "last_modified",
            "datemodified"
        )
        try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0) {
                        c.getString(nameIdx)?.takeIf { it.isNotBlank() }?.let { display = it }
                    }
                    for (col in arrayOf("last_modified", "datemodified")) {
                        val i = c.getColumnIndex(col)
                        if (i >= 0 && !c.isNull(i)) {
                            val v = c.getLong(i)
                            if (v > 0) {
                                mtime = v
                                break
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Best-effort; copy still uses the URI.
        }
        return Meta(display, mtime)
    }

    fun formatBytes(n: Long): String {
        if (n < 1024) return "$n B"
        if (n < 1024 * 1024) return "${"%.1f".format(n / 1024.0)} KB"
        if (n < 1024L * 1024 * 1024) return "${"%.1f".format(n / (1024.0 * 1024.0))} MB"
        return "${"%.1f".format(n / (1024.0 * 1024 * 1024))} GB"
    }
}
