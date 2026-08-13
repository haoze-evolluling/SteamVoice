# Windows → Android 局域网无线音响：开源项目与完整链路调研

> 调研目标：寻找可以复用的 GitHub 开源项目，用尽可能少的自研代码实现“Windows 电脑将声音通过局域网 Wi‑Fi 传输到 Android 手机/平板，并由 Android 设备作为扬声器播放”的完整链路。
>
> 核心原则：优先复用已经实现音频采集、网络传输、编码解码、设备发现、缓冲和 Android 播放的成熟代码，避免从零重新设计整个音频系统。

## 1. 项目需求与目标架构

目标软件由 Windows 端和 Android 端组成。Windows 端需要能够获取系统或指定应用的播放音频，并通过当前局域网连接把音频发送给 Android；Android 端则作为网络音响接收音频，并通过手机或平板自身的扬声器、耳机或其他 Android 音频输出设备进行播放。理想情况下，用户打开 Windows 端后可以发现局域网中的 Android 设备，选择某台设备进行连接，然后 Windows 的声音就能够低延迟地从 Android 播放出来。

从技术上看，这个需求可以拆成几个相互独立的模块：Windows 音频捕获、可选的 Windows 虚拟扬声器、音频编码、局域网传输、设备发现、网络抖动缓冲、Android 解码以及 Android 音频输出。最理想的方案不是自己重新实现所有模块，而是从已经成熟的项目中挑选完整链路或直接拼接模块。

一个典型的最终架构可以表示为：

```text
Windows 音频系统
      │
      ├── 方案 A：WASAPI Loopback 直接捕获
      │
      └── 方案 B：虚拟扬声器 / 虚拟声卡
                    │
                    ▼
               PCM 音频
                    │
              可选 Opus 编码
                    │
                    ▼
              Wi‑Fi / 局域网
                    │
                    ▼
          Android 网络音频接收器
                    │
              解码 / 抖动缓冲
                    │
                    ▼
            AudioTrack / Oboe
                    │
                    ▼
              Android 扬声器
```

---

## 2. 第一优先级：SoundRemote

### 2.1 Windows Server

**项目名称：SoundRemote Server**  
**GitHub：** https://github.com/SoundRemote/server-windows  
**许可证：** GPL-3.0  
**主要技术：** C++、Windows、WASAPI/音频捕获、Opus

SoundRemote Server 是目前最值得优先研究的 Windows 端项目之一，因为它的目标与本项目非常接近。官方仓库的项目描述直接说明，它是一个桌面端程序，可以捕获 PC 音频并发送到配套的 Android 客户端，同时还能够接收 Android 端发送的快捷键并在电脑上模拟执行。仓库本身包含 Windows 工程、Opus 相关代码以及构建所需文件，因此并不是一个只有概念代码的实验项目。citeturn304927view0

对本项目而言，SoundRemote Server 最大的价值在于它已经把“Windows 端获取音频并送入网络传输链路”的核心工作做出来了。你不需要从零开始研究 WASAPI 音频会话、音频格式协商、实时音频线程和 Opus 编码，而是可以先把它视为一个已经能够工作的 PC Audio Source，再围绕它重新设计自己的 UI、设备管理、连接逻辑和协议。

### 2.2 Android Client

**项目名称：SoundRemote Android Client**  
**GitHub：** https://github.com/SoundRemote/client-android  
**许可证：** GPL-3.0  
**主要技术：** Kotlin、Jetpack Compose、Android、Opus

这是 SoundRemote 的 Android 配套客户端。官方 README 明确说明，它与 SoundRemote Server 配对后，可以把 PC 音频流式传输到 Android 设备，同时还提供远程键盘快捷键、PC 媒体控制等额外功能。仓库使用 Kotlin，并且已经集成 Opus 相关内容，因此对 Android 音频接收、解码和播放的实现具有很高的参考和复用价值。citeturn304927view1

从“不要重复造轮子”的角度看，这两个仓库组合起来是目前最贴近你目标的一套现成全链路代码。它已经把 Windows Server 与 Android Client 拆分开，因此你甚至可以先分别跑起来验证音频链路，再逐步替换 UI 和产品逻辑，而不是一开始就自己写一套跨平台协议。

### 2.3 需要注意的地方

SoundRemote 虽然非常接近目标，但它与“Windows 出现一个新的虚拟扬声器设备”这个需求并不完全等价。它的 Windows 端核心思路是捕获 PC 音频后发送给客户端，而不是一定通过一个额外的虚拟音频输出设备作为中间层。因此，如果你的最终产品明确要求 Windows 系统设置中出现一个类似“Android Speaker”的独立扬声器，那么 SoundRemote 不能单独解决这一点，仍然需要额外加入虚拟音频设备方案。

