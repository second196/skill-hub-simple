---
title: Skill 运行观测与证据设计
description: SkillHub CLI 运行事件采集、OTLP 接收、PostgreSQL 存储、双视图和指标聚合技术设计
audience:
  - product-development
owner: product-development
status: confirmed
lastReviewed: 2026-09-03
sourceType: manual
---

# 方案：Skill 运行观测与证据

> 设计版本：v1.1；对应 `requirement.md` v2。v1 是已确认并已实施到 Task 4 转换器的基线；本版增加已确认的 CR-026 本地 Collector 真实入口和生命周期修正。首期存储继续采用 PostgreSQL 15 + Redis Streams。

## 1. 目标与非目标

### 1.1 目标

建立从目标 Agent 到 SkillHub 控制台的运行证据链：

```text
Agent 插件/Hook/OTLP -> CLI 标准化/脱敏 -> 本地 spool
-> OTLP 批量上报 -> PostgreSQL 15 追加式事件
-> Outbox/Redis Streams -> Trace/Skill 调用/指标聚合
-> Vue 双视图、比较和平台管道状态
```

同一原始事件必须同时支持 AgentTrace 和 SkillInvocation，所有指标能回溯到时间范围、范围权限、运行时、Tracker 和不可变 Skill 版本。网络中断、重复上传、字段缺失、无法归因和脱敏动作都必须可见。

### 1.2 非目标

- 不执行 Agent 业务任务，不替代目标 Agent runtime、通用日志或 APM。
- 不根据 Skill 名称、当前安装或 `latest` 推测实际运行版本。
- 不默认采集完整 transcript、prompt、代码、终端输出或文件正文。
- 不执行评测 Runner、形成发布结论或直接触发版本发布。
- 首期不依赖 ClickHouse、Kafka、S3、OAuth2、统一单点登录或微服务拆分。

## 2. 需求依据与版本

| 依据 | 版本/范围 | 用途 |
| --- | --- | --- |
| `docs/product-development/features/feature-skill-runtime-observability/requirement.md` | v2 confirmed | 十二条运行观测和 CLI 需求 |
| `docs/product-development/work-items/work-skill-hub-platform/decomposition.md` | confirmed / CR-022 | Feature 边界、需求归属和默认值 |
| `docs/product-development/work-items/work-skill-hub-platform/index.md` | CR-022 | CLI、版本摘要和跨 Feature 契约 |
| `docs/research/skill-hub-research.md` | 1.2、4.1、5.1、6.1、6.3、6.4、7.1-7.3 | 事件、归因、双视图、指标和隐私依据 |
| `D:/program/witty-skill-insight/scripts/agent-trace-collectors/` | 本地参考源码 | 插件/Hook、OTLP、spool、checkpoint、脱敏和补报证据 |

本设计和实施计划版本均为 v1 confirmed。运行观测依赖安装恢复 Feature 提供运行时适配器安装状态和 Tracker 版本，消费资产治理 Feature 的 `version_digest` 和保留策略。

## 3. 架构和实施基线

| 类型 | 文档 | 版本/状态 | 约束 |
| --- | --- | --- | --- |
| 后端架构 | `docs/product-development/architecture/backend-architecture.md` | v0.5-draft | JDK 8 模块化单体；遥测接收、存储、聚合和查询属于后端；主机采集属于 CLI |
| 前端架构 | `docs/product-development/architecture/frontend-architecture.md` | v0.3-draft | Vue 3、TypeScript、Vue Router、Pinia；页面不推测指标或权限 |
| 实施规范入口 | `docs/product-development/standards/implementation/index.md` | v0.4-draft | Java、Vue、Node.js CLI、TypeScript、数据库和分层测试 |
| 数据库规范 | `docs/product-development/standards/implementation/database-design.md` | v0.6-draft | PostgreSQL 15 分区事件、全局幂等、Outbox、投影、聚合和保留 |
| Java 规范 | `docs/product-development/standards/implementation/java-best-practices.md` | active / 嵩山版 | 分层、异常、事务、日志、命名和资源边界 |
| Vue 组件规范 | `docs/product-development/standards/implementation/component-standard.md` | active | 页面/组件职责、显式状态、分页和可访问性 |
| TypeScript 规范 | `docs/product-development/standards/implementation/typescript-best-practices.md` | active | 外部输入先校验、精确类型、异步失败语义和最小公共导出 |

