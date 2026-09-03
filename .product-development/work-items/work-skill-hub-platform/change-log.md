# Work Item 变更记录

## CR-001

- 日期：2026-09-01
- 用户原话：结合调研报告中的内容取出详细的需求
- 类型：scope-change
- 原因：将调研报告整理为可独立验收的 Work Item、Feature 和语义化需求映射。
- 影响需求：全部候选 `requirement-*`
- 影响方案：none
- 影响任务：none
- 代码范围：none
- 审批状态：pending
- 验证状态：not-run
- 替代关系：none

## CR-002

- 日期：2026-09-01
- 用户原话：确认这个拆分
- 类型：requirement-clarification
- 原因：确认四个 Feature 的拆分及依赖关系。
- 影响需求：全部候选 `requirement-*` 的归属边界
- 影响方案：none
- 影响任务：none
- 代码范围：none
- 审批状态：approved
- 验证状态：not-run
- 替代关系：none

## CR-003

- 日期：2026-09-01
- 用户原话：首期运行时范围、发布/灰度/回退参数、数据保留规则采用业界中最佳的数据，或者采用默认的数据，这些都需要可以配置，后期进行调整。然后创建真实的需求文档
- 类型：requirement-clarification
- 原因：确认首期运行时、发布策略和数据保留采用可配置默认值，并授权创建四个 Feature 的正式需求文档。
- 影响需求：全部候选 `requirement-*`
- 影响方案：none
- 影响任务：none
- 代码范围：none
- 审批状态：approved
- 验证状态：not-run
- 替代关系：none

## CR-004

- 日期：2026-09-02
- 用户原话：确认 `feature-skill-installation-recovery` 的 `requirement.md v1` 作为设计输入，并按“后端编排、运行时适配器实际安装、Vue 控制台展示”的边界继续生成正式 `design.md` 和 `implementation-plan.md`
- 类型：design-start
- 原因：安装与回退 Feature 的需求基线和控制面/运行时适配器责任边界已确认，形成设计双产物。
- 影响需求：`feature-skill-installation-recovery` 的六条 `requirement-*`
- 影响方案：创建 `design.md v1-draft`
- 影响任务：创建 `implementation-plan.md v1-draft`
- 代码范围：none；design only
- 审批状态：需求输入已确认；方案和实施计划待确认
- 验证状态：文档结构、需求覆盖和 `git diff --check` 已完成
- 替代关系：none

## CR-005

- 日期：2026-09-02
- 用户原话：确认 `feature-skill-installation-recovery` 的设计和实施计划。
- 类型：design-confirmation
- 原因：固化第二个 Feature 的设计基线，允许后续按计划进入实现阶段。
- 影响需求：`feature-skill-installation-recovery` 的六条 `requirement-*`
- 影响方案：`design.md v1` confirmed
- 影响任务：`implementation-plan.md v1` confirmed
- 代码范围：none；implementation not started
- 审批状态：approved
- 验证状态：文档结构、需求覆盖和 `git diff --check` 已通过
- 替代关系：确认 CR-004 设计交接

## CR-006

- 日期：2026-09-02
- 用户原话：开始实施。
- 类型：implementation-start
- 原因：按已确认的安装与回退 Feature 设计和实施计划开始实现。
- 影响需求：`feature-skill-installation-recovery` 的六条 `requirement-*`
- 影响方案：none
- 影响任务：Task 1 至 Task 8，按顺序执行
- 代码范围：`backend/`、`frontend/` 和安装 Feature 测试
- 审批状态：用户明确授权
- 验证状态：Task 1 进行中
- 替代关系：none

## CR-007

- 日期：2026-09-02
- 用户原话：确认这个增量需求、设计和实施流程；界面需要与我给你的项目一样，额外功能不需要一样，但主题色是需要与公司一致。
- 类型：scope-change
- 原因：在不改变四个 Feature 拆分和跨 Feature 依赖的前提下，扩展 `feature-skill-asset-release-governance` 对参考项目的资产发现、版本内容、审核治理和管理员控制台对齐范围，并增加品牌化界面约束。
- 影响需求：新增 CR-015 语义化需求，仍归属 `feature-skill-asset-release-governance`
- 影响方案：该 Feature `design.md` v3 增量
- 影响任务：该 Feature `implementation-plan.md` v3 的 Task 11-17
- 代码范围：资产治理后端、PostgreSQL V10、Vue 路由/页面/样式；不改变安装、观测和评测 Feature 的职责
- 审批状态：approved
- 验证状态：not-run
- 替代关系：none
