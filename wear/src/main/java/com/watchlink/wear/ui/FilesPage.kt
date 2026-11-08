package com.watchlink.wear.ui

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.watchlink.shared.FileCodec
import com.watchlink.shared.ReceivedFile
import com.watchlink.shared.TransferProgress
import com.watchlink.shared.TransferState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 第三页：文件（点按打开 / 安装，长按删除） */
class FilesPage(
    context: Context,
    private val onOpen: (ReceivedFile) -> Unit,
    private val onDelete: (ReceivedFile) -> Unit,
    private val onPickFile: () -> Unit,
    private val pathOf: (ReceivedFile) -> String
) {

    private class TransferRow(
        val id: String,
        val bar: ProgressBar,
        val detail: TextView
    )

    private val ctx = context
    private var lastSignature: String = ""
    private val transferRows = ArrayList<TransferRow>()

    private val title: TextView = Ui.text(context, 12f, Ui.DIM).apply {
        gravity = Gravity.CENTER
        text = "文件"
    }

    private val sendButton: TextView = Ui.text(context, 10f, Ui.ACCENT, bold = true).apply {
        text = "发送文件到手机"
        gravity = Gravity.CENTER
        background = Ui.rounded(context, Ui.SURFACE, 12f)
        setPadding(Ui.dp(context, 10f), Ui.dp(context, 8f), Ui.dp(context, 10f), Ui.dp(context, 8f))
        isClickable = true
        setOnClickListener { onPickFile() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = Ui.dp(context, 6f); bottomMargin = Ui.dp(context, 2f) }
    }

    private val list = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }

    val root: ScrollView = ScrollView(context).apply {
        isFillViewport = true
        clipToPadding = false
        setPadding(
            Ui.dp(context, 10f), Ui.dp(context, 10f),
            Ui.dp(context, 10f), Ui.dp(context, 16f)
        )
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(title)
                addView(sendButton)
                addView(list)
            },
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    fun render(
        transfers: List<TransferProgress>,
        files: List<ReceivedFile>,
        phoneName: String
    ) {
        val active = transfers.filter {
            it.state == TransferState.RECEIVING || it.state == TransferState.SENDING
        }

        val signature = buildString {
            append(active.joinToString(",") { it.id })
            append('|')
            append(files.joinToString(",") { it.path + it.receivedAt })
            append('|')
            append(phoneName)
        }

        if (signature == lastSignature) {
            // 结构没变：只刷新进度，避免整页重建造成卡顿
            for (transfer in active) {
                val row = transferRows.firstOrNull { it.id == transfer.id } ?: continue
                row.bar.progress = (transfer.fraction * 100).toInt()
                row.detail.text = progressText(transfer)
            }
            return
        }

        lastSignature = signature
        transferRows.clear()
        list.removeAllViews()

        title.text = if (files.isEmpty()) "文件" else "文件 · ${files.size}"

        for (transfer in active) {
            list.addView(transferCard(transfer, phoneName))
        }

        if (files.isEmpty() && active.isEmpty()) {
            list.addView(emptyCard())
            return
        }

        for (file in files) {
            list.addView(
                fileCard(file, phoneName),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = Ui.dp(ctx, 5f) }
            )
        }
    }

    private fun progressText(transfer: TransferProgress): String {
        val action = if (transfer.direction == com.watchlink.shared.TransferDirection.TO_PHONE) "发送中" else "接收中"
        return "$action ${(transfer.fraction * 100).toInt()}% · ${FileCodec.formatSize(transfer.transferredBytes)}"
    }

    private fun transferCard(transfer: TransferProgress, phoneName: String): LinearLayout {
        val card = Ui.card(ctx, 15f)

        card.addView(
            Ui.text(ctx, 11f, Ui.TEXT).apply {
                text = transfer.fileName
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
        )

        val bar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = (transfer.fraction * 100).toInt()
            progressTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Ui.TRACK)
        }
        card.addView(
            bar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(ctx, 5f)
            ).apply { topMargin = Ui.dp(ctx, 5f) }
        )

        val detail = Ui.text(ctx, 8.4f, Ui.DIM).apply { text = progressText(transfer) }
        card.addView(
            detail,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Ui.dp(ctx, 3f) }
        )

        card.addView(
            Ui.text(ctx, 7.6f, Ui.FAINT).apply {
                text = if (transfer.direction == com.watchlink.shared.TransferDirection.TO_PHONE) {
                    "发往 ${phoneName.ifBlank { "手机" }}"
                } else {
                    "来自 ${phoneName.ifBlank { "手机" }}"
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Ui.dp(ctx, 1f) }
        )

        transferRows.add(TransferRow(transfer.id, bar, detail))
        return card
    }

    private fun fileCard(file: ReceivedFile, phoneName: String): LinearLayout {
        val card = Ui.card(ctx, 15f)
        card.isClickable = true
        card.setOnClickListener { onOpen(file) }
        card.setOnLongClickListener {
            onDelete(file)
            true
        }

        card.addView(
            Ui.text(ctx, 11.2f, Ui.TEXT, bold = true).apply {
                text = file.name
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                isSingleLine = false
            }
        )

        val sender = file.sender.ifBlank { phoneName }.ifBlank { "手机" }
        card.addView(
            Ui.text(ctx, 8.4f, Ui.DIM).apply {
                text = "来自 $sender · ${FileCodec.formatSize(file.size)} · ${clock(file.receivedAt)}"
                isSingleLine = false
                maxLines = 2
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Ui.dp(ctx, 3f) }
        )

        card.addView(
            Ui.text(ctx, 7.6f, Ui.FAINT).apply {
                text = "保存在 ${pathOf(file)}"
                isSingleLine = false
                maxLines = 2
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Ui.dp(ctx, 2f) }
        )

        card.addView(
            Ui.text(ctx, 7.6f, Ui.ACCENT).apply {
                text = "点按打开 · 长按删除"
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Ui.dp(ctx, 3f) }
        )
        return card
    }

    private fun emptyCard(): LinearLayout {
        val card = Ui.card(ctx, 15f)
        card.gravity = Gravity.CENTER
        card.addView(
            Ui.text(ctx, 12f, Ui.TEXT).apply {
                text = "还没有收到文件"
                gravity = Gravity.CENTER
            }
        )
        card.addView(
            Ui.text(ctx, 8.8f, Ui.DIM).apply {
                text = "在手机 App 里点「选择文件并发送」"
                gravity = Gravity.CENTER
                isSingleLine = false
                maxLines = 2
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Ui.dp(ctx, 4f) }
        )
        return card
    }

    private fun clock(timestamp: Long): String {
        if (timestamp <= 0L) return "--:--"
        return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
    }
}
