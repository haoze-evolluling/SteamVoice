# SteamVoice

SteamVoice 将 Windows 电脑正在播放的系统音频实时传输到同一局域网内的 Android 设备。电脑端通过 WASAPI loopback 捕获默认输出设备，使用 Opus 编码后经 UDP 发送；Android 端以媒体播放前台服务接收、解码并播放音频。两端均可主动发现对方并发起连接，首次连接需要对方确认授权。

当前版本：`1.2.0`

## 功能

- 双向发现与连接：Android 端可浏览局域网内的电脑并主动发起连接；电脑端也可扫描并连接 Android 设备，一台电脑可同时连接多台设备外放。
- 统一连接授权：无论哪端发起连接，目标设备都会弹出授权确认（应用内模态框或系统通知操作），支持“以后自动同意该设备”，授权基于稳定的设备标识而非 IP。已授权设备可在两端设置页中查看与移除。
- 多设备同步播放：音频包携带发送端时间戳，接收端通过 NTP 风格时钟同步估算偏差，按统一目标播放时间调度；声卡时钟漂移以 ±0.2% 内的速率微调平滑吸收，校准过程无跳音爆音。
- 同步校准动画：连接建立时两端以“检测 → 计算 → 同步 → 完成”的动态动画呈现真实校准进度（声波、设备节点连线脉冲与阶段指示），完成后展示实测时钟偏差与往返延迟，并自然过渡回正常播放界面；电脑端静音时会以 DTX 静音帧保活，空闲不再断连。
- Android 端视觉：Android 12+ 默认跟随系统“莫奈取色”（Material You 动态配色），低版本回退品牌配色。
- 中英文双语：桌面端与 Android 端均内置中文、英文两种语言，可在各自设置页切换（跟随系统 / 简体中文 / English），界面、通知与状态提示即时生效。
- 低延迟传输：48 kHz、双声道 Opus 音频，支持 10 ms 和 20 ms 音频帧。
- 自适应码率：接收端反馈丢包和积压状态，发送端在 48-192 kbps 间自动调整。
- 丢包处理：支持 Opus FEC 与丢包补偿。
- 接收端设置：可选择初始码率（64/96/128/192 kbps）和音频帧时长；设置会在连接时同步至电脑端。

## 使用方法

1. 确保 Windows 电脑和 Android 设备连接到同一个局域网。建议关闭访客网络、AP 隔离或 VPN。
2. 在 Android 设备安装并打开 SteamVoice，授予通知权限（Android 13 及以上）。应用会自动启动接收服务并显示常驻媒体播放通知。
3. 任一端发起连接：
   - 手机端：在“附近的电脑”列表中点击连接，电脑端弹出授权确认；
   - 电脑端：扫描并选择 Android 设备连接，手机端弹出授权确认（应用不在前台时通过通知按钮操作）。
4. 首次连接时可选“以后自动同意该设备”，之后该设备将免确认直连。
5. 电脑默认输出设备中的声音会开始在 Android 设备播放。使用完成后，在任一端断开。

> Windows 端采集的是系统默认播放设备的声音，不是麦克风输入。请先确认目标音频正从该默认输出设备播放。

## 项目结构

```text
SteamVoice/
├── SteamVoice-Android/   Android 接收端（Kotlin、Jetpack Compose、CMake）
├── SteamVoice-Desktop/   Windows 发送端（Go、Wails、Vue）
├── docs/                 协议与设计文档
└── SteamVoiceLogo.svg    项目图标
```

## 构建 Android 接收端

要求：Android Studio 或 JDK 11、Android SDK/NDK（项目指定 NDK `27.0.12077973`），以及可用于构建的 Android 设备或模拟器。最低支持 Android 7.0（API 24）。

```powershell
cd SteamVoice-Android
.\gradlew.bat assembleDebug
```

生成的 APK 位于：

```text
SteamVoice-Android/app/build/outputs/apk/debug/app-debug.apk
```

