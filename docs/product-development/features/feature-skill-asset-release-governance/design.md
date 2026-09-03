---
title: Skill 资产与发布治理设计
description: Skill 资产登记、不可变版本、授权发布、门禁、审计和数据保留的详细技术设计
audience:
  - product-development
owner: product-development
status: active
lastReviewed: 2026-09-02
sourceType: manual
---

# 方案：Skill 资产与发布治理

> 设计版本：v4（包含已确认的 CR-015、CR-016 增量）。CR-017 API Token 增量将当前实施设计提升为 v5；方案、风险和实施计划已由用户确认，可作为后续 implementation 的设计依据。

## 1. 目标与非目标

### 1.1 目标

为公司内部 Skill 建立可追溯的治理控制面，完成：

```text
注册/导入 -> 完整性检查 -> 不可变版本 -> 证据门禁
-> 审核/发布决策 -> 公司/项目/环境绑定 -> 灰度观察/下线/撤回
```

系统必须能回答：资产从哪里来、某个版本的内容摘要是什么、是否完整、在哪些授权范围生效、发布依据和策略版本是什么、谁执行了操作，以及失败发生在哪个阶段。

### 1.2 非目标

- 不实现 Agent runtime 执行、Skill 安装、安装失败回退或实际流量调度。
- 不实现 Tracker、原始运行事件摄入、Trace/指标计算或评测 Runner。
- 不实现静态扫描器、评测引擎、Finding 生成或 Skill 内容自动生成；本 Feature 只接收并校验证据。
- 不建设 SaaS 多租户，不替代公司统一身份、日志、APM 或对象存储平台。
- 不使用 OAuth2、统一单点登录、CLI Device Flow 或 API Token；首期只支持账户密码登录和服务端 Session。

## 2. 需求依据与版本

| 依据 | 版本/范围 | 用途 |
| --- | --- | --- |
| `docs/product-development/features/feature-skill-asset-release-governance/requirement.md` | v4 | 本 Feature 的原始需求、CR-015 对齐增量和 CR-017 API Token 增量 |
| `docs/product-development/work-items/work-skill-hub-platform/decomposition.md` | confirmed | Feature 边界、跨 Feature 契约和需求映射 |
| `docs/research/skill-hub-research.md` | 3.1、4.3、5.1、6.2、6.4、7.3、8.1 | Registry、审核、扫描、发布和保留的调研事实 |
| `.product-development/features/feature-skill-asset-release-governance/state.md` | CR-008 后 | 当前阶段、确认决策和 greenfield 事实 |

当前实施设计版本为 v5，实施计划版本为 v5。跨 Feature 关联统一使用不可变 `version_digest`；缺失关联必须显式标记，禁止用 `latest` 补齐。

## 3. 架构和实施基线

### 3.1 已确认基线

| 类型 | 文档 | 版本/状态 | 本方案约束 |
| --- | --- | --- | --- |
| 后端架构 | `docs/product-development/architecture/backend-architecture.md` | v0.4-draft，用户已确认 | JDK 8、Spring Boot 2.7.18 模块化单体、Spring MVC 5.3.31、Spring Security 5.7.11、MyBatis-Plus 3.5.5、分层目录 |
| 前端架构 | `docs/product-development/architecture/frontend-architecture.md` | v0.3-draft，用户已确认 | Vue 3、TypeScript、Vite、Vue Router、Pinia、Feature 模块化 |
| 实施规范入口 | `docs/product-development/standards/implementation/index.md` | v0.3-draft，用户已确认 | 按 Feature 影响范围读取 Java、Vue 组件、TypeScript、数据库规范 |
| 数据库规范 | `docs/product-development/standards/implementation/database-design.md` | v0.5-draft，由 v0.4 补充导入留痕和灰度状态 | PostgreSQL 15、MyBatis-Plus、Flyway、追加式审计、版本化策略 |
| Java 规范 | `docs/product-development/standards/implementation/java-best-practices.md` | active，嵩山版 | Controller/Service/Mapper 分层、明确类型、异常分层、事务和命名约束 |
| Vue 组件规范 | `docs/product-development/standards/implementation/component-standard.md` | active | 单一职责、明确 Props/Emits、状态最小化、分页和可访问性 |
| TypeScript 规范 | `docs/product-development/standards/implementation/typescript-best-practices.md` | active | 精确类型、外部输入先校验、禁止无界 `any`、异步失败语义明确 |
| TypeScript 注释规范 | `docs/product-development/standards/implementation/typescript-doc-style-guide.md` | active | 公共类型和接口使用结构化文档注释 |

数据库规范从 v0.4-draft 补充为 v0.5-draft，是本 Feature 设计所需的最小增量：新增 `skill_import_attempt`，并将 `PENDING_GRAY` 纳入发布决策状态。容量、对象存储、分析存储、备份、RPO/RTO 和灾备仍是待确认项，不能作为已验证事实。

### 3.2 技术边界

- PostgreSQL 15 是治理元数据和事务状态的权威来源；时间使用 `TIMESTAMP(3) WITH TIME ZONE`，结构化扩展使用 `JSONB`。
- Skill 制品、来源快照和大型报告进入不可变对象存储，数据库只保存 URI、媒体类型、大小和摘要。
- 目录搜索使用可由 PostgreSQL 重建的派生索引；搜索索引没有状态写权限。
- Redis 6.2.14 Streams 通过 Outbox 投递跨 Feature 事件；事件消费必须幂等、可重试且可观察。
- 平台内部观测使用 OpenTelemetry Java 1.32.0、Micrometer 1.10.13、SLF4J 1.7.36 和 Logback 1.2.13，不把用户 Agent 运行观测混入本 Feature。
- 前端只消费后端契约，不能在浏览器决定权限、门禁、版本不可变性或默认发布版本。

## 4. 源码现状和影响范围

当前仓库没有业务 `src`、构建文件、API、数据库迁移、对象存储适配器或测试入口。以下文件和符号均是实施阶段拟创建的 greenfield 目标，不是现有代码事实。首个实施 Slice 必须先创建工程骨架和真实测试入口。

### 4.1 影响范围

| 影响层 | 目标范围 |
| --- | --- |
| 后端 | 资产、版本、目录、发布、门禁、策略、权限、审计、保留和外部适配器 |
| 前端 | 资产目录、资产/版本详情、发布门禁、范围绑定、策略和审计控制台 |
| 数据库 | PostgreSQL 15 治理表、索引、Flyway 迁移、Outbox 和保留策略 |
| 跨 Feature | 向安装、运行观测和评测提供 `version_digest`、发布绑定、策略和撤回契约 |
| 外部能力 | iflytek Registry/Scanner/RBAC 作为适配器候选；实际接口、许可证和扩展点需 PoC 验证 |

### 4.2 跨 Feature 责任

