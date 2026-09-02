---
title: Skill 资产与发布治理实施计划
description: Skill 资产与发布治理方案的可执行实施任务和验证计划
audience:
  - product-development
owner: product-development
status: draft
lastReviewed: 2026-09-01
sourceType: manual
---

# 实施计划：Skill 资产与发布治理

> 状态：无效草稿。设计阶段基线检查未通过；在架构基线和实施标准确认前，本文件不得作为实施依据。

## 0. 基线阻塞说明

本计划依赖的架构和实施标准文档尚未建立或确认，因此当前任务路径、技术栈和验证命令均不能作为正式实施基线。

## 1. 计划基线

- 需求版本：v1，来源为当前 Feature 的 `requirement.md`。
- 设计版本：v1，来源为当前 Feature 的 `design.md`。
- 计划版本：v1。
- 当前仓库状态：greenfield；以下 `src`、`tests`、`contracts` 路径均为拟创建路径，不代表现有文件。
- 实施前置：先确定语言、框架、权威数据库、制品存储、搜索组件和真实测试命令；这些选择不能改变本计划中的领域契约和验收行为。

## 2. 文件结构与职责

| 操作 | 路径 | 职责 |
| --- | --- | --- |
| Create | `src/asset-governance/domain/asset-models` | 资产、内容清单、来源和完整性结果 |
| Create | `src/asset-governance/domain/version-models` | 不可变版本、摘要和差异模型 |
| Create | `src/asset-governance/domain/lifecycle` | 生命周期状态和合法转移 |
| Create | `src/asset-governance/domain/release-models` | 发布绑定、门禁证据和策略模型 |
| Create | `src/asset-governance/domain/governance-models` | 角色、范围、审计和保留策略模型 |
| Create | `src/asset-governance/application/asset-service` | 注册、导入、版本创建和目录用例编排 |
| Create | `src/asset-governance/application/release-service` | 门禁计算、审批、发布绑定和撤回用例 |
| Create | `src/asset-governance/application/policy-service` | 发布和保留策略版本化、生效和查询 |
| Create | `src/asset-governance/application/authorization-service` | 范围 RBAC 和审批人分离校验 |
| Create | `src/asset-governance/infrastructure/persistence` | 权威元数据、制品、证据、审计和索引持久化适配器 |
| Create | `src/asset-governance/infrastructure/adapters/iflytek` | iflytek Registry/Scanner/RBAC 能力适配边界 |
| Create | `contracts/asset-governance` | 安装、观测、评测 Feature 的跨域契约 |
| Create | `tests/asset-governance` | 单元、集成、安全、契约和恢复测试 |

上述路径在实现阶段可按已选技术栈增加扩展名或拆分文件，但不得改变职责、字段语义和 Slice 验收范围。

## 3. 任务

### Task 1：建立资产、元数据和版本领域闭环

Slice：`slice-asset-version-domain`

需求：`requirement-skill-asset-registration`、`requirement-skill-metadata-completeness`、`requirement-skill-version-immutability`、`requirement-skill-lifecycle-state`

依赖：无；但依赖实施前工程基线已建立。

文件：Create `src/asset-governance/domain/asset-models`、`src/asset-governance/domain/version-models`、`src/asset-governance/domain/lifecycle`、`src/asset-governance/application/asset-service`、`src/asset-governance/infrastructure/persistence`；Test `tests/asset-governance/asset-version-domain`。

目标：授权用户可以导入可读且完整的 Skill，得到可追溯的草稿/候选版本；内容变化产生新版本，历史版本不能原地修改；状态变化符合状态机。

实现：对来源、必要内容、内容清单、依赖和运行时矩阵执行显式检查；保存 `source_snapshot` 和 `version_digest`；用版本摘要作为写入幂等键；实现 `draft -> candidate -> published` 及下线、撤回、废弃所需的合法转移；导入失败保留失败原因但不得生成可发布版本。

测试/验证类型：unit、integration、static。

测试场景：正常导入；来源不可读；必要内容缺失；未知元数据；同一内容重复导入；已创建版本被修改；非法状态转移；下线版本被当作默认版本。

测试文件或替代证据：`tests/asset-governance/asset-version-domain`；若工程测试框架尚未建立，先用领域模块的最小测试入口验证状态、摘要和失败结果，再记录实际命令。

验证：运行工程基线确定后的领域测试命令，预期所有正常场景通过，失败和非法操作返回结构化原因；执行静态检查确认 `version_digest` 在创建后无更新路径。

停止条件：导入失败仍能生成可发布版本、历史版本内容可被覆盖、状态机允许绕过候选或测试命令无法稳定复现时停止后续 Slice。

回滚：删除本 Slice 新增的 greenfield 模块和测试入口；不删除已导入的永久制品或审计数据，数据恢复使用版本和状态迁移脚本。

### Task 2：建立目录、版本差异和发布范围闭环

Slice：`slice-catalog-release-scope`

