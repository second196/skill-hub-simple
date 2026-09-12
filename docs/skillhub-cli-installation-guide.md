# SkillHub CLI 安装指南

SkillHub CLI 提供上传、查询和安装平台技能三项能力。本文档同时适用于人工操作和 AI Agent 调用。

## 安装 CLI

```bash
npm install -g @second196/skillhub-cli
skillhub --help
```

也可以不全局安装：

```bash
npx @second196/skillhub-cli --help
```

CLI 支持 macOS 和 Windows，需要 Node.js 20 或更高版本。

## 安装 Agent 操作说明

如果希望 Agent 能根据自然语言调用 SkillHub CLI，可以使用：

```bash
npx skills add https://github.com/second196/skill-hub-simple/tree/main/skill --global
```

`--global` 表示安装到用户空间，避免只对当前项目生效。该操作说明 Skill 与平台中的实际业务 Skill 是两回事：

- Agent 操作说明：帮助 Agent 知道何时、如何调用 `skillhub`
- 平台技能：通过 `skillhub install <slug>` 下载和安装

## 全局选项

默认服务地址：

```text
http://127.0.0.1:8080
```

后端部署在其他地址时，为命令追加：

```bash
--service-url http://your-host:8080
```

需要机器可读输出时追加：

```bash
--json
```

## 上传技能

```bash
skillhub upload <input-path...> [options]
```

输入可以是 ZIP、Skill 目录或单个 `SKILL.md`，一次可以传入多个输入：

```bash
skillhub upload ./skill-a ./skill-b --category 研发
skillhub upload ./skill-a.zip ./skill-b.zip --category 工具 --json
```

每个输入独立上传，命令最后汇总成功和失败结果；出现失败项时返回非 0 退出码。

可用选项：

| 选项 | 默认值 | 说明 |
| --- | --- | --- |
| `--category <category>` | `其他` | 技能分类 |
| `--name <name>` | 自动生成 | 复合包名称覆盖值 |
| `--description <description>` | 自动生成 | 复合包描述覆盖值 |
| `--service-url <url>` | `http://127.0.0.1:8080` | 服务地址 |
| `--json` | 关闭 | JSON 输出 |

### 复合技能包

如果目录或 ZIP 的根目录已有 `SKILL.md`，整个包会被作为一个 Skill 上传，所有子目录和资源都会保留。

如果根目录没有 `SKILL.md`，但包内存在一个或多个嵌套 `SKILL.md`，CLI 和后端会把它识别为复合技能包：

- 自动生成根目录 `SKILL.md`
- 保留所有子技能和其他文件
- 不会选择某一个子技能作为整个包
- 不会要求调用方逐个上传子技能

父级名称和描述会从包内标准配置、README 标题和正文、输入目录或 ZIP 名称中自动生成。需要固定元数据时使用：

```bash
skillhub upload ./skill-collection \
  --category 工具 \
  --name "技能集合" \
  --description "包含多个可独立使用的技能。"
```

`SKILL.md` 需要使用 UTF-8 编码并包含 YAML frontmatter。普通技能的 frontmatter 至少包含 `name` 和 `description`；`version` 可省略，默认是 `0.0.0`。

## 查询技能

```bash
skillhub list
skillhub list --category 研发
skillhub list --json
```

可用选项：

| 选项 | 默认值 | 说明 |
| --- | --- | --- |
| `--category <category>` | 不筛选 | 按分类筛选 |
| `--service-url <url>` | `http://127.0.0.1:8080` | 服务地址 |
| `--json` | 关闭 | JSON 输出 |

## 安装平台技能

```bash
skillhub install <slug> [options]
```

默认安装到用户空间：

```bash
skillhub install ui-ux-pro-max
```

此时 CLI 会：

1. 下载并保存到 SkillHub 用户级存储
2. 检测本机常见 Agent 的用户级技能目录
3. 创建目录链接，无法链接时复制文件
4. 输出实际安装目录和 Agent 入口

切换项目后不需要重新安装。只有明确传入 `--target` 时才安装到指定目录：
例如：
```bash
skillhub install ui-ux-pro-max --target .skills
```

可用选项：

| 选项 | 默认值 | 说明 |
| --- | --- | --- |
| `--target <directory>` | 用户级目录 | 指定项目或自定义安装目录 |
| `--version <digest>` | 最新版本 | 指定版本摘要 |
| `--service-url <url>` | `http://127.0.0.1:8080` | 服务地址 |
| `--json` | 关闭 | JSON 输出 |

## Agent 调用流程

当用户要求查看平台技能时：

```bash
skillhub list --json
```

当用户要求安装某个技能时：

```bash
skillhub install <slug>
```

当用户要求上传本地技能时：

```bash
skillhub upload <path> --category <category>
```

当用户提供多个技能路径时，一次调用批量上传：

```bash
skillhub upload <path-a> <path-b> <path-c> --category <category> --json
```