已确认：原始事件和聚合结果首期权威存储为 PostgreSQL 15；Redis Streams 只传递异步聚合通知；原始事件默认保留 365 天，CLI spool 默认保留 7 天，聚合指标默认永久。容量、备份、RPO/RTO 和切换 ClickHouse 的阈值仍待确认。

## 4. 源码现状和影响范围

### 4.1 已有事实

- `RuntimeEvidenceContract` 当前只校验 SHA-256 `version_digest`，没有遥测 Controller、事件存储、聚合服务或查询 API。
- `ApiTokenService` 和 `ApiTokenScopeFilter` 尚未实现 `telemetry:write`，CR-022 资产治理 Task 33 负责补齐公共 Token 契约。
- `application.yml` 已有 PostgreSQL 和 Redis 配置；现有 Outbox Dispatcher 可参考，但当前只处理治理/安装事件。
- 安装域已存在 `InstallationObservabilityEventPublisher`、安装事件幂等和运行时矩阵，可提供安装/切换/回退/撤回上下文。
- 当前仓库没有 `cli/`；资产治理和安装恢复增量将先建立 CLI 骨架、凭据和适配器安装框架。
- Witty 的 `trace-transport.cjs` 已实现稳定事件 ID、JSONL spool、文件锁、checkpoint、100 条/512 KiB 批次、7 天保留、脱敏、2000 字符截断和有界重试，可作为行为参考。

### 4.2 影响范围

| 层 | 新增/修改范围 |
| --- | --- |
| CLI | 规范事件模型、运行时 Collector、脱敏、spool、checkpoint、批量上传和状态输出 |
| 后端 | OTLP 接收、归一化、幂等、PostgreSQL 事件存储、Outbox、聚合、查询、保留和平台指标 |
| 数据库 | Flyway V14、月分区原始事件、批次/去重/投影/聚合/Outbox/checkpoint 表 |
| 前端 | AgentTrace、SkillInvocation、指标、比较有效性和平台管道页面 |
| 跨 Feature | 读取安装/Tracker/版本上下文，向评测和发布门禁提供带条件的证据，不接管其状态 |

## 5. 方案概览

```text
Codex Hook/OTel、IDE 扩展、Claude Code OTLP
                   |
          CanonicalRuntimeEvent
                   |
      redact -> append JSONL -> checkpoint
                   |
      Bearer telemetry:write / OTLP HTTP JSON
                   |
         TelemetryIngestController
                   |
  validate scope -> normalize -> PostgreSQL transaction
        | batch | dedup | runtime_event | outbox
        +-------------------+------------------+
                            |
             Redis Stream aggregate wake-up
                            |
      TraceProjection / InvocationProjection / MetricAggregate
                            |
               Query API -> Vue 中文控制台
```

关键一致性原则：服务端只有在 PostgreSQL 批次、去重记录、原始事件和聚合 Outbox 同时提交后才确认受理。Redis 发布失败不会撤销受理；Dispatcher 后续重试。聚合器收到重复/乱序消息时按批次和 checkpoint 幂等重算受影响窗口。

## 6. 模块和职责边界

| 模块 | 负责 | 不负责 |
| --- | --- | --- |
| CLI `collectors` | 从受支持运行时提取真实事件并保留缺失字段 | 推断运行时未提供的信息 |
| CLI `normalization` | 统一事件类型、稳定 ID、时间、父子关系和属性白名单 | 服务端版本/权限决定 |
| CLI `privacy` | 上传前脱敏、拒绝和截断 | 保存原始敏感副本 |
| CLI `spool` | JSONL、锁、checkpoint、批次、补报和本地丢弃统计 | 标记服务端未确认事件为成功 |
| 后端 `telemetry.ingest` | Bearer/范围校验、OTLP 解析、二次脱敏、幂等事务和批次结果 | 运行时采集和本地配置 |
| 后端 `telemetry.attribution` | 校验明确版本/Tracker 关联，记录未知原因 | 使用 `latest` 推断 |
| 后端 `telemetry.projection` | 从原始事件重建 Trace 和调用投影 | 修改原始事件 |
| 后端 `telemetry.metric` | 可重算指标、条件和比较有效性 | 无真值时命名业务准确率 |
| 后端 `telemetry.retention` | 按已生效策略清理到期分区和去重记录 | 删除永久聚合/审计数据 |
| 前端 `runtime-observability` | 查询、筛选、双向导航、定义和状态展示 | 客户端重算权威指标 |

