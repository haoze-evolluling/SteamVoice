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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.core.content.ContextCompat
import com.haoze.steamvoice.ui.theme.SteamVoiceTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch



/** 电脑在设备列表中的连接状态。 */
enum class PcConnectionState { ONLINE, CONNECTING, CONNECTED }

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { startReceiver() }
    private val discovery by lazy { PcDiscovery(this) }
    private val connector = PcConnector()
    private val repository by lazy { SettingsRepository(applicationContext) }
    private var selfId by mutableStateOf("")
    private val selfName: String by lazy { DeviceIdentity.friendlyName() }
    private var receiverRunning by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch(Dispatchers.IO) { selfId = repository.settings.first().deviceId }
        setContent {
            SteamVoiceTheme {
                ReceiverScreen(
                    discovery = discovery,
                    connector = connector,
                    selfId = selfId,
                    selfName = selfName,
                    receiverRunning = receiverRunning,
                    onConnect = ::connectToPc,
                    onDisconnect = ::disconnectFromPc,
                    onSettings = { startActivity(Intent(this, SettingsActivity::class.java).putExtra("receiver_running", receiverRunning)) },
                )
            }
        }
        ensureReceiverRunning()
    }

    override fun onStart() { super.onStart(); discovery.start() }
    override fun onStop() { super.onStop(); discovery.stop() }

    private fun ensureReceiverRunning() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        startReceiver()
    }

    private fun startReceiver() {
        ContextCompat.startForegroundService(this, Intent(this, AudioReceiverService::class.java))
        receiverRunning = true
    }

    private fun connectToPc(pc: PcDevice, onDone: (PcConnector.ConnectResult) -> Unit) {
        if (selfId.isEmpty()) return
        ensureReceiverRunning()
        val requestId = selfId
        Thread {
            val result = connector.request(pc, requestId, selfName)
            runOnUiThread { onDone(result) }
        }.start()
    }

    private fun disconnectFromPc(pc: PcDevice) {
        if (selfId.isEmpty()) return
        val requestId = selfId
        Thread { connector.bye(pc, requestId) }.start()
    }
}

