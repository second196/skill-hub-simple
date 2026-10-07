# SkillHub CLI 安装指南

SkillHub 由两套 CLI 组成：

| 包 | 命令 | 作用 |
| --- | --- | --- |
| `@second196/skillhub-cli` | `skillhub` / `skillhub-cli` | 上传、查询、安装平台技能 |
| `@second196/skillhub-observer` | `skillhub-observer` | 采集本地 Claude Code / Codex 会话，生成报告并上传观测数据 |

本文适用于AI Agent 调用。

> **本文档包含 3 个必须完成的安装步骤。**
> 人工执行时请逐步进行；**AI Agent 执行时必须遵守 §0 的执行协议：三步全做、逐步验收、缺一不可。**

## 0. 给 AI Agent 的执行协议（必读）

本指南包含 **3 个必须完成的步骤**，顺序为 **步骤 1 → 步骤 2 → 步骤 3**。

- **逐步验收**：每一步都必须运行其“验收”命令并检查期望结果。**任何一步验收未通过，都不得宣布安装完成**，请先按该步“失败处理”修复后重试。
- **幂等可重跑**：所有命令都可以安全地重复执行。“之前装过”**不是**跳过验收的理由，请以“验收命令的实际输出”为准。
- **真执行，不是阅读**：本文档本身不是可执行程序。你必须真正运行命令，而不是只阅读内容后声称完成。
- **完成后必须自证**：全部执行完毕后，请运行 §4.2 的“一键验收脚本”，并输出 §4.3 的“完成报告表”。
- **反误解（关键）**：步骤 3 只是把一份**操作说明**复制进 Agent 的 Skill 目录，它**不会**安装或启用 Observer。Observer 只在**步骤 2** 由你执行 `skillhub-observer install` 后才会写入 hooks 并开始采集。`skillhub`（CLI）本身**没有任何** observer 子命令，两者是相互独立的包。

执行总览：

```text
§0.1 前置检查
  └─ 步骤 1：安装/更新 CLI（skillhub）        → 验收 1
  └─ 步骤 2：安装并启用 Observer（必做）      → 验收 2
  └─ 步骤 3：安装 Agent 操作说明              → 验收 3
§4 总验收（Definition of Done）：一键验收脚本 + 完成报告表
```

## 0.1 前置检查（硬门槛）

先确认运行时与后端可用，再进行后续步骤。

```bash
# 1) 运行时（需 Node.js >= 20）
node -v      # 期望：v20 及以上
npm  -v

# 2) 后端可达（SkillHub 无 /actuator/health，用 /api/skills 探活）
curl -fsS -o /dev/null -w 'backend=%{http_code}\n' http://127.0.0.1:8080/api/skills   # 期望：backend=200

# 3) 环境判断（选择后续对应平台的命令分支）
#    macOS / Linux / Windows
```

- **后端不可达就先不要做步骤 2**：`skillhub-observer install` 虽会写入 hooks，但随后的 `drain` 一定失败、验收过不了。
- 后端不在默认地址时，请把后面所有命令里的 `http://127.0.0.1:8080` 替换为你的实际地址。

## 1. 安装或更新 CLI（步骤 1）

### 1.1 执行

先检查是否已安装：

```bash
skillhub --version
```

如果命令不存在，或需要安装/更新到最新版本：

```bash
npm install -g @second196/skillhub-cli@latest
```

从源码安装（仓库内路径）：

```bash
cd cli/skillhub
npm install
npm run build
npm install -g .
```

`skillhub` 和 `skillhub-cli` 是等价的命令名。也可以不全局安装，直接用 `npx`：

```bash
npx @second196/skillhub-cli@latest --help
```

### 1.2 验收

```bash
skillhub --version   # 期望：skillhub/<semver> <os>-<arch> node-vX，如 skillhub/0.4.1 darwin-arm64 node-v24.18.0
skillhub --help      # 期望：列出 upload/check/verify-source/prepare/list/install/uninstall 等子命令
```

### 1.3 失败处理

