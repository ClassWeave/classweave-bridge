# 贡献指南 / Contributing — ClassWeave Bridge

感谢你愿意为 `classweave-bridge` 提交补丁、Issue 或新特性。

> 本项目源自《移动互联网前沿技术》课程作业，因此倾向于 **保持代码简洁、契合协议契约**。
> 重大架构变更建议先开 Issue 讨论。

## 1. 开发流程

1. **Fork** 本仓库到你的账户，并基于 `main` 切出特性分支：
   ```bash
   git checkout -b feat/your-feature
   ```
2. 拉起本地开发环境：
   ```bash
   cp local.properties.example local.properties
   # 填入本机 Android SDK 路径
   ./gradlew assembleDebug
   ```
3. 提交 PR 前请确认：
   - `./gradlew lint` 通过；
   - `./gradlew assembleDebug` 编译通过；
   - 没有提交任何敏感信息（参见下文「安全检查」）。

## 2. 提交信息约定

建议遵循 [Conventional Commits](https://www.conventionalcommits.org/)：

```
feat(forwarder): 支持按 deviceId 单播下行
fix(usb): 修复 USB 断线后未及时清空缓冲区的问题
docs(readme): 补充 ADB Tunnel 验证步骤
refactor(bluetooth): 抽离 EndpointRegistry 为独立模块
```

## 3. 代码风格

- 遵循 [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html)；
- 异步统一使用协程 + `Dispatchers.IO`，避免在 UI 线程做 IO；
- 跨组件状态用 `StateFlow / SharedFlow` 暴露，禁止直接持有可变全局状态；
- `CoroutineScope` 使用 `SupervisorJob`，避免单子任务失败拖垮全局；
- JSON 字段统一 `camelCase`，与 Host / Client 的 Envelope 对齐；
- 日志统一通过 `AppLog + onLog` 回调流出，方便 UI 与持久化复用。

## 4. 安全检查（提交前必读）

在 `git push` 之前请自查：

- [ ] 没有提交 `local.properties`、`*.keystore`、`*.jks`、`*.p12`；
- [ ] 没有把 AccessKey / Token / 密码硬编码到源码或资源文件；
- [ ] 没有引入 `/Users/<your-name>` 这类本机绝对路径；
- [ ] 没有把 IDE 个人配置（`.idea/workspace.xml` 等）误加入提交；
- [ ] 没有把内部过程文档、AI 协作日志 (`CLAUDE.md`、`.claude/`、`.specstory/`) 上传。

最简扫描命令：

```bash
git diff --staged | rg -i 'AKID|LTAI|secret|password|token|/Users/'
```

如果不慎提交了密钥，请立刻：

1. 在云控制台 **轮换 / 吊销** 该密钥；
2. 用 `git filter-repo` 或 BFG 重写历史；
3. 强制推送，并通知所有协作者重新 clone。

## 5. 报告漏洞

请勿在公开 Issue 中讨论安全漏洞，参考 [SECURITY.md](SECURITY.md) 中的私下披露流程。

## 6. 许可

提交到本仓库的所有代码均默认以 **Apache License 2.0** 授权，详见 [LICENSE](LICENSE)。

感谢你的贡献！
