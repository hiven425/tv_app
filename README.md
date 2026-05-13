# TvCast — 索尼电视投屏接收器

让索尼 Android TV / Google TV **同时成为** DLNA、AirPlay、Google Cast 发现端，手机端**无需安装任何 App**——直接用 iPhone 控制中心的"屏幕镜像/AirPlay"、Android 的"投射"、各视频 App 内置的"小电视"图标即可投到电视。

## 当前进度

| 版本 | 内容 | 状态 |
|---|---|---|
| **v0.1** | Gradle 工程骨架、Leanback UI、ExoPlayer 播放、SSDP+UPnP HTTP+SOAP、AirPlay 服务发现、AirPlay 视频 URL、AirPlay PCM 音频骨架、CI 出 APK | ✅ |
| **v0.2** | UPnP GENA 事件订阅、AirPlay 二进制 plist 解析、PUT /photo 图片投屏、AirPlay 元数据（标题/艺人/封面）、DMAP 解析、Google Cast 服务发现、字节序修正、now-playing UI | ✅ |
| **v0.3** | DIAL 协议（YouTube/Netflix 投屏）、AudioManager 真实音量、Settings 页 + DataStore 持久化、发送方历史 UI、MediaSession + 通知控制、DLNA HEAD + 进度回填 | ✅ |
| **v0.4** | AirPlay 2 协议端点骨架（/info、/pair-*、/fp-setup）、DLNA 字幕提取、AirPlay 图片转场 + 去重、网络变化自动重启、屏幕休眠/唤醒电源管理、崩溃日志落盘 | ✅ |
| **v1.0** | Java ALAC 解码器、AudioFocus 系统集成、DLNA SetNextAVTransportURI 播放列表、UPnP HTTP keep-alive、远程投屏 WebUI、Android 11+ 包可见性 queries | ✅ |

## v1.0 关键改动一览

- **Java ALAC 解码器**——`airplay/AlacDecoder.kt` 移植自 Apple Apache-2.0 参考实现，覆盖 AirPlay 1 实际用到的 16-bit 单/双通道子集：bit reader、自适应 Rice 解码（ag_dec 等价）、LPC 预测器（dp_dec 等价）、L/R mid-side demix。RTSP ANNOUNCE 时从 SDP 的 `a=fmtp` 行抽取配置；mDNS TXT 改回 `cn=0,1`，iPhone 音乐 App 可选 ALAC 编码
- **AudioFocus 系统集成**——`AudioFocusHelper` 封装 API 26+ 的 `AudioFocusRequest` 与旧版双轨；ExoPlayer 与 RAOP 启动前申请 `AUDIOFOCUS_GAIN`，监听 `LOSS_TRANSIENT` 自动暂停、`GAIN` 恢复，杜绝与系统通知音 / 来电铃声打架
- **DLNA SetNextAVTransportURI**——`RendererState` 持有 next URI；SOAP 处理保存；`PlaybackEnded` 事件由 ExoPlayer STATE_ENDED 触发，`CastService.advancePlaylist()` 消费并 PlayMedia 下一首
- **UPnP HTTP keep-alive**——`UpnpHttpServer.handleClient` 改为按连接循环读请求；soTimeout 15s 关闭闲置连接；响应头默认 `Connection: keep-alive`。BubbleUPnP 等每秒轮询 GetPositionInfo 的控制点不再产生数千次 TCP 握手
- **远程投屏 WebUI**——`GET /web` 返回一个深色风格的 HTML 表单，`POST /web/cast` 接收 URL → 直接 PlayMedia。在任何浏览器粘贴视频 / 音乐 / 图片链接即投屏，**完全无需手机端 App 也无需电视端额外配置**。主屏二维码已改为指向 WebUI
- **Android 11+ 包可见性**——manifest 增加 `<queries>` 块，声明 `com.google.android.youtube.tv`、`com.netflix.ninja` 等，让 `resolveActivity()` 在 Android 11+ 不被默认隔离遮蔽，DIAL launch 才能真正起到效果

## v1.0 未交付项（结构性限制，单仓库无法独立解决）

下列三项在 v0.x 的 README 里已多次声明会"v1.0 推进"，但深入研究后发现**全部属于第三方/平台施加的硬约束**——单靠这个仓库的代码改不动。直接列在这里，避免你浪费时间等：

