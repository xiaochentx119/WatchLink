package com.watchlink.phone

import com.watchlink.shared.MediaState
import com.watchlink.shared.ReceivedFile
import com.watchlink.shared.StatusSnapshot
import com.watchlink.shared.TransferProgress
import com.watchlink.shared.WatchNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ConnectedWatch(val id: String, val name: String)

/**
 * 进程内的单一数据源：Service 往里写，Compose UI 从里读。
 */
object AppState {

    private val _snapshot = MutableStateFlow(StatusSnapshot())
    val snapshot: StateFlow<StatusSnapshot> = _snapshot.asStateFlow()

    private val _notifications = MutableStateFlow<List<WatchNotification>>(emptyList())
    val notifications: StateFlow<List<WatchNotification>> = _notifications.asStateFlow()

    private val _media = MutableStateFlow(MediaState())
    val media: StateFlow<MediaState> = _media.asStateFlow()

    private val _receivedFiles = MutableStateFlow<List<ReceivedFile>>(emptyList())
    val receivedFiles: StateFlow<List<ReceivedFile>> = _receivedFiles.asStateFlow()

    private val _watches = MutableStateFlow<List<ConnectedWatch>>(emptyList())
    val watches: StateFlow<List<ConnectedWatch>> = _watches.asStateFlow()

    private val _transfers = MutableStateFlow<List<TransferProgress>>(emptyList())
    val transfers: StateFlow<List<TransferProgress>> = _transfers.asStateFlow()

    private val _listenerGranted = MutableStateFlow(false)
    val listenerGranted: StateFlow<Boolean> = _listenerGranted.asStateFlow()

    private val _serviceRunning = MutableStateFlow(false)
    val serviceRunning: StateFlow<Boolean> = _serviceRunning.asStateFlow()

    fun setSnapshot(value: StatusSnapshot) {
        _snapshot.value = value
    }

    fun setNotifications(value: List<WatchNotification>) {
        _notifications.value = value
    }

    fun setMedia(value: MediaState) {
        _media.value = value
    }

    fun addReceivedFile(file: ReceivedFile) {
        _receivedFiles.value = (listOf(file) + _receivedFiles.value).distinctBy { it.path }.take(30)
    }

    fun setWatches(value: List<ConnectedWatch>) {
        _watches.value = value
    }

    fun setListenerGranted(granted: Boolean) {
        _listenerGranted.value = granted
    }

    fun setServiceRunning(running: Boolean) {
        _serviceRunning.value = running
    }

    fun upsertTransfer(progress: TransferProgress) {
        val current = _transfers.value.toMutableList()
        val index = current.indexOfFirst { it.id == progress.id }
        if (index >= 0) current[index] = progress else current.add(0, progress)
        _transfers.value = current.take(20)
    }

    fun clearFinishedTransfers() {
        _transfers.value = _transfers.value.filter {
            it.state == com.watchlink.shared.TransferState.SENDING ||
                it.state == com.watchlink.shared.TransferState.RECEIVING
        }
    }
}
