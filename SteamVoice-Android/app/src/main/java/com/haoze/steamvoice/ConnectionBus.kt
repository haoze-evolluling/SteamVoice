package com.haoze.steamvoice

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import java.net.InetAddress
import java.util.concurrent.ConcurrentLinkedQueue

/** 当前正在向本机推送音频的电脑。port 为发送方 socket 端口，用于时钟同步。 */
data class ActivePc(
    val deviceId: String,
    val name: String,
    val address: InetAddress,
    var port: Int = 0,
)

/** 等待用户决定的电脑连接请求。 */
data class PcAuthPrompt(
    val requestId: String,
    val deviceId: String,
    val name: String,
    val host: String,
    val createdAtMs: Long,
)

/** 与电脑时间基准的校准进度，驱动连接期间的同步校准动画。 */
enum class CalibrationPhase { DETECT, CALCULATE, SYNC, DONE }

data class CalibrationState(
    val pcName: String,
    val phase: CalibrationPhase,
    /** 时钟偏差与往返延迟（毫秒），对齐后有值。 */
    val offsetMs: Long? = null,
    val rttMs: Long? = null,
)

/**
 * UI、连接器与接收服务之间的进程内状态总线。
 * 服务是唯一写入方（决策队列除外），UI 只读并在按钮回调里投递决策。
 */
object ConnectionBus {
    val activePc = MutableStateFlow<ActivePc?>(null)
    val authPrompt = MutableStateFlow<PcAuthPrompt?>(null)

    /** 接收服务发布的校准进度；断开连接时置回 null。 */
    val calibration = MutableStateFlow<CalibrationState?>(null)
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** 连接器在收到电脑同意后排队登记发送方，接收循环随即采纳。 */
    @Volatile
    var queuedSender: ActivePc? = null

    /** requestId → (allow, remember)，由 UI 或通知动作投递。 */
    val decisions = ConcurrentLinkedQueue<Triple<String, Boolean, Boolean>>()

    /** 用户在 UI 上主动断开的电脑标识，由接收循环消费。 */
    val localDisconnects = ConcurrentLinkedQueue<String>()

    fun notify(message: String) {
        messages.tryEmit(message)
    }
}
