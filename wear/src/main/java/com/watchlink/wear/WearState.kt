package com.watchlink.wear

import com.watchlink.shared.MediaState
import com.watchlink.shared.ReceivedFile
import com.watchlink.shared.StatusSnapshot
import com.watchlink.shared.TransferProgress
import com.watchlink.shared.WatchNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 手表端的进程内状态仓库：Service 写入，Compose 界面读取。
 */
object WearState {

    private val _snapshot = MutableStateFlow<StatusSnapshot?>(null)
    val snapshot: StateFlow<StatusSnapshot?> = _snapshot.asStateFlow()

    private val _media = MutableStateFlow(MediaState())
    val media: StateFlow<MediaState> = _media.asStateFlow()

    private val _notifications = MutableStateFlow<List<WatchNotification>>(emptyList())
    val notifications: StateFlow<List<WatchNotification>> = _notifications.asStateFlow()

    private val _transfers = MutableStateFlow<List<TransferProgress>>(emptyList())
    val transfers: StateFlow<List<TransferProgress>> = _transfers.asStateFlow()

    private val _files = MutableStateFlow<List<ReceivedFile>>(emptyList())
    val files: StateFlow<List<ReceivedFile>> = _files.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _phoneName = MutableStateFlow("")
    val phoneName: StateFlow<String> = _phoneName.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    fun setSnapshot(value: StatusSnapshot) {
        _snapshot.value = value
        _loaded.value = true
    }

    fun setNotifications(value: List<WatchNotification>) {
        _notifications.value = value
        _loaded.value = true
    }

    fun setMedia(value: MediaState) {
        _media.value = value
    }

    fun setConnected(value: Boolean) {
        _connected.value = value
    }

    fun setPhoneName(value: String) {
        _phoneName.value = value
    }

    fun setFiles(value: List<ReceivedFile>) {
        _files.value = value
    }

    fun addFile(file: ReceivedFile) {
        _files.value = (listOf(file) + _files.value).distinctBy { it.path }
    }

    fun removeFile(path: String) {
        _files.value = _files.value.filterNot { it.path == path }
    }

    fun upsertTransfer(progress: TransferProgress) {
        val current = _transfers.value.toMutableList()
        val index = current.indexOfFirst { it.id == progress.id }
        if (index >= 0) current[index] = progress else current.add(0, progress)
        _transfers.value = current.take(10)
    }
}
