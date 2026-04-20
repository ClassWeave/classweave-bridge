# 安全策略 / Security Policy — ClassWeave Bridge

## 支持的版本

`classweave-bridge` 是课程作业级别的开源项目，**当前仅对 `main` 分支提供安全维护**。

| 版本 | 是否接收安全更新 |
| --- | --- |
| `main` | ✅ |
| 其他历史 tag / 分支 | ❌ |

## 报告漏洞

如果你发现了潜在的安全问题（例如：协议层伪造、Bridge 越权转发、消息注入、
蓝牙 RFCOMM 未授权连接 / 配对劫持、USB-TCP 隧道劫持、敏感日志泄漏等），**请勿在
公开 Issue / PR 中直接披露**。请改为通过以下任一渠道与我们私下沟通：

- 通过 GitHub [Private vulnerability reporting](https://github.com/ClassWeave/classweave-bridge/security/advisories/new) 提交（推荐）；
- 或通过 [@ClassWeave](https://github.com/ClassWeave) 组织主页公示的项目联系邮箱与我们联系。

报告时请尽量包含：

1. 受影响版本（commit SHA、APK 版本号）；
2. 漏洞描述与潜在影响（机密性 / 完整性 / 可用性）；
3. 复现步骤、最小复现代码或抓包；
4. 你的处置期望（披露时间、是否署名等）。

我们会在 **5 个工作日** 内确认收到，并在评估后给出修复时间表。
对负责任披露我们会在补丁发布时致谢（除非你明确希望匿名）。

## 已知的安全注意事项

由于本项目是课程实验作品，开源版本中默认存在以下安全限制，**部署前请自行评估并加固**：

1. **明文传输**：
   - **USB-TCP 隧道**（`adb reverse` + `127.0.0.1:9000`）按 `[4 字节 BE 长度][UTF-8 JSON]`
     帧格式明文传输 Envelope。
   - **Bluetooth RFCOMM**（SPP）链路同样为明文 JSON 帧，无业务层加密。
   - 若部署到不可信物理环境，请按需叠加 TLS / mTLS 或应用层签名。

2. **Bridge 信任假设**：
   - Bridge 仅做透传，不校验业务 payload 的合法性。攻击者若能接管 Bridge，
     可对所有上下行消息进行重放或丢弃，但**无法**伪造 Host 的业务权威响应
     （`state.full / state.delta / 业务 ack` 仅由 Host 生成）。
   - `bridge.status` 上报的设备列表来自本地 `EndpointRegistry`，请勿据此做
     强信任决策。

3. **蓝牙服务发现**：
   - 默认 SPP `SERVICE_UUID = 0000c1a5-5e4e-e000-b1d9-e00000000001`，对周边任意
     完成配对的设备可见，且 RFCOMM 不依赖系统 PIN 即可由对端发起连接。
   - 生产场景建议引入应用层 PIN 码确认或离线下发的会话密钥来限制接入范围。

4. **运行时权限**：
   - 需要 `BLUETOOTH_ADVERTISE / CONNECT / SCAN`、`ACCESS_FINE_LOCATION` 等敏感权限。
   - **不依赖** Google Play Services（无 GMS Nearby），普通 AOSP 系统也可运行，
     但请确保宿主 ROM 已正确实现经典蓝牙（BR/EDR）与运行时权限授予链路。

5. **日志与诊断**：
   - 默认会在 UI 与 logcat 上打印较多调试信息（包含 sessionId、deviceId、name 等）。
   - **正式部署前请关闭或调低日志等级**，避免泄漏会话标识与设备名。

## 安全相关的提交建议

参见 [CONTRIBUTING.md §4 安全检查](CONTRIBUTING.md#4-安全检查提交前必读)。

感谢你帮助 ClassWeave Bridge 变得更安全！