- 安装与回退 Feature 读取 `PUBLISHED` 绑定并执行下载、安装、切换、停用和实际回退。
- 运行时观测 Feature 采集事件、计算有效调用和运行指标；本 Feature 只提供版本/范围/策略上下文和保留契约。
- 评测与持续演进 Feature 生产静态扫描、评测、回归和风险证据；本 Feature 校验证据完整性和有效期，不执行 Runner。
- 撤回事件由本 Feature 记录并通过 Outbox 发布；下游必须回报逐实例处理结果，不能把通知发送成功伪装成停用完成。

## 5. 方案概览

采用 Java 8 模块化单体治理控制面：同步请求负责权威事务和可查询结果，制品/报告写入对象存储，异步任务负责派生索引和跨 Feature 通知。

```text
Vue 3 Console / approved clients
             |
      Account + Session API
             |
   Controller -> Application Service
             |
   Domain rules / transaction boundary
       |          |          |
 PostgreSQL   Object store  Redis Streams + Outbox
       |          |          |
  governance  artifacts   install/observe/evaluation contracts
             |
     derived catalog search projection
```

核心原则：PostgreSQL 中的版本、策略、证据索引、决策、绑定和审计在同一治理域内保持一致；任何外部调用都在本地事务提交后通过 Outbox 发出，不把外部成功作为本地提交条件。

## 6. 模块和职责边界

| 模块 | 负责 | 明确不负责 |
| --- | --- | --- |
| `asset` | 资产身份、注册/导入、来源、清单、元数据完整性 | 内容生成、扫描执行 |
| `version` | 摘要、不可变版本、差异、生命周期转移 | 修改历史版本 |
| `catalog` | 授权范围内搜索、筛选、详情和关联查询 | 越权查询、权威状态写入 |
| `release` | 公司/项目/环境绑定、当前版本并发控制、下线/撤回 | 实际流量切换和安装回退 |
| `gate` | 证据接收、有效性校验、策略门禁和决策快照 | 扫描/评测执行 |
| `policy` | 发布、灰度、回退、审批、保留策略版本和生效解析 | 无审计修改历史策略 |
| `governance` | 账户、角色、范围授权、审批人分离、Session | 统一身份/单点登录 |
| `audit` | 追加式审计、前后状态和查询 | 普通删除或更新永久数据 |
| `integration` | 对象存储、搜索、iflytek 和下游契约适配 | 绕过领域 Service 直接写表 |

依赖方向固定为 `controller -> service -> mapper`；领域规则由 Service/Domain 承担，Mapper 只访问数据库，DO、DTO、VO、Query 不混用。

## 7. 后端目录和前端目录落位

### 7.1 后端目标目录

遵循 `backend-architecture.md`，包名仍使用待替换的 `com.km.skillhub` 模板：

```text
backend/src/main/java/com/km/skillhub/
├─ controller/{asset,version,catalog,release,gate,policy,governance,audit}/
├─ service/{asset,version,catalog,release,gate,policy,governance,audit}/
│  └─ impl/
├─ mapper/{asset,version,catalog,release,gate,policy,governance,audit}/
├─ model/{entity,dto,vo,query}/
├─ common/{constant,enums}/
├─ config/
├─ exception/
├─ interceptor/
├─ aspect/
├─ filter/
├─ handler/
└─ integration/{artifact,search,iflytek,downstream}/
backend/src/main/resources/
├─ mapper/
├─ db/migration/
├─ application.yml
└─ logback-spring.xml
backend/src/test/java/com/km/skillhub/
├─ service/
├─ mapper/
├─ integration/
├─ contract/
└─ support/
```

### 7.2 前端目标目录和路由

遵循 `frontend-architecture.md`，路由使用目标语义路径，具体菜单文案不在本设计中扩展：

```text
frontend/src/
├─ app/
├─ router/
├─ stores/
├─ pages/asset-governance/
│  ├─ AssetCatalogPage.vue
│  ├─ AssetDetailPage.vue
│  ├─ VersionDetailPage.vue
│  ├─ ReleaseDecisionPage.vue
│  ├─ PolicyPage.vue
│  └─ AuditPage.vue
├─ modules/asset-governance/{api,components,composables,services,types,tests}/
├─ components/
├─ api/
├─ services/
├─ types/
└─ utils/
```

| 路由 | 页面责任 | 写操作 |
| --- | --- | --- |
| `/assets` | 授权范围内目录搜索 | 注册/导入入口 |
| `/assets/:assetId` | 资产、来源、完整性和版本列表 | 下线入口按权限显示 |
| `/assets/:assetId/versions/:versionDigest` | 版本清单、差异、生命周期和关联证据 | 申请候选/撤回按权限显示 |
| `/releases/:decisionId` | 决策、策略、门禁和逐范围结果 | 发布、下线、紧急撤回按权限显示 |
| `/governance/policies` | 发布/灰度/回退/保留策略版本 | 治理管理员 |
| `/governance/audit` | 审计查询和状态还原 | 只读 |

登录路由仅提供账户密码表单；后端通过 HttpOnly、Secure、SameSite Cookie 建立 Session，前端不保存长期 Token。

## 8. 接口、类型和数据结构

### 8.1 公共 API 约束

所有写接口都接收 `X-Request-Id` 或等价幂等键，响应包含请求 ID、对象 ID、当前状态和可定位错误码。列表接口使用分页和稳定排序。错误分为参数错误、权限错误、业务阻断、外部依赖失败和系统错误，不向客户端回显密钥、凭据、完整制品或内部绝对路径。

建议的后端接口边界：

```text
POST   /api/v1/assets/imports
GET    /api/v1/assets/imports/{requestId}
GET    /api/v1/assets
GET    /api/v1/assets/{assetId}
POST   /api/v1/assets/{assetId}/versions
GET    /api/v1/assets/{assetId}/versions/{versionDigest}
GET    /api/v1/assets/{assetId}/versions/{versionDigest}/diff
POST   /api/v1/releases/decisions
GET    /api/v1/releases/decisions/{decisionId}
POST   /api/v1/releases/{decisionId}/approve
POST   /api/v1/releases/{decisionId}/rollback
POST   /api/v1/releases/{decisionId}/revoke
GET    /api/v1/governance/policies
POST   /api/v1/governance/policies
GET    /api/v1/audits
POST   /api/v1/session/login
POST   /api/v1/session/logout
GET    /api/v1/session/current
```

`rollback` 在本 Feature 中只产生治理撤回/回退意图和证据，不执行运行时版本切换；真实切换由安装与回退 Feature 完成。

### 8.2 核心类型

