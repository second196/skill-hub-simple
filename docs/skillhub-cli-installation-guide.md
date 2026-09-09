# SkillHub CLI 安装指南

这份文档是 SkillHub CLI 的唯一统一说明入口。CLI 只提供上传、查询和安装平台技能三项能力；下面是安装步骤

## 第一步 安装 SkillHub CLI

全局安装：

```bash
npm install -g @second196/skillhub-cli
```

安装后可以使用：

```bash
skillhub --help
```

## 第二步 安装 skillhub-cli Skill

Agent 使用标准的 `npx skills add`：

```bash
npx skills add https://github.com/second196/skill-hub-simple/skill
```

通用写法：

```bash
npx skills add <GitHub skill URL>
```

例如，要安装本仓库中某个公开的 Skill，可以把 `<GitHub skill URL>` 换成对应的 GitHub 目录地址。
