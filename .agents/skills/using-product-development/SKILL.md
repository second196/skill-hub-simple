---
name: using-product-development
description: 面向复杂仓库研发任务的完整七阶段研发治理流程。用于需求分解、需求澄清、方案设计、实施计划、功能实现、缺陷修复、代码与文档评审、验证、发布检查和研发文档维护；通过持久化 Work Item/Feature 状态、变更基线、逐 Slice 验证和隔离评审支持长上下文与跨会话执行。
---

# 研发流程治理

本 Skill 是研发任务的流程控制器。它保留需求、方案、实现、评审、验证、发布检查、文档七个阶段，并内置需求分解、头脑风暴、计划编写、计划执行和子代理开发方法。

研发对象分为五层：

```text
Work Item（总需求）
  -> Feature（独立交付单元）
      -> requirement-*（一个可验证行为）
          -> Slice（可运行的最小纵向切片）
              -> Task（一次可执行、可验证的修改）
```

`requirement-*` 必须使用能表达实际需求含义的短横线名称，例如
`requirement-skill-version-immutability`、`requirement-install-failure-fallback`。
禁止使用 `REQ-*`、`REQ-001`、`requirement-001` 或其他只有编号的需求标识。

## 1. 每轮启动

收到用户消息后，按顺序执行：

1. 查找并读取适用的 `AGENTS.md`；不存在时说明没有仓库专项路由。
2. 读取 `references/collaboration/rules.md` 和 `references/collaboration/transparency.md`。
3. 判断是否属于产品研发任务；不是则按普通任务处理，不套七阶段产物。
4. 研发任务读取 `references/collaboration/stage-entry.md`，识别唯一阶段。
5. 尚未确定 Work Item 或 Feature 时，先在 `requirement` 阶段执行 `references/workflow/decomposition.md`；不得因输入来自单个文件、调研材料或用户指定标题而默认只有一个 Feature。
6. 已指定 Feature 时，读取 `references/shared/feature-state.md`、`references/shared/artifact-locations.md`，再读取正式产物目录和 `.product-development` 控制目录中的 `state.md`；不得依赖旧聊天记忆恢复状态。
7. 读取当前阶段文件以及该阶段明确要求的方法文件，不读取无关阶段。
8. 如果发现 `state.md` 不存在或与当前工作区不一致，先进入恢复/校正动作，不直接继续执行。

每轮开始时必须先向用户报告：当前阶段、Work Item、Feature、已读取的规则、允许修改范围和本轮出口证据。没有这些信息时，不得开始写文件或改代码。

## 2. 七阶段入口

| 阶段 | 用户意图 | 必读文件 |
| --- | --- | --- |
| `requirement` | 分解、整理、澄清、确认需求 | `references/workflow/requirement.md`、`references/workflow/decomposition.md` |
| `design` | 技术方案、影响面、实施计划 | `references/workflow/design.md` |
| `implementation` | 实现、修复、重构、补测试 | `references/workflow/implementation.md` |
| `review` | 只读评审需求、方案、计划、代码或证据 | `references/workflow/review.md` |
| `verification` | 测试、构建、验证矩阵、回归检查 | `references/workflow/verification.md` |
| `release-check` | 发布风险、灰度、监控、回滚检查 | `references/workflow/release-check.md` |
| `documentation` | 维护已确认文档、入口和交叉链接 | `references/workflow/documentation.md` |

一次只进入一个阶段。阶段内必要的澄清、计划、自检和定向验证不是新阶段。

## 3. 内置方法

以下能力已完整内置，本流程不得再调用同名外部 Skill：

- `references/methods/brainstorming.md`：需求澄清和方案探索。
- `references/methods/writing-plans.md`：可执行实施计划编写。
- `references/methods/executing-plans.md`：计划审查、逐任务实现和检查点。
- `references/methods/subagent-development.md`：隔离任务包、实现者和双阶段评审。

阶段文件决定何时使用方法、使用哪些步骤以及何时停止。方法不得改变阶段、文档落位、写入权限和人工确认门禁。

四个方法不是名称占位，而是本 Skill 的强制执行协议：

1. 所有文字输入先按 `decomposition.md` 提取事实、需求、建议、假设、来源和待确认项；输入来源不决定是否复杂。
2. 需求有歧义或方案有多个路径时，按 `brainstorming.md` 逐问澄清，区分事实、假设、建议和待确认项。
3. 进入方案阶段时，按 `writing-plans.md` 和 `test-feedback.md` 先核对源码事实与可测试性，再把每个 `requirement-*` 拆为可独立验证的 Slice/Task；每个任务必须有文件、符号、逻辑、命令、预期结果和回滚方式。
4. 进入实现阶段时，按 `executing-plans.md` 和 `test-feedback.md` 逐 Slice、逐任务执行；每个任务都要经历必要的失败复现、最小修改、定向验证、失败归因、规范检查和质量检查。
5. 只有用户授权多 Agent 时，才按 `subagent-development.md` 派发隔离任务；实现者完成后必须依次通过规范符合性评审和代码质量评审。