## 7. 目录落位

```text
backend/src/main/java/com/km/skillhub/telemetry/
├─ controller/
├─ service/
├─ domain/
├─ mapper/
├─ model/{dto,entity,query,vo}/
├─ store/
└─ job/
backend/src/test/java/com/km/skillhub/{telemetry,integration}/

cli/src/telemetry/
├─ model/
├─ collectors/{codex,editor,claude}/
├─ normalization/
├─ privacy/
├─ spool/
└─ upload/

frontend/src/
├─ modules/runtime-observability/{api,components,services,types}/
└─ pages/runtime-observability/
```

`TelemetryEventStore` 是服务层与 PostgreSQL Mapper 之间的存储边界，首期只有 `PostgresTelemetryEventStore`。未来引入 ClickHouse 必须保持接收结果、幂等、查询语义和保留审计兼容，并经过独立设计，不在本期预实现双写。

## 8. 接口、类型和数据结构

### 8.1 CLI 标准事件

```text
CanonicalRuntimeEvent:
  schemaVersion, eventId, eventType, occurredAt
  scopeId, runtimeKey, runtimeVersion, trackerVersion
  sessionId, traceId, spanId, parentSpanId, sequence
  agentId?, parentAgentId?, model?, tool?, mcpServer?
  skillName?, versionDigest?, invocationId?, triggerType?
  status, durationMs?, inputTokens?, outputTokens?, cost?
  attributes, missingFields[], privacyActions[]
```

`eventId` 由运行时稳定 ID优先；缺失时使用运行时、Session、Span、事件类型和序列的规范化 SHA-256 派生，不包含 prompt、Token 或本地路径。`versionDigest` 只有在适配器获得明确实际版本时写入；否则写 `missingFields`。

### 8.2 接收和查询 API

```text
POST /api/v1/telemetry/otlp/v1/traces
POST /api/v1/telemetry/otlp/v1/logs
GET  /api/v1/telemetry/ingest-batches/{requestId}
GET  /api/v1/observability/traces
GET  /api/v1/observability/traces/{traceId}
GET  /api/v1/observability/invocations
GET  /api/v1/observability/invocations/{invocationId}
GET  /api/v1/observability/metrics
POST /api/v1/observability/comparisons
GET  /api/v1/observability/platform
```

写接口要求 `Authorization: Bearer` 和 `telemetry:write`，同时校验 Token 主体对 `scopeId` 的授权。查询接口支持 Session，复用范围 RBAC。所有列表必须携带时间范围并使用 `(occurredAt,eventId)` seek 游标。

`TelemetryIngestResult` 返回 `requestId`、`batchId`、`accepted`、`duplicate`、`rejected`、`status` 和按稳定错误码聚合的拒绝原因。默认每批最多 100 条或 512 KiB；服务端硬限制默认 1000 条或 5 MiB，均可配置。超过硬限制返回 413，不读取或记录正文。

### 8.3 PostgreSQL 15 模型

Flyway `V14__create_runtime_observability.sql` 创建 `telemetry_ingest_batch`、`runtime_event_dedup`、按 UTC 月分区的 `runtime_event`、`agent_trace`、`skill_invocation`、`metric_aggregate`、`telemetry_aggregation_outbox` 和 `telemetry_aggregation_checkpoint`。字段和不变量遵循 database v0.6-draft。

原始事件只追加。`runtime_event_dedup.event_id` 提供跨分区全局幂等；默认至少保留 372 天。Trace/调用/指标是可重建投影，可按聚合定义版本重算。分区至少预建当前月和后续两个月；无匹配分区时接收失败并产生平台告警，不使用无界默认分区。

## 9. 数据流和状态变化

### 9.1 客户端采集与补报

1. Collector 捕获运行时事件，立即转换为标准事件并执行字段白名单、脱敏和截断。
2. 事件追加到 `skillhub/telemetry/<runtime>/<token-hash>/<UTC-date>/events.jsonl`；写入失败只记录本地丢弃计数，不影响 Agent 主流程。
3. 单实例上传器持有锁，从 checkpoint 字节位置读取完整 JSONL 行，按 100 条/512 KiB 组批。
4. 2xx 结果中的 accepted/duplicate/permanent-rejected 均形成终态；只有连续终态记录才能推进 checkpoint。429、5xx 和网络错误不推进位置。
5. 断裂尾行等待后续完整写入；7 天到期或默认 512 MiB 容量触发清理时记录 `DROPPED_RETENTION` 或 `DROPPED_CAPACITY`，不得显示为已上报。

