package com.watchlink.wear

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import com.watchlink.shared.CommandCodec
import com.watchlink.shared.Protocol
import com.watchlink.shared.ReceivedFile
import com.watchlink.wear.ui.FilesPage
import com.watchlink.wear.ui.NotificationsPage
import com.watchlink.wear.ui.StatusPage
import com.watchlink.wear.ui.SwipePager
import com.watchlink.wear.ui.Ui
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : Activity() {

    private lateinit var pager: SwipePager
    private lateinit var pageDots: LinearLayout
    private lateinit var dotViews: List<View>
    private lateinit var statusPage: StatusPage
    private lateinit var notificationsPage: NotificationsPage
    private lateinit var filesPage: FilesPage

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var uiJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        WearLinkService.start(this)
    }

    override fun onStart() {
        super.onStart()
        renderAll()
        uiJob = scope.launch {
            launch { WearState.snapshot.collect { renderStatus() } }
            launch { WearState.notifications.collect { renderStatus() } }
            launch { WearState.media.collect { renderStatus() } }
            launch { WearState.connected.collect { renderStatus() } }
            launch { WearState.phoneName.collect { renderStatus() } }
            launch { WearState.transfers.collect { renderFiles() } }
            launch { WearState.files.collect { renderFiles() } }
        }
    }

    override fun onStop() {
        uiJob?.cancel()
        uiJob = null
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /* --------------------------------- 界面搭建 --------------------------------- */

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        statusPage = StatusPage(this) { WearLinkService.start(this) }
        notificationsPage = NotificationsPage(
            context = this,
            onDismiss = { key ->
                WearLinkService.sendCommand(this, CommandCodec.encode(Protocol.CMD_DISMISS, key))
            },
            onClearAll = {
                WearLinkService.sendCommand(this, Protocol.CMD_CLEAR_NOTIFICATIONS)
            },
            onMediaToggle = {
                WearLinkService.sendCommand(this, Protocol.CMD_MEDIA_TOGGLE)
            },
            onMediaNext = {
                WearLinkService.sendCommand(this, Protocol.CMD_MEDIA_NEXT)
            },
            onMediaPrev = {
                WearLinkService.sendCommand(this, Protocol.CMD_MEDIA_PREV)
            },
            onVolumeUp = {
                WearLinkService.sendCommand(this, Protocol.CMD_VOLUME_UP)
            },
            onVolumeDown = {
                WearLinkService.sendCommand(this, Protocol.CMD_VOLUME_DOWN)
            }
        )
        filesPage = FilesPage(
            this,
            onOpen = { file -> openFile(file) },
            onDelete = { file -> confirmDelete(file) },
            onPickFile = { pickFile() },
            pathOf = { file -> WatchStorage.pathLabel(this, File(file.path)) }
        )

        pager = SwipePager(this).apply {
            addPage(statusPage.root)
            addPage(notificationsPage.root)
            addPage(filesPage.root)
            onPageChanged = { index -> updateDots(index) }
        }
        root.addView(
            pager,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        pageDots = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        root.addView(
            pageDots,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply { bottomMargin = Ui.dp(this@MainActivity, 6f) }
        )

        setContentView(root)
        buildDots()
    }

    private fun buildDots() {
        pageDots.removeAllViews()
        dotViews = (0 until 3).map { index ->
            View(this).apply {
                background = Ui.rounded(this@MainActivity, if (index == 0) Ui.ACCENT else Ui.TRACK, 3.5f)
                layoutParams = LinearLayout.LayoutParams(
                    Ui.dp(this@MainActivity, 7f),
                    Ui.dp(this@MainActivity, 7f)
                ).apply {
                    marginStart = Ui.dp(this@MainActivity, 3f)
                    marginEnd = Ui.dp(this@MainActivity, 3f)
                }
            }.also { pageDots.addView(it) }
        }
    }

    private fun updateDots(current: Int) {
        dotViews.forEachIndexed { index, view ->
            val size = Ui.dp(this, if (index == current) 7f else 5f)
            val params = view.layoutParams as LinearLayout.LayoutParams
            params.width = size
            params.height = size
            view.layoutParams = params
            view.background = Ui.rounded(this, if (index == current) Ui.ACCENT else Ui.TRACK, 3.5f)
        }
    }

    /* --------------------------------- 数据渲染 --------------------------------- */

    private fun renderAll() {
        renderStatus()
        renderFiles()
    }

    private fun renderStatus() {
        statusPage.render(
            snapshot = WearState.snapshot.value,
            notificationCount = WearState.notifications.value.size,
            connected = WearState.connected.value,
            phoneName = WearState.phoneName.value
        )
        notificationsPage.render(
            media = WearState.media.value,
            items = WearState.notifications.value
        )
    }

    private fun renderFiles() {
        filesPage.render(
            transfers = WearState.transfers.value,
            files = WearState.files.value,
            phoneName = WearState.phoneName.value
        )
    }

    /* --------------------------------- 文件操作 --------------------------------- */

    private fun pickFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        try {
            startActivityForResult(intent, REQUEST_PICK_FILE)
        } catch (e: ActivityNotFoundException) {
            toast("手表上没有文件选择器")
        }
    }

    @Deprecated("watch 端不依赖 androidx.activity，沿用经典回调")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK_FILE && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            WearLinkService.sendFile(this, uri)
            toast("正在发送到手机")
        }
    }

    private fun openFile(file: ReceivedFile) {
        val target = File(file.path)
        if (!target.exists()) {
            toast("文件已不存在")
            WearState.removeFile(file.path)
            return
        }
        try {
            WatchStorage.open(this, target)
        } catch (e: ActivityNotFoundException) {
            val ext = target.name.substringAfterLast('.', "").ifBlank { "该" }
            toast("手表上没有能打开 $ext 文件的应用")
        } catch (t: Throwable) {
            toast("打开失败：${t.message ?: "未知错误"}")
        }
    }

    private fun confirmDelete(file: ReceivedFile) {
        AlertDialog.Builder(this)
            .setTitle("删除文件")
            .setMessage(file.name)
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                runCatching { File(file.path).delete() }
                WearState.removeFile(file.path)
                toast("已删除")
            }
            .show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val REQUEST_PICK_FILE = 2001
    }
}
