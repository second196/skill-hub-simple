---
title: Skill 资产与发布治理方案
description: Skill 资产登记、不可变版本、授权发布、发布门禁、审计和数据保留的技术设计
audience:
  - product-development
owner: product-development
status: draft
lastReviewed: 2026-09-01
sourceType: manual
---

# 方案：Skill 资产与发布治理

> 状态：无效草稿。设计阶段基线检查未通过；在架构基线和实施标准确认前，本文件不得作为正式设计依据。

## 0. 基线阻塞说明

本仓库当前缺少设计阶段要求的架构和实施标准基线。本文件此前形成的方案内容仅保留为讨论草稿，不能据此进入实现、评审、验证或发布检查。

## 1. 目标与非目标

### 1.1 目标

为公司内部 Skill 建立一个可追溯的治理控制面，形成以下闭环：

```text
导入/注册 -> 完整性检查 -> 不可变版本 -> 候选门禁
-> 审核/自动决策 -> 公司/项目/环境发布绑定 -> 下线/撤回
```

平台必须能够回答：某个 Skill 从哪里来、当前有哪些版本、哪个版本在哪个授权范围生效、发布依据是什么、谁做了什么操作，以及策略何时生效。

### 1.2 非目标

- 不实现 Agent runtime 执行、Skill 安装或安装失败回退。
- 不实现运行时 Tracker、Agent Trace 或生产调用采集。
- 不实现评测 Runner、判定器或 Skill 内容自动生成。
- 不把 iflytek SkillHub 直接作为目标产品的完整后端，也不在本 Feature 引入 SaaS 多租户。
- 不规定当前仓库不存在的具体数据库、云厂商、部署平台或编程语言实现。

## 2. 需求依据与版本

- 需求基线：`requirement.md` v1。
- Work Item 分解：`docs/product-development/work-items/work-skill-hub-platform/decomposition.md`。
- 调研依据：`docs/research/skill-hub-research.md` 第 3.1、4.3、5.1、6.2、6.4、7.3、8.1 节。
- 设计版本：v1。
- 计划版本：v1。

已确认的产品决策：首期为公司私有化部署；首期运行时为 Codex CLI、VS Code/Cursor/Windsurf 扩展和 Claude Code OTLP，运行时矩阵可配置；发布、灰度、回退和保留策略可按公司、项目、环境配置并版本化审计。

## 3. 源码现状和影响范围

### 3.1 源码事实

当前仓库没有 `src`、业务服务、数据库模型、API、构建脚本或测试入口；现有内容是产品调研、需求和研发治理文档。因此本方案是 greenfield 目标设计，下面的模块和文件均为实施阶段拟创建的目标结构，不是现有实现事实。

### 3.2 影响范围

本 Feature 新增治理控制面，向其他 Feature 提供以下稳定结果：

| 消费方 | 本 Feature 提供 | 消费方负责 |
| --- | --- | --- |
| 安装与回退 | 已发布版本、发布绑定、可安装状态、撤回通知、灰度/回退策略 | 下载、安装、切换、实例停用和实际回退 |
| 运行时观测 | `skill_id`、`version_digest`、发布范围和策略上下文 | 采集调用、统计灰度调用和质量证据 |
| 评测与持续演进 | 版本、内容摘要、门禁证据关联点 | 评测执行、报告生成、Finding 和候选版本 |

跨 Feature 的核心约束是：任何运行、安装或评测关联都必须引用不可变的 `version_digest`，不能用 `latest` 补齐缺失版本。

## 4. 方案概览

采用“独立治理域 + 上游能力适配器”的架构。

```text
                    +---------------------------+
                    | Governance API / Console  |
                    +-------------+-------------+
                                  |
                    +-------------v-------------+
                    | Asset Governance Service  |
                    | registry/catalog/gate      |
                    | release/audit/retention    |
                    +--+----------+----------+---+
                       |          |          |
             +---------v--+ +-----v-----+ +--v-------------+
             | Authoritative| | Immutable | | Search/Query  |
             | metadata DB  | | artifact  | | index         |
             +--------------+ | storage   | +---------------+
                              +-----------+
                       ^             ^
                       |             |
              iflytek adapter   Evaluation/Runtime contracts
```

