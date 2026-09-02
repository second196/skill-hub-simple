---
title: SKILL HUB 数据库设计规范
description: SKILL HUB 资产治理、安装回退、运行观测和评测演进的数据模型、索引、迁移与保留规则
audience:
  - product-development
  - backend-developers
  - database-administrators
owner: product-development
status: draft
lastReviewed: 2026-09-02
sourceType: manual
---

# SKILL HUB 数据库设计规范

本文结合 SKILL HUB 总体需求，定义事务型治理数据、制品数据、运行观测数据和评测数据的存储边界、表结构、索引、迁移、权限和保留规则。参考 `kmplm-development-process` 的 MyBatis-Plus、Flyway 和命名约束；SKILL HUB 自身事务数据库采用 PostgreSQL 15，不复用参考仓库中与其他产品有关的业务表。

## 1. 基线元信息

| 项目 | 内容 |
| --- | --- |
| 基线版本 | v0.4-draft |
| 适用产品 | SKILL HUB，公司内部私有化部署 |
| 适用 Feature | 资产与发布治理、安装与回退、运行时观测、评测与持续演进 |
| 事务数据库 | PostgreSQL 15 |
| 数据访问 | MyBatis / MyBatis-Plus |
| 数据库迁移 | Flyway，版本脚本与数据库厂商隔离 |
| PostgreSQL JDBC | 42.2.27，Java 8 兼容基线 |
| Flyway 版本 | 8.5.13，Java 8 兼容基线 |
| 异步任务 | Redis Streams 6.2.14，消费幂等、失败重试和积压可观测 |
| 高吞吐运行数据 | 独立分析存储，候选为 ClickHouse；具体组件待确认 |
| 制品和大报告 | 不可变对象存储；具体组件待确认 |
| 确认状态 | draft，待确认容量、部署、备份和数据安全基线 |
| 维护责任 | product-development |

当前仓库没有业务源码、数据库或迁移历史。以下表和路径是目标设计，不是已有数据库事实。

## 2. 总体存储架构

```text
                         +----------------------+
                         | Java API / MyBatis   |
                         +----------+-----------+
                                    |
                  +----------------v----------------+
                  | PostgreSQL 15: 权威事务与治理元数据 |
                  +----------------+----------------+
                                   |
          +------------------------+------------------------+
          |                         |                        |
          v                         v                        v
    对象存储                  搜索索引                  分析存储
  制品/报告/快照              目录派生索引          原始事件/Trace/指标
```

### 2.1 PostgreSQL 负责

- Skill 资产、不可变版本、来源和运行时兼容矩阵。
- 生命周期、发布范围、门禁证据索引、发布决策和策略版本。
- 组织范围、角色授权、审批记录和审计记录。
- 安装实例/Tracker 配置的低频元数据、评测运行索引、Finding 和候选版本索引。
- 迁移历史、幂等键、Outbox 事件和数据保留策略。

### 2.2 其他存储负责

- 对象存储：Skill 制品、来源快照、静态扫描报告、评测报告和大型证据；对象名必须包含内容摘要，上传后不可覆盖。
- 分析存储：原始运行事件、Agent Trace、SkillInvocation 和 MetricAggregate。原始事件默认保留 365 天，聚合指标永久保留。
- 搜索索引：资产目录的派生查询索引，可从 PostgreSQL 15 重建，不拥有状态写权限。

## 3. 通用表设计规范

### 3.1 命名和字段

- 表名、字段名、索引名使用小写 `snake_case`；表名使用业务对象单数形式。
- 主键统一使用 `BIGINT`，DDL 默认使用 PostgreSQL 15 的 identity/sequence 生成；外部对象 ID 使用 `VARCHAR(64)` 或 `VARCHAR(128)`。
- 时间统一使用 `TIMESTAMP(3) WITH TIME ZONE`，以服务端 UTC 存储并在界面按组织时区展示。
- 可变表默认包含 `created_at`、`updated_at`、`created_by`、`updated_by`、`row_version`；不可变表只允许插入并保留创建信息。
- 状态使用 `VARCHAR(32)` 配合 Java 枚举和应用校验，不使用数据库原生 enum 类型，便于兼容迁移。
- 大文本、制品和报告不直接放入 PostgreSQL 15；PostgreSQL 15 只保存 URI、大小、媒体类型、内容摘要和加密/脱敏状态。
- JSON 结构化字段使用 PostgreSQL `JSONB`，只保存策略快照、清单和外部扩展字段；需要检索或唯一约束的字段必须独立成列。