需求：`requirement-skill-catalog-search`、`requirement-skill-version-immutability`、`requirement-skill-lifecycle-state`、`requirement-skill-release-scope`

依赖：Task 1。

文件：Modify `src/asset-governance/application/asset-service`、`src/asset-governance/infrastructure/persistence`；Create `src/asset-governance/domain/release-models`、`src/asset-governance/application/release-service`、`tests/asset-governance/catalog-release-scope`。

目标：用户只能在授权范围内搜索和查看 Skill；能够查询版本差异、发布目标、每个范围当前版本和发布时间；一个版本可绑定多个范围。

实现：建立按名称、描述、标签、来源、运行时、状态和版本的查询条件；详情结果明确区分缺失/未知元数据；实现 `company/project/environment` 范围绑定、环境优先的当前版本解析和绑定幂等；只允许 `published` 版本成为 active binding；提供版本差异查询但不改变任何历史内容。

测试/验证类型：integration、security、contract。

测试场景：多条件搜索；不同版本和状态区分；越权搜索；多范围发布；范围重叠；同一请求重试；不可分发版本绑定；查询指定版本的内容差异。

测试文件或替代证据：`tests/asset-governance/catalog-release-scope`、`contracts/asset-governance/release-binding`。

验证：运行目录和范围集成测试，预期查询结果只包含授权对象；同一范围重复发布只保留一个有效 binding；契约校验拒绝草稿、下线、撤回和废弃版本。

停止条件：范围优先级产生两个不可判定的当前版本、越权对象可查询或发布 binding 可指向不可分发状态时停止后续门禁实现。

回滚：停止目录索引更新并撤销本 Slice 新增的 active binding；保留权威版本、已成功范围的审计和可恢复的索引快照。

### Task 3：建立发布门禁和可配置发布策略

Slice：`slice-release-gate-policy`

需求：`requirement-skill-release-gate`、`requirement-skill-release-scope`

依赖：Task 1、Task 2。

文件：Modify `src/asset-governance/domain/release-models`、`src/asset-governance/application/release-service`；Create `src/asset-governance/application/policy-service`、`tests/asset-governance/release-gate-policy`、`contracts/asset-governance/gate-evidence`。

目标：发布服务能够基于完整证据和生效策略生成可解释的自动发布、人工审批或阻断决策；策略和决策可以追溯到公司/项目/环境范围。

实现：定义静态扫描、受控评测、风险、人工审核和灰度观察证据；校验证据关联的版本摘要、生成时间和有效期；实现默认策略：低风险白名单、无高危/严重问题、无权限或外部写操作变化、无新运行时兼容性变化、至少 30 个有效评测案例、回归结果和有效评分不低于基线；保存灰度 10%、观察 24 小时、至少 30 次调用和错误率/评分/高危回退条件；策略缺失、证据缺失或条件无法判断时阻断。

测试/验证类型：unit、integration、contract。

测试场景：完整低风险白名单自动发布；高风险进入人工审批；扫描失败；评测案例不足；评分低于基线；权限变化；外部写操作变化；新运行时兼容性变化；证据过期；策略不存在；策略版本切换；单范围部分失败。

测试文件或替代证据：`tests/asset-governance/release-gate-policy`、`contracts/asset-governance/gate-evidence`。

验证：运行门禁和策略测试，预期每个阻断结果包含缺失项、命中规则、策略版本和目标范围；自动发布只能命中完整白名单条件；实际灰度流量和回退动作由下游契约测试验证。

停止条件：门禁可通过缺失证据、策略条件不可判断时放行、或门禁决策不能还原全部证据时停止发布绑定实现。

回滚：将新策略标记为不可生效并恢复上一个已审核版本；撤销尚未生效的 binding；不修改历史证据和历史决策。

### Task 4：建立 RBAC、审批人分离和治理审计

Slice：`slice-governance-audit`

需求：`requirement-skill-governance-audit`

依赖：Task 2、Task 3。

文件：Create `src/asset-governance/domain/governance-models`、`src/asset-governance/application/authorization-service`、`src/asset-governance/infrastructure/persistence/audit`、`tests/asset-governance/governance-audit`。

目标：导入、版本创建、发布、下线、紧急撤回、安装/运行数据查看、评测执行和治理配置均有明确的范围权限和审计结果；人工申请人与审批人分离。

实现：定义 `asset_contributor`、`reviewer`、`release_manager`、`governance_admin`、`auditor` 及范围授权；将权限校验放在关键用例入口；拒绝同一人工主体申请并审批；自动发布使用 `actor_type=service`；审计写入主体、时间、对象、前后状态、原因、范围和策略版本；为安装、运行观测、评测 Feature 提供授权和撤回事件契约。

测试/验证类型：integration、security、contract。

测试场景：未授权导入；跨项目发布；越权查看运行数据；同一人工主体申请/审批；机器主体冒充人工；紧急撤回；部分范围撤回；审计状态还原；重复请求。

