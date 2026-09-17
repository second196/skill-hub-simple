# SkillHub CLI 安装指南

SkillHub 由两套 CLI 组成：

| 包 | 命令 | 作用 |
| --- | --- | --- |
| `@second196/skillhub-cli` | `skillhub` / `skillhub-cli` | 上传、查询、安装平台技能 |
| `@second196/skillhub-observer` | `skillhub-observer` | 采集本地 Claude Code / Codex 会话，生成报告并上传观测数据 |

本文适用于人工操作和 AI Agent 调用。

## 前置条件

- macOS、Windows 或 Linux
- Node.js 20 或更高版本
- npm
- SkillHub 后端已启动（默认 `http://127.0.0.1:8080`）

## 安装流程

完整安装分为三步：

1. 安装或更新 SkillHub CLI（技能包）。
2. 安装并启用 SkillHub Observer（**观测数据采集，必须单独安装**）。
3. 为 AI Agent 安装 SkillHub CLI 操作说明。

### 1. 安装或更新 CLI

先检查 CLI 是否已安装：

```bash
skillhub --version
```

如果命令不存在，或需要安装最新版本，执行：

```bash
npm install -g @second196/skillhub-cli@latest
```

安装后验证：

```bash
skillhub --version
skillhub --help
```

`skillhub` 和 `skillhub-cli` 是等价的命令名。也可以不进行全局安装，直接使用 `npx`：

```bash
npx @second196/skillhub-cli@latest --help
```

从源码安装（仓库内路径）：

```bash
cd cli/skillhub
npm install
npm run build
npm install -g .
```

### 2. 安装并启用 Observer（观测采集）

Observer 负责在本机收集 Claude Code / Codex 会话，并可靠上传到后端。**只有执行过 `install`，才会开始采集。**

#### 2.1 安装 Observer

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

#### 2.2 启用采集

确保后端已启动，然后：

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

#### 2.3 验证采集是否生效

```bash
skillhub-observer config
skillhub-observer drain --reconcile
```

查看上传日志：

| 系统 | 日志路径 |
| --- | --- |
| Windows | `%LOCALAPPDATA%\SkillHub\observability\logs\observer.log` |
| macOS | `~/Library/Application Support/SkillHub/observability/logs/observer.log` |
| Linux | `$XDG_DATA_HOME/skillhub/observability/logs/observer.log` |

Windows 示例：

```powershell
Get-Content "$env:LOCALAPPDATA\SkillHub\observability\logs\observer.log" -Tail 30
```

出现 `drain uploaded ...` 表示已成功上传。

#### 2.4 后端地址变更

改 IP/端口**不需要重新 install**：

```bash
skillhub-observer config-set --host 192.168.1.50 --port 8080
skillhub-observer config-set --service-url http://10.0.0.8:9090
```

`drain` / 开机任务每次运行都会重读配置。

#### 2.5 本地报告（可选）

```bash
skillhub-observer report --open
```

### 3. 安装 Agent 操作说明

执行以下命令，将 SkillHub CLI 操作说明安装到用户级 Skill 目录：

```bash
npx skills add https://github.com/second196/skill-hub-simple/tree/main/skill --global
```

重复执行该命令即可检查并补齐本地 Agent 的安装状态，不会改变已正确安装的内容。

## 概念说明

本文档中的两类 Skill 含义不同：

- **Agent 操作说明**：指导 AI Agent 在适当场景调用 `skillhub`。
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

Observer 验证：

```bash
skillhub-observer config
skillhub-observer drain --reconcile
```

## 下载量说明

技能搜索页和技能详情页会展示累计下载量。每次通过 Web 下载或执行
`skillhub install <slug>` 成功下载技能 ZIP 后，平台会将对应技能的下载量加一。
查询命令仍然可以一次列出平台中的全部技能；CLI 不提供删除技能命令。