```text
SkillAsset: assetId, assetKey, name, description, ownerScope,
  sourceSummary, metadataStatus, lifecycleSummary, tags
SkillVersion: assetId, versionDigest, versionLabel, artifactDigest,
  manifest, sourceSnapshot, metadataStatus, lifecycleState, createdAt
ImportAttempt: requestId, assetId?, sourceType, sourceLocator,
  status, failureStage?, failureCode?, failureReason?, artifactDigest?
ReleaseDecision: decisionId, versionDigest, targets, policyVersion,
  mode, state, gateResults, approval, evidence, reason
ReleaseBinding: bindingId, assetId, versionDigest, scopeType, scopeId,
  bindingState, isCurrent, decisionId, effectiveAt
PolicyVersion: policyVersion, scope, effectiveAt, autoRelease,
  grayRatio, observationWindowSeconds, minimumValidCalls,
  rollbackConditions, retentionRules
AuditRecord: actor, action, object, beforeState, afterState,
  reason, scope, policyVersion, occurredAt
```

外部 JSON 先在 integration 边界解析为明确 DTO，再转换为领域对象；不得把 `Map<String,Object>` 或未校验的 JSON 传入门禁规则。

## 9. 数据模型和不变量

### 9.1 PostgreSQL 15 核心表

使用数据库规范中的表模型：`skill_asset`、`skill_import_attempt`、`skill_artifact`、`skill_version`、`skill_version_manifest`、`skill_dependency`、`runtime_definition`、`skill_runtime_compatibility`、`governance_scope`、`principal`、`governance_role`、`principal_scope_role`、`release_policy_version`、`retention_policy_version`、`release_binding`、`release_decision`、`gate_evidence`、`release_decision_evidence`、`approval_record`、`audit_log` 和 `governance_event_outbox`。

### 9.2 关键不变量

- `version_digest` 是规范化制品、必要元数据和清单的 SHA-256 内容身份；唯一且创建后不可更新。
- `skill_import_attempt` 对 `request_id` 唯一；失败必须记录阶段和原因，且没有 `PUBLISHED` 版本。
- 同一资产、范围类型和范围 ID 只能有一条 `is_current = TRUE` 的绑定；使用 PostgreSQL 部分唯一索引和事务锁保证。
- 发布决策固定命中的策略版本、证据摘要和范围；证据过期、缺失、摘要不匹配或条件不可判断时阻断。
- `release_decision` 和 `gate_evidence` 追加写入；普通业务接口不得更新或删除。
- 策略和保留规则采用新版本插入，不修改历史生效记录。
- 所有安装、运行观测、评测关联保存 `version_digest`；缺失时保存 `version_unknown` 和原因。

### 9.3 默认策略与可配置规则

默认值是首期配置，不是代码常量：

| 规则 | 默认值 | 配置维度 |
| --- | --- | --- |
| 自动发布 | 仅低风险白名单候选 | 公司/项目/环境 |
| 自动发布门槛 | 无高危/严重问题；无权限/外部写操作变化；无新运行时兼容变化；至少 30 个有效案例；回归和有效评分不低于稳定基线 | 同上 |
| 灰度比例 | 10%，向上取整且至少 1 个实例 | 同上 |
| 观察窗口 | 24 小时且至少 30 次有效调用 | 同上 |
| 错误率回退 | 比基线增加 2 个百分点，或相对增加 20%；基线为 0 时达到 2% | 同上 |
| 评分回退 | 有效评分下降 5 个百分点 | 同上 |
| 紧急回退 | 高危/严重问题立即触发并上报 | 同上 |
| 原始运行事件 | 365 天 | 同上，可由运行观测域执行 |
| 制品/版本/评测/决策/审计 | 永久 | 同上，不允许普通删除 |
| 聚合指标 | 永久 | 同上，可由运行观测域执行 |

策略解析优先级为环境 > 项目 > 公司；同级冲突阻断自动发布。调整策略只能创建新版本，写入生效时间、创建主体、审批主体和审计记录。

## 10. 数据流和状态变化

### 10.1 导入流程

```text
登录 Session -> 范围/RBAC 校验 -> 创建 ImportAttempt(STARTED)
 -> 读取来源 -> 校验必需文件和可读性 -> 计算制品/版本摘要
 -> 保存对象存储和清单 -> 创建 DRAFT -> 完整性通过后转 CANDIDATE
 -> 提交目录投影和审计 Outbox
```

来源不可访问、必要内容缺失、摘要计算失败或对象存储失败时，`ImportAttempt` 转为 `FAILED`，保存 `failure_stage`、稳定 `failure_code` 和脱敏原因；事务不得创建可发布版本。重复 `request_id` 返回原结果，不重复生成资产、版本或审计事件。

### 10.2 生命周期状态机

```text
DRAFT -> CANDIDATE -> PUBLISHED -> OFFLINE -> DEPRECATED
                         |             |
                         +-> EMERGENCY_REVOKED
```

只有 `CANDIDATE` 可以进入门禁；`PUBLISHED` 必须已有有效决策和绑定；`OFFLINE`、`EMERGENCY_REVOKED`、`DEPRECATED` 不能作为默认分发版本。普通下线不强制停用存量实例，紧急撤回向安装 Feature 发出阻断新增安装和停用请求。

### 10.3 发布与灰度流程

1. Gate Service 读取候选版本、证据摘要和解析后的策略快照。
2. 缺证据、证据过期、扫描失败、高风险变化或审批人不分离时写入 `BLOCKED` 或 `PENDING_APPROVAL`。
3. 低风险白名单满足自动门槛时创建 `APPROVED`；需要灰度时同时写入 `PENDING_GRAY` 和 `GRAY_OBSERVATION` 证据。
4. `PENDING_GRAY` 只表示已允许开始观察，不是全量发布；观察证据达标后才建立/更新 `ACTIVE` 全量绑定。
5. 触发回退条件时写入 `REVOKED` 或回退决策，保存阈值、基线、样本数和触发证据，并通过 Outbox 通知下游。

## 11. 正常流程、异常、超时和重试

| 场景 | 行为、幂等和恢复 |
| --- | --- |
| 来源不可读/必要文件缺失 | 记录导入失败阶段和原因；不创建可发布版本 |
| 外部 Registry/Scanner 超时 | 限定次数重试并使用退避；超时证据为 `INCOMPLETE`，默认阻断 |
| 证据摘要不匹配/已过期 | 拒绝关联或阻断决策，保留失败审计 |
| 审核超时或审批人重复 | 保持 `PENDING_APPROVAL`；拒绝同一人工主体既申请又审批 |
| 重复导入/发布/撤回 | 根据请求幂等键返回原结果，不重复计账 |
| 多范围发布部分失败 | 每个范围独立事务结果和审计；成功范围保留，失败范围不创建有效绑定 |
| Outbox 投递失败 | 本地事务保持成功，事件进入重试状态；超过重试上限进入人工处理队列 |
| 搜索投影失败 | 权威数据仍可查询；重建投影，不改变生命周期或 binding |
| 紧急撤回通知部分失败 | 先阻断新绑定，再记录逐范围/逐下游结果；下游补偿持续可查 |
| 保留任务失败或新策略缩短失败 | 不删除数据，保留旧策略并记录任务结果 |
| 数据库并发发布 | 使用 `row_version`、事务锁和部分唯一索引；冲突返回可重试业务错误 |