### 9.2 服务端接收事务

```text
认证/范围/大小 -> OTLP 结构解析 -> 属性白名单/二次脱敏
-> 版本和 Tracker 归因 -> 批次/去重/事件/Outbox 同事务提交
-> 返回接收结果 -> Dispatcher 发布 Redis 通知
```

相同 `requestId + payloadDigest` 返回原批次；同一请求 ID 不同摘要返回 `IDEMPOTENCY_CONFLICT`。事件 ID 已存在计为 duplicate，不重复插入或聚合。单条语义错误可产生 PARTIAL 批次，其余有效事件仍提交；整个 JSON/OTLP 结构非法则 400 且不创建事件。

### 9.3 归因与双视图

`RuntimeAttributionService` 只接受事件明确携带并能通过格式/资产存在性校验的 `versionDigest`，同时保存运行时、Tracker 版本和安装实例引用。缺失、格式错误、版本不存在或运行时不匹配分别写入未知原因；事件仍保留。

聚合器按 `traceId` 构建 AgentTrace 树，按 `invocationId` 或 Skill 调用 Span 构建 SkillInvocation。两者保存彼此 ID 和原始事件时间范围：Trace 详情能定位所有调用，调用详情能回到 Trace/父 Span。缺失父 Span 时保留孤立标志，不虚构节点。

### 9.4 指标聚合和比较

默认聚合窗口为 5 分钟，查询可按小时、天和自定义窗口重组。每个聚合保存维度摘要、定义版本、样本数、分子、分母、缺失数和完整性状态，覆盖调用量、成功/错误、判定通过率、判定覆盖率、Token、成本、延迟和触发率。

准确率只有存在业务回执或脚本真值时才返回；只有 LLM Judge 时名称为“判定通过率”。比较 API 固定或展示版本、运行时、模型参数摘要、工具环境摘要、任务集、判定器和样本数；默认每组至少 30 个有效样本。条件不一致、样本不足或缺少真值时返回 `comparable=false` 和中文原因，不计算误导性差值。

## 10. 异常、超时、重试和部分失败

| 场景 | 处理 |
| --- | --- |
| CLI 写盘失败/容量满 | 不阻塞 Agent；记录丢弃计数和原因，status 显示数据缺口 |
| 网络、429、5xx | 指数退避加抖动，单次 flush 最多重试 4 次；checkpoint 不推进 |
| 400/401/403/413 | 不盲目重试；保留或隔离记录，提示配置、凭据、作用域或大小问题 |
| 单条事件非法 | 批次 PARTIAL，返回稳定错误码；合法事件提交，永久拒绝项写本地 reject journal |
| PostgreSQL 事务失败 | 整批未受理并可重试，不返回成功 |
| Redis 不可用 | Outbox 保持 PENDING/RETRY；已受理事件可查询，聚合延迟明确显示 |
| 聚合重复/崩溃 | 读取 PostgreSQL 并重算受影响窗口，成功后推进 checkpoint |
| 迟到事件 | 在可配置迟到窗口内重开聚合窗口；超出时保留原始事件并标记聚合延迟 |
| 分区缺失 | 拒绝接收并告警，先补建分区再重试 |
| 版本无法归因 | 保留事件和 unknown 原因，不绑定最新版本 |
| 保留任务失败 | 不删除分区，记录失败和重试；缩短策略必须审计 |

## 11. 权限、安全、性能和兼容性

- 浏览器账户密码 Session/CSRF 与 CLI Bearer 分离；Bearer 失败为 401，作用域或范围不足为 403。
- Token 原文、Authorization、Cookie、本地绝对路径和敏感文本不进入 PostgreSQL、Redis、日志或前端响应。
- CLI 先脱敏，服务端再执行白名单和二次脱敏；默认拒绝 transcript/prompt/code，允许文本最长 2000 字符。
- PostgreSQL 查询必须带范围和时间，事件分区按月；写入使用批量参数化 SQL，不逐条往返。
- Redis 消息只含 batch/outbox ID、时间范围和 schema version，不含运行正文。
- Schema 使用 `schemaVersion` 向后兼容；未知字段忽略并记录，未知必需版本拒绝。ClickHouse 迁移不得改变外部 API 和事件 ID。

