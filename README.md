<div align="center">
  <h1>ClassWeave Bridge</h1>
  <p><strong>课堂多端协同系统中的 USB ↔ Bluetooth 中继桥接端</strong></p>
  <p>
    <a href="LICENSE"><img alt="License" src="https://img.shields.io/badge/license-Apache%202.0-blue.svg"></a>
    <img alt="Platform" src="https://img.shields.io/badge/platform-Android%2012%2B-3DDC84.svg">
    <img alt="Language" src="https://img.shields.io/badge/language-Kotlin-7F52FF.svg">
  </p>
  <p>
    官方网站：<a href="https://classweave.mem.report/">classweave.mem.report</a>　·　仓库地址：<a href="https://github.com/ClassWeave/classweave-bridge">github.com/ClassWeave/classweave-bridge</a>
  </p>
</div>

`ClassWeave Bridge` 是一个 **运行在 OrangePI 5B（Android 12）等单板计算机上的中继桥接 App**：
通过 **USB 3.0 / Type-C** 与教师笔记本（macOS Host）相连，
通过 **Bluetooth RFCOMM（SPP）** 与学生 Android 终端相连，
为整个课堂会话提供一条**纯传输层、零业务状态**的中继链路。

```
[macOS Host] ── USB-C (USB 3.0, ADB Tunnel TCP) ── [OrangePI 5B / Android 12 / Bridge] ── BT RFCOMM ── [Android A]
                                                                                                \─────── [Android B]
```

> Bridge 是 **纯传输层中继**，不修改任何业务 payload、不持有任何业务状态、
> 不代替主控端发出业务确认。所有业务权威归 macOS Host。

## 特性

- **双向 Envelope 转发**：上行 Android → Host 改写 `sender / relay`，下行 Host → Android 按 `target.type` 路由（广播 / 单播 / 丢弃）。
- **会话握手**：自动 `session.hello` → `session.welcome`，并维持 15 s 心跳与 30 s `bridge.status` 上报。
- **USB 链路**：基于 `adb reverse` 在 USB 上建立 TCP 隧道（`127.0.0.1:9000`），指数退避自动重连（1 s → 30 s）。
- **Bluetooth RFCOMM Server**：以固定 SPP UUID 监听，按 `[4 字节 BE 长度][UTF-8 JSON]` 帧格式与 Android 客户端通信，每个连接独立读循环。
- **设备注册表**：Android `deviceId ↔ RFCOMM endpointId` 映射，online / offline 状态实时跟踪。
- **可视化 UI**：Jetpack Compose 提供启停控制、USB / Session / Bluetooth 状态卡片与实时日志。

## 系统要求

- Android 12+（API 31+，需要 `BLUETOOTH_CONNECT / BLUETOOTH_ADVERTISE / BLUETOOTH_SCAN` 与 `ACCESS_FINE_LOCATION` 运行时权限）
- **不依赖** Google Play Services / GMS，普通 AOSP 系统即可
- 推荐硬件：OrangePI 5B、Pixel 3a+、其他带 USB OTG / device mode 且支持经典蓝牙（BR/EDR）的 Android SBC
- 编译环境：JDK 17、Android SDK 35、Gradle Wrapper（已附带）

## 构建与安装

```bash
# 1. 准备本地 SDK 路径
cp local.properties.example local.properties
# 编辑 local.properties，填入你的 Android SDK 路径

# 2. 构建 debug APK
./gradlew assembleDebug

# 3. 安装到 Bridge 设备（OrangePI 等）
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 运行测试

在教师 macOS（Host）上准备一条 USB-TCP 隧道：

```bash
# 方案 A（推荐）：Host 作为 TCP Server，Bridge 作为 TCP Client
adb reverse tcp:9000 tcp:9000

