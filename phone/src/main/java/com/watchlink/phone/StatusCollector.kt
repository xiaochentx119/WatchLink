package com.watchlink.phone

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.watchlink.shared.RingerMode
import com.watchlink.shared.StatusSnapshot

/**
 * 从系统读取手机实时状态：电量、WiFi、响铃模式、免打扰。
 */
class StatusCollector(private val context: Context) {

    @SuppressLint("MissingPermission", "deprecation")
    fun collect(): StatusSnapshot {
        val battery = readBattery()
        val wifi = readWifi()
        val ringer = readRinger()

        return StatusSnapshot(
            batteryPercent = battery.percent,
            charging = battery.charging,
            batteryTemperatureC = battery.temperatureC,
            wifiEnabled = wifi.enabled,
            wifiConnected = wifi.connected,
            wifiSsid = wifi.ssid,
            ringerMode = ringer.mode,
            doNotDisturb = ringer.doNotDisturb,
            notificationCount = 0,
            updatedAt = System.currentTimeMillis(),
            phoneName = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        )
    }

    /* ------------------------------- 电量 ------------------------------- */

    private data class BatteryInfo(val percent: Int, val charging: Boolean, val temperatureC: Float)

    private fun readBattery(): BatteryInfo {
        val intent: Intent? = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) {
            (level * 100f / scale).toInt().coerceIn(0, 100)
        } else {
            val bm = context.getSystemService(BatteryManager::class.java)
            (bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0).coerceIn(0, 100)
        }

        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plug = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL ||
            plug != 0

        val tempRaw = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        return BatteryInfo(percent, charging, tempRaw / 10f)
    }

    /* ------------------------------- WiFi ------------------------------- */

    private data class WifiInfo(val enabled: Boolean, val connected: Boolean, val ssid: String?)

    @SuppressLint("MissingPermission", "deprecation")
    private fun readWifi(): WifiInfo {
        val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)

        val enabled = runCatching { wifiManager?.isWifiEnabled == true }.getOrDefault(false)

        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity?.let { cm ->
            cm.getNetworkCapabilities(cm.activeNetwork)
        }
        val connected = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

        val ssid = runCatching {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return@runCatching null
            val raw = wifiManager?.connectionInfo?.ssid?.trim('"')
            raw?.takeIf { it.isNotBlank() && it != "<unknown>" }
        }.getOrNull()

        return WifiInfo(enabled, connected, ssid)
    }

    /* ---------------------------- 铃声 / 免打扰 ---------------------------- */

    private data class RingerInfo(val mode: RingerMode, val doNotDisturb: Boolean)

    private fun readRinger(): RingerInfo {
        val audio = context.getSystemService(AudioManager::class.java)
        val mode = when (audio?.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> RingerMode.SILENT
            AudioManager.RINGER_MODE_VIBRATE -> RingerMode.VIBRATE
            else -> RingerMode.NORMAL
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        val filter = nm?.currentInterruptionFilter ?: NotificationManager.INTERRUPTION_FILTER_ALL

        // 部分机型不给读 currentInterruptionFilter，用系统 zen_mode（0 = 关闭）兜底
        val zenMode = runCatching {
            android.provider.Settings.Global.getInt(context.contentResolver, "zen_mode", 0)
        }.getOrDefault(0)

        val doNotDisturb = filter != NotificationManager.INTERRUPTION_FILTER_ALL || zenMode != 0

        return RingerInfo(mode, doNotDisturb)
    }
}
