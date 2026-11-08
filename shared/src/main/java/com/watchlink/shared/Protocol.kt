package com.watchlink.shared

/**
 * 手机端（TCP 服务端）与手表端（TCP 客户端）之间的局域网协议。
 *
 * 一帧 = [4 字节大端 payload 长度][1 字节帧类型][payload]
 * payload 用 DataOutputStream 写入，字段顺序由 FrameCodec 约定。
 */
object Protocol {

    /** 手机端监听的 TCP 端口。 */
    const val TCP_PORT = 8975

    /** UDP 自动发现端口。 */
    const val DISCOVERY_PORT = 8976

    /** 手表广播的探针内容。 */
    const val DISCOVERY_PROBE = "WATCHLINK_WHO"

    /** 手机回复的前缀，后面跟 TCP 端口号。 */
    const val DISCOVERY_REPLY_PREFIX = "WATCHLINK_HERE:"

    /** 单个数据帧上限，避免异常长度导致 OOM。 */
    const val MAX_FRAME_BYTES = 512 * 1024

    /** 文件分块大小。 */
    const val CHUNK_SIZE = 32 * 1024

    /** 手表最多显示多少条通知。 */
    const val MAX_NOTIFICATIONS = 40

    /** 手表 -> 手机：请立刻重新推送一次。 */
    const val CMD_REFRESH = "refresh"

    /** 手表 -> 手机：清除一条通知（后面跟 |key）。 */
    const val CMD_DISMISS = "dismiss"

    /** 手表 -> 手机：清除全部通知。 */
    const val CMD_CLEAR_NOTIFICATIONS = "clear_notifications"

    const val CMD_MEDIA_TOGGLE = "media_toggle"
    const val CMD_MEDIA_NEXT = "media_next"
    const val CMD_MEDIA_PREV = "media_prev"
    const val CMD_VOLUME_UP = "volume_up"
    const val CMD_VOLUME_DOWN = "volume_down"

    /** 指令参数分隔符：例如 dismiss|0|com.tencent.mm|... */
    const val ARG_SEPARATOR = "|"

    /** 手机端保持"可被发现"的时长，超时后停止 UDP 发现以省电。 */
    const val DISCOVERY_WINDOW_MS = 3 * 60 * 1000L

    /** 手机端状态轮询间隔。 */
    const val SYNC_INTERVAL_MS = 3_000L
}

object FrameType {
    const val HELLO = 1
    const val STATUS = 2
    const val NOTIFICATIONS = 3
    const val COMMAND = 4
    const val FILE_BEGIN = 5
    const val FILE_DATA = 6
    const val FILE_END = 7
    const val PING = 8
    const val PONG = 9
    const val MEDIA = 10
}

/** 一帧原始数据。 */
class Frame(val type: Int, val body: ByteArray)
