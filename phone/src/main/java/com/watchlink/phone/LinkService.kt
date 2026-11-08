package com.watchlink.phone

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.watchlink.shared.CommandCodec
import com.watchlink.shared.FileHeader
import com.watchlink.shared.MediaState
import com.watchlink.shared.Protocol
import com.watchlink.shared.TransferDirection
import com.watchlink.shared.TransferProgress
import com.watchlink.shared.TransferState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 常驻服务：采集手机状态/通知/媒体 → 推给手表；执行手表发来的指令；处理双向文件。
 */
class LinkService : Service(), LanServer.Listener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var collector: StatusCollector
    private lateinit var server: LanServer
    private lateinit var mediaMonitor: MediaMonitor

    private var discoveryDeadline = 0L
    private var foregroundShown = false
    private var discoveryTimer: Job? = null

    override fun onCreate() {
        super.onCreate()
        collector = StatusCollector(this)
        mediaMonitor = MediaMonitor(this)
        server = LanServer(this, this)
        server.start()

        AppState.setServiceRunning(true)
        createChannel()
        openDiscoveryWindow()

        scope.launch { pollingLoop() }
        scope.launch { notificationLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SEND_FILE -> {
                val uri = intent.getStringExtra(EXTRA_URI)?.let(Uri::parse)
                if (uri != null) scope.launch { sendFile(uri) }
            }
            ACTION_REFRESH -> {
                openDiscoveryWindow()
                scope.launch { pushAll() }
            }
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        discoveryTimer?.cancel()
        server.stop()
        AppState.setServiceRunning(false)
        AppState.setWatches(emptyList())
        scope.cancel()
        super.onDestroy()
    }

    /* --------------------------- 发现窗口 / 前台通知 --------------------------- */

    /** 打开 3 分钟"可被手表发现"的窗口，同时把常驻通知放出来。 */
    private fun openDiscoveryWindow() {
        discoveryDeadline = SystemClock.elapsedRealtime() + Protocol.DISCOVERY_WINDOW_MS
        server.startDiscovery()
        showForeground()

        discoveryTimer?.cancel()
        discoveryTimer = scope.launch {
            delay(Protocol.DISCOVERY_WINDOW_MS)
            onDiscoveryWindowClosed()
        }
    }

    /**
     * 窗口到期：停止 UDP 发现；若仍没有手表连着，就把常驻通知收起来（按用户要求）。
     * TCP 监听继续保留，手表的"重连上次 IP"仍然可用。
     */
    private fun onDiscoveryWindowClosed() {
        server.stopDiscovery()
        Log.i(TAG, "发现窗口结束，停止扫描")
        if (server.clientCount == 0) {
            hideForeground()
        }
    }

    private fun showForeground() {
        runOnMain {
            if (foregroundShown) return@runOnMain
            runCatching {
                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else {
                    0
                }
                ServiceCompat.startForeground(this, FOREGROUND_ID, buildNotification(), type)
                foregroundShown = true
            }.onFailure { Log.w(TAG, "前台通知启动失败", it) }
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else Handler(Looper.getMainLooper()).post(block)
    }

    private fun hideForeground() {
        runOnMain {
            if (!foregroundShown) return@runOnMain
            runCatching {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                foregroundShown = false
            }
        }
    }

    /* --------------------------- LanServer.Listener --------------------------- */

    override fun onClientsChanged(names: List<String>) {
        AppState.setWatches(names.map { ConnectedWatch(it, it) })
        if (names.isNotEmpty()) {
            // 手表连上就恢复前台通知（否则刚连上又被系统回收）
            if (foregroundShown) updateNotification()
            else showForeground()
            scope.launch { pushAll() }
        }
    }

    override fun onCommand(raw: String) {
        val name = CommandCodec.name(raw)
        val argument = CommandCodec.argument(raw)
        Log.i(TAG, "收到指令: $name ($argument)")

        when (name) {
            Protocol.CMD_REFRESH -> Unit
            Protocol.CMD_DISMISS -> {
                PhoneNotificationListener.instance?.let { listener ->
                    runCatching { listener.cancelNotification(argument) }
                }
            }
            Protocol.CMD_CLEAR_NOTIFICATIONS -> {
                val keys = AppState.notifications.value.map { it.key }
                PhoneNotificationListener.instance?.let { listener ->
                    keys.forEach { key -> runCatching { listener.cancelNotification(key) } }
                }
            }
            Protocol.CMD_MEDIA_TOGGLE -> mediaMonitor.toggle()
            Protocol.CMD_MEDIA_NEXT -> mediaMonitor.next()
            Protocol.CMD_MEDIA_PREV -> mediaMonitor.previous()
            Protocol.CMD_VOLUME_UP -> mediaMonitor.volumeUp()
            Protocol.CMD_VOLUME_DOWN -> mediaMonitor.volumeDown()
        }

        scope.launch {
            delay(120)
            pushAll()
        }
    }

    override fun onError(message: String) {
        Log.w(TAG, message)
    }

    /* ------------------------------ 同步循环 ------------------------------ */

    private suspend fun pollingLoop() {
        while (scope.isActive) {
            runCatching {
                val snapshot = collectSnapshot()
                AppState.setSnapshot(snapshot)

                val media = mediaMonitor.snapshot()
                AppState.setMedia(media)

                if (server.clientCount > 0) {
                    server.broadcastStatus(snapshot)
                    server.broadcastMedia(media)
                }
            }.onFailure { Log.w(TAG, "同步状态失败", it) }

            delay(Protocol.SYNC_INTERVAL_MS)
        }
    }

    private suspend fun notificationLoop() {
        AppState.notifications.drop(1).collectLatest { items ->
            runCatching {
                if (server.clientCount > 0) {
                    server.broadcastNotifications(items)
                    val snapshot = collectSnapshot()
                    AppState.setSnapshot(snapshot)
                    server.broadcastStatus(snapshot)
                }
            }.onFailure { Log.w(TAG, "同步通知失败", it) }
        }
    }

    private fun collectSnapshot() = collector.collect().copy(
        notificationCount = AppState.notifications.value.size,
        updatedAt = System.currentTimeMillis()
    )

    private fun pushAll() {
        runCatching {
            val snapshot = collectSnapshot()
            AppState.setSnapshot(snapshot)
            server.broadcastStatus(snapshot)
            server.broadcastNotifications(AppState.notifications.value)

            val media = mediaMonitor.snapshot()
            AppState.setMedia(media)
            server.broadcastMedia(media)
        }.onFailure { Log.w(TAG, "pushAll 失败", it) }
    }

    /* ------------------------------ 文件发送 ------------------------------ */

    private suspend fun sendFile(uri: Uri) {
        val (name, size) = queryFileMeta(uri)
        val mime = runCatching { contentResolver.getType(uri) }.getOrNull()
            ?: "application/octet-stream"

        val transferId = UUID.randomUUID().toString()
        var progress = TransferProgress(
            id = transferId,
            fileName = name,
            totalBytes = size,
            transferredBytes = 0L,
            state = TransferState.SENDING,
            direction = TransferDirection.TO_WATCH
        )
        AppState.upsertTransfer(progress)

        if (server.clientCount == 0) {
            AppState.upsertTransfer(
                progress.copy(state = TransferState.FAILED, error = "手表未连接")
            )
            return
        }

        try {
            server.sendFile(
                header = FileHeader(name, size, mime),
                openInputStream = {
                    contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("无法读取所选文件")
                }
            ) { sent ->
                progress = progress.copy(transferredBytes = sent)
                AppState.upsertTransfer(progress)
            }
            AppState.upsertTransfer(
                progress.copy(
                    transferredBytes = if (size > 0) size else progress.transferredBytes,
                    state = TransferState.DONE
                )
            )
        } catch (t: Throwable) {
            Log.w(TAG, "发送文件失败", t)
            AppState.upsertTransfer(
                progress.copy(state = TransferState.FAILED, error = t.message ?: "发送失败")
            )
        }
    }

    private fun queryFileMeta(uri: Uri): Pair<String, Long> {
        var name = "file"
        var size = 0L
        runCatching {
            contentResolver.query(
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
        return com.watchlink.shared.FileCodec.sanitizeName(name) to size
    }

    /* ------------------------------ 通知 ------------------------------ */

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "手机与手表同步", NotificationManager.IMPORTANCE_MIN).apply {
                description = "保持手机状态与通知同步到手表"
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher)
        .setContentTitle("WatchLink")
        .setContentText(
            if (server.clientCount > 0) "已连接手表" else "等待手表连接（3 分钟内可发现）"
        )
        .setOngoing(true)
        .setSilent(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .build()

    private fun updateNotification() {
        if (!foregroundShown) return
        runCatching {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            manager.notify(FOREGROUND_ID, buildNotification())
        }
    }

    companion object {
        private const val TAG = "WatchLinkService"
        private const val CHANNEL_ID = "watchlink_sync"
        private const val FOREGROUND_ID = 1001

        const val ACTION_SEND_FILE = "com.watchlink.phone.action.SEND_FILE"
        const val ACTION_REFRESH = "com.watchlink.phone.action.REFRESH"
        const val ACTION_STOP = "com.watchlink.phone.action.STOP"
        const val EXTRA_URI = "extra_uri"

        fun start(context: Context) {
            val intent = Intent(context, LinkService::class.java).setAction(ACTION_REFRESH)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun sendFile(context: Context, uri: Uri) {
            val intent = Intent(context, LinkService::class.java)
                .setAction(ACTION_SEND_FILE)
                .putExtra(EXTRA_URI, uri.toString())
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }
    }
}

/**
 * 读取并控制手机的媒体播放（依赖已有的通知使用权）。
 */
class MediaMonitor(private val context: Context) {

    private val audioManager: AudioManager? = context.getSystemService(AudioManager::class.java)
    private var controller: MediaController? = null

    fun snapshot(): MediaState {
        val volume = runCatching { audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0 }.getOrDefault(0)
        val maxVolume = runCatching { audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0 }.getOrDefault(0)

        val sessions = runCatching {
            val manager = context.getSystemService(MediaSessionManager::class.java) ?: return@runCatching emptyList()
            manager.getActiveSessions(ComponentName(context, PhoneNotificationListener::class.java))
        }.getOrDefault(emptyList())

        val playing = sessions.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        }
        val target = playing
            ?: sessions.firstOrNull {
                it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.isNotBlank() == true
            }

        controller = target
        if (target == null) {
            return MediaState(active = false, volume = volume, maxVolume = maxVolume)
        }

        val metadata = target.metadata
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        if (title.isBlank()) {
            return MediaState(active = false, volume = volume, maxVolume = maxVolume)
        }
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty()
        val appName = runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(target.packageName, 0)).toString()
        }.getOrDefault(target.packageName)

        return MediaState(
            active = true,
            title = title,
            artist = artist.orEmpty(),
            appName = appName,
            isPlaying = playing != null,
            volume = volume,
            maxVolume = maxVolume
        )
    }

    fun toggle() {
        val current = controller ?: return
        val playing = current.playbackState?.state == PlaybackState.STATE_PLAYING
        runCatching {
            if (playing) current.transportControls.pause() else current.transportControls.play()
        }
    }

    fun next() {
        runCatching { controller?.transportControls?.skipToNext() }
    }

    fun previous() {
        runCatching { controller?.transportControls?.skipToPrevious() }
    }

    fun volumeUp() {
        runCatching { audioManager?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0) }
    }

    fun volumeDown() {
        runCatching { audioManager?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0) }
    }
}