逻辑上的权威数据由治理域持有。iflytek SkillHub 的 Registry、版本、namespace、审核、RBAC、CLI 分发和静态扫描能力作为可替换适配器候选；适配器不得绕过本系统的版本摘要、范围绑定、策略版本和审计记录。

## 5. 模块和职责边界

| 模块 | 职责 | 不负责 |
| --- | --- | --- |
| Asset Registry | 注册/导入、来源追踪、内容清单、导入失败记录 | 生成或修改 Skill 内容 |
| Version Service | 创建内容版本、计算摘要、差异查询、生命周期状态 | 原地更新历史版本 |
| Catalog Service | 授权范围内搜索、筛选、详情和关联信息 | 越权显示资产 |
| Release Scope Service | 公司/项目/环境绑定、当前版本解析、发布范围查询 | 实际流量分配 |
| Gate Service | 收集证据、校验门禁、生成发布决策 | 执行评测和扫描本身 |
| Policy Service | 发布、灰度、回退、审批和保留策略的版本管理 | 无审计地修改生效策略 |
| Governance Service | RBAC、范围授权、审批人分离、机器主体控制 | 替代组织身份系统 |
| Audit Service | 记录关键操作和状态变化，提供还原查询 | 删除永久审计数据 |
| Retention Service | 计算策略和生效范围，向数据域提供执行契约 | 代替运行观测域删除原始事件 |

## 6. 接口、类型和数据结构

以下是领域契约，不是对不存在代码的事实描述。实施时应以契约测试固定字段语义。

### 6.1 资产和版本

```text
SkillAsset
  asset_id: stable identifier
  name, description, labels
  source_type, source_locator
  license: known | unknown | missing
  dependencies: declared list
  runtime_matrix: runtime, version constraint, support status
  content_manifest: required/optional content and read status
  owner_scope

SkillVersion
  version_id: stable identifier
  asset_id
  version_label: display version
  version_digest: immutable content identity
  source_snapshot: immutable source reference
  metadata_check: passed | failed | incomplete
  lifecycle_state
  created_by, created_at
```

`version_digest` 必须由规范化后的 Skill 制品和必要元数据计算，创建后不可变。显示版本号可以重复出现在不同资产中，但不能替代 `version_digest`。

### 6.2 发布、门禁和策略

```text
ReleaseBinding
  binding_id, asset_id, version_digest
  scope_type: company | project | environment
  scope_id
  binding_state: active | offline | emergency_revoked
  effective_at, released_at
  policy_version, decision_id

GateEvidence
  evidence_id, version_digest
  evidence_type: static_scan | evaluation | risk | review | gray_observation
  result: pass | fail | incomplete | expired
  evidence_locator, evidence_digest
  generated_at, expires_at
  producer_type: human | service

ReleasePolicyVersion
  scope_type, scope_id, version, effective_at
  required_evidence_types
  minimum_valid_cases: default 30
  auto_release_conditions
  gray_ratio: default 10%
  observation_window: default 24h
  minimum_valid_calls: default 30
  rollback_conditions
  approval_rules
```

发布决策必须保存命中的策略版本、全部门禁结果、审批信息和目标范围。证据过期、摘要不匹配、策略版本不存在或条件无法判断时，决策为阻断。

### 6.3 权限、审计和保留

```text
AuditRecord
  audit_id, actor_id, actor_type: human | service
  action, object_type, object_id
  before_state, after_state, reason
  effective_scope, policy_version, occurred_at

RetentionPolicyVersion
  policy_id, scope_type, scope_id, version, effective_at
  data_class: artifact | immutable_version | evaluation_report
            | release_decision | audit | raw_runtime_event
            | metric_aggregate
  retention: permanent | duration
  approved_by, created_at
```

默认保留值为：制品、不可变版本、评测报告、发布决策和审计永久保留；原始运行事件保留 365 天；聚合指标永久保留。原始运行事件和聚合指标由运行观测 Feature 实际存储，但必须消费本 Feature 的保留策略契约。

