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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.haoze.steamvoice.ui.theme.SteamVoiceTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var running by mutableStateOf(false)
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { startReceiver() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repository = SettingsRepository(applicationContext)
        setContent { SteamVoiceTheme { ReceiverScreen(running, ::toggleReceiver) { startActivity(Intent(this, SettingsActivity::class.java).putExtra("receiver_running", running)) } } }
    }

    private fun toggleReceiver() {
        if (running) { stopService(Intent(this, AudioReceiverService::class.java)); running = false }
        else if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else startReceiver()
    }
    private fun startReceiver() { ContextCompat.startForegroundService(this, Intent(this, AudioReceiverService::class.java)); running = true }
}

@Composable
private fun ReceiverScreen(running: Boolean, onToggle: () -> Unit, onSettings: () -> Unit) {
    Scaffold(topBar = { Row(Modifier.fillMaxWidth().statusBarsPadding(), horizontalArrangement = Arrangement.End) { IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "设置") } } }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(28.dp), Arrangement.Center, Alignment.CenterHorizontally) {
            Text("SteamVoice", style = MaterialTheme.typography.headlineLarge)
            Text(if (running) "已准备好接收电脑音频" else "启动接收服务以连接电脑", modifier = Modifier.padding(top = 12.dp, bottom = 24.dp))
            Button(onClick = onToggle) { Text(if (running) "停止接收" else "开始接收") }
        }
    }
}

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repository = SettingsRepository(applicationContext)
        val receiverRunning = intent.getBooleanExtra("receiver_running", false)
        setContent {
            SteamVoiceTheme {
                val settings by repository.settings.collectAsState(initial = AudioSettings())
                SettingsScreen(settings, repository, {
                    if (receiverRunning) {
                        stopService(Intent(this, AudioReceiverService::class.java))
                        ContextCompat.startForegroundService(this, Intent(this, AudioReceiverService::class.java))
                    }
                }) { finish() }
            }
        }
    }
}

@Composable
private fun SettingsScreen(settings: AudioSettings, repository: SettingsRepository, restart: () -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    Scaffold(topBar = { Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().statusBarsPadding()) { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }; Text("设置", style = MaterialTheme.typography.titleLarge) } }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Text("播放延迟", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            SettingsGroup(
                items = LatencyMode.entries.map { mode ->
                    SettingRowData(mode.label, "约 ${mode.targetMs} ms", Icons.Default.Speed, mode == settings.latency) {
                        scope.launch { repository.setLatency(mode); restart() }
                    }
                }
            )
            Spacer(Modifier.height(28.dp))
            Text("音频码率", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            SettingsGroup(
                items = listOf(64, 96, 128, 192).map { bitrate ->
                    SettingRowData("$bitrate kbps", if (bitrate == 128) "推荐的音质和网络占用平衡" else "Opus 立体声传输", Icons.Default.GraphicEq, bitrate == settings.bitrateKbps) {
                        scope.launch { repository.setBitrate(bitrate); restart() }
                    }
                }
            )
        }
    }
}

private data class SettingRowData(
    val title: String,
    val subtitle: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val selected: Boolean,
    val onClick: () -> Unit,
)

@Composable
private fun SettingsGroup(items: List<SettingRowData>) {
    Column(verticalArrangement = spacedBy(8.dp)) {
        items.forEach { item ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = item.selected,
                        role = Role.RadioButton,
                        onClick = item.onClick,
                    ),
                shape = MaterialTheme.shapes.medium,
                color = if (item.selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            ) {
                SettingOption(item)
            }
        }
    }
}

@Composable
private fun SettingOption(item: SettingRowData) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(item.icon, null, tint = if (item.selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.primary)
        Spacer(Modifier.padding(horizontal = 12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.titleMedium)
            Text(item.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        RadioButton(selected = item.selected, onClick = null)
    }
}