此外，两个仓库均采用 GPL-3.0。若项目最终需要闭源商业分发，或者需要采用与 GPL 不兼容的许可证，应在正式复用代码之前仔细审查许可证义务，包括衍生作品的源码提供、动态/静态链接方式以及发布方式。仅仅“是 GitHub 开源项目”并不意味着代码可以不受限制地直接嵌入任何产品。

---

## 3. 第二优先级：Scream

**项目名称：Scream**  
**GitHub：** https://github.com/duncanthrax/scream  
**许可证：** MS-PL  
**主要作用：Windows 虚拟网络声卡 / 虚拟扬声器

Scream 是整个方案中最值得关注的底层项目之一。它本质上是一个 Windows 虚拟音频设备驱动，会让 Windows 出现一个独立的虚拟声音设备。所有输出到这个设备的音频都会作为 PCM 音频流发布到本地网络。官方 README 明确把它定义为“Virtual network sound card for Microsoft Windows”，并说明它是基于微软 MSVAD 音频驱动示例实现的。citeturn924754search0

Scream 的优势在于，它恰好解决了“如何让 Windows 把一个普通扬声器一样的设备交给第三方程序使用”这个难题。用户可以在 Windows 的声音设置或者具体应用的输出设置里，将 Scream 选择为播放设备。这样，系统或应用写入虚拟扬声器的数据会进入 Scream，然后由驱动直接进行网络发送。

Scream 的网络协议非常适合做第一版原型。官方实现采用局域网 UDP PCM 传输，默认目标是 `239.255.77.77:4010`，也可以配置成单播。每个数据帧最多约 1157 字节，其中 5 字节用于描述采样率、采样宽度、通道数和声道布局，剩余部分是 PCM 音频数据。官方还明确建议 Receiver 只需要读取网络数据并把它送入本地音频输出，同时使用少量缓冲处理网络抖动。citeturn924754search0

如果目标是尽快做出第一版，Scream 可以让整个 Windows 端非常简单：Windows 音频系统 → Scream 虚拟扬声器 → UDP PCM → Wi‑Fi。这样不需要自己开发 Windows 内核音频驱动，也不需要首先研究如何把系统音频重新注入虚拟设备。

### 3.1 Scream 的局限

当前 Scream 官方仓库虽然提供了 Unix/Linux Receiver、Windows Receiver，以及一些嵌入式 Receiver，但没有一个可以直接拿来当作 Android 官方客户端的成熟实现。也就是说，它非常适合作为 Windows 端基础，但 Android 接收端还需要寻找现成第三方项目，或者自行实现一个很薄的 Receiver。官方仓库甚至明确列出了第三方 `cornrow` Receiver 和 STM32/ESP32 Receiver，但没有官方 Android Receiver。citeturn924754search0

因此，Scream 本身不是最适合“直接 Fork 后两端一起发布”的完整方案，但它是最适合满足“Windows 必须有一个虚拟扬声器”的底层基础。

---

## 4. 第三优先级：Snapcast

**项目名称：Snapcast**  
**GitHub：** https://github.com/snapcast/snapcast  
**主要作用：网络多房间同步音频系统

Snapcast 是一个非常成熟的网络音频系统，它解决的问题比单纯的“把一段音频发送到另一个设备”更复杂。它的核心设计是一个服务器负责提供音频数据，多个客户端通过网络接收并同步播放，因此特别适合未来扩展成“一台 Windows 电脑连接多个 Android 音响”或者“一个家庭局域网里有多个播放器”的场景。GitHub 仓库将其定位为同步的多房间音频播放器。citeturn304927view2

Snapcast 最值得借鉴的地方不是单纯的传输，而是网络音频真正容易出问题的部分，例如客户端缓冲、时间戳、播放同步、设备管理以及多客户端状态管理。如果以后希望让两台甚至多台 Android 设备同时作为左右声道或者多房间音箱，就不能只考虑“UDP 收到就播放”，而需要一套更完整的同步机制；Snapcast 在这一方面已经提供了成熟的参考实现。

从架构角度看，可以把它理解成：Windows 音频源 → Snapserver → Wi‑Fi → Snapclient。Android 设备则承担 Snapclient 的角色。与 Scream 相比，Snapcast 本身不强调“Windows 虚拟扬声器”这个概念，它更强调完整的网络音频分发系统，因此更适合做网络音频中间层，而不是直接解决虚拟声卡问题。

