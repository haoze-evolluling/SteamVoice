package com.haoze.steamvoice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockSyncEstimatorTest {

    @Test
    fun relativeOffsetHidesMonotonicClockOrigin() {
        val estimator = ClockSyncEstimator(nowNs = { 1_000_000L })
        estimator.onExchange(0, 9_835_695_000_000L, 9_835_695_000_000L, 0)
        estimator.onExchange(1_000_000, 9_835_696_000_000L, 9_835_696_000_000L, 1_000_000)
        assertEquals(0L, estimator.relativeOffsetMs())
    }

    @Test
    fun zeroRttConstantOffsetMapsExactly() {
        val estimator = ClockSyncEstimator(nowNs = { 1_000_000L })
        // 对端时钟比本机快 500ms：本机 100 时发送，对端钟面读 600。
        estimator.onExchange(t1 = 100, t2 = 600, t3 = 600, t4 = 100)
        assertEquals(100L, estimator.mapToLocal(600L))
        assertEquals(-400L, estimator.mapToLocal(100L))
    }

    @Test
    fun symmetricDelayPreservesOffset() {
        val estimator = ClockSyncEstimator(nowNs = { 1_000_000L })
        // 单程 1ms、零偏移：t1=0 发送，对端 1ms 处收到，2ms 处回发，本机 3ms 收到。
        estimator.onExchange(t1 = 0, t2 = 1_000, t3 = 2_000, t4 = 3_000)
        assertEquals(1_000L, estimator.mapToLocal(1_000L))
    }

    @Test
    fun medianRejectsJitterOutliers() {
        val estimator = ClockSyncEstimator(windowSize = 5, nowNs = { 1_000_000L })
        // 一个 RTT 异常大的坏样本（offset 被严重拉偏）不应主导中位数。
        estimator.onExchange(t1 = 0, t2 = 500, t3 = 500, t4 = 1_000)
        estimator.onExchange(t1 = 2_000, t2 = 2_500, t3 = 2_500, t4 = 3_000)
        estimator.onExchange(t1 = 4_000, t2 = 4_500, t3 = 4_500, t4 = 5_000)
        estimator.onExchange(t1 = 6_000, t2 = 6_500, t3 = 6_500, t4 = 7_000)
        estimator.onExchange(t1 = 8_000, t2 = 60_000, t3 = 60_500, t4 = 200_000)
        val mapped = estimator.mapToLocal(10_000L)!!
        assertTrue("mapped=$mapped", mapped in 9_900L..10_100L)
    }

    @Test
    fun negativeRttSampleIgnored() {
        val estimator = ClockSyncEstimator(nowNs = { 1_000_000L })
        estimator.onExchange(t1 = 1_000, t2 = 100, t3 = 100, t4 = 200)
        assertFalse(estimator.hasEstimate)
        assertNull(estimator.mapToLocal(0L))
    }

    @Test
    fun staleSamplesExpire() {
        var now = 0L
        val estimator = ClockSyncEstimator(maxSampleAgeNs = 1_000_000L, nowNs = { now })
        now = 0
        estimator.onExchange(t1 = 0, t2 = 0, t3 = 0, t4 = 0)
        assertTrue(estimator.hasEstimate)
        now = 2_000_000
        assertFalse(estimator.hasEstimate)
    }

    @Test
    fun estimateRefreshesAfterClockReset() {
        var now = 0L
        val estimator = ClockSyncEstimator(maxSampleAgeNs = 1_000_000L, nowNs = { now })
        now = 0
        // 对端快 500ms，RTT 为 0。
        estimator.onExchange(t1 = 0, t2 = 500, t3 = 500, t4 = 0)
        assertEquals(-500L, estimator.mapToLocal(0L))
        // 对端时钟重置后偏移变为 900ms：旧样本过期、新样本接管。
        now = 5_000_000
        estimator.onExchange(t1 = 5_000_000, t2 = 5_000_900, t3 = 5_000_900, t4 = 5_000_000)
        assertEquals(-900L, estimator.mapToLocal(0L))
    }

    @Test
    fun stableEstimateRequiresEnoughSamples() {
        val estimator = ClockSyncEstimator(nowNs = { 1_000_000L })
        estimator.onExchange(t1 = 0, t2 = 1_000, t3 = 2_000, t4 = 3_000)
        estimator.onExchange(t1 = 10_000, t2 = 11_000, t3 = 12_000, t4 = 13_000)
        estimator.onExchange(t1 = 20_000, t2 = 21_000, t3 = 22_000, t4 = 23_000)
        assertFalse("3 个样本尚未收敛", estimator.hasStableEstimate)
        estimator.onExchange(t1 = 30_000, t2 = 31_000, t3 = 32_000, t4 = 33_000)
        assertTrue(estimator.hasStableEstimate)
    }

    @Test
    fun medianOffsetAndRttReportedInMilliseconds() {
        val estimator = ClockSyncEstimator(nowNs = { 1_000_000L })
        // 对端慢 2.5ms（offset=-2.5ms），单程 2ms，往返 4ms。
        estimator.onExchange(t1 = 10_000_000, t2 = 9_500_000, t3 = 10_500_000, t4 = 15_000_000)
        assertEquals(-2L, estimator.medianOffsetMs())
        assertEquals(4L, estimator.lastRttMs())
    }
}
