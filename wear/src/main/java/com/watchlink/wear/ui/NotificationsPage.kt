package com.watchlink.wear.ui

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.watchlink.shared.MediaState
import com.watchlink.shared.WatchNotification
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 第二页：正在播放的媒体 + 通知中心 */
class NotificationsPage(
    context: Context,
    private val onDismiss: (String) -> Unit,
    private val onClearAll: () -> Unit,
    private val onMediaToggle: () -> Unit,
    private val onMediaNext: () -> Unit,
    private val onMediaPrev: () -> Unit,
    private val onVolumeUp: () -> Unit,
    private val onVolumeDown: () -> Unit
) {

    private val ctx = context
    private var lastSignature: String = ""

    private val title: TextView = Ui.text(context, 12f, Ui.DIM).apply {
        gravity = Gravity.CENTER
        text = "通知"
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
                addView(list)
            },
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    fun render(media: MediaState, items: List<WatchNotification>) {
        val signature = buildString {
            append(media.active).append(':').append(media.title).append(':')
            append(media.isPlaying).append(':').append(media.volumePercent).append('|')
            items.forEach { append(it.key).append(it.timestamp) }
        }
        if (signature == lastSignature) return
        lastSignature = signature

        list.removeAllViews()

        if (media.active) {
            list.addView(mediaCard(media))
        }

        title.text = if (items.isEmpty()) "通知" else "通知 · ${items.size}"
        list.addView(title)

        if (items.isEmpty()) {
            list.addView(emptyState())
            return
        }

        list.addView(clearAllRow())
        items.take(30).forEach { item -> list.addView(notificationCard(item)) }
    }

    /* --------------------------------- 媒体卡 --------------------------------- */

    private fun mediaCard(media: MediaState): LinearLayout {
        val card = Ui.card(ctx, 16f)

        card.addView(
            Ui.text(ctx, 8f, Ui.ACCENT).apply {
                text = if (media.isPlaying) "正在播放" else "已暂停"
            }
        )

        card.addView(
            Ui.text(ctx, 12f, Ui.TEXT, bold = true).apply {
                text = media.title
                maxLines = 2
                isSingleLine = false
                ellipsize = TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Ui.dp(ctx, 2f) }
        )

        val subtitle = listOf(media.artist, media.appName).filter { it.isNotBlank() }.joinToString(" · ")
        if (subtitle.isNotBlank()) {
            card.addView(
                Ui.text(ctx, 8.4f, Ui.DIM).apply {
                    text = subtitle
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = Ui.dp(ctx, 2f) }
            )
        }

        val controls = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        controls.addView(controlButton("上一首") { onMediaPrev() }, weightParams(1f))
        controls.addView(
            controlButton(if (media.isPlaying) "暂停" else "播放") { onMediaToggle() },
            weightParams(1.3f).apply { marginStart = Ui.dp(ctx, 4f); marginEnd = Ui.dp(ctx, 4f) }
        )
        controls.addView(controlButton("下一首") { onMediaNext() }, weightParams(1f))
        card.addView(controls, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = Ui.dp(ctx, 7f) })

        val volumeRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        volumeRow.addView(controlButton("−", widthDp = 26f) { onVolumeDown() })
        volumeRow.addView(
            Ui.text(ctx, 8.4f, Ui.DIM).apply {
                text = "音量 ${media.volumePercent}%"
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f }
        )
        volumeRow.addView(controlButton("+", widthDp = 26f) { onVolumeUp() })
        card.addView(volumeRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = Ui.dp(ctx, 5f) })

        card.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = Ui.dp(ctx, 5f) }

        return card
    }

    private fun weightParams(weight: Float) =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { this.weight = weight }

    private fun controlButton(
        label: String,
        widthDp: Float = 0f,
        onClick: () -> Unit
    ): TextView = Ui.text(ctx, 9f, Ui.TEXT, bold = true).apply {
        text = label
        gravity = Gravity.CENTER
        background = Ui.rounded(ctx, Ui.SURFACE_ALT, 10f)
        setPadding(Ui.dp(ctx, 6f), Ui.dp(ctx, 6f), Ui.dp(ctx, 6f), Ui.dp(ctx, 6f))
        isClickable = true
        setOnClickListener { onClick() }
        if (widthDp > 0f) {
            layoutParams = LinearLayout.LayoutParams(Ui.dp(ctx, widthDp), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    /* --------------------------------- 通知 --------------------------------- */

    private fun clearAllRow(): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        row.addView(
            Ui.text(ctx, 8f, Ui.ACCENT).apply {
                text = "清除全部"
                setPadding(Ui.dp(ctx, 8f), Ui.dp(ctx, 4f), Ui.dp(ctx, 8f), Ui.dp(ctx, 4f))
                background = Ui.rounded(ctx, Ui.SURFACE, 9f)
                isClickable = true
                setOnClickListener { onClearAll() }
            }
        )
        row.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = Ui.dp(ctx, 2f)
            bottomMargin = Ui.dp(ctx, 2f)
        }
        return row
    }

    private fun notificationCard(item: WatchNotification): LinearLayout {
        val card = Ui.card(ctx, 15f)

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val badge = Ui.text(ctx, 8.4f, Ui.BLUE, bold = true).apply {
            text = item.appLabel.take(1)
            gravity = Gravity.CENTER
            background = Ui.rounded(ctx, Ui.SURFACE_ALT, 4f)
        }
        header.addView(badge, LinearLayout.LayoutParams(Ui.dp(ctx, 15f), Ui.dp(ctx, 15f)))
        header.addView(
            Ui.text(ctx, 9.2f, Ui.DIM).apply {
                text = item.appLabel
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f }
        )
        header.addView(Ui.text(ctx, 8.4f, Ui.DIM).apply { text = clock(item.timestamp) })
        card.addView(header)

        if (item.displayTitle.isNotBlank()) {
            card.addView(
                Ui.text(ctx, 12f, Ui.TEXT, bold = true).apply {
                    text = item.displayTitle
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = Ui.dp(ctx, 3f) }
            )
        }

        if (item.text.isNotBlank()) {
            card.addView(
                Ui.text(ctx, 10f, Ui.DIM).apply {
                    text = item.text
                    maxLines = 2
                    isSingleLine = false
                    ellipsize = TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = Ui.dp(ctx, 2f) }
            )
        }

        val actionRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        actionRow.addView(
            Ui.text(ctx, 8f, Ui.ACCENT).apply {
                text = "清除"
                setPadding(Ui.dp(ctx, 6f), Ui.dp(ctx, 2f), Ui.dp(ctx, 6f), Ui.dp(ctx, 2f))
                isClickable = true
                setOnClickListener { onDismiss(item.key) }
            }
        )
        card.addView(actionRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = Ui.dp(ctx, 2f) })

        card.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = Ui.dp(ctx, 4f) }

        return card
    }

    private fun emptyState(): LinearLayout {
        val card = Ui.card(ctx, 16f)
        card.gravity = Gravity.CENTER
        card.addView(
            Ui.text(ctx, 14f, Ui.DIM, bold = true).apply {
                text = "无通知"
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(ctx, 44f)
            )
        )
        return card
    }

    private fun clock(timestamp: Long): String {
        if (timestamp <= 0L) return "--:--"
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
    }
}