---

## 5. Snapdroid：Snapcast 的 Android 客户端

**项目名称：Snapdroid**  
**GitHub：** https://github.com/snapcast/snapdroid  
**主要作用：Android 端 Snapcast Client

Snapdroid 是 Snapcast 体系中的 Android 客户端仓库。它的价值在于，它不是一个单纯的控制面板，而是为了让 Android 设备真正参与 Snapcast 的客户端播放链路而设计的。因此，如果你准备研究“Android 如何稳定地接收网络音频、维护播放器状态、处理后台播放并输出到手机音频设备”，Snapdroid 是非常值得分析的代码库。citeturn304927view3

对于本项目来说，Snapdroid 的最佳使用方式未必是完整 Fork。更加现实的办法是重点学习其中的 Android 网络协议处理、音频播放、生命周期管理、后台服务以及设备状态维护等部分。如果最终自己设计一个更轻量的 Android Receiver，可以把 Snapdroid 当成成熟的 Android 网络音频播放器参考。

---

## 6. ScreamRouter

**项目名称：ScreamRouter**  
**GitHub：** https://github.com/netham45/ScreamRouter  
**主要作用：动态家庭网络音频路由器

ScreamRouter 可以看作是位于音源和多个音频终端之间的一个“网络音频路由层”。项目支持将不同协议和音频源路由到多个 Sink，并且围绕网络音频提供更多管理和处理能力。这个项目特别适合参考未来的多设备架构，而不只是第一版的单 Windows → 单 Android 场景。citeturn304927view4

如果你的软件以后希望支持“一台电脑同时输出到手机、平板、另一台电脑、网络音箱”等多个设备，那么直接使用一个路由器式架构会比 Windows 程序直接维护所有连接更加灵活。ScreamRouter 可以提供非常有价值的架构参考，尤其是音频 Sink、网络协议适配和路由关系等方面。

不过从项目落地角度看，ScreamRouter 更偏向一个通用网络音频基础设施，而不是一个现成的“Windows + Android 两端应用”。因此它更适合做技术参考和后续扩展基础，而不应该作为第一版产品的唯一底座。

---

## 7. SonoBus

**项目名称：SonoBus**  
**GitHub：** https://github.com/sonosaurus/sonobus  
**主要作用：实时网络音频传输与协作

SonoBus 是一个开源实时网络音频传输工具，目标是通过互联网或局域网进行低延迟音频传输。它的源代码公开，项目使用 C++，并且长期围绕网络音频、低延迟、编码和实时处理进行开发。citeturn304927view5

它对你的项目最大的价值不是直接提供一套 Windows + Android 无线音响产品，而是提供成熟的网络音频实现思路。如果后续决定使用 Opus 压缩而不是 Scream 风格的原始 PCM，或者希望在网络带宽不稳定时降低码率，那么 SonoBus 的编码、缓冲和实时传输实现很值得参考。

不过 SonoBus 的产品定位偏向实时音乐协作、音频对谈等场景，所以它的整个产品架构相对复杂。对于“Windows 作为音源、Android 只负责当扬声器”这个需求而言，它属于技术参考项目，而不是最直接的基础项目。

---

## 8. Windows WASAPI 音频捕获项目

**项目名称：AudioCapture / WASAPI Audio Capture**  
**GitHub 参考：** https://github.com/masonasons/AudioCapture

这个项目主要研究 Windows WASAPI 音频捕获，包括系统级 Loopback Capture 和特定进程音频捕获。它可以利用 `IAudioClient` 与 Loopback 机制捕获应用或者系统播放出来的音频，并支持实时混音、格式转换以及 Opus 等编码。citeturn924754search2

这类项目非常适合另一条路线：如果最终觉得“Windows 虚拟扬声器”并不是必须的，那么可以直接从系统音频里抓 PCM，然后编码后传到 Android。这样用户不用改变系统默认输出设备，也不需要安装内核驱动，安装体验会更简单。

这种方案的主要缺点是软件捕获到的是“真实输出声音”，而不是一个独立的虚拟扬声器设备。如果你的产品理念是提供一个真正的 `Android Speaker` 设备供 Windows 应用选择，那么 WASAPI Loopback 更适合做备用方案，而不是最终架构。

---

## 9. 各项目在完整链路中的位置

