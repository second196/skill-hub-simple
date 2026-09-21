---
name: skillhub-cli-operation-guide
description: Use when an AI coding agent needs to operate SkillHub CLI — check/upload/list/install skills on Windows or other platforms. Version and category must live inside the skill package; CLI never injects version. Temp-dir metadata MUST be written back to the skill source before upload; temp-only success is forbidden. Infer category from skill content; never default to 其他 without reading. Prefer skillhub.cmd / npx on Windows.
---

# SkillHub CLI 操作手册（Agent）

## 0. 一次成功检查清单（先读这段）

1. **Windows 入口**：优先 `skillhub.cmd` 或 `npx @second196/skillhub-cli`。**不要**先试 `skillhub.ps1`。用 `Get-Command skillhub` 确认命令位置。
2. **version 必须在 Skill 包内**，CLI **没有** `--skill-version` 上传参数，也**不会**注入版本。
3. **分类必须先读 skill 内容再判定**，并写入包内。禁止未读内容就默认「其他」。
4. **临时目录只中转，必须 write-back**：在 temp 写入 `package.json`/元数据后，必须覆盖回**技能源目录**，删除 temp，再对**源目录** `verify-source` + `upload`。
5. **禁止假成功**：「只在临时目录补全、用临时路径上传成功、源目录仍缺 package.json」不算发布成功。
6. 成功标准：`平台 list 核验通过` **且** `verify-source 源目录通过`。

## 1. CLI 入口

```bash
skillhub <command> [options]
skillhub-cli <command> [options]
```

默认服务地址：`http://127.0.0.1:8080`。

```bash
npm install -g @second196/skillhub-cli
npx @second196/skillhub-cli <command> [options]
```

Windows PowerShell：

```powershell
Get-Command skillhub
skillhub.cmd --help
npx @second196/skillhub-cli --help
```

## 2. 契约（必须遵守）

| 主题 | 规则 |
| --- | --- |
| 版本来源 | **只解析包内**：单 Skill = 根 `SKILL.md` frontmatter `version`；复合包 = 包根 `package.json` / `.codex-plugin/plugin.json` 的 `version` |
| CLI 注入 | **禁止**。无 `--skill-version` 上传参数 |
| 版本身份 | SemVer `version_label`；**没有 digest** |
| 同版本再传 | `VERSION_EXISTS`（不可覆盖），错误带 `suggestedNextVersion` |
| 版本未升 | `VERSION_BUMP_REQUIRED` |
| 复合包 | 不要根 `SKILL.md`；至少一个 `*/SKILL.md`；**源目录必须有**包根 package.json |
| 分类判定 | 上传前读 skill 内容，写入包内 category；禁止未读默认「其他」 |
| 分类优先级 | 包内 category > 平台已有 > CLI `--category` > `其他` |
| **write-back** | temp 补全后 **必须** `prepare --complete-from` 覆盖回源目录并删除 temp |
| **发布成功** | 平台核验 + **源目录** `verify-source` 双通过；temp-only 成功 **无效** |
| 观测 | 包内无 version 不能上传；无版本历史观测不上传 |

## 2.1 分类判定（上传前必做）

流程：**读内容 → 选类型 → 写进包内源目录 → verify-source → upload 源目录**。

### 必读材料

- 根 `SKILL.md` description / 正文职责
- 复合包子 Skill 目录与 `*/SKILL.md` name/description
- README / package.json description
- 用户明确指定的分类（冲突时以用户为准并说明）

### 规则

1. 选最贴近类型，写入**源目录**包内字段（单包 frontmatter / 复合包 package.json）。
2. 禁止未读内容设「其他」。
3. 「其他」仅在读过内容仍无法归类，或用户明确要求时使用。
4. 平台已有分类合理时继承；明显错误时以包内 category 覆盖。
5. CLI `--category` 只是后备。

### 常见分类（参考，不限于此）

| 分类 | 典型内容 |
| --- | --- |
| **研发** | 需求/设计/实施/评审/验证/发布、工程流程治理 |
| **工具** | CLI、脚本、效率辅助、操作手册类 |
| **设计** | UI/UX、视觉、交互、设计系统 |
| **数据** | 分析、指标、报表、数据工程 |
| **运维** | 部署、监控、环境、发布运维 |
| **文档** | 写作、规范、知识整理 |
| **测试** | 质量保障、用例、自动化测试 |
| **其他** | 读过内容后仍不属于以上，或用户明确指定 |

示例：`using-product-development`（七阶段研发治理）→ `category: 研发`。

### 包内示例

单 Skill frontmatter：

