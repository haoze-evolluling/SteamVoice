package com.haoze.steamvoice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PeerCalibrationControlTest {
    @Test fun roundTripPreservesLongIdsAndMeasurements() {
        val value = PeerCalibrationControl(PeerCalibrationControl.COMMIT, 7L, "android-" + "a".repeat(40), "pc-123", 99L, -4_000_000L, 12L)
        assertEquals(value, PeerCalibrationControl.decode(value.encode(), value.encode().size))
    }

    @Test fun rejectsMalformedLengthsAndKinds() {
        val value = PeerCalibrationControl(PeerCalibrationControl.REQUEST, 1L, "a", "pc")
        val bytes = value.encode()
        bytes[5] = 99
        assertNull(PeerCalibrationControl.decode(bytes, bytes.size))
        assertNull(PeerCalibrationControl.decode(bytes, bytes.size - 1))
    }
}