## 7. 数据流和状态变化

### 7.1 资产导入到候选

```text
注册请求
 -> 身份/范围授权
 -> 读取来源和必要内容
 -> 保存不可变制品与内容清单
 -> 完整性检查
 -> 创建 draft 版本
 -> 创建新内容时进入 candidate
```

来源不可访问、内容无法读取或缺少必要内容时，保存失败结果和原因，但不创建可发布候选。未知元数据保持 `unknown` 或 `missing`，不由平台猜测。

### 7.2 候选到发布

```text
candidate
 -> 收集静态扫描/评测/风险/审核证据
 -> 按生效中的 ReleasePolicyVersion 计算门禁
 -> auto_release 或 manual_approval
 -> 创建 ReleaseBinding
 -> published
```

默认自动发布只适用于低风险白名单候选，并且必须满足：无高危或严重安全问题、无权限或外部写操作变化、无新运行时兼容性变化、有效评测案例不少于 30 个、回归结果和有效评分不低于基线。其他候选必须进入人工审批。

### 7.3 生命周期状态机

```text
draft -> candidate -> published -> offline -> deprecated
                         |             |
                         +-> emergency_revoked
```

门禁失败的候选保持 `candidate`，同时记录阻断原因，不产生 `published` 绑定。普通下线默认阻止新增安装；已安装实例的停用由安装与回退 Feature 执行。紧急撤回可以从 `published` 进入 `emergency_revoked`，并向安装 Feature 发送带范围和原因的撤回事件。`offline`、`emergency_revoked` 和 `deprecated` 版本不能作为默认安装版本。

同一范围的当前绑定更新必须具有幂等键（版本摘要、范围、决策 ID）。重复请求返回原决策，不生成第二条有效绑定。

## 8. 正常流程

1. 贡献者在授权范围内提交来源定位和 Skill 内容。
2. Registry 校验来源、必要文件和内容可读性，生成制品清单和内容摘要。
3. 系统创建草稿版本；内容发生变化时创建新的候选版本，历史版本保持只读。
4. 目录建立可搜索索引，并展示完整性检查、来源、依赖、运行时矩阵和版本差异。
5. 评测与扫描服务回写带版本摘要的证据，审核人查看风险和差异。
6. Gate Service 固化当时生效的策略版本，计算自动发布或人工审批路径。
7. 发布服务在授权公司/项目/环境创建绑定，并记录当前版本、生效时间和决策。
8. 安装与回退 Feature 读取 `published` 绑定，运行观测和评测 Feature 继续回写证据。

## 9. 异常、超时、重试和部分失败

| 场景 | 处理 |
| --- | --- |
| 来源不可读 | 导入失败，返回可定位原因，不创建可发布版本 |
| 必要内容缺失 | 创建失败结果或不可发布草稿，不允许进入门禁通过状态 |
| 证据缺失/过期 | 发布阻断，列出缺失证据和有效期 |
| 扫描或评测失败 | 候选保持不可发布，保存失败结果，不伪造通过证据 |
| 策略不可用 | 默认阻断发布；不回退到隐式默认放行 |
| 审批超时 | 候选保持原状态，不自动发布 |
| 发布绑定重复请求 | 以幂等键返回原结果，不重复变更状态 |
| 单个范围发布失败 | 保留已成功范围的审计结果，失败范围不生成 active 绑定，并返回逐范围结果 |
| 紧急撤回部分失败 | 先阻断新增安装，记录逐范围停用结果；存量停用由下游补偿并持续审计 |
| 保留策略缩短失败 | 不删除数据，保留旧策略并记录失败原因 |

不提供普通用户临时删除单条数据的接口。策略执行、删除或归档失败不能改变历史制品、版本、评测报告、发布决策和审计记录。

## 10. 权限、安全、性能和兼容性

### 10.1 权限模型

