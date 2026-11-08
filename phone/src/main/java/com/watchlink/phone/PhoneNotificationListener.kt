package com.watchlink.phone

import android.app.Notification
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.watchlink.shared.Protocol
import com.watchlink.shared.WatchNotification

/**
 * 读取手机上的通知（状态栏通知），同步给手表。
 * 需要用户在"设置 → 通知使用权"里手动授权。
 */
class PhoneNotificationListener : NotificationListenerService() {

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onListenerConnected() {
        Log.i(TAG, "通知监听已连接")
        AppState.setListenerGranted(true)
        refresh()
    }

    override fun onListenerDisconnected() {
        Log.i(TAG, "通知监听已断开")
        AppState.setListenerGranted(false)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        refresh()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        refresh()
    }

    private fun refresh() {
        val active = try {
            activeNotifications
        } catch (t: Throwable) {
            Log.w(TAG, "读取 activeNotifications 失败", t)
            null
        } ?: return

        val items = active
            .mapNotNull { toModel(it) }
            .sortedByDescending { it.timestamp }
            .take(Protocol.MAX_NOTIFICATIONS)

        AppState.setNotifications(items)
    }

    private fun toModel(sbn: StatusBarNotification): WatchNotification? {
        // 忽略自己弹出的前台服务通知，避免回环
        if (sbn.packageName == packageName) return null

        val notification = sbn.notification ?: return null
        // 忽略"群组摘要"，否则同一拨消息会重复一遍
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null

        val extras: Bundle = notification.extras ?: Bundle()
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val text = (
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)
            )?.toString().orEmpty().trim()

        if (title.isBlank() && text.isBlank()) return null

        return WatchNotification(
            key = sbn.key ?: "${sbn.packageName}#${sbn.id}",
            appLabel = resolveAppLabel(sbn.packageName),
            title = title,
            text = text,
            timestamp = sbn.postTime,
            ongoing = sbn.isOngoing
        )
    }

    private fun resolveAppLabel(pkg: String): String = runCatching {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    companion object {
        private const val TAG = "WatchLinkNotify"

        /** 供同步服务调用 cancelNotification() 清除手机上的通知。 */
        @Volatile
        var instance: PhoneNotificationListener? = null
            private set
    }
}