- `skillhub: command not found`：可能已全局安装但 npm 全局 bin 不在 `PATH`。可用 `npx @second196/skillhub-cli@latest` 兜底，或把 `npm prefix -g` 下的 `bin` 目录加入 `PATH`。
- `npm install -g` 权限失败：改用 nvm 管理的 Node，或配置 `npm config set prefix <可写目录>` 后重试。

## 2. 安装并启用 Observer（步骤 2，必须执行，不可跳过）

Observer 负责在本机收集 Claude Code / Codex 会话，并可靠上传到后端。**只有执行过 `install`，才会开始采集。**

### 2.1 安装 Observer 包

```bash
npm install -g @second196/skillhub-observer@latest
skillhub-observer --help
```

从源码安装：

```bash
cd cli/observer
npm install
npm run build
npm install -g .
```

### 2.2 启用采集

确保后端已启动（见 §0.1），然后：

```bash
skillhub-observer install --service-url http://127.0.0.1:8080
# 或
skillhub-observer install --host 127.0.0.1 --port 8080
```

该命令会：

1. 写入 Claude Code / Codex hooks（Stop / SessionEnd / SessionStart 等）
2. 写入本机 `config.json`（后端地址）
3. 注册当前用户开机登录 + 每 5 分钟的后台补传任务（Windows 无窗口）
4. 后台启动一次 `drain --reconcile` 补扫历史会话

### 2.3 验收

命令级验收：

```bash
skillhub-observer --help            # 期望：列出 install/config/config-set/drain/report/upload 等子命令，版本 0.5.0
skillhub-observer config            # 期望：serviceUrl 为你的后端地址，而不是「(未设置)」
skillhub-observer drain --reconcile # 期望：退出码为 0
```

文件级验收（确认 hooks 真的写进去了）：

- **Codex**：`~/.codex/hooks.json` 存在且包含 SkillHub 的 hook 入口；`~/.codex/config.toml` 含 `[features]` 且 `hooks = true`
- **Claude Code**：`~/.claude/settings.json` 含对应事件
- **日志**：出现 `drain start` / `drain complete`（本机有会话时会看到 `drain uploaded`）

日志路径：

| 系统 | 日志路径 |
| --- | --- |
| Windows | `%LOCALAPPDATA%\SkillHub\observability\logs\observer.log` |
| macOS | `~/Library/Application Support/SkillHub/observability/logs/observer.log` |
| Linux | `$XDG_DATA_HOME/skillhub/observability/logs/observer.log` |

查看日志（Windows 示例）：

```powershell
Get-Content "$env:LOCALAPPDATA\SkillHub\observability\logs\observer.log" -Tail 30
```

出现 `drain uploaded ...` 表示已成功上传。

### 2.4 后端地址变更

改 IP/端口**不需要重新 install**：

```bash
skillhub-observer config-set --host 192.168.1.50 --port 8080
skillhub-observer config-set --service-url http://10.0.0.8:9090
```

`drain` / 开机任务每次运行都会重读配置。

### 2.5 本地报告（可选）

```bash
skillhub-observer report --open
```

## 3. 安装 Agent 操作说明（步骤 3）

执行以下命令，将 SkillHub CLI 操作说明安装到用户级 Skill 目录：

```bash
npx --yes skills add https://github.com/second196/skill-hub-simple/tree/main/skill --global
```

> 注意：这一步**只安装“操作说明”**，**不会**安装或启用 Observer（Observer 见步骤 2）。

重复执行该命令即可检查并补齐本地 Agent 的安装状态，不会改变已正确安装的内容。

### 3.1 验收

```bash
test -s ~/.agents/skills/skillhub-cli-operation-guide/SKILL.md && echo "step3 OK"
```

- 期望输出 `step3 OK`，即 `~/.agents/skills/skillhub-cli-operation-guide/SKILL.md` 存在且非空。
- 命令输出里的 `PromptScript does not support global skill installation` 属**无害警告**，不算失败。

## 4. 总验收（Definition of Done）

**三步全部通过本节验收，才算安装完成。**

### 4.1 通过标准

