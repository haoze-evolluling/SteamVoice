package com.haoze.steamvoice

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class SteamVoicePacket(val session: Long, val sequence: Long, val pcm: ByteArray)

object SteamVoiceProtocol {
    const val port = 40125
    private const val headerSize = 24
    fun decode(data: ByteArray, length: Int): SteamVoicePacket? {
        if (length < headerSize || data.copyOfRange(0, 4).decodeToString() != "SV01" || data[4].toInt() != 1 || data[5].toInt() != 2) return null
        val buffer = ByteBuffer.wrap(data, 0, length).order(ByteOrder.BIG_ENDIAN)
        if (buffer.getInt(6) != 48000 || buffer.getShort(10).toInt() != 16) return null
        val payloadLength = buffer.getShort(20).toInt() and 0xffff
        if (payloadLength + headerSize != length) return null
        val session = buffer.getInt(12).toLong() and 0xffffffffL
        val sequence = buffer.getInt(16).toLong() and 0xffffffffL
        return SteamVoicePacket(session, sequence, data.copyOfRange(headerSize, length))
    }
}
