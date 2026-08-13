package com.haoze.steamvoice

import java.util.TreeMap

class PacketJitterBuffer(private val targetPackets: Int = 4) {
    private val packets = TreeMap<Long, ByteArray>()
    private var session = -1L
    private var nextSequence = -1L
    fun offer(packet: SteamVoicePacket) {
        if (packet.session != session) { packets.clear(); session = packet.session; nextSequence = packet.sequence }
        if (packet.sequence >= nextSequence) packets.putIfAbsent(packet.sequence, packet.pcm)
    }
    fun take(): ByteArray? {
        if (nextSequence < 0 || packets.size < targetPackets) return null
        val pcm = packets.remove(nextSequence)
        nextSequence++
        return pcm
    }
    fun clear() { packets.clear(); nextSequence = -1L; session = -1L }
}