1. `skillhub --version` 正常输出版本号；
2. `skillhub-observer` 已安装，`install` 已执行，`config` 显示 `serviceUrl` 非空，`drain --reconcile` 退出码为 0；
3. Codex 的 `~/.codex/config.toml` 已启用 `[features].hooks = true`，且 `~/.codex/hooks.json` 含 SkillHub hooks；
4. `~/.agents/skills/skillhub-cli-operation-guide/SKILL.md` 存在且非空。

### 4.2 一键验收脚本

Agent 必须运行对应平台的脚本；**脚本退出码非 0 即表示未完成**，不得宣布成功。

#### macOS / Linux（bash）

```bash
#!/usr/bin/env bash
# SkillHub 三步安装验收（macOS / Linux）
set -u
fail=0
pass() { printf 'PASS  %s\n' "$1"; }
bad()  { printf 'FAIL  %s\n' "$1"; fail=1; }

# 步骤 1：CLI
if command -v skillhub >/dev/null 2>&1 && skillhub --version >/dev/null 2>&1; then
  pass "步骤1 skillhub: $(skillhub --version)"
else
  bad "步骤1 skillhub 不可用（未安装或不在 PATH）"
fi

# 步骤 2：Observer 包
if command -v skillhub-observer >/dev/null 2>&1 && skillhub-observer --help >/dev/null 2>&1; then
  pass "步骤2 skillhub-observer 已安装"
else
  bad "步骤2 skillhub-observer 不可用（未安装或不在 PATH）"
fi

# 步骤 2：config.json 已写入
if skillhub-observer config >/dev/null 2>&1; then
  svc="$(skillhub-observer config 2>/dev/null | sed -n 's/^serviceUrl: //p')"
  if [ -n "$svc" ] && [ "$svc" != "(未设置)" ]; then
    pass "步骤2 config.json serviceUrl=$svc"
  else
    bad "步骤2 config.json 未设置 serviceUrl（可能未执行 install）"
  fi
else
  bad "步骤2 读取配置失败（可能未执行 install）"
fi

# 步骤 2：Codex hooks 已启用
if grep -qE '^[[:space:]]*hooks[[:space:]]*=[[:space:]]*true' "$HOME/.codex/config.toml" 2>/dev/null; then
  pass "步骤2 Codex [features].hooks = true"
else
  bad "步骤2 Codex ~/.codex/config.toml 未启用 hooks"
fi
if [ -s "$HOME/.codex/hooks.json" ] && grep -qi 'skillhub' "$HOME/.codex/hooks.json"; then
  pass "步骤2 Codex ~/.codex/hooks.json 已写入 SkillHub hooks"
else
  bad "步骤2 Codex ~/.codex/hooks.json 缺少 SkillHub hooks"
fi

# 步骤 2：drain 可运行（连通后端）
if skillhub-observer drain --reconcile >/dev/null 2>&1; then
  pass "步骤2 drain --reconcile 成功（后端可达）"
else
  bad "步骤2 drain --reconcile 失败（后端不可达或未 install）"
fi

# 步骤 3：Agent 操作说明
if [ -s "$HOME/.agents/skills/skillhub-cli-operation-guide/SKILL.md" ]; then
  pass "步骤3 Agent 说明已落盘: ~/.agents/skills/skillhub-cli-operation-guide/SKILL.md"
else
  bad "步骤3 Agent 说明缺失（未执行 npx skills add）"
fi

echo "----"
if [ "$fail" -eq 0 ]; then
  echo "ALL STEPS OK ✅  三步安装全部通过验收"
else
  echo "存在未通过项 ❌  请按 §5《常见失败与修复》处理后重跑本脚本"
  exit 1
fi
```

#### Windows（PowerShell）

