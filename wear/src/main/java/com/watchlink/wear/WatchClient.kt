package com.watchlink.wear

import android.content.Context
import android.os.Build
import android.util.Log
import com.watchlink.shared.Chirp
import com.watchlink.shared.FileHeader
import com.watchlink.shared.FrameCodec
import com.watchlink.shared.FrameReader
import com.watchlink.shared.FrameType
import com.watchlink.shared.FrameWriter
import com.watchlink.shared.MediaState
import com.watchlink.shared.Protocol
import com.watchlink.shared.StatusSnapshot
import com.watchlink.shared.WatchNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * 手表端的局域网客户端：UDP 广播发现手机 → TCP 长连接 → 收状态/通知/媒体/文件，也能反向发文件。
 */
class WatchClient(
    private val context: Context,
    private val listener: Listener
) {

    interface Listener {
        fun onConnected(phoneName: String)
        fun onDisconnected()
        fun onStatus(snapshot: StatusSnapshot)
        fun onNotifications(items: List<WatchNotification>)
        fun onMedia(state: MediaState)
        fun onFileBegin(header: FileHeader)
        fun onFileChunk(buffer: ByteArray, offset: Int, length: Int)
        fun onFileEnd()
        fun onFileFailed(message: String)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: Socket? = null
    private var writer: FrameWriter? = null
    private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null
    private val transferSeq = AtomicInteger(1)

    fun start() {
        acquireMulticastLock()
        scope.launch { mainLoop() }
    }

    fun stop() {
        scope.cancel()
        closeSocket()
        runCatching { multicastLock?.release() }
        multicastLock = null
    }

    fun requestRefresh() {
        sendCommand(Protocol.CMD_REFRESH)
    }

    fun sendCommand(command: String) {
        scope.launch {
            val target = writer ?: return@launch
            runCatching { target.writeCommand(command) }
                .onFailure { Log.w(TAG, "发送指令失败: $command", it) }
        }
    }

    /** 把手表上的文件发给手机。 */
    fun sendFile(
        header: FileHeader,
        openStream: () -> InputStream,
        onProgress: (Long) -> Unit,
        onFinished: (Boolean, String?) -> Unit
    ) {
        scope.launch {
            val target = writer
            if (target == null) {
                onFinished(false, "未连接手机")
                return@launch
            }
            val id = transferSeq.getAndIncrement()
            try {
                target.writeFileBegin(id, header)
                openStream().use { input ->
                    val buffer = ByteArray(Protocol.CHUNK_SIZE)
                    var sent = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        target.writeFileChunk(id, buffer, read)
                        sent += read
                        onProgress(sent)
                    }
                }
                target.writeFileEnd(id)
                onFinished(true, null)
            } catch (t: Throwable) {
                Log.w(TAG, "发送文件失败", t)
                onFinished(false, t.message ?: "发送失败")
            }
        }
    }

    fun isConnected(): Boolean = socket?.isConnected == true && !(socket?.isClosed ?: true)

    private fun acquireMulticastLock() {
        runCatching {
            val wifi = context.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
            multicastLock = wifi?.createMulticastLock("watchlink-wear")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private suspend fun mainLoop() {
        while (scope.isActive) {
            val host = discover() ?: connectToSaved()
            if (host == null) {
                listener.onDisconnected()
                delay(2_000)
                continue
            }
            try {
                connectAndRead(host)
            } catch (t: Throwable) {
                Log.i(TAG, "连接中断: ${t.message}")
            } finally {
                closeSocket()
                listener.onDisconnected()
            }
            delay(1_500)
        }
    }

    /** 广播探针，等待手机回复自己的地址。 */
    private suspend fun discover(): String? = withContext(Dispatchers.IO) {
        var datagram: DatagramSocket? = null
        try {
            datagram = DatagramSocket().apply {
                broadcast = true
                reuseAddress = true
                soTimeout = 1_000
            }
            val probe = Protocol.DISCOVERY_PROBE.toByteArray(Charsets.UTF_8)
            for (address in broadcastTargets()) {
                runCatching {
                    datagram.send(
                        DatagramPacket(probe, probe.size, InetAddress.getByName(address), Protocol.DISCOVERY_PORT)
                    )
                }
            }
            val buffer = ByteArray(256)
            val deadline = System.currentTimeMillis() + 3_000
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    datagram.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue
                }
                val text = String(packet.data, 0, packet.length, Charsets.UTF_8).trim()
                if (text.startsWith(Protocol.DISCOVERY_REPLY_PREFIX)) {
                    val host = packet.address.hostAddress
                    Log.i(TAG, "发现手机: $host")
                    if (host != null) {
                        rememberHost(host)
                        return@withContext host
                    }
                }
            }
            null
        } catch (t: Throwable) {
            Log.w(TAG, "发现失败", t)
            null
        } finally {
            runCatching { datagram?.close() }
        }
    }

    private fun connectToSaved(): String? = rememberedHost().takeIf { it.isNotBlank() }

    private fun rememberHost(host: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_HOST, host).apply()
    }

    private fun rememberedHost(): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_HOST, "").orEmpty()

    private fun broadcastTargets(): Set<String> {
        val result = mutableSetOf("255.255.255.255")
        runCatching {
            NetworkInterface.getNetworkInterfaces()
                .toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.interfaceAddresses.toList() }
                .mapNotNull { it.broadcast?.hostAddress }
                .forEach { result += it }
        }
        return result
    }

    private suspend fun connectAndRead(host: String) = withContext(Dispatchers.IO) {
        val sock = Socket().apply {
            tcpNoDelay = true
            connect(InetSocketAddress(host, Protocol.TCP_PORT), 3_000)
        }
        socket = sock

        val out = FrameWriter(DataOutputStream(BufferedOutputStream(sock.getOutputStream())))
        writer = out
        out.writeHello(Build.MODEL ?: "Watch", Build.VERSION.SDK_INT)
        out.writeCommand(Protocol.CMD_REFRESH)

        val reader = FrameReader(DataInputStream(BufferedInputStream(sock.getInputStream())))
        Log.i(TAG, "已连接手机 $host")

        while (scope.isActive) {
            val frame = reader.next() ?: break
            when (frame.type) {
                FrameType.HELLO -> {
                    val (name, _) = FrameCodec.decodeHello(frame.body)
                    listener.onConnected(name)
                }
                FrameType.STATUS -> listener.onStatus(FrameCodec.decodeStatus(frame.body))
                FrameType.NOTIFICATIONS -> listener.onNotifications(FrameCodec.decodeNotifications(frame.body))
                FrameType.MEDIA -> listener.onMedia(FrameCodec.decodeMedia(frame.body))
                FrameType.FILE_BEGIN -> listener.onFileBegin(FrameCodec.decodeFileBegin(frame.body).second)
                FrameType.FILE_DATA -> {
                    val id = Chirp.readTransferId(frame.body)
                    listener.onFileChunk(frame.body, 4, frame.body.size - 4)
                }
                FrameType.FILE_END -> listener.onFileEnd()
                FrameType.PING -> out.write(FrameType.PONG)
            }
        }
    }

    private fun closeSocket() {
        runCatching { writer = null }
        runCatching { socket?.close() }
        socket = null
    }

    private companion object {
        const val TAG = "WatchLinkClient"
        const val PREFS = "watchlink"
        const val KEY_HOST = "last_host"
    }
}