服务内部异常必须包含 request ID、asset ID、version digest、scope ID 和 decision ID 等必要上下文，但不得记录凭据、密钥、完整制品和敏感运行文本。

## 12. 权限、安全、性能和兼容性

### 12.1 账户、Session 和 RBAC

- 登录使用账户密码，密码以 BCrypt 哈希保存；Session ID 通过 HttpOnly、Secure、SameSite Cookie 传输。
- `asset_contributor` 可在授权范围注册和提交版本；`reviewer` 可审查证据；`release_manager` 可申请发布；`governance_admin` 可管理授权和策略；`auditor` 只读审计。
- 每次操作按 Session 身份、角色、公司/项目/环境范围、对象状态和审批分离顺序校验。
- 自动发布使用 `service` 主体；不得伪造人工审批记录。申请主体不能审批自己的决策。
- CSRF、Session 失效、密码重试限制、Cookie 安全参数和密钥轮换由后端安全配置任务固定并测试。

### 12.2 性能与兼容

需求没有确认吞吐、延迟、容量、并发和可用性数值，不自行补齐。实现必须分页目录/审计/门禁，深度分页使用 seek；大报告按需加载；异步索引与通知不能阻塞导入和发布事务。新增状态和字段必须保持旧客户端可忽略未知字段，不能用前端猜测补齐缺失版本。

## 13. 观测、迁移和回滚

### 13.1 观测

平台请求、导入、门禁、策略解析、Outbox、外部适配器和数据库查询使用结构化日志、Trace 和 Micrometer 指标。至少记录请求 ID、状态、耗时、重试次数、结果和失败码；敏感字段脱敏。重点指标包括导入成功/失败、门禁阻断、审批等待、发布部分失败、Outbox 积压和投影延迟。

### 13.2 首次迁移

空库使用 Flyway 按依赖顺序创建范围/主体、资产/版本/导入、策略/门禁/发布、审计/Outbox 表。建议目标脚本：

```text
backend/src/main/resources/db/migration/
├─ V1__create_governance_scope.sql
├─ V2__create_skill_asset_version_import.sql
├─ V3__create_release_gate_policy.sql
├─ V4__create_authorization_audit.sql
├─ V5__create_cross_feature_metadata.sql
└─ R__rebuild_catalog_projection.sql
```

当前不存在历史数据库，因此不设计存量迁移。每次迁移必须能在空库执行，Flyway 脚本不可修改；破坏性变化拆为兼容、数据迁移和清理步骤。

### 13.3 回滚

- 策略错误：停止自动发布，恢复上一已审核策略版本；不修改历史版本。
- 门禁/绑定错误：撤销未生效绑定或创建紧急撤回决策；不删除历史证据。
- 外部适配器异常：停止自动发布并切换独立适配器/人工路径，禁止绕过门禁。
- 前端构建异常：恢复上一构建产物；API 不兼容先通过兼容字段解决。
- Outbox 或投影异常：重试/重建派生数据；不回滚已经提交的权威版本和审计。

## 14. 备选方案与取舍

| 方案 | 结论 | 原因 |
| --- | --- | --- |
| 直接 Fork iflytek SkillHub | 不采用 | Registry、namespace、RBAC 和 Scanner 可复用，但会把目标产品的范围、证据、策略和跨 Feature 契约绑死在上游模型中 |
| 多个开源项目拼接成运行时微服务 | 不采用 | 版本、权限、证据和审计一致性分散，跨服务补偿复杂；当前需求没有拆分为独立部署的依据 |
| 模块化单体 + 能力适配器 | 采用 | 保持单一事务边界和统一审计，同时为 Registry/Scanner/RBAC/对象存储保留替换边界，适合 greenfield 首期 |
| 以对象存储或搜索索引为权威 | 不采用 | 无法可靠保证并发 binding、策略生效、权限和审计一致性 |

## 15. 可行性证据和验证限制

### 15.1 已有证据

- 调研报告确认 iflytek SkillHub 具备 Registry、版本、审核、RBAC、CLI 分发和扫描思路，适合作为适配器候选。
- 调研报告确认评测结果、运行证据和发布治理必须关联不可变 Skill 版本，支持 `version_digest` 契约。
- 已确认技术基线全部保持 JDK 8 兼容，认证边界收敛为账户密码和服务端 Session。
- PostgreSQL 15 部分唯一索引、JSONB、事务锁和 Flyway 迁移可表达本方案的不变量，具体需迁移集成测试验证。

### 15.2 未验证事实

- 仓库没有业务源码、构建文件、测试和运行环境，无法验证编译、真实 API 或数据库执行。
- iflytek 实际扩展点、数据模型、许可证和供应链尚未做 PoC。
- 对象存储、搜索索引、分析存储、容量增长、备份/RPO/RTO 和组织范围来源尚未确认。

上述限制进入实施计划的阻塞项；不得在设计阶段标记为代码可行性已通过。

## 16. 可测试性与验证策略

| 层级 | 场景 | 证据 |
| --- | --- | --- |
| 单元 | 摘要规范化、状态机、策略优先级、门禁阈值、保留默认值 | 规则结果、拒绝原因和边界值 |
| Service 集成 | 导入成功/失败、版本不可变、发布绑定并发、多范围部分失败 | PostgreSQL 记录、状态和审计关联 |
| Mapper/迁移 | 空库 Flyway、唯一索引、JSONB、追加式表权限 | 迁移日志、约束和查询计划 |
| 契约 | 安装/观测/评测使用 `version_digest`，撤回事件和 `PENDING_GRAY` | 契约测试报告；缺失关联显式返回 |
| 安全 | 登录 Session、越权、审批人分离、普通用户删除、CSRF | 4xx 结果和审计记录 |
| 前端组件/E2E | 目录筛选、版本差异、门禁证据、逐范围结果、策略和审计 | 组件/页面测试与截图/报告 |
| 运维演练 | Outbox 重试、投影重建、适配器超时、策略回退、撤回补偿 | 任务状态、指标和恢复记录 |
| 静态文档 | 路径、版本、链接、需求/任务/验证映射 | Markdown 检查结果 |

首个代码 Slice 需要先建立 Maven/Vite 测试入口；当前不存在可执行测试命令，因此本轮只执行文档和基线静态校验。

## 17. 需求覆盖矩阵

