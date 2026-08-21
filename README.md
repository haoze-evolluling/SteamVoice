# SteamVoice

SteamVoice 是一款专为局域网设计的超低延迟无线音频串流工具，支持将 Windows 电脑正在播放的系统音频实时推送到一台或多台 Android 设备。

- **发送端（Windows）**：通过 WASAPI Loopback 捕获系统默认输出设备音频，经 Opus 实时编码后通过 UDP 传输。
- **接收端（Android）**：前台媒体服务结合 Jitter Buffer、高精度 NTP 时钟同步与自适应重采样（±0.2% 微调）实现超低延迟、无爆音的多设备平滑同步播放。

---

## ✨ 核心特性

- **低延迟高保真音频传输**
  - 基于 48 kHz 双声道 Opus 实时编解码，支持 10 ms / 20 ms 帧长。
  - 支持 64 / 96 / 128 / 192 kbps 动态码率，内置 Opus In-band FEC（前向纠错）与丢包补偿。
  - 支持静音 DTX（非连续传输）保活，无音频输出时自动降低带宽与功耗并维持连接。

- **双向发现与设备级安全授权**
  - 基于 mDNS（`_steamvoice._udp.local.`）自动发现局域网内的 Windows 电脑与 Android 接收端。
  - 支持两端双向发起连接；首次连接需在被连接端确认授权（支持应用内弹窗与系统通知操作）。
  - 基于稳定的设备唯一标识（`device_id`）管理信任列表，支持免确认快速重连。

- **多设备同步与独立声道路由**
  - **高精度时钟同步**：通过 NTP 风格往返探测估算时钟偏差与 RTT，采用平滑时钟对齐与重采样微调，多设备外放无回音、无跳音。
  - **独立声道路由**：单台电脑可同时连接多台 Android 设备，支持为每台设备独立分配声道（立体声 / 左声道 / 右声道），轻松组建分体式多声道音频系统。
  - **接收端同播校准**：支持两台 Android 接收端之间直接发起同步校准（`SVAC`），重置本地音频抖动队列以对齐播放进度。

- **现代交互与双语支持**
  - **Android 端**：支持 Android 12+ Material You 动态取色（Monet）与深浅色模式，依托常驻前台服务保障后台稳定播放。
  - **桌面端**：基于 Wails v2 + Vue 3 构建，界面轻量精致，实时呈现连接状态与同步校准阶段。
  - **多语言**：两端均原生支持简体中文与英文，支持手动切换或跟随系统语言。

---

## 🚀 快速上手

### 前置条件
1. 确保 Windows 电脑与 Android 设备连接在**同一局域网**（建议使用 5 GHz Wi-Fi，并关闭路由器的“AP 隔离”或“访客模式”）。
2. 在 Android 端安装并打开 SteamVoice，授予通知权限（Android 13+）以允许前台常驻服务运行。
3. Windows 发送端采集的是**系统默认输出设备**的声音（而非麦克风）。请确保需要串流的音频已正常输出至该默认设备。

### 连接与使用
1. **发现与发起连接**（支持双向发起）：
   - **手机端发起**：在“附近的电脑”列表中点击目标电脑发起连接；
   - **电脑端发起**：在设备列表中点击扫描到的 Android 设备发起连接。
2. **授权配对**：首次连接时在被连接端弹出的授权提示中点击“允许”（可勾选“以后自动同意该设备”）。
3. **播放与控制**：连接成功后，电脑端音频即在 Android 设备上实时播放；可在电脑端随时切换声道分配或断开连接。

---

## 📡 网络与传输协议

SteamVoice 采用 v4 传输协议，所有数据均封装为独立的 UDP 数据报文（无应用层分片）。完整定义详见 [docs/protocol.md](docs/protocol.md)。

### 端口与角色定义

| 角色 | 监听端口 | 承载报文 | 功能说明 |
| :--- | :--- | :--- | :--- |
| **Android 接收端** | `UDP 40125` | `SV01` / `SVCR` / `SVHB` / `SVTS` / `SVCT` / `SVCS` / `SVAC` | 接收音频数据、连接控制、心跳探测、时钟同步与同播校准 |
| **Windows 发送端** | `UDP 40126` | `SVCR` | 接收来自 Android 接收端的连接请求、响应与断开信令 |

### 核心报文类型

- **`SV01`（音频数据）**：40 字节包头，承载 48 kHz 双声道 Opus 编码音频帧及发送端纳秒单调时钟时间戳。
- **`SVCR`（连接控制）**：管理连接生命周期（Request / Response / Bye），承载设备唯一标识用于双向安全授权。
- **`SVHB`（心跳保活）**：32 字节 Ping / Pong 双向心跳探测，用于检测异常网络中断与触发自动重连。
- **`SVTS`（时钟同步）**：NTP 风格双向纳秒时间戳交换，用于估算往返延迟（RTT）与时钟偏差。
- **`SVCT`（状态反馈）**：接收端向发送端上报丢包率、队列积压、时钟偏差与 RTT 等运行状态。
- **`SVCS`（设置同步）**：两端协商并同步音频参数（码率与帧长）。
- **`SVAC`（设备同播校准）**：Android 接收端之间点对点交互，用于重置本地抖动队列并对齐播放时间戳。

