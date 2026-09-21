---
name: skillhub-cli-operation-guide
description: Use when an AI coding agent needs to operate SkillHub CLI — check/upload/list/install skills on Windows or other platforms. Version must live inside the skill package; CLI never injects version. Infer category from skill content before upload; never default to 其他 without reading. Prefer skillhub.cmd / npx on Windows.
---

# SkillHub CLI 操作手册（Agent）

## 0. 一次成功检查清单（先读这段）

1. **Windows 入口**：优先 `skillhub.cmd` 或 `npx @second196/skillhub-cli`。**不要**先试 `skillhub.ps1`（常被执行策略拦截）。用 `Get-Command skillhub` 确认命令位置。
2. **version 必须在 Skill 包内**，CLI **没有** `--skill-version` 上传参数，也**不会**注入版本。
3. **分类必须先读 skill 内容再判定**，并写入包内（单包 frontmatter `category` / 复合包 `package.json` `category`）。禁止未读内容就默认「其他」。
4. 上传后必须 **list 核验** `slug + version_label + category`。
5. 包元数据不完整时：**临时目录补全 → 完整覆盖回源目录 → 删除临时目录 → 再 upload**。

## 1. CLI 入口

```bash
skillhub <command> [options]
skillhub-cli <command> [options]
```

默认服务地址：`http://127.0.0.1:8080`。

```bash
npm install -g @second196/skillhub-cli
# 或不安装：
npx @second196/skillhub-cli <command> [options]
```

Windows PowerShell 示例：

```powershell
Get-Command skillhub
skillhub.cmd --help
# 或
npx @second196/skillhub-cli --help
```

## 2. 契约（必须遵守）

| 主题 | 规则 |
| --- | --- |
| 版本来源 | **只解析包内**：单 Skill = 根 `SKILL.md` frontmatter `version`；复合包 = 包根 `package.json` 的 `version` |
| CLI 注入 | **禁止**。无 `--skill-version` 上传参数；`skillhub --version` 只打印 CLI 工具版本 |
| 版本身份 | 平台用 **SemVer `version_label`** 区分；**没有 digest** |
| 同版本再传 | **拒绝** `VERSION_EXISTS`（版本不可变），错误会给出 `suggestedNextVersion` |
| 版本未升 | **拒绝** `VERSION_BUMP_REQUIRED`，错误会给出建议下一版本 |
| 复合包 | **不要**创建根 `SKILL.md`；至少一个 `*/SKILL.md`；version 写在包根 package.json |
| 分类判定 | **上传前必须读 skill 内容**，按最贴近语义选择分类并写入包内；禁止未读内容默认「其他」 |
| 分类优先级 | **包内 category > 平台已有 skill 的 category > CLI `--category` > `其他`** |
| 观测 | Skill 包必须有 version 才能上传；**无版本号的历史观测数据不会上传**，只上传该版本下的观测数据 |

## 2.1 分类判定（上传前必做）

分类不是 Agent 随口发挥，也不是 CLI 默认值。流程固定为：**读内容 → 选类型 → 写进包内 → 上传 → 核验**。

### 必读材料（至少读完再选）

- 根 `SKILL.md` 的 `description` 与正文标题/职责说明
- 复合包子 Skill 目录名与各 `*/SKILL.md` 的 name/description（如 `sop-requirement`、`sop-implement`）
- 根 `README.md` / 包根 `package.json` 的 description
- 用户在任务里明确指定的分类（若与内容冲突且用户坚持，以用户为准并说明）

### 选择与写入规则

1. 根据内容选**最贴近**的一类，写入包内：
   - 单 Skill：根 `SKILL.md` frontmatter `category`
   - 复合包：包根 `package.json` 的 `category`
2. **禁止**在未读 skill 内容时把分类设为「其他」。
3. 「其他」仅在读过内容后，确认确实不属于任何常见类型，或用户明确要求时使用。
4. 平台已有同名 skill 且原分类合理时：**优先继承**，不要无故改分类。
5. 平台原分类明显错误（如研发流程被标成「其他」），且本次包内已写正确分类时：以**包内 category** 为准覆盖。
6. CLI `--category` 只是后备：包内没有、平台也没有时才用；不要用它替代「读内容写包内」。

### 常见分类（参考，不限于此）

| 分类 | 典型内容 |
| --- | --- |
| **研发** | 需求/方案/实施/代码评审/验证/发布检查、工程流程治理、研发文档与协作规范 |
| **工具** | CLI、脚本、效率辅助、通用 Agent 能力、操作手册类 |
| **设计** | UI/UX、视觉、交互、设计系统 |
| **数据** | 分析、指标、报表、数据工程 |
| **运维** | 部署、监控、环境、发布运维 |
| **文档** | 写作、规范、知识整理 |
| **测试** | 质量保障、用例设计、自动化测试专项 |
| **其他** | 读过内容后仍确实不属于以上类型，或用户明确指定 |

内容明显更贴切时，允许使用上表之外的准确类型词；但优先从上表选，避免同一类 skill 分类漂移。

### 示例：`using-product-development`

内容是七阶段研发治理（需求、设计、实现、文档、评审、验证、发布检查），应判定为：

```json
{ "category": "研发" }
```

而不是在包内无 category 时掉进「其他」。

### 单 Skill 包内示例