| 项目 | Windows 音频捕获 | Windows 虚拟扬声器 | 网络传输 | Android 接收 | Android 播放 | 多设备/同步 | 适合作为最终底座 |
|---|---|---|---|---|---|---|---|
| SoundRemote | ✅ | ❌ | ✅ | ✅ | ✅ | 一般 | ⭐⭐⭐⭐⭐ |
| Scream | ✅（虚拟设备） | ✅ | ✅ | ❌官方 Android | 需要 Receiver | 一般 | ⭐⭐⭐⭐⭐ |
| Snapcast | ✅/可接入多种源 | ❌ | ✅ | ✅（Snapdroid） | ✅ | ✅ | ⭐⭐⭐⭐⭐ |
| ScreamRouter | ✅/可接多种源 | 可配合 Scream | ✅ | 视 Sink/客户端实现 | ✅/可扩展 | ✅ | ⭐⭐⭐⭐ |
| SonoBus | ✅ | 非核心 | ✅ | Android/移动端方向需结合具体代码 | ✅ | 一般 | ⭐⭐⭐ |
| AudioCapture | ✅ | ❌ | 自行实现 | 自行实现 | 自行实现 | ❌ | ⭐⭐⭐ |

---

## 10. 最推荐的两条完整路线

### 路线 A：直接复用 SoundRemote

这是最适合第一版快速落地的方案。直接以以下两个仓库作为基础：

```text
https://github.com/SoundRemote/server-windows
https://github.com/SoundRemote/client-android
```

Windows Server 负责捕获音频并进行网络发送，Android Client 负责网络接收、音频处理和播放。由于两端已经配套设计，你可以先保持原始协议和播放逻辑不变，只修改 UI、设备发现、连接流程和产品名称。这样可以最大程度降低前期技术风险。citeturn304927view0turn304927view1

它最适合的产品形态是：用户在 Android 打开“无线音响”应用，Windows 打开发送端，然后在局域网中发现并连接设备。Windows 可以直接把系统声音送入传输链路，Android 负责作为音响播放。第一阶段甚至可以不做虚拟声卡，以尽快验证低延迟、断线重连和 Wi‑Fi 环境下的稳定性。

### 路线 B：Scream + 自制轻量 Android Receiver

如果“Windows 必须识别出一个扬声器”是硬性需求，那么建议以 Scream 为 Windows 端基础。Scream 可以在 Windows 系统中提供一个独立的虚拟网络声卡，并将写入该设备的 PCM 音频通过 UDP 发布到局域网。citeturn924754search0

Android 端则自己实现一个非常薄的 Receiver：监听 Scream 的 UDP 数据、解析 5 字节协议头、根据采样率/位深/声道数创建 AudioTrack、增加一个很小的 Jitter Buffer，然后持续向 Android 音频输出设备写入 PCM。这一部分虽然需要开发，但逻辑比从零实现整个网络音频系统简单很多，因为 Scream 已经把 Windows 虚拟声卡、协议定义和发送端全部完成了。

---

## 11. 推荐的最终产品架构

如果不考虑“最快上线”，而考虑最终产品的体验和扩展性，我更推荐把系统设计成可插拔架构：Windows 音频源层与网络传输层分离，Android 播放层也与具体协议分离。这样第一版可以使用 SoundRemote 或 Scream 的现有实现，后续再替换成自己的协议，而不需要重写整个客户端。

建议的结构如下：

```text
                           Windows
                              │
            ┌─────────────────┴─────────────────┐
            │                                   │
     WASAPI Loopback                     Virtual Speaker
            │                                   │
            └─────────────────┬─────────────────┘
                              ▼
                         PCM / Float PCM
                              │
                    ┌─────────┴─────────┐
                    │                   │
                PCM/UDP             Opus/UDP
                    │                   │
                    └─────────┬─────────┘
                              ▼
                         Wi‑Fi / LAN
                              │
                              ▼
                       Android Receiver
                              │
                     Jitter Buffer / Decode
                              │
                    ┌─────────┴─────────┐
                    │                   │
                AudioTrack            Oboe
                    │                   │
                    └─────────┬─────────┘
                              ▼
                        Android Speaker
```

这种架构下，Windows 音频采集、编码器、网络协议和 Android 播放器都是可以替换的。例如第一版采用 Scream PCM，第二版可以把网络传输改成 Opus，第三版再加入多设备同步，而 Android UI 和设备管理部分都不需要全部重写。

---

## 12. 第一版为什么建议优先使用 PCM

