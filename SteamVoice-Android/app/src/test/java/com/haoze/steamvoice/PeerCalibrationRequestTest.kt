package com.haoze.steamvoice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerCalibrationRequestTest {
    @Test
    fun matchingActivePcWithNoOperationIsAcceptedImmediately() {
        assertTrue(acceptsPeerCalibrationRequest("pc-1", "pc-1", 0L))
    }

    @Test
    fun requestForAnotherPcIsRejected() {
        assertFalse(acceptsPeerCalibrationRequest("pc-2", "pc-1", 0L))
    }

    @Test
    fun requestIsRejectedWhileAnotherCalibrationIsActive() {
        assertFalse(acceptsPeerCalibrationRequest("pc-1", "pc-1", 42L))
    }
}