```markdown
---
name: my-skill
description: Skill说明
version: 1.0.0
category: 研发
---
```

复合包 `package.json`（**源目录**包根）：

```json
{
  "name": "my-composite-skill",
  "description": "复合Skill包说明",
  "version": "1.0.0",
  "category": "研发"
}
```

## 2.2 write-back 发布流程（强制）

**原则：上传只认技能源目录；temp 只能中转，不能成为发布终点。**

```text
1. 确定技能源目录 S（如 .agents/skills/<name>）
2. skillhub check S --json
3. 若 S 缺 package.json / version / category：
   a. 创建临时目录 T，完整复制 S → T
   b. 在 T 内补全 package.json / SKILL.md（version + category，由你根据 skill 内容写入）
   c. skillhub prepare S --complete-from T --json
      # 要求：T 必须完整 → 覆盖 S → 删除 T
   d. skillhub verify-source S --json   # 必须通过
4. 若源目录只读，prepare/覆盖失败：
   → **发布失败**，报告用户，禁止改用「只上传 T」冒充成功
5. skillhub upload S --source-dir S --json
   # 成功 = 平台核验通过 + verify-source S 通过
6. skillhub list --json
   # 核验 slug + version_label + category
```

### 禁止事项

- 禁止只在 T 写 `package.json`，直接 `upload T`，且不覆盖回 S
- 禁止上传成功后源目录仍无 `package.json`（复合包）
- 禁止用「POST 200 / upload ok:true」单独作为成功结论（若 `sourceVerify.ok=false`，整体失败）

## 3. 命令

### 3.1 check

```bash
skillhub check <skill-dir> --json
```

输出含：`ok`、`packageKind`、`hasRootSkillMd`、`hasPackageJson`、`sourceVerify`、`categoryHint`。

### 3.2 verify-source（write-back 门禁）

```bash
skillhub verify-source <skill-dir> --json
```

断言**磁盘上**的技能源目录元数据完整；复合包必须存在根 `package.json`（或 plugin.json）。

### 3.3 prepare

```bash
# 源已完整时规范化写回
skillhub prepare <skill-dir> --json

# 从完整临时目录覆盖回源，并删除临时目录（官方 write-back）
skillhub prepare <skill-dir> --complete-from <temp-dir> --json
```

`prepare` **不会**发明 version/category。temp 不完整则失败。

### 3.4 upload

```bash
skillhub upload <skill-dir> [--source-dir <skill-dir>] --json
```

| Option | Default | Meaning |
| --- | --- | --- |
| `--source-dir <dir>` | 同 input-path | 技能源目录；上传后必须仍通过 verify-source |
| `--category` | 无 | 后备分类 |
| `--json` | off | 机器可读 |
| `--service-url` | `http://127.0.0.1:8080` | 后端地址 |

结果字段：`platformVerify`、`sourceVerify`、`publishOk`。  
`publishOk=false` 时 **不算成功**（exit ≠ 0）。

### 3.5 list

```bash
skillhub list --json
```

数组字段：`name/slug/category/version_label/status/...`。空文本：`暂无Skill`。

### 3.6 install

```bash
skillhub install <slug> [--skill-version <semver>] [--target <dir>] --json
```

`--skill-version` 是**安装**指定 `version_label`，不是上传注入版本。

## 4. 错误码

| Code | 含义 | Agent 动作 |
| --- | --- | --- |
| `SOURCE_METADATA_INCOMPLETE` | 源/临时包元数据不完整 | 按 §2.2 write-back；禁止 temp-only 上传 |
| `VERSION_SEMVER_REQUIRED` / `VERSION_REQUIRED` | 缺包内 version | 在**源目录**写入 version 后再传 |
| `VERSION_EXISTS` | 平台已有同 version | 用 `suggestedNextVersion` 升**包内** version |
| `VERSION_BUMP_REQUIRED` | version ≤ 平台最新正式版 | 同上 |
| `SKILL_FILE_REQUIRED` | 无可用 SKILL 结构 | 补全子 Skill |

## 5. Agent 工作流（压缩版）

1. `list` / `install`：直接用 CLI  
2. `publish`：**读内容判 category** → 补全到**源目录**（temp 仅中转 + `prepare --complete-from`）→ `verify-source` → `upload --source-dir` → `list` 核验  
3. Windows：`skillhub.cmd` / `npx`  
4. 复合包：源目录 package.json 必备；不要造根 SKILL.md  
5. 成功判定：`publishOk / platformVerify / sourceVerify` 全通过  

版本：CLI `0.4.1`