连接设备后可安装调试包：

```powershell
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

也可使用仓库内的 `debug_apk.bat` 和 `debug_apk_install.bat` 脚本。发布构建可执行 `.\gradlew.bat assembleRelease`；当前项目的 release 构建使用调试签名，仅适合内部测试，正式分发前应配置自己的签名密钥。

## 构建 Windows 发送端

Windows 发送端依赖 CGO。构建前请准备：

- Go 1.26 或兼容版本
- Node.js 与 npm
- Wails CLI：`go install github.com/wailsapp/wails/v2/cmd/wails@latest`
- Visual Studio Build Tools（Desktop C++ workload）或 MinGW-w64
- libopus 开发文件，以及能找到它的 `pkg-config`
- NSIS（仅制作安装程序时需要；`makensis` 必须在 `PATH` 中）

开发运行：

```powershell
cd SteamVoice-Desktop
$env:CGO_ENABLED = "1"
cd frontend
npm install
npm run build
cd ..
wails dev -tags steamvoice_opus
```

构建 Windows 安装程序：

```powershell
cd SteamVoice-Desktop
.\build.bat
```

该脚本会生成面向所有用户的 NSIS 安装程序，产物位于 `SteamVoice-Desktop/build/bin/`。在 CI 或其他脚本中调用时使用 `.\build.bat --no-pause`，避免构建结束后等待键盘输入。

## 网络与故障排查

- **找不到设备**：确认两端在同一子网，并检查 Windows 防火墙、路由器 AP 隔离和 VPN。电脑需允许 SteamVoice 的 UDP 40126 入站（授权与主动连接依赖此端口）。
- **已连接但没有声音**：检查 Windows 的默认播放设备，以及实际播放音频的应用是否输出到该设备。
- **Android 端无法保持接收**：允许应用发送通知并避免系统对应用施加省电限制；接收过程依赖前台服务通知。
- **Windows 端提示 Opus 编码器不可用**：确认以 `steamvoice_opus` 构建标签启动，并让 `pkg-config --exists opus` 成功。
- **声音断续**：优先确保 Wi-Fi 信号稳定；可在 Android 设置中切换 20 ms 帧长或调整初始码率。

## 协议概览

设备发现使用 `_steamvoice._udp.local.` 服务，TXT 记录中的 `role` 区分电脑（`pc`）与手机（`speaker`）。音频数据以 UDP 发送，当前实现使用版本 4 的 `SV01` 数据包（40 字节头，含发送端流时钟时间戳），固定为 48 kHz 双声道 Opus。控制面包括：

- `SVCR`：连接控制（请求/响应/断开），承载稳定设备标识，实现双向授权与即时断开。
- `SVTS`：NTP 风格时钟同步，接收端据此将音频时间戳映射到本机时钟以同步播放。
- `SVCT`：接收端反馈（丢包/积压/队列），供发送端自适应码率；同时附带校准阶段与时钟偏差、往返延迟，驱动两端的同步校准动画。
- `SVCS`：音频设置同步。

端口约定：Android 接收端固定监听 UDP 40125；电脑控制端口固定为 UDP 40126。具体字段可参考源代码中的 Android `SteamVoiceProtocol` 与桌面端 `internal/protocol`。

`docs/protocol.md` 记录的是早期 PCM v1 协议说明，与当前 Opus v4 实现不完全一致。

## 开发验证

```powershell
# Android 单元测试
cd SteamVoice-Android
.\gradlew.bat test

# Desktop Go 测试
cd ..\SteamVoice-Desktop
go test ./...
```

## 第三方组件

- [Opus](https://opus-codec.org/)：音频编解码。
- [Wails](https://wails.io/)：Windows 桌面应用框架。
- [Vue](https://vuejs.org/)：桌面端界面。
- [Jetpack Compose](https://developer.android.com/jetpack/compose)：Android 界面。