| requirement-<semantic-name> | 方案响应 | 计划任务 | 验证方式 | 状态 |
| --- | --- | --- | --- | --- |
| `requirement-skill-asset-registration` | ImportAttempt、来源读取、清单、失败阶段和不可发布约束 | Task 2、Task 3 | Service 集成：成功、不可读、缺失内容、重复请求 | covered |
| `requirement-skill-metadata-completeness` | 元数据状态、清单、依赖和运行时矩阵显式保存 unknown/missing | Task 2、Task 5 | DTO/Service 单元和详情接口集成 | covered |
| `requirement-skill-catalog-search` | 授权范围、名称/描述/标签/来源/运行时/状态/版本查询 | Task 5 | Mapper/投影集成、分页和越权测试 | covered |
| `requirement-skill-version-immutability` | 摘要唯一、来源快照不可变、差异查询和数据库追加式约束 | Task 3、Task 4 | 摘要篡改、重复版本和差异测试 | covered |
| `requirement-skill-lifecycle-state` | 明确状态机、可分发过滤、状态原因和审计 | Task 4 | 状态转移、非法转移和默认版本测试 | covered |
| `requirement-skill-release-scope` | 公司/项目/环境范围、当前 binding 并发控制和逐范围结果 | Task 6 | 多范围发布、冲突和部分失败集成测试 | covered |
| `requirement-skill-release-gate` | 静态扫描/评测/风险/审核/灰度证据、策略快照和决策 | Task 7、Task 8 | 门禁单元、证据契约、灰度和回退演练 | covered |
| `requirement-skill-governance-audit` | 账户密码 Session、RBAC、审批分离、机器主体、追加审计 | Task 1、Task 9 | 安全集成、越权、Session 和审计还原测试 | covered |
| `requirement-skill-data-retention` | 范围化、版本化、可生效保留策略及默认值 | Task 10 | 策略单元、权限、审计和删除拒绝测试 | covered |

## 18. 风险、待确认事项和不覆盖项

### 18.1 风险

- 基线仍为用户确认的 draft，若技术栈、目录或数据模型变化，必须升级基线并重新检查本设计。
- iflytek 适配器的真实接口和许可证未知；PoC 失败时保留独立 Registry/Scanner/RBAC 接口。
- 评分基线、样本有效性和灰度流量来源由评测/观测 Feature 提供；缺失时本 Feature 只能阻断，不能猜测。
- 永久保留与原始事件 365 天可能造成容量和合规压力；容量、加密、归档、备份和删除执行责任待确认。
- 公司/项目/环境的组织来源、范围变更和冲突授权规则需在实施前固定。
- Session 安全参数、密码策略、CSRF、密钥管理和管理员初始化流程需安全评审。

### 18.2 待确认事项

- [ ] 正式公司反向域名包名替换 `com.km.skillhub`。
- [ ] 对象存储、搜索索引、分析存储的产品、版本和部署方式。
- [ ] PostgreSQL 备份、RPO/RTO、加密、容量和分区策略。
- [ ] 组织范围同步来源、密码复杂度和锁定策略。
- [ ] API 分页协议、错误码目录、前端组件库和浏览器支持范围。

### 18.3 不覆盖项

运行时执行、安装/回退执行、Tracker 和原始运行事件采集、评测 Runner、自动生成 Skill 内容、实际灰度流量切换和跨 Feature 的完整运营页面均不在本 Feature 实施范围。
## 19. CR-012 Incremental Design: Default Accounts

Flyway `V7__seed_default_governance_accounts.sql` creates the first-run accounts and their RBAC bindings:

- `admin`: system governance administrator in the `COMPANY/skillhub` scope, with asset, review, release, policy and audit roles.
- `user`: ordinary asset contributor in the same scope.
- Passwords are stored only as BCrypt hashes. The migration uses conflict-safe inserts and does not overwrite an existing account password.

## 20. CR-015 增量设计：参考项目能力对齐

### 20.1 对齐边界

本增量以参考项目的业务交互和治理能力为参照，保留当前项目的模块化单体、账户密码 Session、PostgreSQL 15、Redis Streams 和本地制品存储约束。参考项目的 React、OAuth/Token 认证、S3 实现和微服务拆分不进入本设计。

| 对齐能力 | 当前设计落位 | 明确不复制 |
| --- | --- | --- |
| Skill 发现、全文搜索、筛选和分页 | `catalog` 扩展发现查询与 PostgreSQL 派生索引 | 不引入外部搜索供应商 |
| Skill 详情、版本列表、文件浏览和下载 | `catalog`、`content`、`integration.artifact` | 不引入 S3；制品使用可替换本地存储 |
| 语义化版本、`latest/stable/beta` 标签、版本比较 | `version` 扩展标签和只读比较服务 | 标签不能绕过生命周期和门禁 |
| 发布、审核、撤回、归档和恢复 | `release`、`gate`、新增 `review` | 运行时流量切换仍由下游负责 |
| 命名空间及 Owner/Admin/Member | `namespace` 和 `governance` | 不建设 SaaS 租户隔离 |
| 管理员用户、命名空间、标签和审计 | `governance`、`audit`、新增 `admin` 查询接口 | 不增加 Token/CLI 身份认证 |

收藏、评分、订阅、通知以及参考项目中面向其生态的个人设置不在本次增量范围内。

### 20.2 后端模块扩展

在现有 `com.km.skillhub` 模块化单体中新增以下边界，依赖方向仍为 `controller -> service -> mapper`：

```text
backend/src/main/java/com/km/skillhub/
├─ namespace/{controller,model,service,mapper}/
├─ content/{controller,model,service}/
├─ review/{controller,model,service,mapper}/
├─ discovery/{model,service,mapper}/
└─ admin/{controller,model,service}/
```

- `namespace` 负责命名空间、成员和 Owner/Admin/Member 角色；资产查询必须通过命名空间和既有范围权限双重校验。
- `content` 负责包内文件清单、文本预览、单文件下载和版本包下载；不得把用户提供的路径直接拼接到文件系统路径。
- `discovery` 负责授权后的关键词、标签、命名空间、状态、版本和更新时间查询；搜索投影只能作为派生读模型，不能写入权威生命周期。
- `review` 负责候选版本的提交、通过、拒绝、撤回和重新发布申请；审批人必须不同于申请人，并复用既有门禁证据。
- `admin` 负责账户、命名空间、标签和审计的管理员查询/变更；权限检查在服务端完成，前端仅隐藏无权限操作。

### 20.3 公共接口和契约

所有写接口继续要求服务端 Session 和 CSRF；所有读接口都执行范围授权。保留现有 `/api/v1/assets` 治理接口，同时增加以下接口：

```text
GET  /api/v1/skills/search
GET  /api/v1/skills/{namespace}/{slug}
GET  /api/v1/skills/{namespace}/{slug}/versions
GET  /api/v1/skills/{namespace}/{slug}/versions/{versionDigest}/files
GET  /api/v1/skills/{namespace}/{slug}/versions/{versionDigest}/file?path=...
GET  /api/v1/skills/{namespace}/{slug}/versions/{versionDigest}/download
GET  /api/v1/skills/{namespace}/{slug}/versions/compare?from=...&to=...
POST /api/v1/assets/imports/package
POST /api/v1/reviews
POST /api/v1/reviews/{reviewId}/approve
POST /api/v1/reviews/{reviewId}/reject
POST /api/v1/reviews/{reviewId}/withdraw
GET  /api/v1/namespaces
GET  /api/v1/namespaces/{namespaceKey}/members
POST /api/v1/namespaces/{namespaceKey}/members
PUT  /api/v1/namespaces/{namespaceKey}/members/{principalId}/role
GET  /api/v1/admin/accounts
PUT  /api/v1/admin/accounts/{principalId}/status
```