| 协议 | 看似可做的工作 | 真实卡点 |
|---|---|---|
| **AirPlay 2 屏幕镜像** | 已有 `/info` /`/pair-*` /`/fp-setup` 端点骨架，已有 RTSP 解析框架 | iPhone 镜像视频流走 H.264，每个 NALU 用 Apple 自有 **FairPlay v3** 加密；密钥交换基于 RSA-2048 + AES-CTR 而密钥由 Apple 在 OS 里硬编码。开源界（RPiPlay/UxPlay）通过逆向出"通用"密钥来解密 — 但这些密钥来自 Apple 的私有 SDK，本仓库不会内嵌。即使移植 RPiPlay 的 NDK 代码，分发会触及 Apple DMCA 红线 |
| **Google Cast V2 控制** | mDNS `_googlecast._tcp` 已广播，TCP 8009 的 TLS + protobuf 协议是公开的 | Cast 接收设备的 TLS 服务器证书**必须**由 Google 的 CA 签名（GoogleHomeRoot CA 系列），手机端 Chrome / Cast SDK 启动 TLS 握手时会强制校验证书指纹。Google 仅向通过其认证流程的硬件商颁发证书（Chromecast Built-in 计划），第三方 App 拿不到。结果是 Chrome 能"看见"我们但**永远连不上** |
| **Miracast / Wi-Fi Direct 接收** | Android 系统底层支持，理论可走 RTSP/RTP over Wi-Fi P2P | `WifiP2pManager` 的 **Group Owner 自主成组**模式在普通应用沙箱不可用：要么需要 `MANAGE_WIFI_NETWORK_SELECTION` 系统签名权限，要么需要厂商 ROM 集成 Miracast Sink 服务。索尼电视没暴露这个能力给第三方 App |

**替代方案**：
- 想要"手机屏幕镜像到电视"——用 Chromecast Built-in（Sony Bravia 自带）+ Chrome 浏览器的"投射"
- 想要"在 iPhone 上播放任何 App 的视频"——优先使用 AirPlay 1 视频 URL 推送（v0.1 已支持）或在 Safari 全屏播放后用 AirPlay 图标
- 想要"PC 浏览器一键投屏"——直接访问 `http://<电视 IP>:49215/web`（v1.0 新增的 WebUI）

## v0.4 关键改动一览

- **AirPlay 2 协议端点骨架**——补 `/info`（输出 AirPlay 2 设备能力 plist）、`/auth-setup`（回 32 字节零公钥让客户端按瞬态认证走）、`/pair-setup`/`/pair-verify`（回 470 通知"未配对"）、`/pair-pin-start`、`/fp-setup`（回 501 让 mirror 尝试干净失败）。现代 iOS 不再因协议不识别马上断连
- **DLNA 字幕提取**——MetadataParser 同时支持 Samsung `sec:CaptionInfoEx`、UPnP `upnp:caption`、以及 `<res protocolInfo="...subtitle...">` 三种声明方式；提取到的字幕 URL 通过 `MediaItem.SubtitleConfiguration` 喂给 ExoPlayer，电视上自动显示外挂字幕（srt/vtt/ass/ttml）
- **AirPlay 图片转场 + 去重**——读取 `X-Apple-AssetKey` 头去重连发包；读取 `X-Apple-Transition` 头在 PlayerActivity 用 `View.animate()` 实现 Dissolve（淡入）/ SlideLeft / SlideRight。iPhone 照片 App 的连续投屏体验流畅
- **网络变化自动重启**——CastService 注册 `ConnectivityManager.NetworkCallback`，Wi-Fi 重连 / IP 变化触发 750ms 防抖，然后停止所有接收器并以新 IP 重新启动。漫游 / 路由器重启不再导致手机找不到电视
- **屏幕休眠/唤醒电源管理**——监听 `ACTION_SCREEN_OFF` / `ACTION_SCREEN_ON` 系统广播，黑屏暂停 ExoPlayer，亮屏自动续播（仅当 currentTarget 仍是 PlayMedia 时）
- **崩溃日志落盘**——`TvCastApp` 注册全局 `UncaughtExceptionHandler`，把堆栈写到 `filesDir/crashes/crash-<时间戳>.log`，含设备/系统/应用版本元数据；MainActivity 主屏显示"上次崩溃: <时间>"提示。`adb pull /data/data/com.tvcast.app.debug/files/crashes/` 即可取出

## v0.3 关键改动一览

