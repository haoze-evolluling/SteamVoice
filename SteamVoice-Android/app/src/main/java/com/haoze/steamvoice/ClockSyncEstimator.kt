package com.haoze.steamvoice

/**
 * 估算本机单调时钟与发送端流时钟的偏差（NTP 风格）。
 * 按窗口保留样本并取中位数，抗网络抖动；样本过期后自动丢弃，
 * 使重连或发送端时钟重置后能快速收敛到新偏差。
 */
class ClockSyncEstimator(
    private val windowSize: Int = 8,
    private val maxSampleAgeNs: Long = 30_000_000_000L,
    private val nowNs: () -> Long = System::nanoTime,
) {
    private class Sample(val offset: Long, val atNs: Long)

    private val samples = ArrayDeque<Sample>()

    /**
     * 记录一次往返：t1 本机发送、t2 对端接收、t3 对端回复、t4 本机收到回复。
     * offset 定义为 对端时钟 - 本机时钟（对端流时钟减去 offset 得到本机时间）。
     */
    fun onExchange(t1: Long, t2: Long, t3: Long, t4: Long) {
        val rtt = (t4 - t1) - (t3 - t2)
        if (rtt < 0) return
        val offset = ((t2 - t1) + (t3 - t4)) / 2
        val now = nowNs()
        synchronized(samples) {
            samples.addLast(Sample(offset, now))
            while (samples.size > windowSize) samples.removeFirst()
        }
    }

    val hasEstimate: Boolean
        get() = synchronized(samples) { pruneLocked(); samples.isNotEmpty() }

    /** 将发送端流时钟时间映射为本机单调时钟时间；无有效样本时返回 null。 */
    fun mapToLocal(streamNs: Long): Long? {
        synchronized(samples) {
            pruneLocked()
            if (samples.isEmpty()) return null
            val offsets = samples.map { it.offset }.sorted()
            return streamNs - offsets[offsets.size / 2]
        }
    }

    private fun pruneLocked() {
        val now = nowNs()
        while (samples.isNotEmpty() && now - samples.first().atNs > maxSampleAgeNs) {
            samples.removeFirst()
        }
    }
}
