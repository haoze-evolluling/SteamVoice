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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.core.content.ContextCompat
import com.haoze.steamvoice.ui.theme.SteamVoiceTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin



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
            if (result is PcConnector.ConnectResult.Accepted) {
                // 电脑同意后登记发送方，接收循环据此放行音频。
                runCatching {
                    ConnectionBus.queuedSender = ActivePc(pc.deviceId, pc.name, java.net.InetAddress.getByName(pc.host))
                }
            }
            runOnUiThread { onDone(result) }
        }.start()
    }

    private fun disconnectFromPc(pc: PcDevice) {
        if (selfId.isEmpty()) return
        val requestId = selfId
        ConnectionBus.localDisconnects.add(pc.deviceId)
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
    val activePc by ConnectionBus.activePc.collectAsState()
    val authPrompt by ConnectionBus.authPrompt.collectAsState()
    val calibration by ConnectionBus.calibration.collectAsState()
    val pcStates = remember { mutableStateMapOf<String, PcConnectionState>() }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // 校准完成后面板短暂停留展示“已同步”，再自然收起回到播放状态。
    var showCalibDone by remember { mutableStateOf(false) }
    LaunchedEffect(calibration) {
        when (calibration?.phase) {
            CalibrationPhase.DONE -> { showCalibDone = true; delay(1800); showCalibDone = false }
            else -> showCalibDone = false
        }
    }
    LaunchedEffect(Unit) {
        ConnectionBus.messages.collect { scope.launch { snackbar.showSnackbar(it) } }
    }
    LaunchedEffect(devices) {
        // 掉线的电脑从列表移除后，同步清理其连接状态。
        val online = devices.map { it.deviceId }.toSet()
        pcStates.keys.toList().forEach { if (it !in online) pcStates.remove(it) }
    }

    Scaffold(
        topBar = {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("SteamVoice", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        when {
                            activePc != null -> "正在接收 ${activePc?.name} 的音频"
                            receiverRunning -> "接收服务运行中 · 等待电脑连接"
                            else -> "正在启动接收服务…"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "设置") }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val calib = calibration
            AnimatedVisibility(
                visible = calib != null && (calib.phase != CalibrationPhase.DONE || showCalibDone),
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                if (calib != null) {
                    CalibrationPanel(state = calib, done = calib.phase == CalibrationPhase.DONE && showCalibDone)
                }
            }
            if (devices.isEmpty()) {
                EmptyDevices(Modifier.weight(1f))
            } else {
                LazyColumn(
                    Modifier.fillMaxSize().navigationBarsPadding(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp),
                ) {
                    item {
                        Row(
                            Modifier.padding(start = 20.dp, top = 4.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("附近的电脑", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            Text(
                                "${devices.size} 台",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(devices, key = { it.deviceId }) { pc ->
                        // 实际连接状态以接收服务的活动发送方为准。
                        val effective = if (activePc?.deviceId == pc.deviceId) PcConnectionState.CONNECTED else pcStates[pc.deviceId] ?: PcConnectionState.ONLINE
                        PcCard(
                            pc = pc,
                            state = effective,
                            onConnect = {
                                pcStates[pc.deviceId] = PcConnectionState.CONNECTING
                                onConnect(pc) { result ->
                                    when (result) {
                                        is PcConnector.ConnectResult.Accepted -> pcStates.remove(pc.deviceId)
                                        is PcConnector.ConnectResult.Denied -> {
                                            pcStates.remove(pc.deviceId)
                                            scope.launch { snackbar.showSnackbar("电脑端拒绝了连接请求") }
                                        }
                                        is PcConnector.ConnectResult.Timeout -> {
                                            pcStates.remove(pc.deviceId)
                                            scope.launch { snackbar.showSnackbar("无法连接 ${pc.name}，请确认电脑端正在运行") }
                                        }
                                    }
                                }
                            },
                            onDisconnect = {
                                pcStates.remove(pc.deviceId)
                                onDisconnect(pc)
                            },
                        )
                    }
                }
            }
        }
    }

    authPrompt?.let { prompt ->
        PcAuthDialog(
            prompt = prompt,
            onRespond = { allow, remember ->
                ConnectionBus.decisions.add(Triple(prompt.requestId, allow, remember))
            },
        )
    }
}

private val CALIBRATION_STEPS = listOf("检测", "计算", "同步", "完成")

private fun CalibrationPhase.label(): String = when (this) {
    CalibrationPhase.DETECT -> "正在检测电脑的音频信号"
    CalibrationPhase.CALCULATE -> "测量时钟偏差，与电脑时间基准对时"
    CalibrationPhase.SYNC -> "已对齐时钟，等待统一播放时刻"
    CalibrationPhase.DONE -> "已同步 · 回到正常播放"
}

/**
 * 多设备同步校准面板：波形动画 + 检测→计算→同步→完成阶段指示。
 * 阶段由接收服务的真实校准状态驱动（时钟对时、播放启动），
 * 完成后短暂展示“已同步”并自然收起，回到正常播放界面。
 */
@Composable
private fun CalibrationPanel(state: CalibrationState, done: Boolean) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CalibrationWave(Modifier.size(width = 64.dp, height = 28.dp), idle = done)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (done) "已同步" else "同步校准中",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    val stats = if (done || state.phase == CalibrationPhase.SYNC) {
                        val offset = state.offsetMs
                        val rtt = state.rttMs
                        if (offset != null && rtt != null) "时钟偏差 ${abs(offset)} ms · 往返 $rtt ms" else null
                    } else null
                    Text(
                        stats ?: state.phase.label(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    )
                }
            }
            CalibrationSteps(state.phase)
        }
    }
}

/** 随校准进度起伏的声波条；完成后静止为低幅波形。 */
@Composable
private fun CalibrationWave(modifier: Modifier = Modifier, idle: Boolean) {
    val transition = rememberInfiniteTransition(label = "calib-wave")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "calib-wave-phase",
    )
    val barColor = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val bars = 12
        val gap = 2.dp.toPx()
        val barWidth = (size.width - gap * (bars - 1)) / bars
        val mid = size.height / 2f
        for (i in 0 until bars) {
            val level = if (idle) 0.3f else abs(sin(phase + i * 0.55f)) * 0.85f + 0.15f
            val h = size.height * level * 0.9f
            drawRoundRect(
                color = barColor,
                topLeft = Offset(i * (barWidth + gap), mid - h / 2f),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
        }
    }
}

