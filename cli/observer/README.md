# SkillHub Observer

本地技能观测采集器。它负责安装 Agent hooks、扫描 Claude Code / Codex 会话、生成本地完整 HTML 报告，以及把**全部会话原文（含助手回复）**上传到 SkillHub。

本包与 `@second196/skillhub-cli` 独立，不提供技能上传、查询、安装或删除命令。

观测版本身份是包内 SemVer `version_label`（无 digest）：

- 单技能包：根 `SKILL.md` frontmatter `version`
- 复合技能包：最近包根 `package.json` / `.codex-plugin/plugin.json` 的 `version`（子技能继承父包版本；子技能自身 `version` 优先）
- 任一 skill 步无法解析出合法 SemVer 时，整会话不上传

## 安装

```bash
npm install -g @second196/skillhub-observer
skillhub-observer --help
```

需要 Node.js 20 或更高版本。

## 命令

### 安装采集器

```bash
skillhub-observer install --service-url http://127.0.0.1:8080
# 或
skillhub-observer install --host 127.0.0.1 --port 8080
```

会写入：

- Claude Code：`~/.claude/settings.json` 的 PreToolUse / PostToolUse / Stop / SessionEnd / SessionStart
- Codex：`~/.codex/hooks.json` 同样安装上述事件，并启用 `~/.codex/config.toml` 中的 `[features].hooks = true`
- 本机 `config.json`，以及当前用户开机/每 5 分钟的 `drain --reconcile` 任务

Windows 计划任务通过 `wscript.exe` 运行 `bin/drain-hidden.vbs` 隐藏启动，**不会弹出 cmd 窗口**。手动调试可用 `bin/drain.cmd`。

Stop / SessionEnd 只把当前会话写入 `spool/` 并拉起 detached drain。SessionStart 只 drain，不扫描当前会话。Hook 失败时不会阻塞 Agent：始终退出 0，并向 stdout 输出 `{}`。

`install` 结束后会在**后台**启动一次 `drain --reconcile`，不会卡住安装命令。上传进度看 `logs/observer.log`。

### 动态修改后端 IP/端口

后端地址存在本机 `config.json`，改完立刻生效，**不需要重新 install hooks**：

```bash
skillhub-observer config                 # 查看当前配置
skillhub-observer config-path            # 打印配置文件路径
skillhub-observer config-set --host 192.168.1.50 --port 8080
skillhub-observer config-set --service-url http://10.0.0.8:9090
skillhub-observer config-set --host api.example.com --port 443 --protocol https
```

`drain` / `upload` 每次运行都会重新读 `config.json`。临时覆盖可用环境变量：

```powershell
$env:SKILLHUB_SERVICE_URL = "http://127.0.0.1:18080"
```

Windows 默认配置路径：`%LOCALAPPDATA%\SkillHub\observability\config.json`

### 生成本地报告

```bash
skillhub-observer report --open
skillhub-observer report -o ./observation-report.html --service-url http://127.0.0.1:8080
```

报告包含本机全部会话、回合和助手回复。提供 `--service-url` 时，技能会标记是否出现在平台目录中。

默认输出路径：

- macOS：`~/Library/Application Support/SkillHub/observability/report.html`
- Windows：`%LOCALAPPDATA%\SkillHub\observability\report.html`
- Linux：`$XDG_DATA_HOME/skillhub/observability/report.html`

### 上传到平台

```bash
skillhub-observer drain --reconcile
skillhub-observer upload --service-url http://127.0.0.1:8080
```

上传规则：

- 对话结束时自动入队；`drain` 负责 HTTP 和重试，hook 不发 HTTP
- `upload` 全量扫描源 jsonl 后入队并 drain，作为对账兜底
- 上传本机全部会话和回合，包含用户原文、助手回复、工具参数/结果和文档正文
- 平台技能只用于标注，不再作为过滤条件；不生成摘要
- 保留 `SKILL.md` / `*.md` / `*.mdx` / `*.txt` / `*.rst`，丢弃代码和二进制
- 按 `(client_id, session_id, turn_index, step_id)` 幂等写入

## 本地数据

Canonical 存储：

```text
config.json                 # host / port / serviceUrl，可动态改
state.json
client.json
sessions/<client>/<safeId>.jsonl
spool/<safeId>.json
```

一次对话结束时，Stop / SessionEnd hook 会尽量扫描该会话 jsonl，写入 per-session snapshot 和 spool 任务，再拉起 detached drain。`SessionStart`、开机任务和每 5 分钟任务都会 `drain --reconcile`。`report` 从 `sessions/**/*.jsonl` 汇总；全局 `events.jsonl` 只作为历史兼容，不再是上传主路径。

可靠性要点：

- Hook 不发 HTTP，失败也返回 `{}` 和退出码 0
- 空事件、源文件丢失、HTTP 失败都不会 ACK，spool 保留并指数退避（上限 15 分钟）
- ACK 带 spool `version`：上传期间若有新的 Stop 入队，旧上传成功不会删除新任务
- 未 `install` 时 `drain` 会明确报错，不会静默打到默认地址

### 日志

每次 drain / 上传会追加写入：

| 系统 | 路径 |
|------|------|
| Windows | `%LOCALAPPDATA%\SkillHub\observability\logs\observer.log` |
| macOS | `~/Library/Application Support/SkillHub/observability/logs/observer.log` |
| Linux | `$XDG_DATA_HOME/skillhub/observability/logs/observer.log` |

日志内容包括：

- `drain start`：本次使用的 serviceUrl、是否 reconcile
- `drain uploaded`：会话、事件数、spool version
- `drain failed` / `drain deferred`：失败原因与下次重试时间
- `drain complete`：uploaded / deferred / failed 汇总
- hook 扫描或入队异常

查看日志（Windows）：

```powershell
Get-Content "$env:LOCALAPPDATA\SkillHub\observability\logs\observer.log" -Tail 50
```

可靠上传实现见 [../../docs/observer-reliable-upload.md](../../docs/observer-reliable-upload.md)。