## 12. 观测、灰度、迁移和回滚

### 12.1 平台自身观测

Micrometer/Actuator 指标至少包含接收请求、接受/重复/拒绝数、事务延迟、Outbox 积压、Redis 发布失败、聚合延迟、checkpoint 落后、未知版本率、脱敏/截断/丢弃数、分区维护和查询延迟。平台管道指标使用独立命名空间和页面，不混入用户 Agent 指标。

### 12.2 灰度和迁移

先对测试范围启用单一 Codex CLI 适配器，验证事件、归因、补报和数据最小化，再按运行时矩阵逐项启用。V14 只新增表和分区，不修改 V1-V13；上线前执行空库和存量库迁移、分区创建、重放和保留演练。

### 12.3 回滚

可按层关闭 Collector、接收入口、Redis Dispatcher、聚合器或前端路由。关闭聚合不会删除原始事件；恢复后从 checkpoint 重放。应用回滚保留 V14 表，使用后续兼容迁移修正。客户端回滚保留未确认 spool 和 checkpoint，不把待上报数据标记为成功。

## 13. 备选方案与取舍

| 方案 | 优点 | 风险/成本 | 结论 |
| --- | --- | --- | --- |
| PostgreSQL 15 + Redis Streams | 复用现有基础设施、事务幂等清晰、首期运维成本低 | 极高事件量下分区和聚合压力增加 | 采用，用户已确认 |
| PostgreSQL + ClickHouse | 分析性能和压缩更强 | 新增双存储、一致性、备份和运维复杂度 | 达到容量阈值后再设计 |
| Redis Streams 直接作为事件库 | 写入简单 | 保留、查询、审计和恢复不满足权威存储要求 | 不采用 |
| 只接 OTLP，不设本地 spool | 客户端简单 | 网络中断丢失且无法补报 | 不采用 |
| 客户端或前端直接算指标 | 服务端改动少 | 定义不一致、无法审计和复现 | 不采用 |

## 14. 可行性证据

- 本机 PostgreSQL 15 和现有 Flyway/MyBatis 路径已被资产与安装 Feature 使用；分区、JSONB、部分索引和事务能力满足首期模型。
- 现有 Redis Streams Outbox 提供可复用模式；当前环境曾出现 Redis 不可用，正好验证必须保持 PostgreSQL 权威和积压可见。
- Witty 参考实现提供稳定 ID、脱敏、JSONL、锁、checkpoint、批次和重试的可运行证据，但不能直接证明本项目服务端契约和所有运行时版本可用。
- 未验证：真实事件吞吐、365 天容量、备份/RPO/RTO、生产运行时版本和 ClickHouse 切换阈值；实施必须通过负载样本与主机 PoC 补证，不能把设计推断写成通过。

## 15. 可测试性与验证策略

| 层级 | 场景 | 可观察结果 |
| --- | --- | --- |
| CLI 单元 | 稳定 ID、脱敏、截断、JSONL、断裂尾行、checkpoint、清理 | 敏感内容不落盘/上传；失败不推进 checkpoint |
| Collector 契约 | Codex Hook/OTel、IDE、Claude OTLP 样本 | 字段缺失显式；父子关系和事件类型稳定 |
| Java 单元 | OTLP 解析、归因、比较有效性、聚合定义 | 不推断版本；不可比结果有原因 |
| PostgreSQL 集成 | V14、分区、批次幂等、事件去重、重算、保留 | 重试不重复；无分区失败；到期清理可审计 |
| Redis 集成 | Outbox 发布、重复通知、积压和恢复 | Redis 故障不丢原始事件；恢复后聚合 |
| 安全 | 401/403、范围越权、密钥/路径/文本命中 | 无敏感正文持久化或回显 |
| 前端 | 双向导航、筛选、指标定义、不可比和管道失败 | 中文状态准确，不把平台失败混入 Agent 指标 |
| 端到端 | Agent 样本 -> spool -> OTLP -> PG -> Redis -> 页面 | 事件、Trace、调用、版本、Tracker 和指标可追溯 |

## 16. 需求覆盖矩阵