@Composable
private fun ReceiverScreen(
    discovery: PcDiscovery,
    connector: PcConnector,
    selfId: String,
    selfName: String,
    receiverRunning: Boolean,
    onConnect: (PcDevice, (PcConnector.ConnectResult) -> Unit) -> Unit,
    onDisconnect: (PcDevice) -> Unit,
    onSettings: () -> Unit,
) {
    val devices by discovery.devices.collectAsState()
    val pcStates = remember { mutableStateMapOf<String, PcConnectionState>() }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(devices) {
        // 掉线的电脑从列表移除后，同步清理其连接状态。
        val online = devices.map { it.deviceId }.toSet()
        pcStates.keys.toList().forEach { if (it !in online) pcStates.remove(it) }
    }

    Scaffold(
        topBar = {
            Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("SteamVoice", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            if (receiverRunning) "接收服务运行中 · 电脑可直接连接本机" else "正在启动接收服务…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "设置") }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (devices.isEmpty()) {
            EmptyDevices(Modifier.padding(padding))
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text(
                        "附近的电脑",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 2.dp),
                    )
                }
                items(devices, key = { it.deviceId }) { pc ->
                    PcCard(
                        pc = pc,
                        state = pcStates[pc.deviceId] ?: PcConnectionState.ONLINE,
                        onConnect = {
                            pcStates[pc.deviceId] = PcConnectionState.CONNECTING
                            onConnect(pc) { result ->
                                when (result) {
                                    is PcConnector.ConnectResult.Accepted -> pcStates[pc.deviceId] = PcConnectionState.CONNECTED
                                    is PcConnector.ConnectResult.Denied -> {
                                        pcStates[pc.deviceId] = PcConnectionState.ONLINE
                                        scope.launch { snackbar.showSnackbar("电脑端拒绝了连接请求") }
                                    }
                                    is PcConnector.ConnectResult.Timeout -> {
                                        pcStates[pc.deviceId] = PcConnectionState.ONLINE
                                        scope.launch { snackbar.showSnackbar("无法连接 ${pc.name}，请确认电脑端正在运行") }
                                    }
                                }
                            }
                        },
                        onDisconnect = {
                            pcStates[pc.deviceId] = PcConnectionState.ONLINE
                            onDisconnect(pc)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyDevices(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Default.Computer, null, modifier = Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("正在搜索附近的电脑", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text(
                "请确认电脑端 SteamVoice 正在运行，且手机和电脑连接到同一局域网。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            CircularProgressIndicator(Modifier.size(22.dp).padding(top = 6.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun PcCard(pc: PcDevice, state: PcConnectionState, onConnect: () -> Unit, onDisconnect: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Computer, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.padding(horizontal = 12.dp))
            Column(Modifier.weight(1f)) {
                Text(pc.name, style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StateDot(state)
                    Text(
                        state.label(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(pc.host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state == PcConnectionState.CONNECTED) {
                OutlinedButton(onClick = onDisconnect) { Text("断开") }
            } else if (state == PcConnectionState.CONNECTING) {
                CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp)
            } else {
                Button(onClick = onConnect) { Text("连接") }
            }
        }
    }
}

@Composable
private fun StateDot(state: PcConnectionState) {
    val color = when (state) {
        PcConnectionState.CONNECTED -> MaterialTheme.colorScheme.primary
        PcConnectionState.CONNECTING -> MaterialTheme.colorScheme.tertiary
        PcConnectionState.ONLINE -> MaterialTheme.colorScheme.outline
    }
    Surface(color = color, shape = CircleShape, modifier = Modifier.size(8.dp)) {}
}

private fun PcConnectionState.label(): String = when (this) {
    PcConnectionState.ONLINE -> "在线"
    PcConnectionState.CONNECTING -> "连接中…"
    PcConnectionState.CONNECTED -> "已连接"
}

object DeviceIdentity {
    fun friendlyName(): String {
        val manufacturer = android.os.Build.MANUFACTURER.trim()
        val model = android.os.Build.MODEL.trim()
        return listOf(manufacturer, model).filter { it.isNotEmpty() }.distinct().joinToString(" ").ifEmpty { "Android 设备" }
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
            Text("初始发送码率", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            SettingsGroup(
                items = listOf(64, 96, 128, 192).map { bitrate ->
                    SettingRowData(
                        "$bitrate kbps",
                        if (bitrate == 128) "电脑端初始编码值，连接后会自动调整" else "电脑端连接时使用的初始 Opus 码率",
                        Icons.Default.GraphicEq,
                        bitrate == settings.initialBitrateKbps,
                    ) {
                        scope.launch { repository.setInitialBitrate(bitrate); restart() }
                    }
                }
            )
            Spacer(Modifier.height(28.dp))
            Text("音频帧时长", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            SettingsGroup(items = listOf(10, 20).map { frame ->
                SettingRowData("$frame ms", "电脑端连接时使用的音频帧长", Icons.Default.GraphicEq, frame == settings.frameMs) {
                    scope.launch { repository.setFrameMs(frame); restart() }
                }
            })
            Spacer(Modifier.height(28.dp))
            Text("接收格式", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            ProtocolInfoGroup()
        }
    }
}

@Composable
private fun ProtocolInfoGroup() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ProtocolInfoRow("编码器", "Opus")
        ProtocolInfoRow("采样率", "${SteamVoiceProtocol.sampleRate} Hz")
        ProtocolInfoRow("声道", "${SteamVoiceProtocol.channels} 声道立体声")
        ProtocolInfoRow("支持帧长", SteamVoiceProtocol.supportedFrameMilliseconds.sorted().joinToString(" / ") { "$it ms" })
    }
}

@Composable
private fun ProtocolInfoRow(title: String, value: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.GraphicEq, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.padding(horizontal = 12.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