### 3.2 删除与不可变性

- `skill_version`、`skill_artifact`、`release_decision`、`gate_evidence`、评测报告和 `audit_log` 不提供物理删除业务接口。
- 资产下线、版本撤回和废弃使用状态字段；历史版本和决策保持可查询。
- 发布策略和保留策略采用新版本插入，不更新已生效历史策略。
- 关键更新使用 `row_version` 乐观锁；当前范围 binding 使用事务锁或等价并发控制保证同一范围只有一个当前版本。

### 3.3 事务和外键

- 同一治理域内的资产、版本、证据索引、发布绑定和策略使用外键或等价一致性约束。
- 运行事件、评测报告和外部系统对象采用逻辑外键，必须保存来源系统、来源 ID 和版本摘要。
- 跨域通知通过 `governance_event_outbox` 可靠投递，不能把外部调用成功作为本地事务提交条件。
- 所有列表查询分页，禁止 `SELECT *` 和无条件全表扫描；模糊搜索由搜索索引或受控前缀查询承担。

## 4. 总体表模型

### 4.1 资产与版本域

| 表 | 用途 | 关键唯一约束/索引 |
| --- | --- | --- |
| `skill_asset` | Skill 稳定身份和当前资产状态 | `uk_asset_key`；`idx_owner_scope_status` |
| `skill_artifact` | 制品、来源快照和对象存储定位 | `uk_artifact_digest`；`idx_asset_id` |
| `skill_version` | 不可变内容版本 | `uk_asset_version_label`、`uk_version_digest`；`idx_asset_state` |
| `skill_version_manifest` | 版本内容清单和完整性结果 | `uk_version_manifest`；`idx_required_read_status` |
| `skill_dependency` | 版本依赖关系 | `uk_version_dependency`；`idx_dependency_target` |
| `runtime_definition` | 可配置运行时及版本能力 | `uk_runtime_key_version`；`idx_runtime_status` |
| `skill_runtime_compatibility` | 版本与运行时兼容矩阵 | `uk_version_runtime`；`idx_runtime_support` |
| `skill_tag` / `skill_asset_tag` | 目录标签 | `uk_tag_name`、`uk_asset_tag` |

### 4.2 组织、权限与发布域

| 表 | 用途 | 关键唯一约束/索引 |
| --- | --- | --- |
| `governance_scope` | 公司、项目和环境范围 | `uk_scope_type_key`；`idx_parent_scope` |
| `scope_relation` | 范围父子关系和生效状态 | `uk_scope_relation` |
| `principal` | 人员或服务主体引用 | `uk_principal_source_id`；`idx_principal_type_status` |
| `governance_role` | 贡献者、审核人、发布人、治理管理员和审计员角色 | `uk_role_key` |
| `principal_scope_role` | 主体在范围内的角色授权 | `uk_principal_scope_role`；`idx_scope_role` |
| `release_policy_version` | 发布、灰度、回退和审批策略快照 | `uk_policy_scope_version`；`idx_policy_effective_at` |
| `retention_policy_version` | 数据类型保留策略快照 | `uk_retention_scope_type_version`；`idx_retention_effective_at` |
| `release_binding` | 版本发布到授权范围的当前/历史绑定 | `uk_binding_scope_asset_current`；`idx_binding_version_scope` |
| `release_decision` | 门禁计算后的发布/阻断/审批决策 | `uk_decision_request_scope`；`idx_decision_version_state` |
| `gate_evidence` | 扫描、评测、风险、审核和灰度证据索引 | `uk_evidence_digest`；`idx_evidence_version_type` |
| `release_decision_evidence` | 决策与证据多对多关联 | `uk_decision_evidence` |
| `approval_record` | 人工审批记录和审批人分离 | `uk_decision_approver`；`idx_approval_state` |
| `audit_log` | 关键操作和状态还原 | `idx_audit_object_time`、`idx_audit_actor_time` |
| `governance_event_outbox` | 撤回、发布和策略事件可靠投递 | `uk_event_id`；`idx_event_state_next_retry` |

### 4.3 安装与回退域元数据

这些表由 `feature-skill-installation-recovery` 负责写入，本规范固定与治理域的关联方式：

| 表 | 用途 | 必须关联 |
| --- | --- | --- |
| `installation_instance` | Skill 在 Agent/主机/逻辑实例上的安装状态 | `asset_id`、`version_digest`、`runtime_key`、`scope_id` |
| `tracker_binding` | Skill 与 Tracker 的绑定和健康状态 | `installation_instance_id`、Tracker 版本 |
| `installation_operation` | 安装、切换、回退和撤回操作 | 前后 `version_digest`、结果、失败阶段、幂等键 |