| requirement-<semantic-name> | 方案响应 | 计划任务 | 验证方式 | 状态 |
| --- | --- | --- | --- | --- |
| `requirement-agent-runtime-event-collection` | 多运行时 Collector + 受鉴权本地入口 + 标准事件 + 缺失字段 | Task 3、Task 4A、Task 4B、Task 4C | Collector 契约、真实入口和端到端测试 | covered |
| `requirement-skill-invocation-version-attribution` | 明确摘要校验、unknown 原因和 Tracker 关联 | Task 5 | 归因单元/集成测试 | covered |
| `requirement-tracker-buffered-upload` | JSONL spool、checkpoint、状态和服务端幂等 | Task 3、Task 10 | 网络故障/补报测试 | covered |
| `requirement-trace-dual-view` | 同源 AgentTrace/SkillInvocation 投影和双向 ID | Task 5、Task 8 | 投影和页面导航测试 | covered |
| `requirement-skill-observability-metrics` | 条件化指标、分子/分母和定义版本 | Task 6、Task 7、Task 8 | 聚合重算和查询测试 | covered |
| `requirement-observability-comparison-validity` | 条件/样本校验与 `comparable=false` | Task 7、Task 8 | 比较规则和页面测试 | covered |
| `requirement-runtime-data-minimization` | 客户端预处理 + 服务端二次防线 | Task 2、Task 3、Task 9 | 敏感样本安全测试 | covered |
| `requirement-platform-observability` | 独立 Micrometer 指标和管道页面 | Task 9 | Actuator 和页面测试 | covered |
| `requirement-cli-runtime-collection` | Codex/IDE/Claude Collector、本地服务和 VSIX 事件发送 | Task 4A、Task 4B、Task 4C；安装 Task 14-16 | 运行时 fixture、本地 HTTP 契约和主机 PoC | covered |
| `requirement-cli-runtime-upload` | OTLP Bearer、批次结果和查询 | Task 2、Task 3 | HTTP 契约/鉴权测试 | covered |
| `requirement-cli-runtime-buffer-recovery` | 本地锁、checkpoint、Collector 进程恢复、退避、补报和去重 | Task 3、Task 4B、Task 4C、Task 10 | 离线/重启/重复测试 | covered |
| `requirement-cli-runtime-data-minimization` | 本地入口白名单、默认拒绝敏感正文、凭据/路径脱敏和 2000 字符限制 | Task 3、Task 4A、Task 4B、Task 4C、Task 9 | 客户端/服务端双层测试 | covered |

## 17. 风险、待确认事项和不覆盖项

### 17.1 风险和待确认

- 365 天原始事件在真实吞吐下的容量、索引和备份成本未知；首期必须压测并监控分区增长。
- 备份、RPO/RTO、灾备和 ClickHouse 切换阈值待运维确认；不影响当前功能设计，但阻止生产发布结论。
- 最终 Codex、编辑器和 Claude Code 支持版本必须逐项验证；能力缺失应显示，不扩大默认承诺。
- 费用取决于运行时提供的单价或成本值；缺少可靠价格时只展示 Token，不自行估算成本。

### 17.2 不覆盖

- 不采集或展示用户明确排除的完整 transcript/prompt/code 默认内容。
- 不将判定通过率命名为业务准确率，不在条件不一致时给出直接比较结论。
- 不实现跨产品 APM、评测 Runner、自动发布、ClickHouse 双写或远程主机控制。

## 18. CR-026 增量设计：本地 Collector 真实入口

### 18.1 缺陷边界和组件所有权

Task 4 已完成的运行时转换器保留为 Task 4A，不回退、不改写其验证结果。CR-026 补充 Task 4B 本地 Collector 服务和 Task 4C 真实入口验证；安装恢复 Feature 负责将本地服务、密钥和运行时配置安装到目标主机，并让 VSIX 发送真实事件。

```text
Codex Hook -------- POST /hook ---------+
Codex/Claude OTLP - POST /v1/logs ------+-> LocalCollectorServer
VS Code/Cursor/    POST /ide-event -----+      | route/source/schema/size/auth
Windsurf VSIX                              | CollectorRegistry -> privacy
                                           +-> Task 3 JsonlSpool
                                                    |
                                      skillhub telemetry flush -> SkillHub API
```

