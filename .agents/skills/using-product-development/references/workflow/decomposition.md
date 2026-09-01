# 需求分解阶段子流程

本子流程属于 `requirement` 阶段，不是第八个阶段。所有来自用户输入、人工文档、AI 文档、调研材料或对话结论的文字需求，都必须先经过本子流程。

## 1. 分解目标

把一次文字输入整理为：

- 可追溯的事实、需求、建议、假设和待确认项；
- 一个或多个有实际含义的 Feature；
- 每个 Feature 内语义明确的 `requirement-*`；
- Feature 依赖关系、跨 Feature 约束和整体验收路径。

输入来源不决定复杂度。AI 生成的内容不能直接当作已确认事实，但不能因此跳过分解。

## 2. 复杂度判定

分别判断三个维度，不用单一的字数、标题数量或需求数量代替判断：

### 范围复杂度

出现以下任一信号时，必须提出候选 Feature 拆分：

- 多个独立业务目标或可独立验收的交付物；
- 多个用户角色、主流程、数据边界或生命周期；
- 不同负责人、发布节奏、失败模型或权限边界；
- 可以先交付其中一部分而不破坏整体目标；
- 同一输入中包含多个项目、产品能力域或相对独立的子系统。

### 设计复杂度

出现多个独立状态机、跨核心模块、外部依赖、迁移、兼容、并发、持久化或安全边界时，必须在设计前重新复核 Feature 边界。

### 实施复杂度

一次修改无法得到可运行结果、没有独立验证方式、失败无法定位到单个修改，或必须同时修改大量模块时，必须拆成多个 Slice；这不一定意味着要拆 Feature。

## 3. Feature 边界

只有同时满足以下条件，需求才能合并到一个 Feature：

- 共享一个主要用户目标；
- 共享同一条核心业务生命周期；
- 分开后没有独立交付价值；
- 必须共同验收、发布或回滚；
- 能在一个设计边界内说明责任和失败处理。

如果复杂度信号触发但仍决定合并，必须记录合并理由并等待用户确认。不能把“平台”“系统”“调研报告”或输入文件名直接当作 Feature。

## 4. 需求标识

每个需求使用语义化短横线标识，例如：

```text
requirement-skill-version-immutability
requirement-install-failure-fallback
requirement-agent-skill-trace-link
```

禁止使用只有编号、任务号或 `REQ-*` 形式的标识。单个 Feature 内的标识可以保持简短，但总需求映射必须以 `(featureId, requirementId)` 作为唯一键。

## 5. 分解结果

正式需求文档前必须形成并持久化分解结果：

```markdown
# <Work Item>

complexity: simple | moderate | complex
scope_decision: single-feature | multi-feature | needs-confirmation
decomposition_status: draft | confirmed | superseded | blocked | needs-confirmation

## Feature 清单
| Feature | 目标 | 范围 | 独立验收 | 依赖 |
| --- | --- | --- | --- | --- |

## 需求映射
| requirement-<semantic-name> | Feature | 来源证据 | 验收条件 | 状态 |
| --- | --- | --- | --- | --- |

## 合并或拆分理由

## 跨 Feature 约束

## 整体验收

## 用户确认
confirmed_by:
confirmed_at:
```

简单需求也必须记录 `scope_decision: single-feature` 及其理由。

## 6. 通用多 Feature 示例

例如输入文字是“从零建设一个面向客户的订阅平台”，其中包含账号访问、订阅计费和用量报表三个可独立验收的目标。应先形成一个 Work Item，再拆成类似以下 Feature，而不是生成一个名为 `feature-subscription-platform` 的大 Feature：

```text
feature-customer-access
feature-subscription-billing
feature-usage-reporting
```

总需求层记录三个 Feature 的依赖、跨 Feature 数据契约和整体验收；每个 Feature 单独拥有 `requirement.md`、`design.md` 和 `implementation-plan.md`。例如计费 Feature 内再使用 `requirement-subscription-renewal`、`requirement-payment-failure-recovery` 等语义化需求，并在设计阶段继续拆成可运行 Slice。

## 7. 写入门禁

确认前只允许写入分解草稿、总需求控制状态和待确认项，不得写入子 Feature 的正式 `requirement.md`、`design.md`、`implementation-plan.md` 或代码。

用户确认拆分、范围和需求口径后，才为每个 Feature 创建需求文档。若已有正式基线，拆分或合并必须走变更管理，不得静默重写。

## 8. 递归回退

需求、设计或实施阶段发现当前层级过大时，回到本子流程重新分解：

```text
Work Item 过大 -> 拆 Feature
Feature 过大 -> 拆 requirement-* 或 Slice
Slice 过大 -> 拆 Task
需求边界改变 -> 重新确认并升级基线
```
