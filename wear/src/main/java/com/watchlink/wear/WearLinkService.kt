package com.watchlink.wear

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.watchlink.shared.FileCodec
import com.watchlink.shared.FileHeader
import com.watchlink.shared.MediaState
import com.watchlink.shared.Protocol
import com.watchlink.shared.ReceivedFile
import com.watchlink.shared.StatusSnapshot
import com.watchlink.shared.TransferDirection
import com.watchlink.shared.TransferProgress
import com.watchlink.shared.TransferState
import com.watchlink.shared.WatchNotification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.io.OutputStream

/**
 * 手表端前台服务：维持与手机的连接，接收状态/通知/文件。
 */
class WearLinkService : Service(), WatchClient.Listener {

    private val scope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var client: WatchClient

    private var fileOut: OutputStream? = null
    private var currentFile: File? = null
    private var currentName: String = "文件"
    private var currentSize: Long = 0L
    private var receivedBytes: Long = 0L
    private var lastProgressReport: Long = 0L
    private var transferId: String = ""

    override fun onCreate() {
        super.onCreate()
        WearState.setFiles(WatchStorage.scanFiles(this))
        createChannel()
        startAsForeground()

        client = WatchClient(this, this)
        client.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_REFRESH -> scope.launch { client.requestRefresh() }
            ACTION_COMMAND -> intent.getStringExtra(EXTRA_COMMAND)?.let { client.sendCommand(it) }
            ACTION_SEND_FILE -> {
                val uri = intent.getStringExtra(EXTRA_URI)?.let(Uri::parse)
                if (uri != null) scope.launch { sendFileToPhone(uri) }
            }
        }
        return START_STICKY
    }

    /** 手表 -> 手机 传文件。 */
    private suspend fun sendFileToPhone(uri: Uri) {
        val resolver = contentResolver
        var name = "file"
        var size = 0L
        runCatching {
            resolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (nameIndex >= 0) cursor.getString(nameIndex)?.let { name = it }
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        }
        name = FileCodec.sanitizeName(name)
        val mime = runCatching { resolver.getType(uri) }.getOrNull() ?: "application/octet-stream"
        val transferId = "out-${System.currentTimeMillis()}"

        WearState.upsertTransfer(
            TransferProgress(
                id = transferId,
                fileName = name,
                totalBytes = size,
                state = TransferState.SENDING,
                direction = TransferDirection.TO_PHONE
            )
        )

        client.sendFile(
            header = FileHeader(name, size, mime),
            openStream = {
                resolver.openInputStream(uri) ?: throw IllegalStateException("无法读取所选文件")
            },
            onProgress = { sent ->
                WearState.upsertTransfer(
                    TransferProgress(
                        id = transferId,
                        fileName = name,
                        totalBytes = size,
                        transferredBytes = sent,
                        state = TransferState.SENDING,
                        direction = TransferDirection.TO_PHONE
                    )
                )
            },
            onFinished = { ok, error ->
                WearState.upsertTransfer(
                    TransferProgress(
                        id = transferId,
                        fileName = name,
                        totalBytes = size,
                        transferredBytes = if (ok) size else 0,
                        state = if (ok) TransferState.DONE else TransferState.FAILED,
                        error = error,
                        direction = TransferDirection.TO_PHONE
                    )
                )
            }
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runCatching { client.stop() }
        closeFileStream()
        scope.cancel()
        super.onDestroy()
    }

    /* --------------------------- WatchClient.Listener --------------------------- */

    override fun onConnected(phoneName: String) {
        WearState.setConnected(true)
        WearState.setPhoneName(phoneName)
        WatchStorage.rememberPhone(this, phoneName)
        Log.i(TAG, "已连接手机: $phoneName")
    }

    override fun onDisconnected() {
        WearState.setConnected(false)
    }

    override fun onStatus(snapshot: StatusSnapshot) {
        WearState.setSnapshot(snapshot)
    }

    override fun onNotifications(items: List<WatchNotification>) {
        WearState.setNotifications(items)
    }

    override fun onMedia(state: MediaState) {
        WearState.setMedia(state)
    }

    override fun onFileBegin(header: FileHeader) {
        closeFileStream()
        currentName = header.name
        currentSize = header.size
        receivedBytes = 0L
        lastProgressReport = 0L
        transferId = "recv-${System.currentTimeMillis()}"
        WearState.upsertTransfer(
            TransferProgress(
                id = transferId,
                fileName = currentName,
                totalBytes = currentSize,
                state = TransferState.RECEIVING
            )
        )
        try {
            val directory = WatchStorage.incomingDir(this)
            val target = uniqueTarget(directory, FileCodec.sanitizeName(header.name))
            currentFile = target
            fileOut = target.outputStream().buffered()
        } catch (t: Throwable) {
            Log.w(TAG, "创建文件失败", t)
            onFileFailed(t.message ?: "创建文件失败")
        }
    }

    override fun onFileChunk(buffer: ByteArray, offset: Int, length: Int) {
        val out = fileOut ?: return
        try {
            out.write(buffer, offset, length)
            receivedBytes += length
            if (receivedBytes - lastProgressReport >= PROGRESS_STEP) {
                lastProgressReport = receivedBytes
                reportProgress()
            }
        } catch (t: Throwable) {
            onFileFailed(t.message ?: "写入失败")
        }
    }

    override fun onFileEnd() {
        val file = currentFile
        closeFileStream()
        if (file == null) return
        WearState.upsertTransfer(
            TransferProgress(
                id = transferId,
                fileName = currentName,
                totalBytes = currentSize,
                transferredBytes = receivedBytes,
                state = TransferState.DONE
            )
        )
        WearState.addFile(
            ReceivedFile(
                name = file.name,
                size = receivedBytes,
                path = file.absolutePath,
                receivedAt = System.currentTimeMillis(),
                sender = WearState.phoneName.value.ifBlank { WatchStorage.lastPhoneName(this) },
                mimeType = WatchStorage.mimeOf(file.name)
            )
        )
        Log.i(TAG, "文件接收完成: ${file.name} ($receivedBytes bytes)")
    }

    override fun onFileFailed(message: String) {
        closeFileStream()
        WearState.upsertTransfer(
            TransferProgress(
                id = transferId,
                fileName = currentName,
                totalBytes = currentSize,
                transferredBytes = receivedBytes,
                state = TransferState.FAILED,
                error = message
            )
        )
    }

    private fun reportProgress() {
        WearState.upsertTransfer(
            TransferProgress(
                id = transferId,
                fileName = currentName,
                totalBytes = currentSize,
                transferredBytes = receivedBytes,
                state = TransferState.RECEIVING
            )
        )
    }

    private fun closeFileStream() {
        runCatching { fileOut?.flush() }
        runCatching { fileOut?.close() }
        fileOut = null
    }

    private fun uniqueTarget(directory: File, name: String): File {
        val safe = name.ifBlank { "file" }
        var candidate = File(directory, safe)
        if (!candidate.exists()) return candidate
        val dot = safe.lastIndexOf('.')
        val base = if (dot > 0) safe.substring(0, dot) else safe
        val ext = if (dot > 0) safe.substring(dot) else ""
        var index = 1
        while (candidate.exists()) {
            candidate = File(directory, "$base ($index)$ext")
            index++
        }
        return candidate
    }

    /* ------------------------------ 前台通知 ------------------------------ */

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "与手机同步", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
    }

    private fun startAsForeground() {
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("WatchLink")
            .setContentText("正在与手机同步")
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    companion object {
        private const val TAG = "WatchLinkWear"
        private const val CHANNEL_ID = "watchlink_wear"
        private const val NOTIFICATION_ID = 2001
        private const val INCOMING_DIR = "incoming"
        private const val PROGRESS_STEP = 128 * 1024L

        const val ACTION_REFRESH = "com.watchlink.wear.action.REFRESH"
        const val ACTION_COMMAND = "com.watchlink.wear.action.COMMAND"
        const val ACTION_SEND_FILE = "com.watchlink.wear.action.SEND_FILE"
        const val EXTRA_COMMAND = "extra_command"
        const val EXTRA_URI = "extra_uri"

        fun sendCommand(context: android.content.Context, command: String) {
            val intent = Intent(context, WearLinkService::class.java)
                .setAction(ACTION_COMMAND)
                .putExtra(EXTRA_COMMAND, command)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun sendFile(context: android.content.Context, uri: android.net.Uri) {
            val intent = Intent(context, WearLinkService::class.java)
                .setAction(ACTION_SEND_FILE)
                .putExtra(EXTRA_URI, uri.toString())
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun start(context: android.content.Context) {
            val intent = Intent(context, WearLinkService::class.java).setAction(ACTION_REFRESH)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }
    }
}