`POST /api/v1/assets/imports/package` 支持 multipart Skill 包，并保留原有 JSON 导入接口用于兼容已有控制台和自动化测试。导入响应必须返回 `requestId`、`assetId`、`versionDigest`、文件完整性结果和失败留痕；不返回密码、内部文件系统绝对路径或未经授权的制品地址。

### 20.4 PostgreSQL 15 增量模型

新增 Flyway `V10__align_skillhub_registry_governance.sql`，不修改已执行的 V1-V9。主要增量如下：

| 表/变更 | 作用 | 关键约束 |
| --- | --- | --- |
| `skill_namespace` | 命名空间身份、显示名、状态和所属治理范围 | `namespace_key` 唯一；状态变更审计 |
| `skill_namespace_member` | 命名空间成员和角色 | `(namespace_id, principal_id)` 唯一；角色限定 Owner/Admin/Member |
| `skill_asset.namespace_id`, `skill_asset.slug` | 将资产映射到命名空间 | `(namespace_id, slug)` 唯一；历史资产迁移到默认命名空间 |
| `skill_version_file` | 文件路径、类型、大小、摘要和制品相对引用 | `(version_id, path)` 唯一；路径不能为绝对路径或包含 `..` |
| `skill_version_tag` | 当前标签到具体版本的指向 | `(asset_id, tag_name)` 唯一；只允许指向可分发版本 |
| `skill_version_tag_history` | 标签变更历史 | 追加写入；必须关联操作主体和前后 digest |
| `skill_review_task` | 版本审核申请、审核人、状态和意见 | 同一版本最多一个待处理申请；申请人不能审批 |
| `skill_asset` 元数据字段 | 许可、运行时、标签和搜索展示信息 | 缺失值保持 `UNKNOWN`，不自动猜测 |

文件正文不写入 PostgreSQL。`skill_version_file` 保存相对对象键，默认由本地 `skillhub.artifact.root` 配置决定；存储接口保持 `ArtifactStore`，后续可替换实现但本轮不选择 S3。文件读写均通过版本摘要和已校验清单定位。

### 20.5 版本和标签规则

- 版本号按 `MAJOR.MINOR.PATCH`，允许预发布标识；无效格式拒绝导入或发布。
- `latest` 解析为该资产最新的 `PUBLISHED` 版本，解析结果立即转换为具体 `versionDigest` 后再进入下游契约。
- `stable` 和 `beta` 等自定义标签只能绑定 `CANDIDATE` 或 `PUBLISHED` 中符合策略的版本；撤回、下线和废弃时自动解除可分发指向并写入历史。
- 版本比较服务只接受同一资产的两个明确 digest，返回元数据差异、文件状态和文本 diff；二进制只返回摘要和大小变化。

### 20.6 前端信息架构和品牌主题

前端继续使用 Vue 3 和 TypeScript，新增页面与参考项目保持同类信息架构，但不复制其 React 实现：

```text
frontend/src/pages/
├─ discovery/SkillSearchPage.vue
├─ skill/SkillDetailPage.vue
├─ skill/SkillVersionComparePage.vue
├─ namespace/NamespacePage.vue
├─ review/ReviewListPage.vue
├─ review/ReviewDetailPage.vue
└─ admin/{AccountPage,NamespacePage,LabelPage}.vue
```

路由新增 `/search`、`/space/:namespace/:slug`、`/space/:namespace/:slug/versions/compare`、`/dashboard/reviews`、`/dashboard/reviews/:reviewId`、`/dashboard/namespaces`、`/dashboard/namespaces/:namespaceKey/members`、`/admin/accounts`、`/admin/namespaces` 和 `/admin/labels`，既有治理路由继续保留。

官网当前无法在设计环境完成可靠读取，因此精确品牌色标记为待确认；本轮使用集中式、可替换的企业品牌 token：`--brand-primary: #005BAC`、`--brand-deep: #003B70`、`--brand-accent: #1677C8`。页面采用企业蓝作为主操作色，深蓝作为导航/标题强调色，红/黄/绿仅用于风险和状态语义；所有功能文案、错误文案和状态文案使用中文。品牌 token 只放在 `frontend/src/styles.css`，不散落在组件内。

### 20.7 失败处理和安全边界

- 包解析、文件存储、清单生成和数据库写入任一阶段失败，都必须更新 `skill_import_attempt` 并阻止候选版本发布。
- 下载和预览前先验证 Session、范围、版本生命周期和清单路径；文件不存在、摘要不匹配或制品已损坏时返回稳定业务错误并写审计。
- 搜索索引不可用时允许回退到 PostgreSQL 的受限查询；回退不可绕过授权，也不可把索引延迟渲染为“无数据”。
- 管理员停用账户后，当前 Session 在下一次请求鉴权时失效；账户、成员、标签和审核操作均追加审计。
- 外部请求、制品大小、分页大小和预览长度使用配置上限；具体容量、备份、RPO/RTO 仍保留为待确认项。

### 20.8 CR-015 设计验证和待确认项

设计覆盖 `requirement-skill-package-content`、`requirement-skill-semantic-version-tags`、`requirement-skill-discovery-search`、`requirement-skill-file-browse-download`、`requirement-skill-version-comparison`、`requirement-skill-publish-review-lifecycle`、`requirement-skill-namespace-governance`、`requirement-governance-account-management` 和 `requirement-skill-governance-console-branding`。实施前需要验证：

- 参考项目的页面和接口行为与当前业务术语的最终映射；
- 官网精确品牌色、Logo 资源和字体授权；
- 本地制品根目录、最大包大小、备份和清理策略；
- PostgreSQL 全文搜索规模是否满足首期性能目标；不足时再评估可重建搜索适配器；
- 命名空间与现有公司/项目/环境治理范围的组织来源。

## 22. CR-016 企业级前端控制台设计

### 22.1 目标和边界

本增量只升级现有 Vue 控制台的应用壳、页面布局、公共交互和中文显示，不改变后端接口、Session、权限、生命周期、制品存储或安装治理边界。所有用户可见功能文案使用中文，路由、API 路径、代码标识和摘要值继续遵循既有契约。

### 22.2 应用壳和导航

桌面端采用“左侧固定一级导航 + 顶部工作栏 + 中央内容区”；移动端将左侧导航折叠为可关闭抽屉。左侧导航按用户目标分组：工作台、技能中心、治理中心、运行管理和系统管理。系统管理菜单由服务端能力结果控制，隐藏入口不替代服务端鉴权。

