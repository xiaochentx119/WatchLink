package com.watchlink.shared

/**
 * 手机当前的响铃模式。
 * 手表上直接显示中文标签。
 */
enum class RingerMode(val label: String) {
    SILENT("静音"),
    VIBRATE("震动"),
    NORMAL("响铃");

    companion object {
        fun fromName(name: String?): RingerMode =
            entries.firstOrNull { it.name == name } ?: NORMAL
    }
}

/**
 * 手机实时状态快照，手机采集后整包发给手表。
 */
data class StatusSnapshot(
    val batteryPercent: Int = 0,
    val charging: Boolean = false,
    val batteryTemperatureC: Float = 0f,
    val wifiEnabled: Boolean = false,
    val wifiConnected: Boolean = false,
    val wifiSsid: String? = null,
    val ringerMode: RingerMode = RingerMode.NORMAL,
    val doNotDisturb: Boolean = false,
    val notificationCount: Int = 0,
    val updatedAt: Long = 0L,
    val phoneName: String = ""
) {
    val hasNotification: Boolean get() = notificationCount > 0
}

/**
 * 手机上的一条通知摘要，用于手表的"通知中心"。
 */
data class WatchNotification(
    val key: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val timestamp: Long,
    val ongoing: Boolean = false
) {
    val displayTitle: String get() = title.ifBlank { appLabel }
}

/**
 * 手机当前的媒体播放状态。
 */
data class MediaState(
    val active: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val appName: String = "",
    val isPlaying: Boolean = false,
    val volume: Int = 0,
    val maxVolume: Int = 0
) {
    val volumePercent: Int
        get() = if (maxVolume <= 0) 0 else (volume * 100 / maxVolume)
}

/**
 * 手表本地的已接收文件。
 */
data class ReceivedFile(
    val name: String,
    val size: Long,
    val path: String,
    val receivedAt: Long,
    val sender: String = "",
    val mimeType: String = ""
)

enum class TransferDirection(val label: String) {
    TO_WATCH("手机 → 手表"),
    TO_PHONE("手表 → 手机")
}

/**
 * 文件传输进度（手机发送 / 手表接收 / 手表发送 / 手机接收都用它展示）。
 */
data class TransferProgress(
    val id: String,
    val fileName: String,
    val totalBytes: Long,
    val transferredBytes: Long = 0L,
    val state: TransferState = TransferState.SENDING,
    val error: String? = null,
    val direction: TransferDirection = TransferDirection.TO_WATCH
) {
    val fraction: Float
        get() = if (totalBytes <= 0L) 0f else (transferredBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
}

enum class TransferState(val label: String) {
    SENDING("发送中"),
    RECEIVING("接收中"),
    DONE("已完成"),
    FAILED("失败")
}
