# SkillHub Observer

本地技能观测采集器。它负责安装 Agent hooks、扫描 Claude Code / Codex 会话、生成本地完整 HTML 报告，以及把**全部会话原文（含助手回复）**上传到 SkillHub。

本包与 `@second196/skillhub-cli` 独立，不提供技能上传、查询、安装或删除命令。

## 安装

```bash
npm install -g @second196/skillhub-observer
skillhub-observer --help
```

需要 Node.js 20 或更高版本。

## 命令

### 安装采集器

```bash
skillhub-observer install
```

会写入：

- Claude Code：`~/.claude/settings.json` 的 PreToolUse / PostToolUse / Stop / SessionEnd
- Codex：`~/.codex/hooks.json`，并启用 `~/.codex/config.toml` 中的 `[features].hooks = true`

Hook 失败时不会阻塞 Agent：始终退出 0，并向 stdout 输出 `{}`。

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
skillhub-observer upload --service-url http://127.0.0.1:8080
```

上传规则：

- 先扫描本地 Claude Code / Codex 的 jsonl，再与 hook 事件合并
- 上传本机全部会话和回合，包含用户原文、助手回复、工具参数/结果和文档正文
- 平台技能只用于标注，不再作为过滤条件；不生成摘要
- 保留 `SKILL.md` / `*.md` / `*.mdx` / `*.txt` / `*.rst`，丢弃代码和二进制
- 按 `(client_id, session_id, turn_index, step_id)` 幂等写入

## 本地数据

Canonical 存储：

```text
events.jsonl
client.json
```

一次对话结束时，Stop / SessionEnd hook 会扫描该会话 jsonl，并写入 `events.jsonl`。`report` 和 `upload` 在执行前也会全量扫描一次。