```powershell
# SkillHub 三步安装验收（Windows / PowerShell）
$fail = $false
function Pass($m) { Write-Host "PASS  $m" -ForegroundColor Green }
function Bad($m)  { Write-Host "FAIL  $m" -ForegroundColor Red; $script:fail = $true }

# 步骤 1：CLI
$skillhub = Get-Command skillhub -ErrorAction SilentlyContinue
if (-not $skillhub) { $skillhub = Get-Command skillhub.cmd -ErrorAction SilentlyContinue }
if ($skillhub) {
  Pass "步骤1 skillhub: $(& skillhub --version)"
} else {
  Bad "步骤1 skillhub 不可用（未安装或不在 PATH）"
}

# 步骤 2：Observer 包
$obs = Get-Command skillhub-observer -ErrorAction SilentlyContinue
if ($obs) { Pass "步骤2 skillhub-observer 已安装" }
else { Bad "步骤2 skillhub-observer 不可用（未安装或不在 PATH）" }

# 步骤 2：config.json 已写入
if ($obs) {
  $cfg = (& skillhub-observer config 2>$null | Out-String)
  if ($cfg -match 'serviceUrl:\s*(http\S+)') {
    Pass "步骤2 config.json serviceUrl=$($Matches[1])"
  } else {
    Bad "步骤2 config.json 未设置 serviceUrl（可能未执行 install）"
  }
} else {
  Bad "步骤2 无法读取配置（observer 不可用）"
}

# 步骤 2：Codex hooks 已启用
$codexCfg   = Join-Path $HOME '.codex\config.toml'
$codexHooks = Join-Path $HOME '.codex\hooks.json'
if ((Test-Path $codexCfg) -and (Select-String -Path $codexCfg -Pattern '^\s*hooks\s*=\s*true' -Quiet)) {
  Pass "步骤2 Codex [features].hooks = true"
} else {
  Bad "步骤2 Codex config.toml 未启用 hooks"
}
if ((Test-Path $codexHooks) -and (Select-String -Path $codexHooks -Pattern 'skillhub' -Quiet)) {
  Pass "步骤2 Codex hooks.json 已写入 SkillHub hooks"
} else {
  Bad "步骤2 Codex hooks.json 缺少 SkillHub hooks"
}

# 步骤 2：drain 可运行（连通后端）
if ($obs) {
  & skillhub-observer drain --reconcile *> $null
  if ($LASTEXITCODE -eq 0) { Pass "步骤2 drain --reconcile 成功（后端可达）" }
  else { Bad "步骤2 drain --reconcile 失败（后端不可达或未 install）" }
} else {
  Bad "步骤2 drain --reconcile 未执行（observer 不可用）"
}

# 步骤 3：Agent 操作说明
$skillMd = Join-Path $HOME '.agents\skills\skillhub-cli-operation-guide\SKILL.md'
if ((Test-Path $skillMd) -and ((Get-Item $skillMd).Length -gt 0)) {
  Pass "步骤3 Agent 说明已落盘: $skillMd"
} else {
  Bad "步骤3 Agent 说明缺失（未执行 npx skills add）"
}

Write-Host "----"
if (-not $fail) {
  Write-Host "ALL STEPS OK  三步安装全部通过验收" -ForegroundColor Green
} else {
  Write-Host "存在未通过项，请按 §5《常见失败与修复》处理后重跑本脚本" -ForegroundColor Red
  exit 1
}
```

### 4.3 完成报告表

Agent 执行完成后，必须按下表逐行汇报，并为每一项附上**证据**（版本号 / 文件路径 / 日志行）：

| 步骤 | 关键命令 | 结果 | 证据 |
| --- | --- | --- | --- |
| 0.1 前置检查 | `node -v` / `curl .../api/skills` | PASS / FAIL | 版本号、`backend=200` |
| 1 CLI | `skillhub --version` | PASS / FAIL | 版本输出行 |
| 2 Observer 安装 | `skillhub-observer --help` | PASS / FAIL | 命令可用 |
| 2 Observer 启用 | `skillhub-observer config` | PASS / FAIL | `serviceUrl=...` |
| 2 Observer hooks | `~/.codex/config.toml` / `hooks.json` | PASS / FAIL | `hooks = true`、hook 条目 |
| 2 Observer 连通 | `skillhub-observer drain --reconcile` | PASS / FAIL | 退出码 / 日志行 |
| 3 Agent 说明 | `npx --yes skills add ... --global` | PASS / FAIL | `.../SKILL.md` 路径 |
| 4 总验收 | 一键验收脚本 | PASS / FAIL | `ALL STEPS OK` |

## 5. 常见失败与修复

