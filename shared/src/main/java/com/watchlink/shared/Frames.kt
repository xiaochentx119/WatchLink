package com.watchlink.shared

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException

/**
 * 帧编解码。所有字段都用 DataOutputStream 写，两端顺序必须严格一致。
 */
object FrameCodec {

    /** 用固定整数传铃声模式，避免 R8 混淆 enum 名称导致两端不一致。 */
    private fun ringerCode(mode: RingerMode): Int = when (mode) {
        RingerMode.SILENT -> 0
        RingerMode.VIBRATE -> 1
        RingerMode.NORMAL -> 2
    }

    private fun ringerFromCode(code: Int): RingerMode = when (code) {
        0 -> RingerMode.SILENT
        1 -> RingerMode.VIBRATE
        else -> RingerMode.NORMAL
    }

    fun encodeStatus(snapshot: StatusSnapshot): ByteArray = ByteArrayOutputStream().use { bos ->
        DataOutputStream(bos).use { out ->
            out.writeInt(snapshot.batteryPercent)
            out.writeBoolean(snapshot.charging)
            out.writeFloat(snapshot.batteryTemperatureC)
            out.writeBoolean(snapshot.wifiEnabled)
            out.writeBoolean(snapshot.wifiConnected)
            out.writeUTF(snapshot.wifiSsid.orEmpty())
            out.writeInt(ringerCode(snapshot.ringerMode))
            out.writeBoolean(snapshot.doNotDisturb)
            out.writeInt(snapshot.notificationCount)
            out.writeLong(snapshot.updatedAt)
            out.writeUTF(snapshot.phoneName)
        }
        bos.toByteArray()
    }

    fun decodeStatus(body: ByteArray): StatusSnapshot = DataInputStream(ByteArrayInputStream(body)).use { input ->
        StatusSnapshot(
            batteryPercent = input.readInt(),
            charging = input.readBoolean(),
            batteryTemperatureC = input.readFloat(),
            wifiEnabled = input.readBoolean(),
            wifiConnected = input.readBoolean(),
            wifiSsid = input.readUTF().takeIf { it.isNotBlank() },
            ringerMode = ringerFromCode(input.readInt()),
            doNotDisturb = input.readBoolean(),
            notificationCount = input.readInt(),
            updatedAt = input.readLong(),
            phoneName = input.readUTF()
        )
    }

    fun encodeNotifications(items: List<WatchNotification>): ByteArray = ByteArrayOutputStream().use { bos ->
        DataOutputStream(bos).use { out ->
            out.writeInt(items.size)
            for (item in items) {
                out.writeUTF(item.key)
                out.writeUTF(item.appLabel)
                out.writeUTF(item.title)
                out.writeUTF(item.text)
                out.writeLong(item.timestamp)
                out.writeBoolean(item.ongoing)
            }
        }
        bos.toByteArray()
    }

    fun decodeNotifications(body: ByteArray): List<WatchNotification> =
        DataInputStream(ByteArrayInputStream(body)).use { input ->
            val size = input.readInt().coerceIn(0, Protocol.MAX_NOTIFICATIONS * 4)
            buildList(size) {
                repeat(size) {
                    add(
                        WatchNotification(
                            key = input.readUTF(),
                            appLabel = input.readUTF(),
                            title = input.readUTF(),
                            text = input.readUTF(),
                            timestamp = input.readLong(),
                            ongoing = input.readBoolean()
                        )
                    )
                }
            }
        }

    fun encodeMedia(state: MediaState): ByteArray = ByteArrayOutputStream().use { bos ->
        DataOutputStream(bos).use { out ->
            out.writeBoolean(state.active)
            out.writeUTF(state.title)
            out.writeUTF(state.artist)
            out.writeUTF(state.appName)
            out.writeBoolean(state.isPlaying)
            out.writeInt(state.volume)
            out.writeInt(state.maxVolume)
        }
        bos.toByteArray()
    }

    fun decodeMedia(body: ByteArray): MediaState = DataInputStream(ByteArrayInputStream(body)).use { input ->
        MediaState(
            active = input.readBoolean(),
            title = input.readUTF(),
            artist = input.readUTF(),
            appName = input.readUTF(),
            isPlaying = input.readBoolean(),
            volume = input.readInt(),
            maxVolume = input.readInt()
        )
    }

    fun encodeText(text: String): ByteArray = ByteArrayOutputStream().use { bos ->
        DataOutputStream(bos).use { it.writeUTF(text) }
        bos.toByteArray()
    }

    fun decodeText(body: ByteArray): String =
        DataInputStream(ByteArrayInputStream(body)).use { it.readUTF() }

