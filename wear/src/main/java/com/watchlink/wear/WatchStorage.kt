package com.watchlink.wear

import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.watchlink.shared.ReceivedFile
import java.io.File

/** 文件落盘位置、路径展示、打开方式。 */
object WatchStorage {

    private const val DIR = "incoming"
    private const val PREFS = "watchlink"
    private const val KEY_PHONE = "last_phone_name"

    /** 优先存到外部私有目录（用文件管理器能找到），没有外部存储时退回内部目录。 */
    fun incomingDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, DIR).apply { mkdirs() }
    }

    fun scanFiles(context: Context): List<ReceivedFile> {
        val dirs = listOfNotNull(context.getExternalFilesDir(null), context.filesDir)
            .map { File(it, DIR) }
            .filter { it.isDirectory }
        return dirs
            .flatMap { dir -> dir.listFiles()?.filter { it.isFile }?.map { toModel(context, it) } ?: emptyList() }
            .sortedByDescending { it.receivedAt }
    }

    fun toModel(context: Context, file: File): ReceivedFile = ReceivedFile(
        name = file.name,
        size = file.length(),
        path = file.absolutePath,
        receivedAt = file.lastModified(),
        sender = lastPhoneName(context),
        mimeType = mimeOf(file.name)
    )

    /** 给用户看的路径（相对内部存储）。 */
    fun pathLabel(context: Context, file: File): String {
        val external = context.getExternalFilesDir(null)
        return if (external != null && file.absolutePath.startsWith(external.absolutePath)) {
            "Android/data/${context.packageName}/files/incoming"
        } else {
            "应用私有目录/files/incoming"
        }
    }

    fun rememberPhone(context: Context, name: String) {
        if (name.isBlank()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PHONE, name).apply()
    }

    fun lastPhoneName(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PHONE, "").orEmpty()

    fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "apk" -> "application/vnd.android.package-archive"
            "pdf" -> "application/pdf"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "txt", "log", "md" -> "text/plain"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "mp4" -> "video/mp4"
            "zip" -> "application/zip"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
        }
    }

    /** 用系统组件打开文件；APK 会走到系统安装器。 */
    fun open(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeOf(file.name))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
