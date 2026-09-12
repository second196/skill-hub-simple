# SkillHub CLI 安装指南

SkillHub CLI 用于上传、查询和安装 SkillHub 平台技能。本文适用于人工操作和 AI Agent 调用。

## 前置条件

- macOS 或 Windows
- Node.js 20 或更高版本
- npm

## 安装流程

安装分为两步：

1. 安装或更新 SkillHub CLI。
2. 为 AI Agent 安装 SkillHub CLI 操作说明。

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

### 2. 安装 Agent 操作说明

执行以下命令，将 SkillHub CLI 操作说明安装到用户级 Skill 目录：

```bash
npx skills add https://github.com/second196/skill-hub-simple/tree/main/skill --global
```

重复执行该命令即可检查并补齐本地 Agent 的安装状态，不会改变已正确安装的内容。

## 概念说明

本文档中的两类 Skill 含义不同：

- **Agent 操作说明**：指导 AI Agent 在适当场景调用 `skillhub`。
- **平台技能**：通过 `skillhub install <slug>` 从 SkillHub 下载并安装，供 Agent 使用。

安装完成后，可用以下命令验证 CLI 与服务连接：

```bash
skillhub list
```

如果服务不在默认地址 `http://127.0.0.1:8080`，请追加服务地址：

```bash
skillhub list --service-url http://your-host:8080
```