- **DIAL 协议接收器**——YouTube App "投屏到电视" / Netflix 移动端 "Watch on TV" 功能可用：SsdpServer 加 `urn:dial-multiscreen-org:service:dial:1` 广播，新增 `DialServer` 提供 /dd.xml 和 /apps/* REST，收到 launch 请求后通过 deep-link Intent 启动电视上已装的 YouTube / Netflix 应用
- **真音量同步**——之前 DLNA SetVolume / AirPlay SET_PARAMETER volume 只更新 UI 数字；现在 `CastService` 监听 `CastEvent.Volume` 调用 `AudioManager.setStreamVolume(STREAM_MUSIC, ...)`，电视真出声大小跟随手机滑动
- **Settings 页面**——主屏右上"设置"按钮（或遥控器菜单键）进入：编辑设备名、单独开关 DLNA / AirPlay / DIAL / Cast 公告；通过 `AppSettings`(DataStore) 持久化，CastService 监听标志位动态启停子模块
- **发送方历史列表**——主屏底部显示最近 5 个连接过的发送方（iPhone of John、PC 上的 Chrome、等等）
- **MediaSession + 通知控制**——前台通知用 MediaStyle 显示当前播放标题/艺人/封面，含暂停 / 停止按钮；遥控器/通知中心可以直接控制
- **DLNA HEAD 方法 + 实时进度**——部分控制点会先 HEAD 探测 device.xml；ExoPlayer 每秒把 `currentPosition` / `duration` 喂给 CastEventBus，GetPositionInfo / GetMediaInfo 回真实进度而不是 00:00:00（BubbleUPnP 进度条不再卡死）

## v0.2 关键改动一览

- **DLNA 现在能通过 BubbleUPnP/AllConnect 这类正经控制点**——之前 v0.1 用 NanoHTTPD 无法接收 `SUBSCRIBE/UNSUBSCRIBE` HTTP 动词，整个 UPnP HTTP 端已改写为原生 `ServerSocket`，新增 `GenaEventDispatcher` 向订阅者推 `NOTIFY`
- **AirPlay 现代 iOS 应用现在能用**——v0.1 只识别旧文本头格式的 `POST /play`；v0.2 加了 Apple `bplist00` 二进制 plist 解析器（`airplay/BinaryPlist.kt`），完整支持现代 iOS 的 Content-Location 和 Start-Position
- **iPhone 相册"AirPlay 这张照片"功能可用**——`PUT /photo` 直接把图片字节流交给 `PlayerActivity` 的 ImageView 显示
- **正在播放的歌曲在电视上能看见**——`SET_PARAMETER` 携带的 `application/x-dmap-tagged` 标签（DAAP 格式）由 `airplay/DmapParser.kt` 解析出 title/artist/album，封面图（`image/jpeg`）合并到 `CastEvent.Metadata` 事件
- **iPhone 音乐 App 真出声了**——v0.1 的 PCM 数据按大端写进 `AudioTrack` 是杂音；现已就地交换字节序为小端。同时 mDNS TXT 改为只声明 PCM（`cn=0`），避免 iPhone 协商成 ALAC（解码留待 v0.3）
- **Chrome 投屏列表能看到电视**——`_googlecast._tcp` mDNS 公告已添加（仅发现层，控制协议是 v0.3 工作）
- **去掉 NanoHTTPD 依赖**——AirPlay 和 UPnP 两个 server 都是原生 socket 后，依赖更干净

## 协议总览

| 协议 | 端口 | 谁能发送 |
|---|---|---|
| SSDP 广播 / 应答 | UDP `1900` 组播 `239.255.255.250` | DLNA 发现 |
| UPnP HTTP / SOAP / GENA | TCP `49215` | DLNA 控制 + 事件订阅 |
| mDNS / Bonjour | UDP `5353` 组播 | AirPlay / RAOP / Google Cast 公告 |
| AirPlay HTTP + RTSP | TCP `7000` | iPhone / Mac AirPlay |
| RAOP 音频数据 | UDP 动态端口 | iPhone 音乐 App |
| DIAL REST | TCP `49216` | YouTube / Netflix 投屏 |
| Google Cast | TCP `8009`（仅广播） | Chrome 浏览器 等（v0.2 仅可见，不可连） |

## 工程结构

```
tv_app/
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── res/                       — Leanback 主题、布局（含 photo_view）、图标
│   └── java/com/tvcast/app/
│       ├── airplay/
│       │   ├── AirPlayReceiver.kt        — 编排：mDNS + server + RAOP
│       │   ├── AirPlayServer.kt          — 原生 socket 多路复用 HTTP + RTSP
│       │   ├── BinaryPlist.kt            — Apple bplist00 解析器
│       │   ├── DmapParser.kt             — DAAP/DACP 标签二进制解析
│       │   ├── MdnsAdvertiser.kt         — _airplay / _raop / _googlecast 公告
│       │   └── RaopAudioReceiver.kt      — RTP/UDP PCM 接收 + 字节序转换
│       ├── dlna/
│       │   ├── DlnaRenderer.kt           — 编排：SSDP + UPnP HTTP
│       │   ├── GenaEventDispatcher.kt    — GENA 订阅者管理 + NOTIFY 推送
│       │   ├── MetadataParser.kt         — DIDL-Lite 元数据解析
│       │   ├── SsdpServer.kt             — SSDP M-SEARCH/NOTIFY
│       │   ├── UpnpDescriptors.kt        — device.xml / SCPD XML
│       │   └── UpnpHttpServer.kt         — 原生 socket HTTP + GENA
│       ├── event/CastEventBus.kt         — 统一事件总线 + StateFlow
│       ├── service/CastService.kt        — 前台服务托管所有接收器
│       ├── ui/MainActivity.kt            — Leanback 主屏（设备名/IP/QR/now-playing）
│       ├── ui/PlayerActivity.kt          — ExoPlayer + ImageView 双模渲染
│       └── util/                         — 网络、UDN、二维码工具
├── .github/workflows/build.yml           — CI：每次推送 / PR 出 debug APK
└── build.gradle.kts / settings.gradle.kts / app/build.gradle.kts
```

## 构建

### 在 GitHub 上构建（推荐）

把仓库推到 GitHub 后，Actions 会自动跑 `Build APK`，几分钟后在该次 run 的 **Artifacts** 里能下载 `tvcast-debug-apk`。

### 本地构建

需要：JDK 17、Android SDK（API 34）。Gradle Wrapper 会在首次 CI 运行时自动生成；本地若不存在可手动：

```bash
gradle wrapper --gradle-version 8.5 --distribution-type bin
./gradlew assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

## 安装到索尼电视

索尼电视（Android TV 9/10/11）需要先打开"开发者选项"和"USB 调试 / 网络调试"：

1. 设置 → 设备偏好设置 → 关于 → 连续点击"Build" 7 次解锁开发者模式
2. 设置 → 设备偏好设置 → 开发者选项 → 打开 **网络调试**，记下电视 IP

电脑上：

```bash
adb connect <电视 IP>:5555
adb install -r app-debug.apk
```

装完后在 Android TV 主屏幕的"应用"栏里能看到 **TV Cast** 图标。

## 使用

电视端启动 TV Cast 后，确保电视和手机连在同一个路由器：

- **iPhone**
  - 控制中心 → 屏幕镜像 → 选 `Sony TV Cast` → 视频镜像（v0.3 才能真出图，目前握手成功但黑屏）
  - Safari 播放视频 → 视频上的 AirPlay 图标 → 选电视 → ✅
  - 爱奇艺/优酷/Bilibili 内的小电视按钮 → ✅
  - 音乐 App → AirPlay → 电视 → ✅ 音频外放（PCM 字节序已修）
  - 相册 → 选图片 → AirPlay → ✅ 电视全屏显示
  - 锁屏 / 控制中心显示正在播放的歌曲信息 → ✅ 电视主屏同步显示标题/艺人/封面

- **Android 手机**
  - 任何视频/相册 App 里的"投屏/DLNA"按钮 → ✅
  - BubbleUPnP / Hi8 / AllConnect 等"专业"控制点 → ✅（v0.2 已支持 GENA 事件订阅）

- **Mac / Windows**
  - macOS 控制中心 → 屏幕镜像 → AirPlay 视频 / 音频 ✅；屏幕镜像 v0.3
  - Chrome 浏览器：地址栏右边 Cast 图标 → 看见设备但点击会失败（Cast V2 控制协议是 v0.3 工作）

## 调试

```bash
adb logcat -s SsdpServer:V UpnpHttpServer:V GenaEvent:V AirPlayServer:V RaopAudioReceiver:V MdnsAdvertiser:V CastService:V
```

抓 SSDP / mDNS 流量：

```bash
sudo tcpdump -i any -nn 'udp port 1900 or udp port 5353'
```

## 已知限制

详见上方 [v1.0 未交付项](#v10-未交付项结构性限制单仓库无法独立解决) 一节——AirPlay 屏幕镜像、Google Cast 控制、Miracast 接收三项被第三方机制硬封锁。其余功能已就位。

注意：v1.0 已集成 Java ALAC 解码器，但**未经真机测试**——若 iPhone 音乐 App 杂音明显，把 `MdnsAdvertiser.kt` 里 `cn` 字段从 `"0,1"` 改回 `"0"` 即可强制走 PCM 链路。

## 法律提示

AirPlay 2 协议由 Apple 控制，第三方接收器存在灰色地带——大多数开源实现（RPiPlay、UxPlay、shairport-sync）公开存在多年，但 Apple 偶尔会针对特定项目发送 DMCA。本项目仅做接收兼容，不分发 Apple 的密钥或专有代码；自行分发 APK 时请注意所在司法管辖区的相关规定。

`_raop._tcp` 与 RTSP/RAOP 协议参考公开文档；ALAC 解码移植自 Apple 自家开源的 [ALAC reference implementation](https://macosforge.github.io/alac/)（Apache-2.0）。
