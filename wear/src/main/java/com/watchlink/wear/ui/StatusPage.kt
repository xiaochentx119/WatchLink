package com.watchlink.wear.ui

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.watchlink.shared.RingerMode
import com.watchlink.shared.StatusSnapshot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 第一页：实时状态 */
class StatusPage(context: Context, private val onReconnect: () -> Unit) {

    private val ctx = context
    private val ring = BatteryRingView(context)

    private val wifiValue: TextView
    private val ringerValue: TextView
    private val notifValue: TextView
    private val footer: TextView
    private val chip: TextView

    val root: ScrollView = ScrollView(context).apply {
        isFillViewport = true
        clipToPadding = false
        setPadding(
            Ui.dp(context, 12f), Ui.dp(context, 10f),
            Ui.dp(context, 12f), Ui.dp(context, 16f)
        )
        addView(
            Ui.pageLayout(context),
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private val column = root.getChildAt(0) as LinearLayout

    init {
        column.addView(
            Ui.text(ctx, 12f, Ui.DIM).apply {
                text = "实时状态"
                gravity = Gravity.CENTER
            }
        )

        column.addView(ring, LinearLayout.LayoutParams(size(84f), size(84f)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = Ui.dp(ctx, 6f)
        })

        wifiValue = addRow("WiFi", topMargin = 10f)
        ringerValue = addRow("铃声")
        notifValue = addRow("通知")

        footer = Ui.text(ctx, 9.2f, Ui.DIM).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Ui.dp(ctx, 8f) }
        }
        column.addView(footer)

        chip = Ui.text(ctx, 9.2f, Ui.ACCENT).apply {
            gravity = Gravity.CENTER
            setPadding(Ui.dp(ctx, 10f), Ui.dp(ctx, 5f), Ui.dp(ctx, 10f), Ui.dp(ctx, 5f))
            background = Ui.rounded(ctx, Ui.SURFACE, 10f)
            setOnClickListener { onReconnect() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = Ui.dp(ctx, 8f)
            }
        }
        column.addView(chip)
    }

    private fun size(dp: Float) = Ui.dp(ctx, dp)

    private fun addRow(label: String, topMargin: Float = 5f): TextView {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(ctx, Ui.SURFACE, 13f)
            setPadding(Ui.dp(ctx, 11f), Ui.dp(ctx, 7f), Ui.dp(ctx, 11f), Ui.dp(ctx, 7f))
        }
        val labelView = Ui.text(ctx, 10f, Ui.DIM).apply { text = label }
        val valueView = Ui.text(ctx, 11.6f, Ui.TEXT, bold = true).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        row.addView(labelView)
        row.addView(Ui.spacer(ctx, weight = 1f))
        row.addView(
            valueView,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1.4f }
        )
        column.addView(
            row,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { this.topMargin = Ui.dp(ctx, topMargin) }
        )
        return valueView
    }

    fun render(
        snapshot: StatusSnapshot?,
        notificationCount: Int,
        connected: Boolean,
        phoneName: String
    ) {
        ring.percent = snapshot?.batteryPercent ?: -1
        ring.charging = snapshot?.charging == true

        wifiValue.text = when {
            snapshot == null -> "--"
            snapshot.wifiConnected -> snapshot.wifiSsid ?: "已连接"
            snapshot.wifiEnabled -> "已开启"
            else -> "已关闭"
        }
        wifiValue.setTextColor(if (snapshot?.wifiEnabled == true) Ui.ACCENT else Ui.DIM)

        ringerValue.text = snapshot?.ringerMode?.label ?: "--"
        ringerValue.setTextColor(
            when (snapshot?.ringerMode) {
                RingerMode.NORMAL -> Ui.ACCENT
                RingerMode.VIBRATE -> Ui.BLUE
                RingerMode.SILENT -> Ui.AMBER
                null -> Ui.DIM
            }
        )

        notifValue.text = if (notificationCount <= 0) "无" else "$notificationCount 条"
        notifValue.setTextColor(if (notificationCount > 0) Ui.BLUE else Ui.DIM)

        footer.text = when {
            snapshot == null -> "等待手机同步…"
            snapshot.doNotDisturb -> "免打扰开 · 更新 ${clock(snapshot.updatedAt)}"
            else -> "更新 ${clock(snapshot.updatedAt)}"
        }

        chip.text = when {
            connected && phoneName.isNotBlank() -> "● 已连接 $phoneName"
            connected -> "● 已连接手机"
            else -> "○ 未连接 · 点此重试"
        }
        chip.setTextColor(if (connected) Ui.ACCENT else Ui.AMBER)
    }

    private fun clock(timestamp: Long): String {
        if (timestamp <= 0L) return "--:--"
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
    }
}