测试文件或替代证据：`tests/asset-governance/governance-audit`、`contracts/asset-governance/governance-events`。

验证：运行安全和审计测试，预期所有越权动作被拒且产生拒绝审计；给定审计序列可以还原对象状态变化；撤回契约携带范围、原因和执行结果字段。

停止条件：关键操作存在绕过授权入口、机器主体可伪造人工审批、或审计缺少前后状态/范围时停止后续发布验收。

回滚：禁用新增角色映射和未生效授权；保留已写入审计记录；恢复上一个权限策略版本。

### Task 5：建立分层数据保留策略

Slice：`slice-retention-policy`

需求：`requirement-skill-data-retention`、`requirement-skill-governance-audit`

依赖：Task 4。

文件：Modify `src/asset-governance/domain/governance-models`、`src/asset-governance/application/policy-service`；Create `src/asset-governance/application/retention-service`、`contracts/asset-governance/retention-policy`、`tests/asset-governance/retention-policy`。

目标：治理管理员可以按公司/项目/环境查看、发布和调整保留策略；策略有版本、生效时间和审计；普通用户不能直接删除单条数据。

实现：建立数据类型、范围、保留期、审批人和生效时间模型；写入默认策略：制品/不可变版本/评测报告/发布决策/审计永久，原始运行事件 365 天，聚合指标永久；范围级策略按环境、项目、公司优先级解析；缩短保留期必须经过治理权限和人工审批；向运行观测和评测数据域提供查询/执行契约；删除接口只允许策略执行器调用。

测试/验证类型：unit、integration、security、contract。

测试场景：默认策略；新策略未来生效；范围覆盖；缩短保留期；策略冲突；无权限调整；普通用户删除；删除任务失败；运行观测域读取策略。

测试文件或替代证据：`tests/asset-governance/retention-policy`、`contracts/asset-governance/retention-policy`。

验证：运行策略和权限测试，预期新策略的版本、范围和生效时间可查询；无审计的删除全部失败；永久数据没有可用的普通删除路径。

停止条件：策略调整可绕过审计、范围解析不确定或普通业务请求可以删除单条数据时停止数据域接入。

回滚：恢复上一个已审核的保留策略版本并暂停删除/归档执行；不得删除历史策略或永久数据。

### Task 6：完成跨 Feature 契约和最小端到端验收

Slice：`slice-governance-integration-contract`

需求：`requirement-skill-release-gate`、`requirement-skill-governance-audit`、`requirement-skill-lifecycle-state`、`requirement-skill-release-scope`

依赖：Task 1 至 Task 5。

文件：Modify `contracts/asset-governance`；Create `tests/asset-governance/end-to-end-governance`、`src/asset-governance/infrastructure/adapters/iflytek`。

目标：从导入、版本创建、证据门禁、范围发布到撤回的最小链路可运行；安装、运行观测和评测 Feature 可以只依靠稳定契约获取版本和治理结果。

实现：固定版本摘要、发布 binding、门禁证据、撤回事件、灰度观察结果和保留策略的契约；适配器只实现外部 Registry/Scanner 的读写映射，所有写入经过权威治理服务；构造一条包含正常发布、门禁阻断、部分范围失败和紧急撤回的端到端测试链路。

测试/验证类型：contract、integration、e2e、manual。

测试场景：正常发布并被下游查询；缺少版本关联；使用 latest 补齐被拒；扫描失败阻断；灰度证据回写；高危撤回通知；多范围部分失败；策略回滚。

测试文件或替代证据：`tests/asset-governance/end-to-end-governance`；iflytek 适配器需要外部实例时，使用固定 mock 契约和人工 PoC 记录，不能把 mock 结果宣称为上游兼容性已验证。

验证：运行全部 Feature 契约和本 Feature 端到端测试；预期每条结果均能定位到不可变版本、范围、策略、证据和审计记录；执行 `git diff --check` 检查文档与契约变更格式。

停止条件：跨 Feature 字段语义不一致、撤回无法携带影响范围、或端到端结果无法还原发布决策时停止进入代码评审和发布检查。

回滚：停用适配器和新契约版本，恢复上一个契约版本；撤销未生效绑定；保留历史版本、证据、决策和审计数据。

## 4. 任务顺序与质量门

执行顺序为 Task 1 -> Task 2 -> Task 3 -> Task 4 -> Task 5 -> Task 6。每个 Slice 必须先有失败测试或最小验证条件，再实现最小行为并运行定向验证；失败时按任务停止条件停留在当前 Slice，不跨任务掩盖问题。

进入代码评审前必须满足：

- 九条语义需求都至少有实现任务和验证项。
- 所有发布、撤回和保留策略均有版本、生效时间、范围和审计。
- 所有下游关联均使用不可变版本摘要，缺失关联不会绑定 latest。
- 所有门禁失败、策略缺失、权限拒绝和部分范围失败都有结构化结果。
- 绿色工程基线、真实构建命令和测试入口已建立并记录。
