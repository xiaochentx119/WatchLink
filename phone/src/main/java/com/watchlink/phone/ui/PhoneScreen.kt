package com.watchlink.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.watchlink.phone.AppState
import com.watchlink.phone.ConnectedWatch
import com.watchlink.phone.NetworkUtils
import com.watchlink.shared.Protocol
import com.watchlink.shared.RingerMode
import com.watchlink.shared.StatusSnapshot
import com.watchlink.shared.TransferProgress
import com.watchlink.shared.TransferState
import com.watchlink.shared.WatchNotification
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PhoneScreen(
    onPickFile: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onRefresh: () -> Unit
) {
    val snapshot by AppState.snapshot.collectAsStateWithLifecycle()
    val notifications by AppState.notifications.collectAsStateWithLifecycle()
    val watches by AppState.watches.collectAsStateWithLifecycle()
    val transfers by AppState.transfers.collectAsStateWithLifecycle()
    val receivedFiles by AppState.receivedFiles.collectAsStateWithLifecycle()
    val listenerGranted by AppState.listenerGranted.collectAsStateWithLifecycle()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { Header() }
            item { ConnectionCard(watches = watches, onRefresh = onRefresh) }
            item { BatteryCard() }
            if (!listenerGranted) {
                item { PermissionCard(onOpenNotificationAccess = onOpenNotificationAccess) }
            }
            item { StatusCard(snapshot = snapshot) }
            item { NotificationsCard(notifications = notifications) }
            item { FileCard(transfers = transfers, receivedFiles = receivedFiles, onPickFile = onPickFile) }
        }
    }
}

/* ------------------------------------ 顶部 ------------------------------------ */

@Composable
private fun Header() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(WatchGreen),
            contentAlignment = Alignment.Center
        ) {
            Text("W", color = Color(0xFF04130C), fontWeight = FontWeight.Black, fontSize = 22.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                "WatchLink",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Text(
                "手机 → 手表 · 实时同步",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
    }
}

/* --------------------------------- 连接状态卡 --------------------------------- */

@Composable
private fun ConnectionCard(watches: List<ConnectedWatch>, onRefresh: () -> Unit) {
    val connected = watches.isNotEmpty()
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Surface1)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (connected) WatchGreen else WatchRed)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (connected) "已连接手表" else "等待手表连接",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Text(
                    if (connected) {
                        watches.joinToString("、") { it.name }
                    } else {
                        val ip = NetworkUtils.localIpv4() ?: "未连接 WiFi"
                        "手机 $ip · 端口 ${Protocol.TCP_PORT}，手表需连同一 WiFi（3 分钟内可发现）"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
            TextButton(onClick = onRefresh) { Text("重新发现", color = WatchGreen) }
        }
    }
}

/* --------------------------------- 后台常驻 --------------------------------- */