### 4.4 运行观测域

运行观测由 `feature-skill-runtime-observability` 负责。Tracker 元数据可在 PostgreSQL 15 保存；原始运行事件、AgentTrace、SkillInvocation 和 MetricAggregate 使用独立分析存储。不能可靠归属版本的调用保存 `version_unknown` 标志和原因，禁止绑定当前最新版本。

### 4.5 评测与持续演进域

评测由 `feature-skill-evaluation-evolution` 负责，PostgreSQL 15 保存套件、案例、运行、结果、Finding、候选和回归基线索引，报告正文进入对象存储。所有结果必须关联 Skill `version_digest`、评测版本、运行时、模型、环境和执行时间。

## 5. 关键字段设计

### 5.1 `skill_asset`

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `id` | BIGINT | 是 | 主键 |
| `asset_key` | VARCHAR(128) | 是 | 稳定业务标识，唯一 |
| `name` | VARCHAR(128) | 是 | Skill 名称 |
| `description` | VARCHAR(2048) | 是 | 主描述 |
| `owner_scope_id` | BIGINT | 是 | 归属范围 |
| `status` | VARCHAR(32) | 是 | `ACTIVE`、`OFFLINE`、`DEPRECATED` |
| `created_by` / `updated_by` | VARCHAR(128) | 是 | 主体标识 |
| `created_at` / `updated_at` | TIMESTAMP(3) WITH TIME ZONE | 是 | 时间 |
| `row_version` | BIGINT | 是 | 乐观锁 |

索引：`uk_asset_key`；`idx_asset_owner_status(owner_scope_id,status)`；目录全文检索使用 `name`、`description` 和标签的派生索引。

### 5.2 `skill_version`

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `id` | BIGINT | 是 | 主键 |
| `asset_id` | BIGINT | 是 | 所属资产 |
| `version_label` | VARCHAR(64) | 是 | 展示版本号 |
| `version_digest` | CHAR(64) | 是 | 内容身份摘要，不可变 |
| `source_type` / `source_locator` | VARCHAR(32) / VARCHAR(1024) | 是 | 来源类型和定位 |
| `source_snapshot_uri` | VARCHAR(1024) | 是 | 不可变来源快照 |
| `metadata_status` | VARCHAR(32) | 是 | `COMPLETE`、`INCOMPLETE`、`UNKNOWN` |
| `lifecycle_state` | VARCHAR(32) | 是 | 草稿、候选、已发布、下线、紧急撤回、废弃 |
| `created_by` / `created_at` | VARCHAR(128) / TIMESTAMP(3) WITH TIME ZONE | 是 | 创建主体和时间 |

索引：`uk_asset_version_label(asset_id,version_label)`；`uk_version_digest(version_digest)`；`idx_version_asset_state(asset_id,lifecycle_state,created_at)`。

### 5.3 `release_binding`

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `id` | BIGINT | 是 | 主键 |
| `asset_id` | BIGINT | 是 | Skill 资产 |
| `version_digest` | CHAR(64) | 是 | 已发布版本 |
| `scope_type` / `scope_id` | VARCHAR(32) / BIGINT | 是 | 公司/项目/环境 |
| `binding_state` | VARCHAR(32) | 是 | `ACTIVE`、`OFFLINE`、`EMERGENCY_REVOKED` |
| `is_current` | BOOLEAN | 是 | 当前范围绑定标志 |
| `policy_version` | VARCHAR(64) | 是 | 命中的发布策略 |
| `decision_id` | BIGINT | 是 | 发布决策 |
| `effective_at` / `released_at` | TIMESTAMP(3) WITH TIME ZONE | 是 | 生效和发布时间 |

约束：同一 `asset_id + scope_type + scope_id` 只能有一条 `is_current=TRUE` 记录；PostgreSQL 15 使用 `WHERE is_current = TRUE` 的部分唯一索引实现该约束。更新旧绑定和插入新绑定必须在一个事务内完成。历史 binding 不能删除。

目标索引定义：

```sql
CREATE UNIQUE INDEX uk_binding_scope_asset_current
    ON release_binding (asset_id, scope_type, scope_id)
    WHERE is_current = TRUE;
```

