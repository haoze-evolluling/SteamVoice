package com.haoze.steamvoice

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.net.DatagramPacket
import java.net.DatagramSocket
import kotlin.concurrent.thread

class AudioReceiverService : Service() {
    private var socket: DatagramSocket? = null
    private var worker: Thread? = null
    private var nsd: NsdManager? = null
    private var registration: NsdManager.RegistrationListener? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int { startForeground(8, notification()); registerService(); if (worker == null) worker = thread(name = "steamvoice-udp") { receiveLoop() }; return START_STICKY }
    override fun onDestroy() { socket?.close(); worker?.interrupt(); registration?.let { nsd?.unregisterService(it) }; super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    private fun notification() = NotificationCompat.Builder(this, "steamvoice-receiver").setSmallIcon(android.R.drawable.ic_lock_silent_mode_off).setContentTitle(getString(R.string.app_name)).setContentText(getString(R.string.receiver_notification)).setOngoing(true).build().also { val manager=getSystemService(NotificationManager::class.java); manager.createNotificationChannel(NotificationChannel("steamvoice-receiver",getString(R.string.receiver_channel),NotificationManager.IMPORTANCE_LOW)) }
    private fun registerService() { nsd=getSystemService(Context.NSD_SERVICE) as NsdManager; val info=NsdServiceInfo().apply { serviceName="SteamVoice-${android.os.Build.MODEL}"; serviceType="_steamvoice._udp."; port=SteamVoiceProtocol.port }; registration=object:NsdManager.RegistrationListener { override fun onServiceRegistered(i:NsdServiceInfo){}; override fun onRegistrationFailed(i:NsdServiceInfo,e:Int){}; override fun onServiceUnregistered(i:NsdServiceInfo){}; override fun onUnregistrationFailed(i:NsdServiceInfo,e:Int){} }; nsd?.registerService(info,NsdManager.PROTOCOL_DNS_SD,registration) }
    private fun receiveLoop() { val track=newTrack(); val buffer=PacketJitterBuffer(); socket=DatagramSocket(SteamVoiceProtocol.port); val bytes=ByteArray(2048); while (!Thread.currentThread().isInterrupted) { try { val datagram=DatagramPacket(bytes,bytes.size); socket?.receive(datagram); SteamVoiceProtocol.decode(datagram.data,datagram.length)?.let(buffer::offer); while(true) { val pcm=buffer.take()?:break; track.write(pcm,0,pcm.size,AudioTrack.WRITE_BLOCKING) } } catch (_: Exception) { break } }; track.stop(); track.release() }
    private fun newTrack(): AudioTrack { val min=AudioTrack.getMinBufferSize(48000,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_16BIT); return AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()).setAudioFormat(AudioFormat.Builder().setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setBufferSizeInBytes(min * 4).setTransferMode(AudioTrack.MODE_STREAM).build().also { it.play() } }
}
