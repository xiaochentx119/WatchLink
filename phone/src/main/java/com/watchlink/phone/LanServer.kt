package com.watchlink.phone

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.watchlink.shared.Chirp
import com.watchlink.shared.FileCodec
import com.watchlink.shared.FileHeader
import com.watchlink.shared.FrameCodec
import com.watchlink.shared.FrameReader
import com.watchlink.shared.FrameType
import com.watchlink.shared.FrameWriter
import com.watchlink.shared.MediaState
import com.watchlink.shared.Protocol
import com.watchlink.shared.ReceivedFile
import com.watchlink.shared.StatusSnapshot
import com.watchlink.shared.TransferDirection
import com.watchlink.shared.TransferProgress
import com.watchlink.shared.TransferState
import com.watchlink.shared.WatchNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * 手机端的局域网服务：TCP 服务端 + UDP 发现（可限时开启），
 * 负责双向文件传输与状态/通知/媒体推送。
 */
class LanServer(
    private val context: Context,
    private val listener: Listener
) {

    interface Listener {
        fun onClientsChanged(names: List<String>)
        fun onCommand(raw: String)
        fun onError(message: String)
    }

    private class Receiving(
        val saver: PhoneFileSaver,
        val name: String,
        val totalBytes: Long,
        val transferId: String,
        var received: Long = 0,
        var lastReport: Long = 0
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var discoverySocket: DatagramSocket? = null
    private var discoveryJob: Job? = null
    private val clients = CopyOnWriteArrayList<Client>()
    private var multicastLock: WifiManager.MulticastLock? = null
    private val transferSeq = AtomicInteger(1)

    val clientCount: Int get() = clients.size
    val discoveryRunning: Boolean get() = discoveryJob?.isActive == true

    fun start() {
        acquireMulticastLock()
        scope.launch { runServer() }
    }

    /** 只开发现窗口（可被定时关掉以省电）。 */
    fun startDiscovery() {
        if (discoveryRunning) return
        discoveryJob = scope.launch { runDiscovery() }
    }

    fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
        runCatching { discoverySocket?.close() }
        discoverySocket = null
    }

    fun stop() {
        stopDiscovery()
        scope.cancel()
        runCatching { serverSocket?.close() }
        clients.forEach { it.close() }
        clients.clear()
        runCatching { multicastLock?.release() }
        multicastLock = null
    }

    /* ------------------------------ TCP 服务端 ------------------------------ */

    private suspend fun runServer() = withContext(Dispatchers.IO) {
        while (scope.isActive) {
            try {
                val socket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(Protocol.TCP_PORT))
                }
                serverSocket = socket
                Log.i(TAG, "TCP 服务已启动: ${Protocol.TCP_PORT}")
                while (scope.isActive) {
                    val raw = socket.accept()
                    val client = Client(raw)
                    clients.add(client)
                    Log.i(TAG, "手表接入: ${raw.inetAddress.hostAddress}")
                    notifyClients()
                    scope.launch { handleClient(client) }
                }
            } catch (t: Throwable) {
                if (!scope.isActive) break
                Log.w(TAG, "TCP 服务异常", t)
                listener.onError("监听端口失败: ${t.message}")
                delay(2_000)
            }
        }
    }

    private suspend fun handleClient(client: Client) {
        try {
            val reader = FrameReader(DataInputStream(BufferedInputStream(client.socket.getInputStream())))
            client.writer.write(
                FrameType.HELLO,
                FrameCodec.encodeHello(android.os.Build.MODEL ?: "Android", android.os.Build.VERSION.SDK_INT)
            )
            while (scope.isActive) {
                val frame = reader.next() ?: break
                when (frame.type) {
                    FrameType.HELLO -> {
                        val (name, _) = FrameCodec.decodeHello(frame.body)
                        client.deviceName = name
                        notifyClients()
                    }
                    FrameType.COMMAND -> listener.onCommand(FrameCodec.decodeText(frame.body))
                    FrameType.PING -> client.writer.write(FrameType.PONG)
                    FrameType.FILE_BEGIN -> onFileBegin(client, frame.body)
                    FrameType.FILE_DATA -> onFileData(client, frame.body)
                    FrameType.FILE_END -> onFileEnd(client, frame.body)
                }
            }
        } catch (t: Throwable) {
            Log.i(TAG, "连接结束: ${t.message}")
        } finally {
            client.receivers.values.forEach { it.saver.abort() }
            client.receivers.clear()
            clients.remove(client)
            client.close()
            notifyClients()
        }
    }

    private fun notifyClients() {
        listener.onClientsChanged(clients.map { it.deviceName })
    }

    /* ------------------------------ 手表 -> 手机 文件 ------------------------------ */

    private fun onFileBegin(client: Client, body: ByteArray) {
        val (id, header) = runCatching { FrameCodec.decodeFileBegin(body) }.getOrNull() ?: return
        val saver = PhoneFileSaver(context)
        val ok = saver.begin(header.name, header.mimeType)
        val transferId = "in-${id}"
        if (!ok) {
            AppState.upsertTransfer(
                TransferProgress(
                    id = transferId,
                    fileName = header.name,
                    totalBytes = header.size,
                    state = TransferState.FAILED,
                    error = "无法写入文件",
                    direction = TransferDirection.TO_PHONE
                )
            )
            return
        }
        client.receivers[id] = Receiving(saver, header.name, header.size, transferId)
        AppState.upsertTransfer(
            TransferProgress(
                id = transferId,
                fileName = header.name,
                totalBytes = header.size,
                state = TransferState.RECEIVING,
                direction = TransferDirection.TO_PHONE
            )
        )
    }

    private fun onFileData(client: Client, body: ByteArray) {
        if (body.size <= 4) return
        val id = Chirp.readTransferId(body)
        val receiving = client.receivers[id] ?: return
        receiving.saver.write(body, body.size - 4)
        receiving.received += (body.size - 4)
        if (receiving.received - receiving.lastReport >= PROGRESS_STEP) {
            receiving.lastReport = receiving.received
            AppState.upsertTransfer(
                TransferProgress(
                    id = receiving.transferId,
                    fileName = receiving.name,
                    totalBytes = receiving.totalBytes,
                    transferredBytes = receiving.received,
                    state = TransferState.RECEIVING,
                    direction = TransferDirection.TO_PHONE
                )
            )
        }
    }

    private fun onFileEnd(client: Client, body: ByteArray) {
        if (body.size < 4) return
        val id = Chirp.readTransferId(body)
        val receiving = client.receivers.remove(id) ?: return
        val saved = receiving.saver.finish(receiving.received)
        if (saved == null) {
            AppState.upsertTransfer(
                TransferProgress(
                    id = receiving.transferId,
                    fileName = receiving.name,
                    totalBytes = receiving.totalBytes,
                    transferredBytes = receiving.received,
                    state = TransferState.FAILED,
                    error = "保存失败",
                    direction = TransferDirection.TO_PHONE
                )
            )
            return
        }
        AppState.upsertTransfer(
            TransferProgress(
                id = receiving.transferId,
                fileName = saved.name,
                totalBytes = receiving.totalBytes,
                transferredBytes = receiving.received,
                state = TransferState.DONE,
                direction = TransferDirection.TO_PHONE
            )
        )
        AppState.addReceivedFile(
            ReceivedFile(
                name = saved.name,
                size = saved.size,
                path = saved.location,
                receivedAt = System.currentTimeMillis(),
                sender = client.deviceName,
                mimeType = ""
            )
        )
    }

    /* ------------------------------ 数据广播 ------------------------------ */

    fun broadcastStatus(snapshot: StatusSnapshot) {
        forEachClient { it.writer.writeStatus(snapshot) }
    }

    fun broadcastNotifications(items: List<WatchNotification>) {
        forEachClient { it.writer.writeNotifications(items) }
    }

    fun broadcastMedia(state: MediaState) {
        forEachClient { it.writer.writeMedia(state) }
    }

    /** 把文件发给当前连接的第一块手表。 */
    suspend fun sendFile(
        header: FileHeader,
        openInputStream: () -> InputStream,
        onProgress: (Long) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val client = clients.firstOrNull() ?: return@withContext false
        val transferId = transferSeq.getAndIncrement()
        try {
            client.writer.writeFileBegin(transferId, header)
            openInputStream().use { input ->
                val buffer = ByteArray(Protocol.CHUNK_SIZE)
                var sent = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    client.writer.writeFileChunk(transferId, buffer, read)
                    sent += read
                    onProgress(sent)
                }
            }
            client.writer.writeFileEnd(transferId)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "发送文件失败", t)
            throw t
        }
    }

    private inline fun forEachClient(action: (Client) -> Unit) {
        for (client in clients.toList()) {
            try {
                action(client)
            } catch (t: Throwable) {
                Log.i(TAG, "推送失败，移除连接: ${t.message}")
                clients.remove(client)
                client.close()
                notifyClients()
            }
        }
    }

    /* ------------------------------ UDP 发现 ------------------------------ */

    private suspend fun runDiscovery() = withContext(Dispatchers.IO) {
        while (scope.isActive) {
            try {
                val socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(Protocol.DISCOVERY_PORT))
                }
                discoverySocket = socket
                Log.i(TAG, "UDP 发现已开启: ${Protocol.DISCOVERY_PORT}")
                val buffer = ByteArray(256)
                while (scope.isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8).trim()
                    if (text == Protocol.DISCOVERY_PROBE) {
                        val reply = "${Protocol.DISCOVERY_REPLY_PREFIX}${Protocol.TCP_PORT}"
                            .toByteArray(Charsets.UTF_8)
                        socket.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
                        Log.i(TAG, "响应发现请求: ${packet.address.hostAddress}")
                    }
                }
            } catch (t: Throwable) {
                if (!scope.isActive) break
                Log.w(TAG, "UDP 发现异常", t)
                delay(2_000)
            }
        }
    }

    private fun acquireMulticastLock() {
        runCatching {
            val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
            multicastLock = wifi?.createMulticastLock("watchlink")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    /* -------------------------------- 内部类 -------------------------------- */

    private inner class Client(val socket: Socket) {
        val writer = FrameWriter(DataOutputStream(BufferedOutputStream(socket.getOutputStream())))
        val receivers = ConcurrentHashMap<Int, Receiving>()

        @Volatile
        var deviceName: String = "手表"

        fun close() {
            runCatching { socket.close() }
        }
    }

    companion object {
        private const val TAG = "WatchLinkServer"
        private const val PROGRESS_STEP = 256 * 1024L
    }
}
