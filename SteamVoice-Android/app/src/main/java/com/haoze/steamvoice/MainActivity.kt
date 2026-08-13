package com.haoze.steamvoice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.haoze.steamvoice.ui.theme.SteamVoiceTheme

class MainActivity : ComponentActivity() {
    private var running by mutableStateOf(false)
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { startReceiver() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { SteamVoiceTheme { ReceiverScreen(running, ::toggleReceiver) } }
    }

    private fun toggleReceiver() {
        if (running) {
            stopService(Intent(this, AudioReceiverService::class.java))
            running = false
        } else if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startReceiver()
        }
    }
    private fun startReceiver() { ContextCompat.startForegroundService(this, Intent(this, AudioReceiverService::class.java)); running = true }
}

@androidx.compose.runtime.Composable
private fun ReceiverScreen(running: Boolean, onToggle: () -> Unit) {
    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(28.dp), Arrangement.Center, Alignment.CenterHorizontally) {
            Text("SteamVoice", style = MaterialTheme.typography.headlineLarge)
            Text(if (running) "已准备好接收电脑音频" else "启动接收服务以连接电脑", modifier = Modifier.padding(top = 12.dp, bottom = 24.dp))
            Button(onClick = onToggle) { Text(if (running) "停止接收" else "开始接收") }
        }
    }
}