/** 检测 → 计算 → 同步 → 完成 的阶段指示，当前阶段脉动高亮。 */
@Composable
private fun CalibrationSteps(current: CalibrationPhase) {
    val activeIndex = when (current) {
        CalibrationPhase.DETECT -> 0
        CalibrationPhase.CALCULATE -> 1
        CalibrationPhase.SYNC -> 2
        CalibrationPhase.DONE -> 3
    }
    val pulse = rememberInfiniteTransition(label = "step-pulse")
    val scale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "step-pulse-scale",
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        CALIBRATION_STEPS.forEachIndexed { index, label ->
            if (index > 0) {
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier
                        .width(16.dp)
                        .height(2.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = if (index <= activeIndex) 0.7f else 0.25f)),
                )
                Spacer(Modifier.width(6.dp))
            }
            val active = index == activeIndex && current != CalibrationPhase.DONE
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(
                    shape = CircleShape,
                    color = if (index <= activeIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(10.dp).then(if (active) Modifier.scale(scale) else Modifier),
                ) {}
                Spacer(Modifier.height(4.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (index <= activeIndex) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.55f),
                )
            }
        }
    }
}

@Composable
private fun PcAuthDialog(prompt: PcAuthPrompt, onRespond: (allow: Boolean, remember: Boolean) -> Unit) {
    var remember by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = { onRespond(false, false) },
        title = { Text("连接请求") },
        text = {
            Column {
                Text("${prompt.name} 想要把电脑音频推送到本机播放。", style = MaterialTheme.typography.bodyMedium)
                Text(prompt.host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = remember, onCheckedChange = { remember = it })
                    Text("以后自动同意该设备", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onRespond(true, remember) }) { Text("允许") } },
        dismissButton = { TextButton(onClick = { onRespond(false, false) }) { Text("拒绝") } },
    )
}