建议角色为 `asset_contributor`、`reviewer`、`release_manager`、`governance_admin` 和 `auditor`，每个角色受公司/项目/环境范围约束。权限判断顺序为身份有效性、角色权限、目标范围、对象状态和审批分离约束。

申请人工发布的主体不能成为同一决策的人工审批人。自动发布的主体类型为 `service`，审计中明确记录命中规则，禁止填充人工审批人身份。

### 10.2 安全与完整性

- 制品、来源快照、门禁证据和审计对象通过摘要关联；摘要不匹配时拒绝发布。
- 权限变化、外部写操作变化、新运行时兼容性变化和高风险候选默认需要人工审核。
- 审计记录采用追加式写入和受限修改权限；永久数据不得由普通业务接口删除。
- 来源定位、依赖和日志中的敏感内容按平台统一脱敏规则处理。
- iflytek 或其他外部能力只能通过适配器写入受控契约，不能直接修改权威版本和发布状态。

### 10.3 性能和兼容性边界

当前需求和调研没有提供吞吐、延迟、数据规模或并发指标，本方案不自行补齐数值。搜索索引、异步扫描和证据写入应与权威状态解耦；发布决策只读取一致的权威版本、策略和证据快照。

运行时矩阵采用可扩展的 `runtime`、版本约束和支持状态，不把首期四类运行时写死在状态机中。新增运行时兼容性变化默认进入人工审核。

## 11. 观测、灰度、迁移和回滚

### 11.1 发布策略

策略按公司、项目、环境保存版本和生效时间，环境级配置优先于项目级，项目级优先于公司级。没有适用策略或发生范围冲突时阻断自动发布。

首期默认策略：灰度比例 10%、观察 24 小时、至少 30 次有效调用；错误率高于基线、评分低于基线或出现高危问题时触发回退。具体阈值可配置；未配置阈值时不允许自动放行。

本 Feature 保存策略和 `ReleaseBinding`，不直接切换线上流量。安装回退 Feature 执行版本切换和回退，运行观测 Feature 提供有效调用、错误率和评分证据。

### 11.2 迁移

当前没有既有业务数据和代码迁移任务。首次部署应以空状态创建策略、角色和审计根配置；外部 Registry 导入必须保留原来源定位和原始内容摘要，并通过新版本进入本系统。

### 11.3 回滚

- 策略配置错误：停止新发布，恢复上一个已审核的策略版本；不得直接修改历史版本。
- 发布门禁错误：撤销尚未生效的 binding；已生效版本通过紧急撤回或下游回退流程处理。
- 外部适配器异常：切换到独立实现或停止发布，不绕过门禁。
- 数据保留任务异常：暂停删除/归档，恢复旧策略，历史永久数据保持不变。

## 12. 备选方案与取舍

### 12.1 直接 fork iflytek SkillHub

优点是 Registry、namespace、审核、RBAC、Scanner 和 CLI 能力可较快获得。缺点是目标产品需要的运行证据、策略版本、公司/项目/环境范围和跨 Feature 契约会侵入上游模型，后续升级和差异维护风险高。因此不采用直接 fork 作为总体架构。

### 12.2 多个开源项目拼接为统一后端

可以分别复用 Registry、评测和 Tracker，但权限、版本、证据和审计的一致性责任会分散，跨服务失败补偿复杂。仅保留模块级复用和领域参考，不把多个项目拼成不可替换的核心后端。

## 13. 可行性证据

- 调研报告确认 iflytek SkillHub 已具备注册、版本、namespace、审核、RBAC、CLI 分发和静态扫描能力，可作为适配器候选。
- 调研报告确认 skill-up 可产生固定评测条件和结构化报告，适合作为 Gate Service 的证据生产方，而非资产后端。
- 调研报告确认运行观测和评测必须通过不可变 Skill 版本关联，支持本方案把 `version_digest` 作为跨 Feature 稳定键。
- 当前仓库没有可运行实现，因此尚未验证适配器扩展点、数据库约束、存储不可变性和真实 API 兼容性；这些列入实施阶段的 PoC 和契约验证。

## 14. 可测试性与验证策略