### 5.4 `release_policy_version`

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `id` | BIGINT | 是 | 主键 |
| `scope_type` / `scope_id` | VARCHAR(32) / BIGINT | 是 | 策略范围 |
| `policy_version` | VARCHAR(64) | 是 | 规则版本 |
| `effective_at` | TIMESTAMP(3) WITH TIME ZONE | 是 | 生效时间 |
| `minimum_valid_cases` | INT | 是 | 默认 30 |
| `gray_ratio` | DECIMAL(5,4) | 是 | 默认 0.1000 |
| `observation_window_seconds` | BIGINT | 是 | 默认 86400 秒 |
| `minimum_valid_calls` | INT | 是 | 默认 30 |
| `rollback_conditions` | JSONB | 是 | 错误率、评分、高危问题条件 |
| `auto_release_conditions` | JSONB | 是 | 白名单和风险条件 |
| `created_by` / `created_at` | VARCHAR(128) / TIMESTAMP(3) WITH TIME ZONE | 是 | 操作主体和时间 |

策略版本创建后不可更新；更改必须插入新版本。环境级策略优先于项目级，项目级优先于公司级；同级冲突阻断自动发布。

### 5.5 `audit_log`

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `id` | BIGINT | 是 | 主键 |
| `actor_id` / `actor_type` | VARCHAR(128) / VARCHAR(16) | 是 | 人工或服务主体 |
| `action` | VARCHAR(64) | 是 | 操作类型 |
| `object_type` / `object_id` | VARCHAR(64) / VARCHAR(128) | 是 | 操作对象 |
| `before_state` / `after_state` | JSONB | 是 | 前后状态快照 |
| `reason` | VARCHAR(2048) | 是 | 原因 |
| `scope_type` / `scope_id` | VARCHAR(32) / BIGINT | 是 | 生效范围 |
| `policy_version` | VARCHAR(64) | 否 | 关联策略 |
| `occurred_at` | TIMESTAMP(3) WITH TIME ZONE | 是 | 操作时间 |

审计表采用追加写入；查询按对象、主体、范围和时间分页。永久保留，禁止普通用户删除和更新。

### 5.6 `governance_scope` 与授权

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `id` | BIGINT | 是 | 主键 |
| `scope_type` | VARCHAR(32) | 是 | `COMPANY`、`PROJECT`、`ENVIRONMENT` |
| `scope_key` | VARCHAR(128) | 是 | 公司/项目/环境稳定标识 |
| `name` | VARCHAR(256) | 是 | 展示名称 |
| `parent_scope_id` | BIGINT | 否 | 父范围；公司无父范围 |
| `status` | VARCHAR(32) | 是 | `ACTIVE`、`DISABLED` |
| `source_system` / `source_id` | VARCHAR(64) / VARCHAR(128) | 否 | 外部组织来源 |
| `created_at` / `updated_at` | TIMESTAMP(3) WITH TIME ZONE | 是 | 时间 |

`scope_relation` 保存父子关系和生效状态。公司、项目、环境不是 SaaS tenant；范围 ID 必须稳定，授权解析按环境、项目、公司优先级执行。

### 5.7 `gate_evidence`、`release_decision` 与审批

`gate_evidence` 至少包含：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | BIGINT | 证据主键 |
| `version_digest` | CHAR(64) | 被检查的不可变版本 |
| `evidence_type` | VARCHAR(32) | `STATIC_SCAN`、`EVALUATION`、`RISK`、`REVIEW`、`GRAY_OBSERVATION` |
| `result` | VARCHAR(32) | `PASS`、`FAIL`、`INCOMPLETE`、`EXPIRED` |
| `producer_type` / `producer_id` | VARCHAR(16) / VARCHAR(128) | 人工或服务主体 |
| `evidence_uri` / `evidence_digest` | VARCHAR(1024) / CHAR(64) | 报告定位和内容摘要 |
| `conditions` | JSONB | 运行时、模型、案例、样本和环境条件 |
| `generated_at` / `expires_at` | TIMESTAMP(3) WITH TIME ZONE | 生成和失效时间 |

`release_decision` 至少包含 `version_digest`、目标范围、`policy_version`、决策状态（`BLOCKED`、`PENDING_APPROVAL`、`APPROVED`、`REJECTED`、`REVOKED`）、命中规则、阻断原因、请求幂等键和决策时间。`release_decision_evidence` 通过 `decision_id + evidence_id` 关联全部证据。

