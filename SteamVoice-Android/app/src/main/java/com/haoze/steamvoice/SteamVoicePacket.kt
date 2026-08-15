package com.haoze.steamvoice

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class SteamVoicePacket(
    val codec: Int,
    val sampleRate: Int,
    val channels: Int,
    val bitrate: Int,
    val frameMilliseconds: Int,
    val session: Long,
    val sequence: Long,
    val opus: ByteArray,
    val flags: Int = 0,
)

data class ReceiverFeedback(val session: Long, val highestSeq: Long, val received: Long, val lost: Long, val queue: Int, val bitrate: Int) {
    fun encode(): ByteArray {
        val b = ByteBuffer.allocate(30).order(ByteOrder.BIG_ENDIAN)
        b.put("SVCT".toByteArray()).put(3).put(1).putShort(0)
        b.putInt(session.toInt()).putInt(highestSeq.toInt()).putInt(received.toInt()).putInt(lost.toInt()).putShort(queue.toShort()).putInt(bitrate)
        return b.array()
    }
}

object SteamVoiceProtocol {
    const val port = 40125
    const val version = 3
    const val codecOpus = 1
    const val sampleRate = 48000
    const val channels = 2
    const val frameMilliseconds = 10
    private const val headerSize = 32
    fun decode(data: ByteArray, length: Int): SteamVoicePacket? {
        if (length < headerSize || data.copyOfRange(0, 4).decodeToString() != "SV01" || data[4].toInt() != version || data[5].toInt() != codecOpus) return null
        val buffer = ByteBuffer.wrap(data, 0, length).order(ByteOrder.BIG_ENDIAN)
        val rate = buffer.getInt(6)
        val channelCount = data[10].toInt() and 0xff
        val bitrate = buffer.getInt(12)
        val payloadLength = buffer.getShort(24).toInt() and 0xffff
        val frameMs = buffer.getShort(26).toInt() and 0xffff
        if (rate != sampleRate || channelCount != channels || frameMs != frameMilliseconds) return null
        if (payloadLength + headerSize != length) return null
        val session = buffer.getInt(16).toLong() and 0xffffffffL
        val sequence = buffer.getInt(20).toLong() and 0xffffffffL
        return SteamVoicePacket(data[5].toInt(), rate, channelCount, bitrate, frameMs, session, sequence, data.copyOfRange(headerSize, length), data[28].toInt() and 0xff)
    }
}