## 4. Feature 闭环

每个研发请求都必须先确定一个 Work Item。Work Item 可以只有一个 Feature，也可以包含多个 Feature。完整契约见：

- `references/shared/work-item-contract.md`
- `references/shared/test-feedback.md`
- `references/workflow/decomposition.md`

Work Item 总览和分解产物使用独立目录：

```text
docs/product-development/work-items/work-<workItemId>/
  index.md
  decomposition.md

.product-development/work-items/work-<workItemId>/
  state.md
  change-log.md
```

总览层维护 Feature 清单、依赖、跨 Feature 契约、语义化需求映射和整体验收；子 Feature 目录只维护本 Feature 的需求、设计、计划、验证和发布产物。

正式 Feature 产物目录：

```text
docs/product-development/features/feature-<featureId>/
  index.md
  requirement.md
  design.md
  implementation-plan.md
  verification.md
  release-check.md
```

流程控制目录：

```text
.product-development/
  features/
    feature-<featureId>/
      state.md
      change-log.md
```

`featureId` 必须表达实际需求对象和动作，使用稳定的短横线格式，例如 `feature-order-export`、`feature-user-login`、`feature-message-retry`。不得使用 `feature-001`、`feature-requirement-001`、`feature-task-001`、`feature-new-feature` 或其他无法说明需求含义的名称。无法从用户描述中提炼出有意义的名称时，先询问用户，不得自行创建无意义目录。

`state.md` 保存当前阶段、版本、决策、源码事实、任务进度和验证状态；`change-log.md` 只记录影响正式基线的变更，不记录每个普通任务的执行过程。格式见：

- `references/shared/feature-state.md`
- `references/shared/change-management.md`
- `references/shared/artifact-contracts.md`
- `references/shared/artifact-locations.md`
- `references/shared/specialized-route-loading.md`
- `references/shared/test-feedback.md`

新建 Feature 时，先按 `references/shared/artifact-locations.md` 同时确认正式文档目录和流程控制目录，再创建最小状态包。不得因为目录不存在就凭经验创建另一套目录。

上下文压缩、跨会话、子代理接手或任务恢复时，必须从这些持久化产物恢复，不得从聊天摘要猜测。

## 5. 方案双产物硬规则

Feature 的 `design` 阶段默认把以下文件作为一个原子产物集合：

```text
design.md
implementation-plan.md
```

多 Feature Work Item 还必须在总需求层维护 Feature 依赖、跨 Feature 契约和整体验收；每个 Feature 独立生成自己的方案双产物。

用户说“写设计文档”“写方案”“进入 design 阶段”均默认包含两个文件。只有用户明确说“只写方案，不创建或更新实施计划”时，才允许省略 `implementation-plan.md`。

## 6. 每轮执行声明

修改前先输出：

```text
当前阶段：<phase>
当前 Work Item：<work item path 或无 Work Item>
当前 Feature：<feature path 或无 Feature>
本轮读取：<规则、状态、阶段产物和源码范围>
允许修改：<精确文件或模块>
完成证据：<阶段出口检查>
```

阶段开始、每个任务完成/阻塞、用户确认取舍、验证结果变化以及准备切换会话前，都要更新控制目录中的 `state.md`。记录结果、决策、未决事项、实际修改、验证、当前任务和下一停止点。只有影响正式需求、方案或计划基线的变更，才追加控制目录中的 `change-log.md`。

### 上下文压缩或恢复时

当会话变长、收到压缩后的上下文、重新打开任务或交给子代理时，执行固定恢复协议：

1. 重新读取 `AGENTS.md`、协作规则、控制目录中的 `state.md`、当前阶段产物、活动变更条目和当前 diff；
2. 只按 `state.md` 中的当前任务读取相关源码符号，不重新扫描整个仓库；
3. 用三句话复述当前阶段、已确认决策、未决问题和允许范围；
4. 对照恢复前的最后一个验证结果；无法确认时停止并标记 `NEEDS_CONTEXT`，不得凭记忆继续。

`state.md` 必须保持短小，保存索引、事实结论和证据路径，不粘贴大段源码、聊天记录或命令输出。

## 7. 停止条件

遇到以下任一情况立即停止：

1. 当前阶段出口条件已满足。
2. 用户没有要求进入下一阶段。
3. 需要需求取舍、公共 API 变更、兼容性风险、跨模块扩大范围、多 Agent、合入或发布确认。
4. 计划与源码事实冲突，或验证失败、反复失败、无法执行。
5. 缺少会改变结果的关键信息。

不得用“基本完成”“大体完成”掩盖阻塞或未验证项。