顶部工作栏展示面包屑、全局技能搜索入口、当前账户和退出操作。品牌区域显示“KM 技能中心”和“资产、发布与运行治理”，不在功能文案中使用英文产品词。

### 22.3 页面布局

| 页面 | 结构和交互 |
| --- | --- |
| 工作概览 | 统计摘要、待处理审核、最近导入、异常安装和最近治理事件；摘要只聚合已有接口结果 |
| 技能发现 | 搜索框、命名空间/状态/标签/更新时间筛选、结果列表、分页和加载/空/错误/无权限状态 |
| 资产目录 | 页面级上传入口、筛选工具栏、资产表格、生命周期徽标、刷新和分页 |
| 上传技能 | 左侧导入表单，右侧校验、进度和导入结果；失败必须保留失败阶段和原因 |
| 资产详情 | 资产头部、概览/版本/发布范围/审计页签；版本页签进入版本详情 |
| 版本详情 | 版本头部、生命周期时间线、元数据、文件清单、预览、门禁证据和固定操作区 |
| 审核工作台 | 左侧审核队列、右侧审核详情；通过/拒绝/撤回使用原因输入和二次确认 |
| 发布决策 | 版本确认、门禁检查、发布范围、灰度观察和结果步骤展示；逐范围显示结果 |
| 发布策略/审计 | 策略表单和版本记录；审计使用筛选表格、详情抽屉和关联请求标识 |
| 安装管理 | 安装实例列表、运行跟踪器状态、健康状态、失败阶段、版本切换和回退操作 |
| 系统管理 | 账户、命名空间、标签采用页签切换，避免长页面堆叠多个管理域 |

### 22.4 公共组件和状态

公共层新增或整理 SideNavigation、TopBar、Breadcrumbs、PageHeader、FilterToolbar、DataTable、StatusBadge、EmptyState、ErrorState、ConfirmDialog、DrawerPanel、Timeline 和 Pagination。页面只负责编排和用户事件，数据请求和领域映射仍留在模块 API、服务和组合式逻辑中。

所有查询统一表达加载中、成功、空数据、部分成功、失败、无权限和数据过期；关键写操作表达请求中、成功、失败和重复提交。颜色不是唯一状态表达方式，状态必须同时包含中文文字和可识别图标或标记。

### 22.5 品牌和视觉令牌

沿用可替换的公司蓝色令牌：--brand-primary: #005BAC、--brand-deep: #003B70、--brand-accent: #1677C8、--brand-soft: #EAF4FC。左侧导航使用深蓝，内容区使用浅灰背景和白色工作面，采用不超过 6px 的圆角、细边框和轻阴影；不使用渐变、装饰光斑或营销式英雄区块。官网精确色值和 Logo 资源仍保留为待确认项，令牌必须集中维护。

### 22.6 目录和兼容策略

应用壳落位于 frontend/src/components/layout/，公共交互组件落位于 frontend/src/components/ui/，业务组件继续位于各 Feature 的 frontend/src/modules/。保留现有业务路由，新增工作概览路由时保留 /assets 等旧入口可直接访问；不因视觉重构删除已有 API、权限和状态流转。

### 22.7 CR-016 验证范围

- 验证桌面端和移动端导航、抽屉、面包屑、页面宽度和文本不重叠；
- 验证主要页面的加载、空数据、失败、无权限和写操作反馈；
- 检查所有用户可见功能文案无中英文混排和乱码，技术摘要值除外；
- 执行前端单元测试、类型检查、生产构建、路由检查和浏览器截图/交互验证；
- CR-016 本身不新增 OAuth2、统一单点登录、API Token、S3、微服务或运行时业务能力；API Token 已由后续 CR-017 单独定义。

## 23. CR-017 API Token 访问设计

### 23.1 设计边界

CR-017 在保留账户密码、服务端 Session 和网页 CSRF 的前提下，为自动化客户端增加独立 Bearer Token 认证。Token 只作为身份和作用域凭据，不负责压缩 Skill、不保存到 Skill 包，也不引入 CLI、OAuth2、统一单点登录、S3 或微服务。

### 23.2 数据模型和密钥处理

新增 PostgreSQL 15 迁移 `V11__create_api_token.sql` 和表 `api_token`。表保存账号、名称、`sk_` 前缀、SHA-256 十六进制摘要、JSONB 作用域、创建/过期/最后使用/撤销时间；`token_hash` 唯一，未撤销 Token 的账号内名称唯一。Token 原文由服务端使用安全随机数生成，只在创建响应中返回一次，禁止日志记录和再次查询恢复。

### 23.3 认证和作用域流程

`ApiTokenAuthenticationFilter` 在匿名认证前读取 `Authorization` 请求头，只有 Bearer 格式参与 Token 查询。服务端按摘要查询 Token，校验撤销时间、过期时间和账号启用状态后创建带 `SCOPE_*` 权限的 Spring Authentication，并更新最后使用时间；不存在、过期、撤销或账号停用统一返回 401。`ApiTokenScopeFilter` 只约束 Bearer 请求，Session 请求继续按既有角色和范围授权执行。

作用域与路由映射如下：

| 作用域 | 路由范围 |
| --- | --- |
| `skill:read` | `GET /api/v1/assets/**`，包括目录、详情、版本、文件和制品下载 |
| `skill:publish` | `POST /api/v1/assets/imports`、`POST /api/v1/assets/imports/package` |
| `token:manage` | `GET/POST /api/v1/tokens/**`、`PUT /api/v1/tokens/{id}/expiration`、`DELETE /api/v1/tokens/{id}` |

作用域过滤器不替代既有资产范围权限：Bearer 身份必须同时满足 Token 作用域和当前账号的资产/治理范围授权。Token 管理接口只允许管理当前主体的 Token。

### 23.4 Token 管理接口

```text
POST /api/v1/tokens
GET  /api/v1/tokens
PUT  /api/v1/tokens/{id}/expiration
DELETE /api/v1/tokens/{id}
```

创建请求支持名称、作用域和可选 `expiresAt`；作用域只能从 `skill:read`、`skill:publish`、`token:manage` 中选择。创建响应包含一次性完整 Token，列表只返回前缀、元数据和状态。过期时间更新支持延长、缩短或清除过期时间，撤销为不可逆操作。所有写操作使用 Session 的 CSRF 校验；Bearer 调用通过 `token:manage` 作用域访问时不要求 Cookie CSRF。

### 23.5 前端管理页面

新增 `/account/tokens` 页面和 `frontend/src/modules/token/` Feature 模块。页面按参考 SkillHub 的令牌管理信息架构实现：侧边栏入口使用 `访问凭证`，顶部提供返回工作台、`Token 管理` 标题和 `管理 CLI 和 API 使用的访问凭证` 副标题；主体为 `API Tokens` 清单卡片，创建入口为 `创建新 Token` 弹窗，创建成功在弹窗内一次性展示并复制原文；修改期限使用支持永不过期、固定期限和自定义时间的弹窗，删除使用二次确认并在成功后从当前清单移除。`访问凭证` 是导航业务名称，凭证本体仍是 API Token，不称为 Token 密码或 API 密钥。作用域和状态作为名称下的辅助信息展示。所有显示文案使用中文，完整 Token 仅驻留当前页面状态，离开或刷新后不再显示。

