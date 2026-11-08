package com.watchlink.shared

import java.io.InputStream
import java.io.OutputStream

/** 文件传输的文件头（先发头，再发原始字节流）。 */
data class FileHeader(
    val name: String,
    val size: Long,
    val mimeType: String = "application/octet-stream"
)

object FileCodec {

    fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
        else -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
    }

    /** 取出一个安全的文件名。 */
    fun sanitizeName(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\').ifBlank { "file" }
        return base.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }

    /** 把 [input] 拷到 [out]，每块回调已拷贝字节数。 */
    fun copyWithProgress(
        input: InputStream,
        out: OutputStream,
        chunkSize: Int = Protocol.CHUNK_SIZE,
        onChunk: (transferred: Long) -> Unit
    ): Long {
        val buffer = ByteArray(chunkSize)
        var transferred = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            out.write(buffer, 0, read)
            transferred += read
            onChunk(transferred)
        }
        out.flush()
        return transferred
    }
}