第一版原型没有必要马上加入复杂的音频编码。Scream 的设计已经证明了局域网 PCM 传输是可行的，而且在 48 kHz、16-bit、双声道的常见配置下，原始 PCM 数据量大约是 1.536 Mbps，也就是约 192 KB/s。对于现代家庭 Wi‑Fi 或局域网环境，这通常是一个非常容易承受的带宽水平。

PCM 最大的优势是实现简单：Windows 不需要额外进行复杂编码，Android 收到数据后也不需要先进行 Opus/AAC 解码，可以直接将 PCM 写入 Android 的 `AudioTrack`。这样特别容易把链路延迟控制得很低，也便于在第一阶段排查网络丢包、音频断裂、缓冲过大等问题。

等到第一版链路稳定之后，再加入 Opus 就比较合理。Opus 可以显著降低网络带宽，但同时会增加编码、解码和缓冲复杂度，因此最好把它放在第二阶段，而不是让编码器成为项目启动时的第一个技术风险。

---

## 13. 设备发现与配对建议

真正做成用户软件之后，“怎么让 Windows 找到 Android”会比简单的 UDP 传输更影响使用体验。第一版可以允许 Android 显示本机 IP，然后由 Windows 手动输入 IP；但正式产品更适合使用 mDNS、UDP Broadcast 或其他局域网服务发现机制，让 Android 自动公布“无线音响”服务，Windows 自动扫描设备列表。

设备发现层应该和音频传输层分离。比如设备发现可以使用 mDNS/NSD，而实际音频数据仍然走 UDP。这样即使未来将 UDP PCM 改成 Opus、RTP 或其他协议，设备发现代码依然可以继续使用。连接成功后，Windows 和 Android 可以交换设备名称、支持的采样率、声道数、协议版本等信息，从而避免直接盲目发送音频。

---

## 14. 缓冲、丢包和延迟处理

最初实现时不要把目标设定成“绝对零延迟”，而应该建立一个很小的抖动缓冲。局域网里的 Wi‑Fi 仍然会受到信号质量、功耗策略、AP 调度和其他设备流量影响，如果 Android 收到 UDP 后完全没有缓冲，很容易出现偶发爆音、卡顿或断续。Scream 官方也明确建议 Receiver 使用少量缓冲来应对网络抖动。citeturn924754search0

第一版可以从约 20～50 ms 的音频缓冲开始测试，再根据实际设备调整。后续如果需要更低延迟，可以进一步采用时间戳、动态 Jitter Buffer 和丢包处理策略。若最终使用 Opus，还可以结合 Opus 自身的丢包恢复能力提高 Wi‑Fi 环境下的稳定性。

---

## 15. License 需要特别关注

这些项目虽然都是公开 GitHub 仓库，但许可证差异很大。SoundRemote 的 Windows 和 Android 仓库当前显示 GPL-3.0；Scream 的仓库则使用 MS-PL；其他项目的许可证也不完全相同。因此，在确定“Fork 哪个项目”之前，必须先确定你自己的软件是否准备开源、是否商业化、是否允许强制公开衍生代码，以及是否需要把第三方库静态链接进客户端。citeturn304927view0turn304927view1turn924754search0

尤其需要注意，不能因为一个仓库存在 GitHub 上就直接把它的代码复制进自己的闭源商业软件。真正开始开发前，应针对每一个最终纳入产品的仓库逐个核对 LICENSE 文件，以及其中再次引用的 Opus、音频驱动样例和其他第三方组件的许可证。

---

## 16. 最终推荐顺序

### 第一名：SoundRemote

```text
Windows Server
https://github.com/SoundRemote/server-windows

Android Client
https://github.com/SoundRemote/client-android
```

这是目前最贴近“Windows → Android”的现成完整方案。它已经有 Windows Server、Android Client、音频捕获、网络传输以及 Android 播放链路，因此最适合直接 Fork 后改造成自己的产品。官方仓库也明确把“Capture and send audio to the client device”以及“Capture and stream audio from a PC to an Android device”作为核心功能。citeturn304927view0turn304927view1

### 第二名：Snapcast + Snapdroid

```text
Snapcast
https://github.com/snapcast/snapcast

Snapdroid
https://github.com/snapcast/snapdroid
```

这是一套更加完整的网络音频体系。如果目标只是让一台 Android 成为一台无线音响，它会显得稍微复杂；但如果未来需要多个 Android 设备同步播放、独立管理和低延迟同步，它的价值非常高。citeturn304927view2turn304927view3

### 第三名：Scream

```text
https://github.com/duncanthrax/scream
```

