# SkillHub Observer

本地技能观测采集器。它负责安装 Agent hooks、扫描 Claude Code / Codex 会话、生成本地完整 HTML 报告，以及把**平台已有技能的完整调用内容**上传到 SkillHub。

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

报告包含本机全部技能调用，包括平台没有的技能。提供 `--service-url` 时，技能会标记为「可上传」或「仅本地」。

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
- 只上传平台已有技能（含已下架）出现过的会话回合
- 上传完整用户原文、完整工具参数/结果、完整文档正文，不生成摘要
- 保留 `SKILL.md` / `*.md` / `*.mdx` / `*.txt` / `*.rst`，丢弃代码和二进制
- 按 `(client_id, session_id, turn_index, step_id)` 幂等写入

## 本地数据

Canonical 存储：

```text
events.jsonl
client.json
```

一次对话结束时，Stop / SessionEnd hook 会扫描该会话 jsonl，并写入 `events.jsonl`。`report` 和 `upload` 在执行前也会全量扫描一次。
