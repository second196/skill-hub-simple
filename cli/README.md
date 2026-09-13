# SkillHub CLI

SkillHub CLI 用于通过命令行上传、查询和安装 SkillHub 平台中的技能。

当前版本：`0.2.1`

## 安装

需要 Node.js 20 或更高版本。

全局安装：

```bash
npm install -g @second196/skillhub-cli
skillhub --version
```

也可以不安装到全局，直接使用 `npx`：

```bash
npx @second196/skillhub-cli --help
```

安装后提供两个等价的命令名：

```bash
skillhub
skillhub-cli
```

## 服务地址

默认连接：

```text
http://127.0.0.1:8080
```

后端部署在其他地址时，为命令追加：

```bash
--service-url http://your-host:8080
```

需要供脚本或 Agent 读取时，追加 `--json` 获取 JSON 输出。

## 命令

### 上传技能

```bash
skillhub upload <input-path...> [options]
```

输入可以是 ZIP 文件、技能目录或单个 `SKILL.md`。一次可以传入多个路径，输入之间相互独立，某一个失败不会阻止其他输入继续上传。

```bash
skillhub upload ./skill-a ./skill-b --category 研发
skillhub upload ./skill-a.zip ./skill-b.zip --category 工具 --json
skillhub upload ./skill-a --category 研发 \
  --service-url http://127.0.0.1:8080
```

选项：

| 选项 | 默认值 | 说明 |
| --- | --- | --- |
| `--category <category>` | `其他` | 技能分类 |
| `--name <name>` | 自动生成 | 覆盖复合技能包的名称 |
| `--description <description>` | 自动生成 | 覆盖复合技能包的描述 |
| `--service-url <url>` | `http://127.0.0.1:8080` | SkillHub 服务地址 |
| `--json` | 关闭 | 输出机器可读的 JSON |

#### 复合技能包

- 根目录有 `SKILL.md` 时，整个目录或 ZIP 会作为一个技能上传，子目录和资源都会保留。
- 根目录没有 `SKILL.md`，但包内存在一个或多个嵌套 `SKILL.md` 时，会作为一个复合技能包上传。
- CLI 会根据包内标准元数据、README 标题和正文、输入路径名称自动生成父级名称和描述。
- CLI 会自动生成根目录 `SKILL.md`，不会把某个子技能单独提升为整个包，也不会强制用户逐个上传。
- 需要固定父级元数据时，可以显式传入 `--name` 和 `--description`。

`SKILL.md` 应使用 UTF-8 编码并包含 YAML frontmatter。`name` 和 `description` 必填，`version` 可省略，省略时使用 `0.0.0`；如果填写，必须是语义化版本号。

CLI 对单个 ZIP、解压后总大小和单个文件的本地预检查上限均为 1GiB，后端也使用相同的 1GiB 业务容量限制。

### 查询技能

```bash
skillhub list [options]
```

```bash
skillhub list
skillhub list --category 研发
skillhub list --json
```

选项：

| 选项 | 默认值 | 说明 |
| --- | --- | --- |
| `--category <category>` | 不筛选 | 按分类筛选 |
| `--service-url <url>` | `http://127.0.0.1:8080` | SkillHub 服务地址 |
| `--json` | 关闭 | 输出完整 JSON |

### 安装技能

```bash
skillhub install <slug> [options]
```

默认安装到用户级 SkillHub 存储，不依赖当前项目目录：

```bash
skillhub install ui-ux-pro-max
```

用户级安装会保存技能文件，并为本机已经存在的 Agent 技能目录创建入口；无法创建目录链接时会自动复制文件。切换到其他项目后仍然可以使用该技能。
安装成功会计入该技能的累计下载量。CLI 只提供上传、查询和安装命令，不提供删除技能命令。

只有明确指定 `--target` 时，才会安装到项目或自定义目录：

```bash
skillhub install ui-ux-pro-max --target .skills
skillhub install ui-ux-pro-max --target ./vendor/skills
```

选项：

| 选项 | 默认值 | 说明 |
| --- | --- | --- |
| `--target <directory>` | 用户级安装 | 指定项目或自定义安装父目录 |
| `--version <digest>` | 默认最新版本 | 指定版本摘要 |
| `--service-url <url>` | `http://127.0.0.1:8080` | SkillHub 服务地址 |
| `--json` | 关闭 | 输出机器可读的 JSON |

默认用户级目录：

- macOS：`~/Library/Application Support/SkillHub/skills`
- Windows：`%LOCALAPPDATA%\SkillHub\skills`
- Linux：`$XDG_DATA_HOME/skillhub/skills`，未设置时使用 `~/.local/share/skillhub/skills`

## Agent 使用建议

Agent 可以先查询平台技能，再根据返回的 `slug` 安装：

```bash
skillhub list --json
skillhub install <slug>
```

上传本地技能时：

```bash
skillhub upload <path> --category <category>
```

如果服务不在默认地址，将 `--service-url` 添加到每条命令中。

## 本地开发

在仓库的 `cli` 目录执行：

```bash
npm install
npm run build
node dist/index.js --help
```

类型检查：

```bash
npm test
```

发布前构建会由 `prepublishOnly` 自动执行：

```bash
npm publish
```