---

## 🛠️ 构建与开发

### 一键构建全工程（Windows）

仓库根目录下提供了批处理脚本，可一键完成 Android Debug APK 与 Windows 安装包的构建：

```powershell
# 运行完整构建
.\build_all.bat

# CI / 脚本调用（构建完成后不暂停）：
.\build_all.bat --no-pause
```

---

### Android 接收端 (`SteamVoice-Android`)

- **环境要求**：
  - JDK 11+
  - Android SDK（Compile SDK 36，Min SDK 24）
  - Android NDK（`27.0.12077973`）
  - CMake
- **编译与安装**：
  ```powershell
  cd SteamVoice-Android

  # 编译 Debug APK
  .\gradlew.bat assembleDebug

  # 编译 Release APK
  .\gradlew.bat assembleRelease

  # 安装 Debug APK 到已连接的 Android 设备
  adb install -r .\app\build\outputs\apk\debug\app-debug.apk
  ```
  > 也可直接执行 `SteamVoice-Android/debug_apk.bat` 进行编译，或执行 `SteamVoice-Android/debug_apk_install.bat` 一键编译并安装。

---

### Windows 发送端 (`SteamVoice-Desktop`)

- **环境要求**：
  - Go 1.26+
  - Node.js & npm
  - Wails CLI（`go install github.com/wailsapp/wails/v2/cmd/wails@latest`）
  - C/C++ 编译器（MSVC 或 MinGW-w64）
  - libopus 开发库与 `pkg-config`
  - NSIS（仅生成安装包时需要，需将 `makensis` 添加至 `PATH`）
- **本地开发调试**：
  ```powershell
  cd SteamVoice-Desktop
  $env:CGO_ENABLED = "1"
  cd frontend; npm install; npm run build; cd ..
  wails dev -tags "steamvoice_opus nolibopusfile"
  ```
- **生成 Windows 安装程序**：
  ```powershell
  cd SteamVoice-Desktop
  .\build.bat
  # CI / 自动化环境调用：
  .\build.bat --no-pause
  ```
  安装程序输出于 `SteamVoice-Desktop/build/bin/`。

---

### 自动化测试

```powershell
# 运行 Android 单元测试
cd SteamVoice-Android
.\gradlew.bat test

# 运行 Windows 发送端 Go 单元测试
cd ..\SteamVoice-Desktop
go test ./...
```

---

## ❓ 常见问题与排查

1. **无法发现设备或连接超时**
   - 确认两端处于同一局域网网段，且未开启路由器的“AP 隔离”或“访客模式”。
   - 检查 Windows 防火墙设置，确保已放行 SteamVoice 的 `UDP 40126`（发送端控制端口）与 `UDP 40125`（接收端端口）。
   - 若开启了全局代理 / VPN，请尝试添加局域网直连或暂时关闭。
2. **已成功连接但听不到声音**
   - 确认 Windows 端正在发声的应用输出到了“系统默认播放设备”。
   - 检查电脑端与 Android 设备的系统媒体音量。
3. **Android 端进入后台或锁屏后播放中断**
   - 确认已授予应用通知权限（前台服务依赖常驻通知保活）。
   - 在系统设置中为 SteamVoice 开启“无限制电池使用”或关闭“省电策略 / 后台冻结”。
4. **音频卡顿、丢包或爆音**
   - 优先使用 5 GHz Wi-Fi 频段以减少局域网抖动与丢包。
   - 在设置中尝试将音频帧长调整为 20 ms，或适当降低音频码率（如 96 kbps）。
5. **Windows 桌面端提示 Opus 编码器不可用**
   - 确认已在具备 C/C++ 编译器及 libopus 环境变量的终端中运行。
   - 确认 `pkg-config --exists opus` 执行成功，且使用 `-tags "steamvoice_opus nolibopusfile"` 标签编译。

---

## 📦 项目结构

```text
SteamVoice/
├── SteamVoice-Android/   # Android 接收端源码（Kotlin、Jetpack Compose、NDK/CMake）
├── SteamVoice-Desktop/   # Windows 发送端源码（Go、Wails v2、Vue 3）
├── docs/                 # 传输协议（v4）及设计规范文档
├── build_all.bat         # 全工程一键构建脚本
└── SteamVoiceLogo.svg    # 项目图标
```

---

## 📄 开源组件与鸣谢

- [Opus](https://opus-codec.org/) - 低延迟交互式音频编解码器
- [Wails](https://wails.io/) - 基于 Go 与 Web 前端的轻量级桌面应用框架
- [Vue.js](https://vuejs.org/) - 渐进式前端用户界面框架
- [Jetpack Compose](https://developer.android.com/jetpack/compose) - Android 原生现代声明式 UI 工具包
