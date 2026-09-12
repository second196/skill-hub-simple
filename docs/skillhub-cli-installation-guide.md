# SkillHub CLI 安装指南

SkillHub CLI 提供上传、查询和安装平台技能三项能力。本文档同时适用于人工操作和 AI Agent 调用。

## 1. 安装 CLI

```bash
npm install -g @second196/skillhub-cli
skillhub --help
```

也可以不全局安装：

```bash
npx @second196/skillhub-cli --help
```

CLI 支持 macOS 和 Windows，需要 Node.js 20 或更高版本。

## 2. 安装 Agent 操作说明

使用下面的命令安装skill，安装完该skill，Agent可以通过skill就知道如何操作skillhub-cli：

```bash
npx skills add https://github.com/second196/skill-hub-simple/tree/main/skill --global
```

`--global` 表示安装到用户空间，避免只对当前项目生效。该操作说明 Skill 与平台中的实际业务 Skill 是两回事：

- Agent 操作说明：帮助 Agent 知道何时、如何调用 `skillhub`
- 平台技能：通过 `skillhub install <slug>` 下载和安装