@Composable
private fun EmptyDevices(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(72.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Computer, null, modifier = Modifier.size(34.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Text("正在搜索附近的电脑", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text(
                "请确认电脑端 SteamVoice 正在运行，且手机和电脑连接到同一 Wi-Fi 网络。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            CircularProgressIndicator(Modifier.size(20.dp).padding(top = 8.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun PcCard(pc: PcDevice, state: PcConnectionState, onConnect: () -> Unit, onDisconnect: () -> Unit) {
    val connected = state == PcConnectionState.CONNECTED
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (connected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface, modifier = Modifier.size(42.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Computer, null, tint = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.padding(horizontal = 12.dp))
            Column(Modifier.weight(1f)) {
                Text(pc.name, style = MaterialTheme.typography.titleMedium)
                Text(pc.host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StateDot(state)
                    Text(
                        state.label(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            when (state) {
                PcConnectionState.CONNECTED -> OutlinedButton(onClick = onDisconnect) { Text("断开") }
                PcConnectionState.CONNECTING -> CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp)
                PcConnectionState.ONLINE -> Button(onClick = onConnect) { Text("连接") }
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
        val trustRepository = PcTrustRepository(applicationContext)
        setContent {
            SteamVoiceTheme {
                val settings by repository.settings.collectAsState(initial = AudioSettings())
                // 音频参数只影响下一次连接协商，不需要重启接收服务
                // （重启会断开正在进行的连接）。
                SettingsScreen(settings, repository, trustRepository) { finish() }
            }
        }
    }
}

@Composable
private fun SettingsScreen(settings: AudioSettings, repository: SettingsRepository, trustRepository: PcTrustRepository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val trustedPcs by trustRepository.trusted.collectAsState(initial = emptyMap())
    Scaffold(topBar = {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(end = 16.dp),
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            Text("设置", style = MaterialTheme.typography.titleLarge)
        }
    }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            SettingsSection(title = "初始发送码率", description = "电脑端发起连接时使用的初始 Opus 码率，播放中会根据网络自动调整") {
                SettingsGroup(
                    items = listOf(64, 96, 128, 192).map { bitrate ->
                        SettingRowData(
                            "$bitrate kbps",
                            if (bitrate == 128) "推荐 · 覆盖大多数家庭网络" else null,
                            Icons.Default.GraphicEq,
                            bitrate == settings.initialBitrateKbps,
                        ) {
                            scope.launch { repository.setInitialBitrate(bitrate) }
                        }
                    }
                )
            }
            SettingsSection(title = "音频帧时长", description = "帧长越小延迟越低，但对网络抖动更敏感") {
                SettingsGroup(items = listOf(10, 20).map { frame ->
                    SettingRowData("$frame ms", if (frame == 10) "推荐 · 延迟更低" else "更稳定", Icons.Default.GraphicEq, frame == settings.frameMs) {
                        scope.launch { repository.setFrameMs(frame) }
                    }
                })
            }
            SettingsSection(title = "已授权电脑", description = "以下电脑连接本机时无需再次确认") {
                if (trustedPcs.isEmpty()) {
                    Text(
                        "暂无已授权电脑。电脑主动连接时，可以选择“以后自动同意该设备”。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        trustedPcs.forEach { (id, name) ->
                            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Default.Computer, null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.padding(horizontal = 10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(name.ifBlank { id.take(12) }, style = MaterialTheme.typography.bodyLarge)
                                        Text(id.take(16), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    OutlinedButton(
                                        onClick = { scope.launch { trustRepository.untrust(id) } },
                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                                        modifier = Modifier.heightIn(min = 36.dp),
                                    ) { Text("移除") }
                                }
                            }
                        }
                    }
                }
            }
            SettingsSection(title = "接收格式", description = "本机作为接收端解码播放的音频格式") {
                ProtocolInfoGroup()
            }
            SettingsSection(title = "关于", description = null) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProtocolInfoRow("多设备同步", "已启用 · 时钟对齐播放")
                    ProtocolInfoRow("传输协议", "UDP 局域网 · Opus 编码")
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, description: String?, content: @Composable () -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (description != null) {
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
        } else {
            Spacer(Modifier.height(8.dp))
        }
        content()
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
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private data class SettingRowData(
    val title: String,
    val subtitle: String?,
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
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(item.icon, null, tint = if (item.selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.primary)
        Spacer(Modifier.padding(horizontal = 12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyLarge)
            if (item.subtitle != null) {
                Text(item.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        RadioButton(selected = item.selected, onClick = null)
    }
}
