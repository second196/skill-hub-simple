# Task 16 主机接入验证证据

## 验证信息

- validation: `validation-installation-task16-real-host-ingress-recovery`
- feature: `feature-skill-installation-recovery`
- task: Task 16 串联安装、修复、探针和真实主机接入
- requirements: `requirement-cli-agent-integration-installation`、`requirement-cli-agent-integration-check`、`requirement-cli-agent-integration-recovery`
- date: 2026-09-04
- classification: none

## 环境

- JDK: `1.8.0_342`，路径解析为 `C:\Users\Administrator\.jdks\oracle_open_jdk-8`
- Node.js: `v20.20.2`
- Codex CLI: `0.151.0-alpha.7.2`
- Visual Studio Code: `1.124.2`
- Memurai: `D:\program_env\redis\memurai.exe`，PID `4156`，`127.0.0.1:6379` 可达
- Redis 检查: Memurai CLI `PING` 返回 `PONG`

## 已执行验证

### Redis Outbox

命令：

```text
mvn -f backend\pom.xml -Dtest=OutboxDeliveryIntegrationTest test
```

使用 JDK 8 和显式 `127.0.0.1:6379` 配置执行，1 个测试通过，0 失败，0 错误，Maven `BUILD SUCCESS`。该验证使用现有 Memurai 进程，未停止或重启该进程。

### Collector 和 Codex Hook

1. `telemetry collector-start --json` 成功启动受管 Collector，监听 `127.0.0.1:43191`。
2. 无凭据安装按预期返回 `COLLECTOR_TELEMETRY_CREDENTIAL_REQUIRED`，未写入 Codex 配置。
3. 使用一次性测试占位值和不可连接的本地服务地址执行 Codex 安装，结果为 `ACTION_REQUIRED`：Collector、Hook 配置、OTel 配置和本地探针均通过；仅等待用户在 Codex `/hooks` 页面确认信任。
4. 调用实际生成的 Hook 处理最小合法 `SessionStart` 事件，Hook 退出码为 0，Collector 写入 1 条 `SESSION_STARTED` 事件，运行时为 `codex-cli`、范围为 1。

### VS Code 扩展

1. 初次主机验证发现 Windows `code.cmd` 入口被错误解析为 Electron `Code.exe`，本地安装返回退出码 9。
2. 修正 `cli/src/adapters/codex/editor-extension-adapter.ts`：Windows `.cmd` 使用固定 `cmd.exe /d /c call` 调用，并保持 `shell: false`。
3. 修正后重新执行本地 VSIX 安装，VS Code 状态为 `ACTIVE/HEALTHY`，扩展列表可检测到 SkillHub 扩展。
4. Collector 收到 `EDITOR_EXTENSION_PROBE` 和 `EDITOR_EXTENSION_ACTIVATED` 事件；事件类型统计为 `SESSION_STARTED` 1 条、`EDITOR_EXTENSION_PROBE` 1 条、`EDITOR_EXTENSION_ACTIVATED` 2 条。
5. 事件记录未包含 prompt、transcript、tool input/output、文件路径或工作目录字段。

### Collector 停止与修复

1. `telemetry collector-stop --json` 成功停止本次 PoC 启动的受管 Collector。
2. `telemetry repair --runtime codex-cli --scope-id 1 --json` 成功重建 Collector 配置并通过本地探针。
3. 修复后 Collector 状态为 `HEALTHY`，Codex 配置摘要保持稳定，VS Code 状态仍为 `ACTIVE/HEALTHY`。
4. 本地接入结果因没有真实 SkillHub 登录凭据保留在待上报队列，未向服务端上传测试数据。

## 回归验证

- `npm --prefix cli test`: 108 项测试，107 通过、0 失败、0 错误、1 项既有 Windows 符号链接权限场景跳过。
- `npm --prefix cli run build`: 通过。
- `git diff --check`: 通过。
- CLI 安全扫描：未发现 `shell: true`、动态 `exec`、凭据输出或 Collector 密钥输出。

## 不可用项和边界

- Cursor、Windsurf 和 Claude Code 当前主机不可用，因此没有执行对应主机安装；其适配器仍由自动化契约测试覆盖。
- 没有真实 SkillHub API Token，因此服务端注册和事件补报未作为在线成功验证；本地接入结果按设计进入待上报队列。
- Codex 仍需要用户在 `/hooks` 中确认信任，确认前状态保持 `ACTION_REQUIRED`，不会被误报为完全激活。

## 结论

Task 16 的本地主机安装、Collector 真实事件入口、VS Code 扩展探针、Codex Hook 事件、停止隔离和 repair 恢复均已获得证据。可将安装恢复 Feature 的下一停点切换为运行观测 Task 4C；本轮不自动进入下一 Feature 任务。