# 验证隧道
adb reverse --list
```

启动 Bridge App 后即可看到连接状态变化：

```
USB Disconnected → USB Connected → Session Hello → Welcome → Heartbeating
                                                        ↓
                                            BT RFCOMM Listening → Android A/B Connected → Forwarding Active
```

## 模块架构

```
com.classweave.bridge/
├── protocol/          统一 Envelope 模型（kotlinx.serialization）
│   ├── Envelope.kt
│   ├── EnvelopeCodec.kt
│   ├── MessageFactory.kt
│   └── BridgeIdentity.kt
├── usb/               USB-TCP 客户端，指数退避重连
│   └── UsbTransportClient.kt
├── core/              转发器 / 会话管理 / 端点注册表
│   ├── Forwarder.kt
│   ├── BridgeSessionManager.kt
│   └── EndpointRegistry.kt
├── bluetooth/         蓝牙 RFCOMM 服务端（Bridge ↔ Android 主通道）
│   └── RfcommHub.kt
├── permissions/       运行时权限处理
│   └── PermissionHelper.kt
├── ui/                Compose UI + ViewModel
│   ├── MainScreen.kt
│   └── LogList.kt
└── model/
    └── AppLog.kt
```

## 协议概览（摘录）

所有跨端消息使用统一的 JSON **Envelope**：

```json
{
  "v": 1,
  "msgId": "<全局唯一>",
  "ts": 1773982800123,
  "sessionId": "sess_xxx",
  "kind": "cmd | state.full | state.delta | ack | error | event",
  "domain": "system | scene | deck | wb",
  "action": "<具体操作>",
  "sender": { "role": "bridge", "deviceId": "bridge_001", "platform": "android" },
  "origin": { "role": "android", "deviceId": "android_003", "platform": "android" },
  "target": { "type": "host | device | all_clients | ios_clients | android_clients" },
  "relay":  { "viaBridge": true, "bridgeId": "bridge_001", "hop": 1 },
  "payload": { /* 业务自定义 */ }
}
```

Bridge 关心的关键消息：

| 消息 | 方向 | 说明 |
| --- | --- | --- |
| `session.hello` | Bridge / Android → Host | Bridge 自身启动后也会以 `role=bridge` 发起 |
| `bridge.status` | Bridge → Host | 周期性上报 USB / Bluetooth 状态与已连接 Android 设备列表 |
| `heartbeat.ping/pong` | 双向 | Bridge 同时代理 Android 客户端的心跳 |
| 全部 `cmd` | Android → Bridge → Host | Bridge 透传，更新 `sender / relay` |
| 全部 `state.*`/`ack`/`event` | Host → Bridge → Android | Bridge 按 `target` 过滤后扇出 |

## 安全与脱敏说明

本项目为 **课程实验作品**，开源版本中：

- 不携带任何长效凭据、签名 keystore、个人 IDE 配置；
- USB-TCP / Bluetooth RFCOMM 链路默认以 `[长度前缀][UTF-8 JSON]` 帧格式明文传输，**生产环境请按需叠加 TLS/mTLS 或应用层签名**。

详见 [SECURITY.md](SECURITY.md) 与 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 相关项目

ClassWeave 由多个独立子项目组成，可单独使用，也可组合成完整的课堂协同系统。
所有仓库均托管在 GitHub 组织 [@ClassWeave](https://github.com/ClassWeave)：

- **[classweave-bridge](https://github.com/ClassWeave/classweave-bridge)**（本项目）— Android 中继桥接
- **[classweave-host](https://github.com/ClassWeave/classweave-host)** — macOS 主控端
- **[classweave-client](https://github.com/ClassWeave/classweave-client)** — iOS / Android 学生客户端（Flutter）
- **[classweave-prompts](https://github.com/ClassWeave/classweave-prompts)** — Vibe coding 提示词与方法论沉淀

## 许可证

本项目以 **Apache License 2.0** 协议开源，详见 [LICENSE](LICENSE) 与 [NOTICE](NOTICE)。