    fun encodeFileBegin(transferId: Int, header: FileHeader): ByteArray = ByteArrayOutputStream().use { bos ->
        DataOutputStream(bos).use { out ->
            out.writeInt(transferId)
            out.writeUTF(header.name)
            out.writeLong(header.size)
            out.writeUTF(header.mimeType)
        }
        bos.toByteArray()
    }

    fun decodeFileBegin(body: ByteArray): Pair<Int, FileHeader> =
        DataInputStream(ByteArrayInputStream(body)).use { input ->
            val id = input.readInt()
            id to FileHeader(
                name = input.readUTF(),
                size = input.readLong(),
                mimeType = input.readUTF()
            )
        }

    fun encodeHello(deviceName: String, sdkInt: Int): ByteArray = ByteArrayOutputStream().use { bos ->
        DataOutputStream(bos).use { out ->
            out.writeUTF(deviceName)
            out.writeInt(sdkInt)
        }
        bos.toByteArray()
    }

    fun decodeHello(body: ByteArray): Pair<String, Int> =
        DataInputStream(ByteArrayInputStream(body)).use { input ->
            input.readUTF() to input.readInt()
        }
}

/** 指令编解码：`命令名|参数`，参数里可以再包含 `|`。 */
object CommandCodec {

    fun encode(name: String, argument: String = ""): String =
        if (argument.isEmpty()) name else name + Protocol.ARG_SEPARATOR + argument

    fun name(raw: String): String = raw.substringBefore(Protocol.ARG_SEPARATOR)

    fun argument(raw: String): String = raw.substringAfter(Protocol.ARG_SEPARATOR, "")
}

/** 带长度前缀的帧写入器（线程安全）。 */
class FrameWriter(private val out: DataOutputStream) {

    @Synchronized
    fun write(type: Int, body: ByteArray = ByteArray(0)) {
        out.writeInt(body.size + 1)
        out.writeByte(type)
        if (body.isNotEmpty()) out.write(body)
        out.flush()
    }

    @Synchronized
    fun writeStatus(snapshot: StatusSnapshot) = write(FrameType.STATUS, FrameCodec.encodeStatus(snapshot))

    @Synchronized
    fun writeNotifications(items: List<WatchNotification>) =
        write(FrameType.NOTIFICATIONS, FrameCodec.encodeNotifications(items))

    @Synchronized
    fun writeMedia(state: MediaState) = write(FrameType.MEDIA, FrameCodec.encodeMedia(state))

    @Synchronized
    fun writeCommand(command: String) = write(FrameType.COMMAND, FrameCodec.encodeText(command))

    @Synchronized
    fun writeFileBegin(transferId: Int, header: FileHeader) =
        write(FrameType.FILE_BEGIN, FrameCodec.encodeFileBegin(transferId, header))

    @Synchronized
    fun writeFileChunk(transferId: Int, buffer: ByteArray, length: Int) {
        val body = ByteArray(length + 4)
        body[0] = (transferId ushr 24).toByte()
        body[1] = (transferId ushr 16).toByte()
        body[2] = (transferId ushr 8).toByte()
        body[3] = transferId.toByte()
        System.arraycopy(buffer, 0, body, 4, length)
        write(FrameType.FILE_DATA, body)
    }

    @Synchronized
    fun writeFileEnd(transferId: Int) {
        val body = ByteArray(4)
        body[0] = (transferId ushr 24).toByte()
        body[1] = (transferId ushr 16).toByte()
        body[2] = (transferId ushr 8).toByte()
        body[3] = transferId.toByte()
        write(FrameType.FILE_END, body)
    }

    @Synchronized
    fun writePing() = write(FrameType.PING)

    @Synchronized
    fun writeHello(deviceName: String, sdkInt: Int) =
        write(FrameType.HELLO, FrameCodec.encodeHello(deviceName, sdkInt))
}

/** FILE_DATA / FILE_END 里的 transferId 解析。 */
object Chirp {
    fun readTransferId(body: ByteArray, offset: Int = 0): Int =
        ((body[offset].toInt() and 0xFF) shl 24) or
            ((body[offset + 1].toInt() and 0xFF) shl 16) or
            ((body[offset + 2].toInt() and 0xFF) shl 8) or
            (body[offset + 3].toInt() and 0xFF)
}

/** 帧读取器：流结束返回 null。 */
class FrameReader(private val input: DataInputStream) {

    fun next(): Frame? {
        val length = try {
            input.readInt()
        } catch (e: EOFException) {
            return null
        }
        if (length < 1 || length > Protocol.MAX_FRAME_BYTES) {
            throw IOException("非法帧长度: $length")
        }
        val type = input.readByte().toInt()
        val body = ByteArray(length - 1)
        input.readFully(body)
        return Frame(type, body)
    }
}
