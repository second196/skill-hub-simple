# 需求阶段

阶段入口：`requirement`

## 1. 阶段目标

把任意文字输入先分解为 Work Item、Feature 和语义化 `requirement-*`，再整理成已确认、可验收、可追溯的 `requirement.md`，为方案阶段提供稳定输入。

需求阶段不决定技术实现，不写接口字段映射、代码结构、实施任务或测试代码。

## 2. 阶段流程

```text
确认入口和 Work Item
-> 读取需求规范和已有状态
-> 收集原始需求
-> 提取事实、需求、建议、假设和待确认项
-> 判断范围、设计和实施复杂度
-> 形成并确认 Feature 拆分
-> 头脑风暴澄清
-> 形成语义化需求清单
-> 用户确认需求口径
-> 写入 requirement.md 和控制记录
-> 自检并停止
```

## 3. 确认输入

进入后先确认：

- 需求来源、项目或版本；
- Work Item 目标、Feature 候选和有实际需求含义的稳定目录名；
- 用户、业务目标和要解决的问题；
- 需求范围和明确非目标；
- 功能流程、规则、界面交互和异常场景；
- 角色、权限、数据、依赖、兼容性、性能、安全、审计和日志要求；
- 验收条件和成功指标；
- 用户已经确认的内容与仍待确认的内容。

缺少的信息不能被 agent 补成事实。若规范允许，可标记为 `needs-confirmation`；否则先向用户提问。

## 4. 使用头脑风暴方法

读取 `references/methods/brainstorming.md`，但只执行需求阶段允许的内容：

1. 检查最小相关仓库上下文和已有 Work Item/Feature 文档；
2. 按 `references/workflow/decomposition.md` 判断范围复杂度，必要时先拆分 Feature；
3. 一次只提出一个会改变需求事实、范围或验收的关键问题；
4. 识别遗漏、边界、术语和冲突；
5. 明确区分用户事实、推断、建议和待确认项；
6. 把结果整理成待确认需求口径。

不得在需求阶段使用头脑风暴方法生成技术设计、实施计划或代码。

## 5. 形成需求文档草稿

正式 `requirement.md` 必须同时遵循仓库专项路由指向的功能需求文档规范。该规范定义固定章节、frontmatter 和“需求评审记录”的填写规则；本 Skill 只补充分解、追溯和确认要求，不另造一套互相冲突的需求文档格式。

在专项规范允许的章节中，至少包含：

```markdown
# <功能名称>

## 1. 版本修订记录
## 2. 需求来源
## 3. 功能描述
### 原子需求清单
### 验收条件、异常与边界
### 待确认事项
## 4. 非功能性需求
## 5. 需求评审记录
```

语义化需求清单每项使用稳定的 `requirement-*` 标识，例如 `requirement-skill-version-immutability`，并记录来源、行为、验收条件、边界和确认状态。标识不得使用 `REQ-*`、`REQ-001`、`requirement-001` 或其他只有编号的名称。

## 6. 用户确认与写入

正式写入前必须向用户展示：

- 需求目标、范围和非目标；
- 原子需求和验收条件；
- 依赖、异常和待确认项；
- Feature 路径和准备修改的文件。

用户至少确认 Feature 拆分、需求口径和写入意愿。这个回复是“用户确认”，必须写入 Work Item/Feature 控制状态的确认人、时间、范围和版本；它不等同于需求文档中的“人工评审记录”。未确认前只能输出分解草稿，不得写入正式 `requirement.md`。

确认后只允许写入：

```text
docs/product-development/features/feature-<featureId>/index.md
docs/product-development/features/feature-<featureId>/requirement.md
.product-development/features/feature-<featureId>/state.md
.product-development/features/feature-<featureId>/change-log.md
```

同时初始化或更新总需求和 Feature 控制目录中的 `state.md` 的拆分状态、需求状态、版本、决策、未决问题、依赖和范围，并初始化 `change-log.md`。不得写 `design.md` 或 `implementation-plan.md`。

## 7. 出口检查

写入后检查：

1. 每个功能点、流程、规则、异常和边界都归属于有语义的 `requirement-*`；
2. 每个需求都有验收条件或明确待确认状态；
3. 非目标、依赖和非功能需求没有被遗漏；
4. 没有把技术方案或实现细节写成需求事实；
5. 控制目录中的 `state.md` 版本和状态正确；
6. diff 没有无关修改。

正式需求文档的 `需求评审记录` 只记录真实发生的人工评审。已发生人工评审时必须补充评审日期、评审人、结论和说明；没有人工评审时保留“暂无人工评审记录”。AI 的生成、检查、对话确认和自动化校验不得冒充人工评审，但应在控制状态中记录对应的确认或验证事实。

## 8. 阶段停止

需求文档完成或因缺少信息阻塞后停止。除非用户明确要求进入 `design`，不得自动设计或实现。

## 9. 需求阶段的复杂度门禁

所有输入都必须先执行需求分解预处理。以下任一情况出现时，不能直接把白话描述改写成一篇长文档：

- 一个描述中包含多个用户角色或多个主流程；
- 同时涉及两个以上模块、服务、包或数据边界；
- 存在权限、持久化、迁移、兼容、并发或外部依赖；
- 验收标准无法由单一可观察行为判断；
- 输入包含多个项目、产品能力域、独立交付物或相对独立的子系统。

此时必须先读取 `references/workflow/decomposition.md`，输出 Work Item 拆分、候选 Feature、语义化需求和待确认问题；每个 `requirement-*` 只表达一个可验证行为。只有用户确认拆分和口径后，才写正式需求文档。

即使最终保留一个 Feature，也必须记录 `scope_decision: single-feature` 及合并理由。复杂度判断不能只依据字数或标题数量。

## 10. Feature 命名门禁

正式创建 Feature 前，必须从需求目标中提炼有实际含义的目录名：

```text
feature-<对象>-<动作>
```

示例：

```text
feature-order-export
feature-user-login
feature-message-retry
feature-api-timeout-fix
```

不得使用以下名称：

```text
feature-001
feature-requirement-001
feature-task-001
feature-new-feature
feature-test
```

如果存在外部任务号，应与需求含义组合，例如 `feature-order-export-WEBCAPP-10234`。如果当前描述不足以提炼对象和动作，先向用户询问，不创建目录，也不使用 `REQ-*`、纯数字或无意义临时占位。