| 现象 | 原因 | 修复 |
| --- | --- | --- |
| `skillhub: command not found` | 未全局安装，或 npm 全局 bin 不在 `PATH` | `npx @second196/skillhub-cli@latest` 兜底；或把 `npm prefix -g` 下的 `bin` 加入 `PATH` |
| `npm install -g` 权限失败 | 无写权限 / 系统 Node | 使用 nvm；或 `npm config set prefix <可写目录>` 后重试 |
| 步骤 2 `drain` 失败 / 日志 `drain failed` | 后端不可达，或未执行 `install` | 先用 §0.1 探活；地址不对用 `skillhub-observer config-set --host/--port`（**无需重装 hooks**） |
| `install` 报“hooks 已写入，但开机任务注册失败” | 无 GUI / CI 环境无法注册启动任务 | 视为“hooks 成功、开机任务失败”的**部分成功**；采集仍可用 `drain` 手动/定时触发 |
| 步骤 3 输出 `PromptScript does not support global skill installation` | 某 Agent 不支持全局技能安装 | **无害警告**，不影响 Codex 等 Agent，继续即可 |
| 步骤 3 后 `~/.agents/skills/...` 仍不存在 | `npx skills add` 未成功 | 重跑 `npx --yes skills add https://github.com/second196/skill-hub-simple/tree/main/skill --global` 并检查输出 |

## 概念说明

本文档中的两类 Skill 含义不同：

- **Agent 操作说明**：指导 AI Agent 在适当场景调用 `skillhub`（即 §3 安装的内容）。
- **平台技能**：通过 `skillhub install <slug>` 从 SkillHub 下载并安装，供 Agent 使用。

**CLI 与 Observer 的职责不同：**

- `skillhub`：管技能包（上传 / 查询 / 安装）
- `skillhub-observer`：管观测数据（会话采集 / 本地报告 / 上传 ingest）

安装完成后，可用以下命令验证 CLI 与服务连接：

```bash
skillhub list
```

如果服务不在默认地址 `http://127.0.0.1:8080`，请追加服务地址：

```bash
skillhub list --service-url http://your-host:8080
```

## 卸载

### 一键卸载 Observer 本机痕迹

```bash
skillhub-observer uninstall
```

默认会：

1. 从 Claude Code / Codex hooks 中移除 Observer 条目
2. 关闭 Codex `[features].hooks`（可用 `--keep-codex-features` 保留）
3. 删除 Windows / macOS / Linux 上的 drain 计划任务与启动器
4. 删除本机数据目录（`--keep-data` 可保留 config/spool/logs/sessions）
5. 打印 npm 卸载命令；加 `--purge-packages` 会直接执行
   `npm uninstall -g @second196/skillhub-observer @second196/skillhub-cli`

### 卸载已安装技能

```bash
skillhub uninstall <slug>        # 卸载单个技能（含 Agent 入口）
skillhub uninstall --all         # 清空用户技能库
skillhub uninstall --all --json
```

### 完全移除两套 CLI

```bash
skillhub-observer uninstall --purge-packages
skillhub uninstall --all
npm uninstall -g @second196/skillhub-observer @second196/skillhub-cli
```

更稳妥的顺序是先 `skillhub uninstall --all`，再 `skillhub-observer uninstall --purge-packages`。

**仍需手动清理的残留（当前命令不覆盖）：**

- `npx skills add .../skill --global` 装入 Agent 目录的 `skillhub-cli-operation-guide`，请自行从 `~/.agents/skills`、`~/.claude/skills`、`~/.codex/skills` 等目录删除
- 平台侧技能与观测数据（后端库内记录）
- `--keep-data` 保留的 `%LOCALAPPDATA%\SkillHub\observability`（或对应 macOS/Linux 路径）数据

## 下载量说明

技能搜索页和技能详情页会展示累计下载量。每次通过 Web 下载或执行
`skillhub install <slug>` 成功下载技能 ZIP 后，平台会将对应技能的下载量加一。
查询命令仍然可以一次列出平台中的全部技能；本地已安装技能可用 `skillhub uninstall` 删除，
平台侧不提供删除技能命令。