`LocalCollectorServer` 只负责本机接收、上下文装配、转换、脱敏和写 spool。它不得读取 CLI API Token，不得访问远程 SkillHub、PostgreSQL 或 Redis，也不得执行安装、发布或任意 Shell。远程上报继续由 Task 3 上传器显式执行。

### 18.2 本地文件和进程契约

本地受管目录为 `<user-home>/.skillhub/collector/`：

| 文件 | 内容 | 约束 |
| --- | --- | --- |
| `collector.json` | schema 版本、固定 host/port、请求上限、spool 定位和运行时映射摘要 | 不含 API Token、本地密钥、原始事件或绝对路径回显 |
| `collector.secret` | 至少 256 bit 随机本地入口密钥 | 首次安装创建；仅当前用户可读写；轮换必须原子更新消费者配置 |
| `collector.pid` | PID、启动随机标识、CLI/Collector 版本和启动时间 | 停止前同时校验进程身份和 `/health`，不得仅凭 PID 结束进程 |
| `collector.lock` | 单实例启动锁 | 崩溃后可识别陈旧锁并恢复，不允许两个进程同时占用 spool |

CLI 公共动作沿用现有 `telemetry <action>` 入口：

```text
skillhub telemetry collector-start [--json]
skillhub telemetry collector-stop [--json]
skillhub telemetry collector-status [--json]
```

`telemetry install` 和 `telemetry repair` 在写入运行时配置前保证 Collector 可启动并通过健康检查；`telemetry status` 仍保持严格只读，只报告进程、端口、鉴权配置和 spool 状态，不启动、停止、修复或补报。进程异常退出后，显式 `collector-start`、`install` 或 `repair` 清理陈旧 PID/锁并恢复；首期不假定 Windows 服务、systemd、登录自启或企业软件分发机制。

服务只绑定 IPv4 回环地址 `127.0.0.1:43191`，不监听 `0.0.0.0`、IPv6 任意地址或局域网接口。端口已占用时先调用最小健康握手：只有进程标识、协议版本和本地密钥均匹配时才接管为已运行；其他占用返回 `COLLECTOR_PORT_IN_USE`，不得结束未知进程或自动换端口导致已安装配置失联。

### 18.3 本地 HTTP 路由

| 方法与路径 | 来源 | 鉴权与输入 | 成功结果 |
| --- | --- | --- | --- |
| `GET /health` | CLI 诊断 | 不接收正文；只返回状态、协议/进程版本和启动标识摘要 | `200`，不返回路径、密钥、事件或 Token |
| `POST /hook` | Codex Hook | `X-SkillHub-Collector-Key`；单个 JSON 对象；固定来源 `codex-cli/hook` | 事件成功追加 spool 后 `202` |
| `POST /v1/logs` | Codex 或 Claude Code OTLP HTTP/JSON | 本地密钥头、固定 `X-SkillHub-Runtime-Key`；仅 OTLP JSON Logs | 有效事件追加后 `202` |
| `POST /ide-event` | VS Code/Cursor/Windsurf VSIX | 本地密钥头、固定编辑器 runtime key；仅允许结构化事件数组或对象 | 有效事件追加后 `202` |

默认正文上限为 1 MiB，可在本地受管配置中下调或调整；服务在累计字节超过上限时立即终止读取并返回 `413`，不得解析、记录或回显正文。未知路由返回 `404`，鉴权失败返回 `401`，非法 JSON/OTLP 返回 `400`，不支持的来源、版本或事件类型返回 `422`，spool 不可写返回 `503`。日志只记录稳定错误码、来源、字节数、事件数和请求关联 ID。

本地密钥使用常量时间比较。Hook 和 VSIX 从受限密钥文件读取密钥，不把密钥编译进脚本或 VSIX；Claude Code OTLP 受运行时限制，可在受管 `OTEL_EXPORTER_OTLP_HEADERS` 中保存本地 Collector 密钥，但该值必须受当前用户文件权限保护，且不得进入 CLI 输出、`runtime-integrations.json`、事件 spool、服务端登记或日志。SkillHub API Token 仍禁止进入任何 Agent 配置。

### 18.4 上下文、转换和数据最小化

请求只能声明受支持的来源，`scopeId`、实际运行时版本、适配器版本和 Tracker 上下文由 Collector 读取安装恢复 Feature 的受管状态并装配；正文中的同名字段不具有授权或归因效力。找不到登记上下文时保留允许的事件并写入明确 `missingFields`，不得绑定默认范围、当前版本或 `latest`。