### 23.6 安全、审计和验证

- Bearer 认证失败为 401，作用域不足为 403；不得把 Token 错误降级为 Session 登录或匿名访问。
- 更新 `last_used_at` 不得记录 Token 原文；审计只记录不可逆 Token 标识和作用域元数据。
- 验证覆盖摘要不可逆、一次性原文、过期/撤销、作用域隔离、账号停用、Session CSRF 保持有效和既有资产 API Bearer 调用。

## 24. CR-020 增量设计：参考 SkillHub 前端对齐

### 24.1 信息架构和术语

参考项目 `web/src/i18n/locales/zh.json` 的用户菜单作为文字基线。当前 Vue 控制台新增或调整以下入口：`控制台`、`我的技能`、`发布`、`我的命名空间`、`治理中心`、`审核管理`、`用户管理`、`标签管理`、`审计日志`、`命名空间管理`、`访问凭证`、`安全设置`、`个人设置`、`通知设置`。当前工程的资产目录、安装管理、发布策略和治理审计作为本产品扩展保留。

本增量明确不创建或恢复 `推广管理`、`举报管理`、`账号合并`、`收藏与评分`，也不提供只看已收藏、收藏筛选及收藏/评分/举报相关交互。所有页面均使用中文可见文案；参考项目内部的 `Token 管理`、`API Tokens` 和“管理 CLI 和 API 使用的访问凭证”按已确认 Token 术语保留，其中“访问凭证”是导航名称。

### 24.2 页面和路由

| 页面 | 主路由 | 交互要求 |
| --- | --- | --- |
| 控制台 | `/dashboard` | 展示当前用户、平台角色、我的技能摘要、访问凭证入口和最近技能，同时保留当前治理运行摘要 |
| 我的技能 | `/dashboard/skills` | 搜索、命名空间筛选、全部/待审核/已发布/已拒绝/已归档/已隐藏状态 Tab、查看详情、更新、归档/恢复、撤回审核；危险动作使用确认弹窗 |
| 发布 | `/dashboard/publish` | 选择命名空间、可见性、上传 ZIP、发布前检查、风险确认弹窗、发布/提交审核、成功后回到我的技能 |
| 搜索技能 | `/search` | 相关性/下载量/最新排序、标签和命名空间筛选、结果数量、分页、技能卡片；不要求登录即可打开页面，实际数据仍由后端授权决定 |
| 技能详情 | `/space/:namespace/:slug` | README/概览/文件/版本 Tab，文件树、文本预览弹窗、下载、安装命令、生命周期操作、版本比较；不包含收藏、评分、举报 |
| 版本比较 | `/space/:namespace/:slug/compare` | 与参考路径一致，同时兼容现有 `/versions/compare` |
| 审核管理 | `/dashboard/reviews` | 技能审核/资料审核、待审核/已通过/已拒绝 Tab、详情查看、审核意见弹窗和通过/拒绝确认弹窗 |
| 治理中心 | `/dashboard/governance` | 待审核任务、治理通知、最近活动和搜索索引维护；不显示被排除的推广/举报区块 |
| 我的命名空间 | `/dashboard/namespaces` | 命名空间列表、创建/冻结/归档/恢复确认弹窗、进入成员管理和审核入口 |
| 命名空间成员 | `/dashboard/namespaces/:slug/members` | 成员列表、添加成员弹窗、角色变更、移除确认弹窗 |
| 命名空间审核 | `/dashboard/namespaces/:slug/reviews` | 展示当前命名空间审核任务并进入审核详情 |
| 管理页面 | `/admin/users`、`/admin/namespaces`、`/admin/labels`、`/admin/audit-log` | 将原合并管理页拆分；保留 `/admin` 兼容入口，服务端继续执行管理员权限 |
| 设置 | `/settings/security`、`/settings/profile`、`/settings/notifications` | 修改密码、个人资料和通知偏好；不实现账号合并 |
| 访问凭证 | `/dashboard/tokens` | 与 `/account/tokens` 双向兼容，页面标题、创建/期限/撤销弹窗沿用 CR-018/019 |

### 24.3 数据与交互边界

前端页面仅调用现有资产、内容、搜索、审核、命名空间、管理员和 Token 接口；新增页面使用现有 DTO 字段映射，不能在前端伪造发布状态、权限或审核结果。当前后端未提供的资料审核、通知偏好和命名空间生命周期写接口以只读或明确“暂未开放”状态展示，不模拟成功结果。所有确认、危险动作、审核意见和文件预览使用统一弹窗/抽屉样式，禁止继续使用 `window.prompt` 和 `window.confirm`。

页面布局沿用 Vue 3 企业控制台：左侧分组导航、顶部工作栏、页面标题区、内容分区、状态标签、空状态、加载状态和响应式移动抽屉。主题继续使用现有公司蓝色 CSS 变量，不引入参考项目的 React、OAuth、S3 或微服务实现。

## 25. CR-021 增量设计：侧边栏收敛与页签承载

### 25.1 信息架构

侧边栏只保留高频业务入口：`控制台`、`我的技能`、`发布`、`我的命名空间`、`搜索`、`资产目录`、`访问凭证`、`治理中心`、`安装管理` 和管理员可见的 `系统管理`，以及 `设置`。治理类低频入口不再单独占用侧边栏项，而是在页面内使用一级页签承载：

| 入口 | 页面内页签 |
| --- | --- |
| 治理中心 | 总览、审核管理、发布策略、审计记录 |
| 系统管理 | 用户管理、命名空间管理、标签管理、审计日志 |
| 设置 | 安全设置、个人设置、通知设置 |

原有 `/dashboard/reviews`、`/governance/policies`、`/governance/audits`、`/admin/users`、`/admin/namespaces`、`/admin/labels`、`/admin/audit-log` 和三个设置路由继续保留，直接访问时仍展示对应页面；新入口通过查询参数切换同一聚合页面，刷新和复制链接后页签保持不变。

### 25.2 视觉和交互

导航项移除“控、技、发”等单字前置文字，使用统一的文本导航、分组标题、左侧选中线和企业蓝选中态。导航区域隐藏滚动条视觉，但仍允许小屏抽屉在内容超出时滚动；不改变移动端抽屉和遮罩交互。页签采用底部边框选中态，支持键盘聚焦和横向溢出，移动端不挤压页面内容。

本增量只调整前端路由编排、页面复用和样式，不新增 API、权限、数据库、认证或被明确排除的推广、举报、账号合并、收藏、评分能力。
