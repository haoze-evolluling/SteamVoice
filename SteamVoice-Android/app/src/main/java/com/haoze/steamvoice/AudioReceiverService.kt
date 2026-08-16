package com.haoze.steamvoice

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.net.DatagramPacket
import java.net.DatagramSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class AudioReceiverService : Service() {
    private companion object { const val TAG = "SteamVoiceReceiver"; const val MAX_UDP_PACKET = 65535 }
    @Volatile private var stopRequested = false
    private var socket: DatagramSocket? = null
    private var worker: Thread? = null
    private var nsd: NsdManager? = null
    private var registration: NsdManager.RegistrationListener? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int { Log.i(TAG, "receiver service starting port=${SteamVoiceProtocol.port}"); stopRequested = false; startForeground(8, notification()); registerService(); if (worker?.isAlive != true) worker = thread(name = "steamvoice-udp") { receiveLoop() }; return START_NOT_STICKY }
    override fun onDestroy() { stopRequested = true; socket?.close(); worker?.interrupt(); registration?.let { nsd?.unregisterService(it) }; stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    private fun notification() = NotificationCompat.Builder(this, "steamvoice-receiver").setSmallIcon(android.R.drawable.ic_lock_silent_mode_off).setContentTitle(getString(R.string.app_name)).setContentText(getString(R.string.receiver_notification)).setOngoing(true).build().also { val manager=getSystemService(NotificationManager::class.java); manager.createNotificationChannel(NotificationChannel("steamvoice-receiver",getString(R.string.receiver_channel),NotificationManager.IMPORTANCE_LOW)) }
    private fun registerService() { val settings = runBlocking { SettingsRepository(this@AudioReceiverService).settings.first() }; nsd=getSystemService(Context.NSD_SERVICE) as NsdManager; val manufacturer=android.os.Build.MANUFACTURER.trim(); val model=android.os.Build.MODEL.trim(); val friendly=listOf(manufacturer,model).filter { it.isNotEmpty() }.distinct().joinToString(" ").ifEmpty { "Android device" }; val info=NsdServiceInfo().apply { serviceName="SteamVoice-$friendly"; serviceType="_steamvoice._udp."; port=SteamVoiceProtocol.port; setAttribute("codec", "opus"); setAttribute("sample_rate", SteamVoiceProtocol.sampleRate.toString()); setAttribute("channels", SteamVoiceProtocol.channels.toString()); setAttribute("bitrate", (settings.initialBitrateKbps * 1000).toString()); setAttribute("frame_ms", SteamVoiceProtocol.supportedFrameMilliseconds.joinToString(",")) }; registration=object:NsdManager.RegistrationListener { override fun onServiceRegistered(i:NsdServiceInfo){ Log.i(TAG,"advertising ${i.serviceName}") }; override fun onRegistrationFailed(i:NsdServiceInfo,e:Int){ Log.e(TAG,"NSD registration failed: $e") }; override fun onServiceUnregistered(i:NsdServiceInfo){}; override fun onUnregistrationFailed(i:NsdServiceInfo,e:Int){ Log.e(TAG,"NSD unregistration failed: $e") } }; nsd?.registerService(info,NsdManager.PROTOCOL_DNS_SD,registration) }
    private fun receiveLoop() {
        val settings = runBlocking { SettingsRepository(this@AudioReceiverService).settings.first() }
        val track = newTrack()
        val buffer = PacketJitterBuffer(targetPackets = 1)
        socket = DatagramSocket(SteamVoiceProtocol.port)
        val bytes = ByteArray(MAX_UDP_PACKET)
        var received = 0L
        var decoded = 0L
        val decoderHandle = OpusNative.createDecoder(48000, 2)
        check(decoderHandle != 0L) { "native Opus decoder unavailable" }
        var lastAddress: java.net.InetAddress? = null
        var lastPort = 0
        var activeSession = 0L
        var highest = 0L; var receivedCount = 0L; var lostCount = 0L; var lastFeedback = System.nanoTime()
        var fecPending = false
        try {
            socket?.soTimeout = 50
            while (!stopRequested && !Thread.currentThread().isInterrupted) {
                val datagram = DatagramPacket(bytes, bytes.size)
                var gotPacket = true
                try { socket?.receive(datagram) } catch (_: java.net.SocketTimeoutException) { gotPacket = false }
                if (!gotPacket) { if (System.nanoTime() - lastFeedback > 200_000_000L) { sendFeedback(lastAddress, lastPort, activeSession, highest, receivedCount, lostCount, buffer.queuedPackets(), settings.initialBitrateKbps * 1000); lastFeedback = System.nanoTime() }; continue }
                received++
                val packet = SteamVoiceProtocol.decode(datagram.data, datagram.length)
                if (packet == null) { Log.w(TAG, "invalid UDP packet length=${datagram.length}"); continue }
                lastAddress = datagram.address; lastPort = datagram.port
                if (activeSession != 0L && activeSession != packet.session) { highest = 0; receivedCount = 0; lostCount = 0; fecPending = false }
                activeSession = packet.session
                if (packet.sequence > highest) { lostCount += (packet.sequence - highest - if (receivedCount == 0L) 0 else 1).coerceAtLeast(0); highest = packet.sequence }
                receivedCount++
                decoded++
                buffer.offer(packet)
                while (true) {
                    val item = buffer.takeItem() ?: break
                    when (item) {
                        is PacketJitterBuffer.Item.Packet -> {
                            if (fecPending) { val recovered = if ((item.value.flags and 1) != 0) OpusNative.decode(decoderHandle, item.value.opus, true) else OpusNative.decodePlc(decoderHandle, 480 * item.value.frameMilliseconds / 10); recovered?.let { track.write(it, 0, it.size, AudioTrack.WRITE_BLOCKING) }; fecPending = false }
                            OpusNative.decode(decoderHandle, item.value.opus, false)?.let { pcm -> val written = track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING); if (written < 0) Log.e(TAG, "AudioTrack.write failed: $written") }
                        }
                        PacketJitterBuffer.Item.Gap -> { lostCount++; fecPending = true }
                    }
                }
                if (System.nanoTime() - lastFeedback > 200_000_000L) { sendFeedback(lastAddress, lastPort, activeSession, highest, receivedCount, lostCount, buffer.queuedPackets(), settings.initialBitrateKbps * 1000); lastFeedback = System.nanoTime() }
            }
        } catch (e: Exception) {
            if (!stopRequested) Log.e(TAG, "receiver loop stopped", e)
        } finally {
            OpusNative.destroyDecoder(decoderHandle)
            Log.i(TAG, "receiver stopped received=$received decoded=$decoded")
            track.stop()
            track.release()
        }
    }
    private fun sendFeedback(address: java.net.InetAddress?, port: Int, session: Long, highest: Long, received: Long, lost: Long, queue: Int, bitrate: Int) { if (address == null || port == 0) return; try { val b=ReceiverFeedback(session, highest, received, lost, queue, bitrate).encode(); socket?.send(DatagramPacket(b,b.size,address,port)) } catch (_: Exception) {} }
    private fun newTrack(): AudioTrack { val min=AudioTrack.getMinBufferSize(48000,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_16BIT); check(min > 0) { "AudioTrack buffer size unavailable: $min" }; return AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build()).setAudioFormat(AudioFormat.Builder().setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setBufferSizeInBytes(min * 2).setTransferMode(AudioTrack.MODE_STREAM).build().also { it.play(); Log.i(TAG,"AudioTrack started buffer=$min configured=${min * 2} state=${it.state}") } }
}
