package com.haoze.steamvoice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ConnControlTest {

    private fun roundTrip(control: ConnControl): ConnControl? {
        val encoded = control.encode()
        return ConnControl.decode(encoded, encoded.size)
    }

    @Test
    fun requestRoundTrip() {
        val decoded = roundTrip(ConnControl(ConnControl.KIND_REQUEST, "pc-1234567890abcdef", "DESKTOP-ABC"))
        assertNotNull(decoded)
        assertEquals(ConnControl.KIND_REQUEST, decoded!!.kind)
        assertEquals("pc-1234567890abcdef", decoded.deviceId)
        assertEquals("DESKTOP-ABC", decoded.name)
    }

    @Test
    fun responseRoundTripAllowAndDeny() {
        for (allow in listOf(true, false)) {
            val decoded = roundTrip(ConnControl(ConnControl.KIND_RESPONSE, "android-id", allow = allow))
            assertNotNull(decoded)
            assertEquals(ConnControl.KIND_RESPONSE, decoded!!.kind)
            assertEquals(allow, decoded.allow)
        }
    }

    @Test
    fun byeRoundTrip() {
        val decoded = roundTrip(ConnControl(ConnControl.KIND_BYE, "android-id"))
        assertNotNull(decoded)
        assertEquals(ConnControl.KIND_BYE, decoded!!.kind)
        assertEquals("android-id", decoded.deviceId)
    }

    @Test
    fun rejectsForeignAndMalformedDatagrams() {
        assertNull(ConnControl.decode(ByteArray(4), 4))
        assertNull(ConnControl.decode("SVCT".toByteArray() + ByteArray(26), 30))
        // wrong version
        val versioned = ConnControl(ConnControl.KIND_BYE, "android-id").encode()
        versioned[4] = 9
        assertNull(ConnControl.decode(versioned, versioned.size))
        // unknown kind
        val kind = ConnControl(ConnControl.KIND_BYE, "android-id").encode()
        kind[5] = 7
        assertNull(ConnControl.decode(kind, kind.size))
        // empty device id in request
        val empty = byteArrayOf(0) + "name".toByteArray()
        val malformed = "SVCR".toByteArray() + byteArrayOf(3, ConnControl.KIND_REQUEST.toByte(), 0, 0) + empty
        assertNull(ConnControl.decode(malformed, malformed.size))
        // response without decision byte
        val short = "SVCR".toByteArray() + byteArrayOf(3, ConnControl.KIND_RESPONSE.toByte(), 0, 0) + "id".toByteArray() + byteArrayOf(0)
        assertNull(ConnControl.decode(short, short.size))
    }

    @Test
    fun roundTripsLongDeviceId() {
        val longId = buildString { repeat(64) { append('a') } }
        val decoded = roundTrip(ConnControl(ConnControl.KIND_REQUEST, longId, "PC"))
        assertNotNull(decoded)
        assertEquals(longId, decoded!!.deviceId)
    }

    @Test
    fun encodeRejectsOversizedDeviceId() {
        val longId = buildString { repeat(65) { append('a') } }
        try {
            ConnControl(ConnControl.KIND_BYE, longId).encode()
            throw AssertionError("oversized id accepted")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun truncatedNameIsAccepted() {
        val longName = buildString { repeat(100) { append('名') } }
        val encoded = ConnControl(ConnControl.KIND_REQUEST, "id", longName).encode()
        val decoded = ConnControl.decode(encoded, encoded.size)
        assertNotNull(decoded)
        assertTrue(decoded!!.name.toByteArray(Charsets.UTF_8).size <= ConnControl.MAX_NAME_LEN)
    }

    @Test
    fun randomBytesRarelyDecode() {
        var decoded = 0
        repeat(200) {
            val bytes = Random.nextBytes(40)
            if (ConnControl.decode(bytes, bytes.size) != null) decoded++
        }
        assertTrue("random datagrams decoded unexpectedly", decoded <= 1)
    }
}
