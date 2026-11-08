package com.watchlink.phone

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.watchlink.shared.FileCodec
import java.io.File
import java.io.OutputStream

/**
 * 接收手表发来的文件：优先存到系统「下载/WatchLink」（用文件管理器直接能翻到），
 * 失败则退回应用外部目录。
 */
class PhoneFileSaver(private val context: Context) {

    data class Saved(val name: String, val location: String, val size: Long)

    private var uri: Uri? = null
    private var output: OutputStream? = null
    private var fallbackFile: File? = null
    private var displayName: String = "file"

    fun begin(name: String, mimeType: String): Boolean {
        displayName = FileCodec.sanitizeName(name)
        val mime = mimeType.ifBlank { "application/octet-stream" }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/WatchLink"
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        uri = runCatching { context.contentResolver.insert(collection, values) }.getOrNull()
        output = uri?.let { runCatching { context.contentResolver.openOutputStream(it) }.getOrNull() }

        if (output != null) return true

        // 兜底：应用外部私有目录
        return runCatching {
            val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "incoming")
            dir.mkdirs()
            val file = File(dir, displayName)
            fallbackFile = file
            output = file.outputStream()
            true
        }.getOrDefault(false)
    }

    fun write(buffer: ByteArray, length: Int) {
        output?.write(buffer, 0, length)
    }

    fun finish(size: Long): Saved? {
        runCatching {
            output?.flush()
            output?.close()
        }
        output = null

        val savedUri = uri
        if (savedUri != null) {
            runCatching {
                val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                context.contentResolver.update(savedUri, values, null, null)
            }
            uri = null
            return Saved(displayName, "下载/WatchLink/$displayName", size)
        }

        val file = fallbackFile
        fallbackFile = null
        if (file != null) {
            Log.i("WatchLinkSaver", "已保存到 ${file.absolutePath}")
            return Saved(file.name, file.absolutePath, size)
        }
        return null
    }

    fun abort() {
        runCatching {
            output?.close()
        }
        output = null
        val savedUri = uri
        if (savedUri != null) {
            runCatching { context.contentResolver.delete(savedUri, null, null) }
        }
        uri = null
        fallbackFile?.let { runCatching { it.delete() } }
        fallbackFile = null
    }
}