@Composable
private fun BatteryCard() {
    val context = LocalContext.current
    val ignoring = remember {
        runCatching {
            context.getSystemService(PowerManager::class.java)
                ?.isIgnoringBatteryOptimizations(context.packageName) == true
        }.getOrDefault(false)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Surface1)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            SectionTitle("后台常驻", if (ignoring) "已白名单" else "未白名单")
            Spacer(Modifier.height(8.dp))
            Text(
                if (ignoring) {
                    "已加入电池优化白名单，手表打开 App 时通常能直接连上。"
                } else {
                    "未加入白名单时，息屏一段时间后系统可能回收后台，手表就连不上了。建议点下面的按钮允许后台运行。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "手机开屏/打开本 App 会重新开启 3 分钟「可被发现」窗口；窗口结束后停止扫描、通知栏提示也会收起。",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
            if (!ignoring) {
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:${context.packageName}")
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = WatchBlue,
                        contentColor = Color(0xFF061225)
                    )
                ) {
                    Text("允许后台运行", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/* --------------------------------- 权限提示 --------------------------------- */

@Composable
private fun PermissionCard(onOpenNotificationAccess: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2113))
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                "还差一步：通知使用权",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = WatchAmber
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "没有这项授权，手表收不到通知内容和通知数量。授权后状态会自动恢复。",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onOpenNotificationAccess,
                colors = ButtonDefaults.buttonColors(
                    containerColor = WatchAmber,
                    contentColor = Color(0xFF241802)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("去授权", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/* --------------------------------- 实时状态 --------------------------------- */

@Composable
private fun StatusCard(snapshot: StatusSnapshot) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Surface1)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            SectionTitle("实时状态", "已同步到手表")

            Spacer(Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "${snapshot.batteryPercent}",
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Bold,
                    color = batteryColor(snapshot.batteryPercent)
                )
                Text(
                    "%",
                    fontSize = 20.sp,
                    color = batteryColor(snapshot.batteryPercent),
                    modifier = Modifier.padding(bottom = 8.dp, start = 2.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.padding(bottom = 8.dp)) {
                    Text(
                        if (snapshot.charging) "充电中" else "电池",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary
                    )
                    if (snapshot.batteryTemperatureC > 0f) {
                        Text(
                            "温度 ${"%.1f".format(snapshot.batteryTemperatureC)}°C",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            ProgressBar(
                fraction = snapshot.batteryPercent / 100f,
                color = batteryColor(snapshot.batteryPercent)
            )

            Spacer(Modifier.height(18.dp))
            HorizontalDivider(color = Surface2)
            Spacer(Modifier.height(14.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                InfoTile(
                    modifier = Modifier.weight(1f),
                    label = "WiFi",
                    value = if (snapshot.wifiEnabled) "已开启" else "已关闭",
                    accent = if (snapshot.wifiEnabled) WatchGreen else TextSecondary,
                    hint = when {
                        snapshot.wifiConnected && snapshot.wifiSsid != null -> snapshot.wifiSsid ?: ""
                        snapshot.wifiConnected -> "已连接"
                        snapshot.wifiEnabled -> "未连接"
                        else -> "关闭"
                    }
                )
                InfoTile(
                    modifier = Modifier.weight(1f),
                    label = "铃声",
                    value = snapshot.ringerMode.label,
                    accent = ringerColor(snapshot.ringerMode),
                    hint = if (snapshot.doNotDisturb) "免打扰开启" else "免打扰关闭"
                )
                InfoTile(
                    modifier = Modifier.weight(1f),
                    label = "通知",
                    value = "${snapshot.notificationCount} 条",
                    accent = if (snapshot.hasNotification) WatchBlue else TextSecondary,
                    hint = "最近 ${formatTime(snapshot.updatedAt)}"
                )
            }
        }
    }
}

@Composable
private fun InfoTile(
    modifier: Modifier,
    label: String,
    value: String,
    accent: Color,
    hint: String
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Surface2)
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
        Spacer(Modifier.height(4.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = accent)
        Spacer(Modifier.height(2.dp))
        Text(
            hint,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            maxLines = 1
        )
    }
}

/* --------------------------------- 通知列表 --------------------------------- */

@Composable
private fun NotificationsCard(notifications: List<WatchNotification>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Surface1)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            SectionTitle("通知", if (notifications.isEmpty()) "无" else "${notifications.size} 条")

            Spacer(Modifier.height(12.dp))

            if (notifications.isEmpty()) {
                Text(
                    "当前没有通知，或有通知但还没授予通知使用权。",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            } else {
                notifications.take(6).forEachIndexed { index, item ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 10.dp),
                            color = Surface2
                        )
                    }
                    NotificationRow(item)
                }
                if (notifications.size > 6) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "手表上可查看全部 ${notifications.size} 条",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun NotificationRow(item: WatchNotification) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Surface2),
            contentAlignment = Alignment.Center
        ) {
            Text(
                item.appLabel.take(1),
                color = WatchBlue,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.appLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    formatTime(item.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
            if (item.title.isNotBlank()) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
            if (item.text.isNotBlank()) {
                Text(
                    item.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 2
                )
            }
        }
    }
}

/* --------------------------------- 文件传输 --------------------------------- */

@Composable
private fun FileCard(
    transfers: List<TransferProgress>,
    receivedFiles: List<com.watchlink.shared.ReceivedFile>,
    onPickFile: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Surface1)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            SectionTitle("文件传输", "双向")

            Spacer(Modifier.height(12.dp))
            Text(
                "手机选文件 → 传到手表；手表上的「文件」页也能选文件发回手机（存到 下载/WatchLink）。",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = onPickFile,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = WatchGreen,
                    contentColor = Color(0xFF04130C)
                )
            ) {
                Text("选择文件并发送到手表", fontWeight = FontWeight.SemiBold)
            }

            if (transfers.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                transfers.take(5).forEach { transfer ->
                    TransferRow(transfer)
                    Spacer(Modifier.height(10.dp))
                }
            }

            if (receivedFiles.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = Surface2)
                Spacer(Modifier.height(10.dp))
                Text(
                    "手表发来的文件",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
                Spacer(Modifier.height(6.dp))
                receivedFiles.take(5).forEach { file ->
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        Text(
                            file.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextPrimary,
                            maxLines = 1
                        )
                        Text(
                            "${file.path} · ${formatSize(file.size)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            maxLines = 2
                        )
                    }
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String = com.watchlink.shared.FileCodec.formatSize(bytes)

@Composable
private fun TransferRow(transfer: TransferProgress) {
    val color = when (transfer.state) {
        TransferState.DONE -> WatchGreen
        TransferState.FAILED -> WatchRed
        else -> WatchBlue
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                transfer.fileName,
                style = MaterialTheme.typography.bodySmall,
                color = TextPrimary,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                when (transfer.state) {
                    TransferState.DONE -> "已完成"
                    TransferState.FAILED -> transfer.error ?: "失败"
                    else -> "${(transfer.fraction * 100).toInt()}%"
                },
                style = MaterialTheme.typography.labelSmall,
                color = color
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            "${transfer.direction.label} · ${formatSize(transfer.transferredBytes)}",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )
        Spacer(Modifier.height(6.dp))
        ProgressBar(fraction = transfer.fraction, color = color, height = 6.dp)
    }
}

/* ----------------------------------- 通用 ----------------------------------- */

@Composable
private fun SectionTitle(title: String, trailing: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )
        Spacer(Modifier.weight(1f))
        Text(trailing, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
    }
}

@Composable
private fun ProgressBar(fraction: Float, color: Color, height: Dp = 8.dp) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(Surface2)
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .clip(RoundedCornerShape(height / 2))
                .background(color)
        )
    }
}

private fun batteryColor(percent: Int): Color = when {
    percent <= 15 -> WatchRed
    percent <= 30 -> WatchAmber
    else -> WatchGreen
}

private fun ringerColor(mode: RingerMode): Color = when (mode) {
    RingerMode.NORMAL -> WatchGreen
    RingerMode.VIBRATE -> WatchBlue
    RingerMode.SILENT -> WatchAmber
}

private fun formatTime(timestamp: Long): String {
    if (timestamp <= 0L) return "--:--"
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
}