```markdown
---
name: my-skill
description: Skill说明
version: 1.0.0
category: 研发
---

# my-skill
...
```

### 复合包 package.json 示例（包根，不要根 SKILL.md）

```json
{
  "name": "my-composite-skill",
  "description": "复合Skill包说明",
  "version": "1.0.0",
  "category": "研发"
}
```

### 源目录不完整时的官方补全流程

```text
1. 创建临时文件夹
2. 将当前 skill 完整复制到临时文件夹
3. 读 skill 内容，判定分类
4. 在临时文件夹内补全 package.json / SKILL.md 的 version 与 category
5. 将临时文件夹的完整内容覆盖回技能源目录
6. 删除临时文件夹
7. skillhub check <skill-dir> --json
8. skillhub upload <skill-dir> --json
9. skillhub list --json   # 核验 slug/version_label/category
```

CLI 也提供：

```bash
skillhub check <skill-dir> --json
skillhub prepare <skill-dir> --json   # 校验通过后用完整临时包覆盖源目录并删除临时目录
```

`prepare` **不会**发明 version/category；包内仍缺 version 时会失败并提示补全流程。

## 3. 命令

### 3.1 check — 上传前自检

```bash
skillhub check ./my-skill
skillhub check ./my-composite --json
```

`check` 会提示包内是否已声明 `category`；未声明时不要直接 upload，先读内容判定并写入包内。

### 3.2 prepare — 临时目录补全后覆盖回源

```bash
skillhub prepare ./my-skill --json
```

### 3.3 upload — 上传

```bash
skillhub upload <input-path...> [options]
```

| Option | Default | Meaning |
| --- | --- | --- |
| `--category <category>` | 无 | 后备：仅当包内无 category 且平台无已有分类时生效。首选仍是**读内容写包内 category** |
| `--name <name>` | 包内/README/路径 | 复合包名称覆盖 |
| `--description <description>` | 包内/README | 复合包描述覆盖 |
| `--service-url <url>` | `http://127.0.0.1:8080` | 后端地址 |
| `--json` | off | 机器可读输出 |
| `--no-check` | check 开启 | 跳过本地完整性检查（不推荐） |

**没有** `--skill-version`。

示例：

```bash
skillhub.cmd upload ./my-skill --json
skillhub.cmd upload ./my-composite --json
npx @second196/skillhub-cli upload ./my-skill --service-url http://127.0.0.1:8080 --json
```

`--json` 成功结果包含：`ok`, `slug`, `version`, `category`, `verify`（上传后 list 核验）。`verify.ok=false` 时不得当作成功闭环。

### 3.4 list — 核验

```bash
skillhub list
skillhub list --category 研发
skillhub list --json
```

`list --json` 返回**数组**（不是 `{ok,results}` 包装）。字段：

```json
[
  {
    "name": "my-skill",
    "slug": "my-skill",
    "category": "研发",
    "version_label": "1.0.0",
    "status": "ACTIVE",
    "description": "...",
    "download_count": 0,
    "updated_at": "..."
  }
]
```

无数据时文本输出为：`暂无Skill`。

上传后核验（必须）：

```bash
skillhub list --json
# 过滤 slug，确认 version_label 与 category 与上传结果一致
```

### 3.5 install

```bash
skillhub install <slug> [options]
```

| Option | Default | Meaning |
| --- | --- | --- |
| `--target <directory>` | 用户级目录 | 安装到项目/自定义目录 |
| `--skill-version <semver>` | 平台最新 | 安装指定 **version_label**（如 1.0.1）。**不要用 `--version`**（那是 CLI 自身版本） |
| `--service-url <url>` | `http://127.0.0.1:8080` | 后端地址 |
| `--json` | off | JSON 输出 |

```bash
skillhub.cmd install my-skill
skillhub.cmd install my-skill --skill-version 1.0.1 --json
skillhub.cmd install my-skill --target .skills
```

## 4. 错误码与处理

| Code | 含义 | Agent 动作 |
| --- | --- | --- |
| `VERSION_SEMVER_REQUIRED` / `VERSION_REQUIRED` | 包内缺 version 或不是 SemVer | 按补全流程写包内 version；不要找 CLI 注入参数 |
| `VERSION_EXISTS` | 平台已有同 version（不可覆盖） | 用错误里的 `suggestedNextVersion` 提升**包内** version 后重传 |
| `VERSION_BUMP_REQUIRED` | 包内 version ≤ 平台最新正式版 | 同上，提升包内 version |
| `SKILL_FILE_REQUIRED` | 无根 SKILL.md 且无 `*/SKILL.md` | 补全子 Skill 结构 |

## 5. Agent 工作流

1. 需要列技能 → `skillhub list --json`
2. 需要安装 → `skillhub install <slug> [--skill-version <semver>]`
3. 需要发布本地 skill →  
   **读 skill 内容并判定 category（写入包内）** → `skillhub check <dir> --json` →（不完整则临时目录补全覆盖）→ `skillhub upload <dir> --json` → `skillhub list --json` 核验 `slug/version_label/category`
4. 用户程序解析输出时加 `--json`
5. 后端非默认地址时加 `--service-url`
6. 复合包：只补 package.json（version + category），**不要**造根 SKILL.md
7. Windows：用 `skillhub.cmd` / `npx`，不要依赖 `skillhub.ps1`
8. 分类：未读 skill 内容不得上传；禁止默认「其他」