| 层级 | 重点场景 | 预期证据 |
| --- | --- | --- |
| 单元测试 | 状态转移、摘要不可变、范围优先级、门禁判定、策略默认值 | 规则结果和阻断原因稳定 |
| 集成测试 | 导入到版本、证据到发布绑定、单范围/多范围发布 | 版本、证据、策略和审计可关联 |
| 契约测试 | 安装、运行观测、评测 Feature 的版本和撤回契约 | 缺失关联显式返回，禁止使用 latest 补齐 |
| 安全测试 | 越权、审批人分离、机器主体、普通用户删除 | 操作被拒且审计完整 |
| 迁移/恢复演练 | 策略恢复、适配器故障、部分范围失败、撤回补偿 | 不产生错误 active binding，历史数据不丢失 |
| 静态检查 | 文档结构、语义需求映射、路径和版本一致性 | 设计和计划无未定义需求或任务 |

当前没有可执行构建或测试命令；实施首个 Slice 时必须先建立工程基线和真实测试入口，再将验证命令写入验证记录。不能把本设计中的静态分析当作代码验证通过。

## 15. 需求覆盖矩阵

| requirement-<semantic-name> | 方案响应 | 计划任务 | 验证方式 | 状态 |
| --- | --- | --- | --- | --- |
| `requirement-skill-asset-registration` | Registry 校验来源、制品和导入失败原因 | Task 1 | 导入集成测试、不可读/缺失内容测试 | covered |
| `requirement-skill-metadata-completeness` | 内容清单、元数据检查和 unknown/missing 显示 | Task 1 | 元数据单元与详情集成测试 | covered |
| `requirement-skill-catalog-search` | 授权范围搜索、筛选、版本和关联详情 | Task 2 | 目录查询集成与越权测试 | covered |
| `requirement-skill-version-immutability` | 摘要、来源快照和只读版本，差异查询 | Task 1、Task 2 | 摘要篡改、版本差异测试 | covered |
| `requirement-skill-lifecycle-state` | 状态机、不可分发过滤和状态审计 | Task 1、Task 2 | 状态转移和默认版本测试 | covered |
| `requirement-skill-release-scope` | 公司/项目/环境 binding 和范围优先级 | Task 2 | 多范围发布与当前版本查询测试 | covered |
| `requirement-skill-release-gate` | 扫描/评测/审核/风险/灰度证据和策略决策 | Task 3、Task 6 | 门禁、策略、部分失败和契约测试 | covered |
| `requirement-skill-governance-audit` | 范围 RBAC、审批人分离、机器主体、追加审计 | Task 4 | 安全集成测试和审计还原测试 | covered |
| `requirement-skill-data-retention` | 范围化、版本化、定时生效的分层保留策略 | Task 5 | 策略单元、权限和删除拒绝测试 | covered |

## 16. 风险、待确认事项和不覆盖项

### 16.1 风险

- iflytek 的真实扩展点、数据模型和许可证/供应链边界尚未在本仓库验证；适配器 PoC 失败时需保留独立实现路径。
- 错误率和评分的组织级数值阈值尚未给出；在配置缺失时阻断自动发布，避免隐式放行。
- 公司、项目、环境之间的组织层级和重叠授权规则尚未有现成实现；实施时必须用明确的范围解析规则和冲突测试固定。
- 永久保留和原始事件 365 天会影响存储成本；容量、加密、归档和删除执行责任需由各数据域补充。
- 审计不可篡改的具体存储机制尚未确定，实施时必须完成恢复和篡改检测验证。

### 16.2 待确认事项

- 实施阶段选择的语言、框架、权威数据库、制品存储和搜索组件。
- 组织规定的错误率和评分回退数值阈值，以及基线计算口径。
- iflytek 模块复用的最终许可证、NOTICE、依赖和安全审查结论。

### 16.3 不覆盖项

灰度流量调度、线上调用计数、安装切换、实例停用、Tracker 采集、评测执行和 Finding/候选生成不在本 Feature 内；本方案仅提供它们所需的版本、范围、策略、证据和审计契约。