如果“Windows 必须出现一个真正的虚拟扬声器”是产品的核心特性，那么 Scream 应该直接进入技术方案。它已经解决了 Windows 虚拟音频驱动、PCM 网络发送和低延迟传输，Android 只需要补一个可靠 Receiver。citeturn924754search0

### 第四名：ScreamRouter

```text
https://github.com/netham45/ScreamRouter
```

它更适合用于未来的网络音频路由、多设备和复杂 Sink 管理，而不是作为第一版单设备产品的最小实现。项目适合做架构参考，也可以在未来承担类似“音频路由中心”的角色。citeturn304927view4

### 第五名：SonoBus

```text
https://github.com/sonosaurus/sonobus
```

它更适合学习低延迟网络音频、编码、实时传输和音频缓冲的实现方式。对于你的项目，它是技术参考价值大于直接复用价值的仓库。citeturn304927view5

---

## 17. 最终结论

综合当前 GitHub 上能够确认的项目，我不建议从零开始写一个“Windows 音频捕获 + 自研网络协议 + Android 播放器”的完整系统。最省开发量的方式是直接以 **SoundRemote Server + SoundRemote Android Client** 为基础，因为两端已经按照 PC 音频流向 Android 的模式完成了配套实现，而且 Windows 和 Android 两个仓库都公开存在并持续维护。citeturn304927view0turn304927view1

如果你的产品需求进一步明确为“Windows 系统中必须出现一个真正的无线扬声器设备”，那么建议采用 **Scream + 自制 Android Receiver** 的路线。Scream 已经完成 Windows 虚拟声卡和网络 PCM 传输，Android 侧只需要围绕其公开协议实现接收、缓冲和播放。这个路线最符合“把手机当成网络声卡对应的扬声器”的产品理念，而且后续可以继续加入设备发现、音量控制、Opus、多个 Android 设备以及同步播放等功能。citeturn924754search0

如果最终目标是发展成一个完整的“局域网无线音频系统”，而不是只解决单台手机播放，那么 **Snapcast + Snapdroid** 值得作为长期架构参考，因为它已经把多客户端、同步播放和网络音频系统中的很多复杂问题解决掉了。citeturn304927view2turn304927view3

因此，建议实际开发时按照以下优先级推进：**先用 SoundRemote 验证产品体验 → 再研究 Scream 虚拟扬声器 → 根据需求决定最终采用 SoundRemote 协议还是 Scream + 自定义 Receiver → 最后再考虑 Opus、多设备同步和更复杂的网络音频路由。** 这样可以最大程度复用已有代码，同时避免一开始就陷入 Windows 内核驱动、实时音频协议和跨设备同步等高复杂度问题。

---

## 18. GitHub 项目总表

| 项目 | GitHub 地址 | 主要作用 | 推荐用途 |
|---|---|---|---|
| SoundRemote Server | https://github.com/SoundRemote/server-windows | Windows PC 音频捕获与网络发送 | 直接作为 Windows 基础 |
| SoundRemote Android Client | https://github.com/SoundRemote/client-android | Android 接收 PC 音频并播放 | 直接作为 Android 基础 |
| Scream | https://github.com/duncanthrax/scream | Windows 虚拟网络声卡、PCM/UDP | 实现真正的虚拟扬声器 |
| Snapcast | https://github.com/snapcast/snapcast | 多房间网络音频与同步 | 长期架构参考 |
| Snapdroid | https://github.com/snapcast/snapdroid | Snapcast Android 客户端 | Android 网络音频参考 |
| ScreamRouter | https://github.com/netham45/ScreamRouter | 网络音频路由与多 Sink | 多设备扩展参考 |
| SonoBus | https://github.com/sonosaurus/sonobus | 低延迟网络音频传输 | Opus/实时音频参考 |
| AudioCapture | https://github.com/masonasons/AudioCapture | Windows WASAPI Loopback/进程音频捕获 | 不做虚拟声卡时的替代方案 |

## 19. 建议下一步

在真正进入编码阶段之前，最应该做的不是继续盲目寻找更多项目，而是把 **SoundRemote、Scream、Snapcast/Snapdroid** 三条路线的实际代码结构拆出来比较，重点确认 Windows 到 Android 的真实音频路径、具体传输协议、音频编码格式、连接发现方式、后台播放机制、延迟和许可证约束。完成这一步之后，就可以明确决定到底是直接 Fork SoundRemote，还是用 Scream 负责虚拟扬声器、再借鉴 Snapdroid/其他 Android 播放实现来构建自己的完整无线音响系统。
