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

## CR-022

- 日期：2026-09-03
- 用户请求：结合 `D:\program\witty-skill-insight` 的真实采集链路，重新梳理并增加 SkillHub CLI；CLI 既支持 Agent 运行数据采集和上报，也支持现有 Skill 上传到 SkillHub。
- 类型：requirement-change
- 原因：原需求明确排除了 CLI，无法覆盖目标 Agent 接入、运行数据补报和本地 Skill 包上传的完整客户端链路；本次将 CLI 定义为跨 Feature 交付渠道并恢复与服务端治理能力的契约。
- 影响需求：安装恢复新增 CLI 接入安装/诊断/恢复；运行观测新增 CLI 采集/OTLP 上报/缓冲补报/数据最小化；资产治理新增 CLI 包校验/上传/幂等/提交审核及 `telemetry:write` 作用域。
- 影响方案：资产治理 `design/plan` v6、安装恢复 `design/plan` v2、运行观测 `design/plan` v1 已由用户统一确认；后端架构 v0.5-draft、实施规范入口 v0.4-draft、数据库规范 v0.6-draft 提供统一基线。
- 影响任务：依次执行资产 Task 33-37、安装 Task 9-13、运行观测 Task 1-10；所有任务须等待三组方案统一确认。
- 代码范围：本轮无代码变更。
- 审批状态：approved；用户于 2026-09-03 确认三组方案双产物，并授权按资产 Task 33-37 -> 安装 Task 9-13 -> 运行观测 Task 1-10 顺序实施。
- 验证状态：文档结构、跨 Feature 需求映射、双产物版本、覆盖矩阵和 V12/V13/V14 顺序检查通过；容量、备份、RPO/RTO、灾备和最终适配器版本仍待确认。
- 替代关系：替代 CR-020/CR-021 中“CLI”排除项；OAuth2、统一单点登录、CLI Device Flow、S3、微服务、收藏、评分、举报、订阅和通知排除项继续有效。

## CR-026

- 日期：2026-09-03
- 用户请求：确认运行观测 Task 4 实施反馈对应的跨 Feature 设计修正。
- 类型：design-correction
- 原因：安装恢复已配置 Hook/OTLP 到本机 `127.0.0.1:43191`，但运行观测方案未定义本地接收进程、生命周期和本地鉴权，现有 VSIX 也未发送运行事件。
- 影响需求：运行事件采集、CLI 运行时采集/缓冲/数据最小化。
- 影响方案：运行观测 `design.md v1.1`/`implementation-plan.md v1.4` 和安装恢复 `design.md v2.1`/`implementation-plan.md v2.2` 已形成确认基线。
- 影响任务：运行观测 Task 4A 保持完成，新增 Task 4B/4C；安装恢复新增 Task 14-16，Task 9-13 保持完成。
- 代码范围：增加只绑定回环地址的 Collector 服务/生命周期、本地鉴权、事件化 VSIX 和跨 Feature 集成测试；Collector 仍不直接访问远程服务或数据库。
- 审批状态：approved；用户于 2026-09-03 确认 CR-026 双 Feature 设计和实施计划增量。
- 验证状态：通过；运行观测 12/12、安装恢复 9/9 个需求均出现在各自设计和计划中，五个新增任务字段完整，版本、路由/安全/回滚契约及跨 Feature 顺序一致，`git diff --check` 无空白错误；CR-026 实现测试未运行。
- 替代关系：增量修正 CR-022，不改变已确认的数据存储、认证和脱敏基线。