`approval_record` 至少包含 `decision_id`、申请主体、审批主体、审批结果、意见、审批时间和主体类型。数据库和 Service 层都必须拒绝同一人工主体同时作为申请人和审批人；自动发布不得写入人工审批记录。

### 5.8 `retention_policy_version`

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `id` | BIGINT | 是 | 主键 |
| `scope_type` / `scope_id` | VARCHAR(32) / BIGINT | 是 | 策略范围 |
| `data_class` | VARCHAR(64) | 是 | 制品、版本、报告、审计、原始事件或聚合指标 |
| `retention_type` | VARCHAR(16) | 是 | `PERMANENT` 或 `DURATION` |
| `retention_seconds` | BIGINT | 否 | 时长策略；永久时为空 |
| `policy_version` | VARCHAR(64) | 是 | 不可变策略版本 |
| `effective_at` | TIMESTAMP(3) WITH TIME ZONE | 是 | 生效时间 |
| `approved_by` | VARCHAR(128) | 是 | 治理审批主体 |

缩短保留期必须产生新版本和审计记录。策略执行器按范围和数据类型解析有效策略；永久数据不产生普通删除任务。

### 5.9 跨 Feature 关联索引

安装、观测和评测表必须包含下列稳定关联字段或等价字段：

| 领域 | 必须字段 |
| --- | --- |
| 安装实例 | `asset_id`、`version_digest`、`runtime_key`、`scope_id`、实例状态 |
| Tracker | Tracker 标识、Tracker 版本、安装实例、运行时和健康状态 |
| 评测运行 | `version_digest`、评测版本、运行时、模型、参数摘要、环境、开始/结束时间 |
| Finding/候选 | 版本摘要、证据 ID、来源类型、严重程度、基线版本和候选版本 |
| 运行事件索引 | `event_id`、`trace_id`、`invocation_id`、`version_digest` 或未知原因、Tracker 版本 |

这些跨 Feature 表的外键默认是逻辑关联，消费方必须校验资产/版本存在和状态；关联缺失时标记 unknown，不绑定最新版本。

## 6. 迁移、索引与恢复

```text
backend/src/main/resources/db/migration/
├─ V1__create_governance_scope.sql
├─ V2__create_skill_asset_version.sql
├─ V3__create_release_gate_policy.sql
├─ V4__create_authorization_audit.sql
├─ V5__create_cross_feature_metadata.sql
└─ R__rebuild_catalog_projection.sql
```

- Flyway 迁移脚本不可修改；破坏性变更必须拆成兼容迁移、数据迁移和清理迁移。
- 外键、范围、状态、版本摘要和时间字段按查询模式建立索引；新增索引必须提供执行计划或查询证据。
- 目录、审计和门禁列表必须分页，深度分页使用游标或基于时间/ID 的 seek 分页。
- 生产账户按读写、迁移、审计查询和策略执行分离最小权限。
- 备份、RPO/RTO 和灾备方式待确认；恢复后必须校验版本摘要、active binding、策略版本和审计连续性。

## 7. 数据安全与保留

- SQL 使用参数化查询，禁止拼接外部输入和 `SELECT *`。
- 数据库连接和密码通过环境变量或配置中心注入，不进入仓库。
- 制品、报告、审计和运行事件按组织安全策略加密；敏感内容进入日志/分析存储前脱敏。
- Skill 制品、不可变版本、评测报告、发布决策和审计数据永久保留。
- 原始运行事件默认保留 365 天；聚合指标永久保留。
- 调整或缩短保留期必须创建新的 `retention_policy_version`，由治理管理员执行并写入 `audit_log`。
- 不提供普通用户临时删除单条数据的接口；删除/归档任务必须使用受限执行主体并记录批次和结果。

## 8. 验证清单与待确认项

- 资产、版本、发布 binding、门禁证据和审计之间能通过 ID、摘要和决策 ID 完整关联。
- 版本、策略、决策和审计不能被普通更新或删除；重复发布和重复事件不重复记账。
- 同一范围不会产生两个当前版本；缺失版本关联不会绑定 `latest`。
- 迁移可在空库执行，恢复演练不会覆盖历史版本和永久数据。
- 待确认：PostgreSQL 15 JDBC 驱动/Flyway/MyBatis-Plus 准确版本、分析存储、对象存储、容量增长、RPO/RTO、分区分表、身份 ID 和正式 Java 包名。

本规范由 `product-development` 维护。数据库选型、核心表、字段语义、索引策略或保留规则发生变化时，必须升级规范版本并同步检查架构、Feature 设计、实施计划和迁移脚本。
