package com.watchlink.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class FrameCodecTest {

    private val sampleStatus = StatusSnapshot(
        batteryPercent = 87,
        charging = true,
        batteryTemperatureC = 33.4f,
        wifiEnabled = true,
        wifiConnected = true,
        wifiSsid = "办公室-5G",
        ringerMode = RingerMode.VIBRATE,
        doNotDisturb = false,
        notificationCount = 3,
        updatedAt = 1730000000000L,
        phoneName = "iQOO Neo10"
    )

    private val sampleNotifications = listOf(
        WatchNotification("0|com.tencent.mm|1|null|10", "微信", "张三", "晚上一起吃饭吗？", 1730000000000L, false),
        WatchNotification("0|com.android.dialer|2|null|10", "电话", "未接来电", "", 1730000001000L, true)
    )

    @Test
    fun statusRoundTripKeepsAllFields() {
        val decoded = FrameCodec.decodeStatus(FrameCodec.encodeStatus(sampleStatus))
        assertEquals(sampleStatus, decoded)
    }

    @Test
    fun notificationsRoundTripKeepsAllFields() {
        val decoded = FrameCodec.decodeNotifications(FrameCodec.encodeNotifications(sampleNotifications))
        assertEquals(sampleNotifications, decoded)
    }

    @Test
    fun emptyNotificationListRoundTrip() {
        assertTrue(FrameCodec.decodeNotifications(FrameCodec.encodeNotifications(emptyList())).isEmpty())
    }

    @Test
    fun fileHeaderRoundTrip() {
        val header = FileHeader("报表 2026.xlsx", 123456789L, "application/vnd.ms-excel")
        val (id, decodedHeader) = FrameCodec.decodeFileBegin(FrameCodec.encodeFileBegin(1, header))
        assertEquals(1, id)
        assertEquals(header, decodedHeader)
    }

    @Test
    fun framesTravelOverRealSocket() {
        ServerSocket(0).use { server ->
            val failure = AtomicReference<Throwable>()
            val serverThread = Thread {
                try {
                    server.accept().use { socket ->
                        val writer = FrameWriter(DataOutputStream(socket.getOutputStream()))
                        writer.writeHello("iQOO Neo10", 36)
                        writer.writeStatus(sampleStatus)
                        writer.writeNotifications(sampleNotifications)
                        writer.writeFileBegin(7, FileHeader("a.bin", 3))
                        writer.writeFileChunk(7, byteArrayOf(1, 2, 3), 3)
                        writer.writeFileEnd(7)
                    }
                } catch (t: Throwable) {
                    failure.set(t)
                }
            }
            serverThread.start()

            java.net.Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 5_000
                val reader = FrameReader(DataInputStream(socket.getInputStream()))

                val hello = reader.next()!!
                assertEquals(FrameType.HELLO, hello.type)
                assertEquals("iQOO Neo10", FrameCodec.decodeHello(hello.body).first)

                val statusFrame = reader.next()!!
                assertEquals(FrameType.STATUS, statusFrame.type)
                assertEquals(sampleStatus, FrameCodec.decodeStatus(statusFrame.body))

                val notificationsFrame = reader.next()!!
                assertEquals(FrameType.NOTIFICATIONS, notificationsFrame.type)
                assertEquals(sampleNotifications, FrameCodec.decodeNotifications(notificationsFrame.body))

                val begin = reader.next()!!
                assertEquals(FrameType.FILE_BEGIN, begin.type)
                val (beginId, beginHeader) = FrameCodec.decodeFileBegin(begin.body)
                assertEquals(7, beginId)
                assertEquals("a.bin", beginHeader.name)

                val data = reader.next()!!
                assertEquals(FrameType.FILE_DATA, data.type)
                assertEquals(7, Chirp.readTransferId(data.body))
                assertEquals(7, data.body.size)

                assertEquals(FrameType.FILE_END, reader.next()?.type)
                assertEquals(null, reader.next())
            }

            serverThread.join(5_000)
            failure.get()?.let { throw AssertionError("服务端写入失败", it) }
        }
    }

    @Test
    fun frameWriterProducesExpectedPreamble() {
        val bos = ByteArrayOutputStream()
        FrameWriter(DataOutputStream(bos)).writeCommand(Protocol.CMD_REFRESH)
        val bytes = bos.toByteArray()
        // 4 字节长度 + 1 字节类型 + payload
        val length = ((bytes[0].toInt() and 0xFF) shl 24) or
            ((bytes[1].toInt() and 0xFF) shl 16) or
            ((bytes[2].toInt() and 0xFF) shl 8) or
            (bytes[3].toInt() and 0xFF)
        assertEquals(bytes.size, length + 4)
        assertEquals(FrameType.COMMAND, bytes[4].toInt())

        val decoded = FrameReader(DataInputStream(ByteArrayInputStream(bytes))).next()!!
        assertEquals(Protocol.CMD_REFRESH, FrameCodec.decodeText(decoded.body))
    }
}