路由先验证大小、鉴权、Content-Type、来源和结构，再调用 Task 4A `CollectorRegistry`，随后复用 Task 3 的标准化、敏感字段过滤、路径脱敏、文本截断和 `JsonlSpool`。允许字段固定为 Session/Trace/Span/父 Span、事件类型、状态、时间、耗时、Token 数值、模型/工具/MCP 标识和明确 Skill 摘要；默认拒绝原始 prompt、transcript、代码、系统提示词、工具输入输出、终端正文、文件正文和绝对路径。

同一运行时事件必须生成稳定 `eventId`。运行时重试可在本地形成重复 JSONL 行，但服务端全局去重必须保证不重复记账；Collector 不用内存推测或覆盖历史事件。只有全部目标标准事件成功追加 spool 才返回 `202`；转换或写盘部分失败时整次请求返回失败，已追加项保留并依靠稳定 ID 去重。

### 18.5 VSIX、Hook 和 OTLP 行为

- Codex Hook 读取标准输入最多 1 MiB，使用 150 ms 有界本地请求；无论连接拒绝、超时、401、413、422 或 503，Hook 自身都以 0 退出，不改变 Codex 结果。
- Codex/Claude OTLP 使用 HTTP/JSON `/v1/logs`；安装适配器写入本地 endpoint、runtime key 和本地鉴权头，不写远程 Bearer Token。
- VSIX 不再周期性只请求 `/status`。扩展订阅目标编辑器真实可获得的会话/Agent、工具、文件修改和终端生命周期事件，转换为无正文的结构化 envelope 后发送 `/ide-event`；运行时不暴露的能力必须标记缺失，不通过命令文本、文件内容或窗口标题推测。
- VSIX 发送采用短超时、有界队列和丢弃计数；Collector 不可用时不阻塞保存、终端或 Agent 操作。扩展停用时释放监听器、定时器和队列，不在后台无限重试。

### 18.6 启停、恢复、重复和回滚

启动状态机为 `STOPPED -> STARTING -> HEALTHY`，异常分支为 `PORT_CONFLICT`、`AUTH_MISMATCH`、`STALE_PROCESS` 或 `FAILED`。启动成功必须在有界时间内完成带密钥健康握手并写入 PID 身份；超时则清理本次启动的进程和临时状态。停止命令只结束身份匹配的受管进程并等待端口释放，失败时返回人工处理状态。

安装/修复顺序固定为“生成或读取本地密钥 -> 启动并验证 Collector -> 原子写入 Hook/OTLP/VSIX 配置 -> 发送无敏感数据的探针事件 -> 保存安装状态”。任一步失败时按摘要保护恢复本次受管配置；已有可用 Collector 不因单个适配器失败而停止。密钥轮换必须先准备新配置，再短窗口兼容新旧密钥，全部消费者验证后撤销旧密钥；轮换失败恢复旧密钥和配置。

关闭或回滚时先禁用对应运行时入口，再停止 Collector；未上传 spool、checkpoint 和 reject journal 保留。回滚不得删除 V14 数据、服务端事件或其他运行时配置。旧 VSIX/Hook 不具备本地鉴权时必须显示不兼容并要求修复，不允许为兼容而开放无鉴权入口。

### 18.7 可测试性和跨 Feature 顺序

自动化必须覆盖回环绑定、非回环拒绝、四个路由、1 MiB 边界、鉴权、坏 JSON/OTLP、来源伪造、受管上下文、稳定 ID、敏感正文拒绝、spool 失败、陈旧 PID、未知端口占用、进程重启、重复请求、VSIX 事件发送和 Agent 退出码隔离。真实主机 PoC 必须实际触发 Codex Hook 和 VS Code 事件并观察 spool 增长；fixture 转换测试不能替代该证据，Claude Code/Cursor/Windsurf 不存在时逐项标记 `unavailable`。

实施顺序固定为：运行观测 Task 4A（已完成）-> Task 4B -> 安装恢复 Task 14 -> Task 15 -> Task 16 -> 运行观测 Task 4C -> Task 5-10。该顺序只修正真实入口，不改变 PostgreSQL 15、Redis Streams、浏览器 Session、CLI Bearer Token、远程 OTLP 上传或既有安装状态契约。
