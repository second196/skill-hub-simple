---
title: Skill 运行观测与证据实施计划
description: 按纵向 Slice 实现 CLI 采集、OTLP 接收、运行事件存储、双视图和指标聚合
audience:
  - product-development
owner: product-development
status: confirmed
lastReviewed: 2026-09-03
sourceType: manual
---

# 实施计划：Skill 运行观测与证据

> 计划版本：v1.4，与 `design.md v1.1` 和 `requirement.md v2` 对齐。v1.3 已实施到 Task 4A 转换器；本版增加已确认的 CR-026 Task 4B/4C，并保持 PostgreSQL 15 + Redis Streams、Task 1 Mapper 扫描和 Task 3/4 验证命令基线不变。

## 1. 实施约束

- 实施顺序遵循 Work Item 契约：先完成资产治理 CR-022 Task 33-37，再完成安装恢复 CR-022 Task 9-13，最后执行本计划 Task 1-10。
- 后端遵循 `docs/product-development/architecture/backend-architecture.md` v0.5-draft、`docs/product-development/standards/implementation/index.md` v0.4-draft、`java-best-practices.md` 和《阿里巴巴 Java 开发手册》嵩山版。
- 数据库遵循 `database-design.md` v0.6-draft：PostgreSQL 15 是运行事件、批次、投影、聚合和 checkpoint 的权威存储；Redis Streams 只传递聚合通知，消费者必须回读 PostgreSQL。
- CLI 遵循 Node.js 20、TypeScript 5.4、npm、`cac`、`zod` 和结构化输入校验基线；外部输入先按 `unknown` 校验，凭据、spool 和 checkpoint 使用受限权限、文件锁与原子替换。
- Vue 页面遵循 `frontend-architecture.md` v0.3-draft、`component-standard.md` 和 TypeScript 两份规范；前端不重算权威指标、不推断版本归属，所有用户可见功能文案使用中文。
- 原始运行事件默认保留 365 天，`runtime_event_dedup` 默认至少保留 372 天，CLI spool 默认保留 7 天，聚合指标默认永久；策略调整必须版本化、授权并审计。
- 容量、备份、RPO/RTO、灾备、生产运行时版本和 ClickHouse 切换阈值未确认，实施只能产出测量证据，不能自行写成生产承诺。

## 2. 事实到文件映射

| 责任 | 现有入口 | 计划落位 |
| --- | --- | --- |
| Bearer 认证和作用域 | `token/security/ApiTokenScopeFilter.java`、`token/service/ApiTokenService.java` | 扩展遥测写路径和 `telemetry:write` 契约，不改变浏览器 Session/CSRF |
| PostgreSQL/Flyway | `backend/src/main/resources/db/migration/V1-V11`；CR-022 预留 V12/V13 | `V14__create_runtime_observability.sql` |
| Redis Streams Outbox | `integration/event/RedisStreamsOutboxDispatcher.java` | 独立遥测 Outbox Mapper、Dispatcher 和聚合消费者 |
| 版本与安装上下文 | `RuntimeEvidenceContract.java`、安装域服务与 V13 接入元数据 | `RuntimeAttributionService` 只校验明确关联，不绑定 `latest` |
| CLI 基线 | 资产治理 Task 33 创建 `cli/`；安装恢复 Task 10-12 创建适配器框架 | `cli/src/telemetry` Collector、脱敏、spool、checkpoint 和上传器 |
| Vue 路由和应用壳 | `frontend/src/router/index.ts`、`frontend/src/components/AppShell.vue` | `runtime-observability` 模块、页面、路由和中文面包屑 |
| 验证入口 | `backend/pom.xml`、`frontend/package.json`；CLI 由资产 Task 33 建立 | Maven、Vitest、CLI 测试、构建、PostgreSQL/Redis 集成和端到端契约 |

## 3. 实施任务

### Task 1：建立 V14 运行观测权威数据模型

Slice：`slice-runtime-event-authoritative-store`

需求：`requirement-agent-runtime-event-collection`、`requirement-tracker-buffered-upload`、`requirement-cli-runtime-buffer-recovery`

依赖：资产治理 Task 35 已占用 V12；安装恢复 Task 9 已占用 V13

文件：Create `backend/src/main/resources/db/migration/V14__create_runtime_observability.sql`、`backend/src/main/java/com/km/skillhub/telemetry/model/entity/TelemetryIngestBatchEntity.java`、`RuntimeEventEntity.java`、`AgentTraceEntity.java`、`SkillInvocationEntity.java`、`MetricAggregateEntity.java`、`TelemetryAggregationOutboxEntity.java`、`TelemetryAggregationCheckpointEntity.java`、`telemetry/mapper/TelemetryIngestBatchMapper.java`、`RuntimeEventMapper.java`、`AgentTraceMapper.java`、`SkillInvocationMapper.java`、`MetricAggregateMapper.java`、`TelemetryAggregationOutboxMapper.java`、`TelemetryAggregationCheckpointMapper.java`、`telemetry/store/TelemetryEventStore.java`、`PostgresTelemetryEventStore.java`；Modify `backend/src/main/java/com/km/skillhub/SkillHubApplication.java`；Test `backend/src/test/java/com/km/skillhub/integration/RuntimeObservabilityMigrationIntegrationTest.java`、`RuntimeEventStoreIntegrationTest.java`。

目标：用 PostgreSQL 15 持久化批次、全局事件去重、月分区原始事件、Trace/调用投影、指标、聚合 Outbox 和 checkpoint，并固定 V12/V13/V14 无冲突迁移顺序。

实现：先写迁移失败测试；`runtime_event` 按 UTC 月 RANGE 分区并预建当前月和后续两个月，`runtime_event_dedup(event_id)` 提供跨分区全局幂等；批次、去重、事件和聚合 Outbox 由 `PostgresTelemetryEventStore.appendBatch` 在同一事务写入；在根启动类现有显式 `@MapperScan` 中增加 `com.km.skillhub.telemetry.mapper`，确保生产运行和集成测试使用同一 Mapper 装配路径；所有查询字段、约束和索引严格采用 database v0.6-draft，不创建默认无界分区。

测试/验证类型：migration、integration、database。

测试场景：空库 V1-V14、存量 V1-V13 升级、重复 event ID、缺失分区、事务中途失败、Trace/Skill/范围时间索引和 UTC 边界月份。

测试文件或替代证据：上述 PostgreSQL 集成测试、Flyway schema history 和索引定义查询结果。

验证：JDK 8 下 `mvn -f backend/pom.xml -Dtest=RuntimeObservabilityMigrationIntegrationTest,RuntimeEventStoreIntegrationTest test`；预期 V14 成功、重复事件只保留一条、事务失败无部分批次、无分区写入明确失败。

停止条件：V12/V13/V14 版本冲突、分区表无法满足全局幂等、事务产生部分提交、查询索引缺少范围或时间前缀时停止。

回滚：关闭遥测写入口并回退应用；保留 V14 表和已接收数据，使用后续兼容迁移修正，不删除或修改已执行迁移。

### Task 2：实现 OTLP JSON 接收、鉴权和受理结果

Slice：`slice-otlp-secure-ingestion`

需求：`requirement-agent-runtime-event-collection`、`requirement-tracker-buffered-upload`、`requirement-runtime-data-minimization`、`requirement-cli-runtime-upload`

依赖：Task 1；资产治理 Task 33 的 `telemetry:write` 作用域基线

文件：Create `backend/src/main/java/com/km/skillhub/telemetry/controller/TelemetryIngestController.java`、`TelemetryIngestBatchController.java`、`telemetry/service/TelemetryIngestService.java`、`OtlpJsonNormalizationService.java`、`ServerPrivacyPolicy.java`、`telemetry/model/dto/OtlpTraceRequest.java`、`OtlpLogRequest.java`、`telemetry/model/vo/TelemetryIngestResultVO.java`、`TelemetryIngestBatchVO.java`、`telemetry/domain/TelemetryErrorCode.java`；Modify `backend/src/main/java/com/km/skillhub/token/security/ApiTokenScopeFilter.java`、`backend/src/main/resources/application.yml`；Test `backend/src/test/java/com/km/skillhub/telemetry/TelemetryIngestServiceTest.java`、`backend/src/test/java/com/km/skillhub/integration/TelemetryIngestSecurityIntegrationTest.java`、`TelemetryIngestContractIntegrationTest.java`。

目标：以 `telemetry:write` Bearer Token 接收 `/api/v1/telemetry/otlp/v1/traces|logs` 的 OTLP HTTP JSON 批次，并返回可查询的 accepted、duplicate、rejected 和稳定错误码。

实现：Controller 只处理媒体类型、请求大小、参数和响应；Service 校验 Token 主体的 `scopeId`、`requestId + payloadDigest` 幂等、结构和 schema 版本，执行属性白名单及二次脱敏后调用 `appendBatch`；默认客户端批次 100 条/512 KiB，服务端硬限制 1000 条/5 MiB，超过限制在读取正文前返回 413；结构非法整批 400，单条语义非法允许 PARTIAL；不记录 Authorization、Cookie 或载荷正文。

测试/验证类型：unit、integration、contract、security。

测试场景：有效 Trace/Log、重复请求、同请求不同摘要、单条非法、整体非法、401、缺少作用域 403、范围越权、账号停用、413、敏感字段和 PostgreSQL 事务失败。

测试文件或替代证据：上述 Java 测试、MockMvc 响应快照和 PostgreSQL 批次记录。

验证：JDK 8 下 `mvn -f backend/pom.xml -Dtest=TelemetryIngestServiceTest,TelemetryIngestSecurityIntegrationTest,TelemetryIngestContractIntegrationTest test`；预期认证/范围状态码稳定，重复上传不重复记账，敏感正文不入库和不回显。

停止条件：Bearer 绕过范围校验、浏览器 Session/CSRF 行为变化、服务端信任客户端脱敏结果、413 后仍解析正文或失败事务返回成功时停止。

回滚：关闭遥测接收路由并恢复 Scope Filter 路由表；保留已受理批次和 V14 数据，不撤销 `telemetry:write` 的其他已确认用途。

### Task 3：实现 CLI 标准事件、脱敏、spool 和补报上传

Slice：`slice-cli-durable-telemetry-upload`

需求：`requirement-tracker-buffered-upload`、`requirement-runtime-data-minimization`、`requirement-cli-runtime-upload`、`requirement-cli-runtime-buffer-recovery`、`requirement-cli-runtime-data-minimization`

依赖：Task 2；资产治理 Task 33 CLI 凭据；安装恢复 Task 10 的本地安全框架

文件：Create `cli/src/telemetry/model/canonical-runtime-event.ts`、`cli/src/telemetry/normalization/runtime-event-normalizer.ts`、`cli/src/telemetry/privacy/runtime-event-redactor.ts`、`cli/src/telemetry/spool/jsonl-spool.ts`、`spool-lock.ts`、`checkpoint-store.ts`、`reject-journal.ts`、`retention-cleaner.ts`、`cli/src/telemetry/upload/telemetry-uploader.ts`、`retry-policy.ts`、`cli/src/clients/otlp-client.ts`；Modify `cli/src/commands/telemetry.ts`、`cli/src/index.ts`；Test `cli/test/unit/telemetry/runtime-event-normalizer.test.ts`、`runtime-event-redactor.test.ts`、`jsonl-spool.test.ts`、`checkpoint-store.test.ts`、`cli/test/integration/telemetry-upload.test.ts`。

目标：事件在上传前完成稳定 ID、白名单、脱敏和截断，先落本地 JSONL，再按 checkpoint 批量补报；只有连续终态记录才能推进 checkpoint。

实现：稳定 ID 优先使用运行时 ID，否则由运行时、Session、Span、事件类型和序列计算 SHA-256；默认拒绝 transcript/prompt/code，脱敏凭据和 Windows/UNC/macOS/Linux 本地路径，允许文本最长 2000 字符；按 100 条/512 KiB 组批，网络/429/5xx 最多重试 4 次且不推进 checkpoint，400/401/403/413 写入明确失败或 reject journal；断裂尾行等待补全，7 天/512 MiB 清理记录丢弃原因。

测试/验证类型：unit、integration、security、build。

测试场景：稳定 ID、敏感词大小写、路径变体、超长文本、断裂尾行、锁竞争、进程重启、accepted/duplicate/rejected 混合结果、429/5xx、401/403、容量和保留清理。

测试文件或替代证据：上述 CLI 测试，全部使用临时用户目录、假时钟和 Mock HTTP 服务，夹具不含真实凭据。

验证：`npm --prefix cli test`、`npm --prefix cli run build`；预期敏感内容不落盘/上传，临时失败不推进 checkpoint，重复补报由服务端计为 duplicate。

停止条件：采集失败改变 Agent 退出码、Token 写入 spool/日志、checkpoint 越过未终态事件、文件锁可被并发写绕过或保留清理显示为成功时停止。

回滚：停用上传器和 Collector，保留未确认 spool/checkpoint；仅由显式清理命令按策略处理本地数据，不自动删除待补报事件。

### Task 4：实现 Codex、编辑器和 Claude Code Collector

Slice：`slice-supported-runtime-collection`

需求：`requirement-agent-runtime-event-collection`、`requirement-cli-runtime-collection`、`requirement-cli-runtime-data-minimization`

依赖：Task 3；安装恢复 Task 11-12 已安装并登记对应适配器

文件：Create `cli/src/telemetry/collectors/collector.ts`、`collector-registry.ts`、`codex/codex-hook-collector.ts`、`codex/codex-otel-log-collector.ts`、`editor/editor-extension-collector.ts`、`claude/claude-otlp-collector.ts`、`cli/test/fixtures/telemetry/{codex,editor,claude}/`、`cli/test/unit/telemetry/collectors/codex-collector.test.ts`、`editor-collector.test.ts`、`claude-collector.test.ts`、`cli/test/integration/runtime-collector-contract.test.ts`。

目标：首期 Codex CLI、VS Code/Cursor/Windsurf 扩展和 Claude Code OTLP 的真实样本转换为统一事件，保留 Session/Trace/Span/父子关系、状态、时间和明确的能力缺失。

实现：每个 Collector 声明运行时版本范围和 capability；只读取适配器真实提供字段，缺失字段写入 `missingFields`；可识别 Skill 调用时携带名称、触发方式和明确版本摘要，否则记录归因缺失；Collector 只调用 Task 3 的标准化/脱敏/spool 接口，不直接发网或写数据库。

测试/验证类型：unit、contract、integration、manual。

测试场景：正常会话、子 Agent、模型、工具、MCP、Skill 调用、父 Span 缺失、运行时字段缺失、不支持版本、坏事件、敏感内容以及采集器异常不影响 Agent。

测试文件或替代证据：上述固定 fixture 和 Collector 契约测试；真实 Codex/编辑器/Claude Code 主机验证分别记录，环境缺失时标记 unavailable。

验证：`npm --prefix cli test`、`npm --prefix cli run build`；可用主机再执行一次目标 Agent 任务并运行 `skillhub telemetry status --json`，预期标准事件可补报且缺失字段不被虚构。

停止条件：任一 Collector 推测版本/父子关系、读取默认禁止正文、运行时不支持却显示健康或采集异常阻塞目标 Agent 时停止。

回滚：从适配器矩阵禁用有问题的 Collector 并恢复上一适配器资源；保留已落盘标准事件及能力缺失状态。

### Task 5：实现版本归因和 AgentTrace/SkillInvocation 双视图

Slice：`slice-trace-invocation-attribution`

需求：`requirement-skill-invocation-version-attribution`、`requirement-trace-dual-view`、`requirement-agent-runtime-event-collection`

依赖：Task 1、Task 2、Task 4

文件：Create `backend/src/main/java/com/km/skillhub/telemetry/service/RuntimeAttributionService.java`、`TraceProjectionService.java`、`SkillInvocationProjectionService.java`、`ObservabilityQueryService.java`、`telemetry/controller/AgentTraceController.java`、`SkillInvocationController.java`、`telemetry/model/query/AgentTraceQuery.java`、`SkillInvocationQuery.java`、`telemetry/model/vo/AgentTraceSummaryVO.java`、`AgentTraceDetailVO.java`、`SkillInvocationSummaryVO.java`、`SkillInvocationDetailVO.java`、`telemetry/domain/AttributionStatus.java`、`AttributionFailureReason.java`；Modify `backend/src/main/java/com/km/skillhub/integration/downstream/RuntimeEvidenceContract.java`；Test `backend/src/test/java/com/km/skillhub/telemetry/RuntimeAttributionServiceTest.java`、`TraceProjectionServiceTest.java`、`SkillInvocationProjectionServiceTest.java`、`backend/src/test/java/com/km/skillhub/integration/ObservabilityQueryIntegrationTest.java`。

目标：同一原始事件流生成可重建的 AgentTrace 和 SkillInvocation 投影，并通过 traceId、invocationId、父 Span 和原始事件时间范围双向定位。

实现：归因只接受事件明确携带且通过 SHA-256、资产存在、运行时兼容和 Tracker 上下文校验的 `versionDigest`；缺失、格式非法、版本不存在、运行时不匹配分别保存 unknown 原因；投影按事件时间和 sequence 构树，缺失父节点保存孤立标志；查询必须校验范围、时间并使用 seek 游标。

测试/验证类型：unit、integration、contract、security。

测试场景：完整 Trace、孤立 Span、乱序/迟到事件、重复聚合、明确版本、未知版本四类原因、跨范围查询、调用到 Trace 和 Trace 到调用双向导航。

测试文件或替代证据：上述 Java 测试和 PostgreSQL 投影查询结果。

验证：JDK 8 下 `mvn -f backend/pom.xml -Dtest=RuntimeAttributionServiceTest,TraceProjectionServiceTest,SkillInvocationProjectionServiceTest,ObservabilityQueryIntegrationTest test`；预期 unknown 不绑定最新版本，双向 ID 和范围授权一致。

停止条件：任何分支使用 `latest` 补齐、缺失父 Span 生成虚假节点、投影修改原始事件或越权查询泄露对象信息时停止。

回滚：关闭投影消费者和查询路由；保留原始事件，修复后从 PostgreSQL 按 checkpoint 重建投影。

### Task 6：实现 Redis 聚合通知、幂等消费和 checkpoint

Slice：`slice-replayable-aggregation-pipeline`

需求：`requirement-tracker-buffered-upload`、`requirement-trace-dual-view`、`requirement-skill-observability-metrics`

依赖：Task 1、Task 2、Task 5

文件：Create `backend/src/main/java/com/km/skillhub/telemetry/service/TelemetryAggregationOutboxDispatcher.java`、`TelemetryAggregationConsumer.java`、`AggregationCheckpointService.java`、`AggregationWindowService.java`；Modify `backend/src/main/resources/application.yml` 增加遥测 Stream、消费者组、重试和迟到窗口配置；Test `backend/src/test/java/com/km/skillhub/telemetry/AggregationCheckpointServiceTest.java`、`backend/src/test/java/com/km/skillhub/integration/TelemetryAggregationOutboxIntegrationTest.java`、`TelemetryAggregationReplayIntegrationTest.java`。

目标：PostgreSQL 提交后通过 Redis Streams 唤醒聚合，重复、乱序、崩溃和 Redis 暂停均可从 Outbox/checkpoint 恢复，不丢失或重复聚合。

实现：Redis 消息只含 outbox/batch ID、schema version 和时间范围；Dispatcher 成功后标记发布，失败进入 RETRY；消费者按 batch 回读 PostgreSQL，锁定受影响 5 分钟窗口并幂等重算，提交投影/聚合后才推进 checkpoint；迟到事件在配置窗口内重开窗口，超窗只记录延迟状态。

测试/验证类型：unit、integration、recovery、Redis。

测试场景：正常发布、Redis 不可用、重复消息、乱序消息、消费中断、checkpoint 更新冲突、迟到事件和恢复后积压清空。

测试文件或替代证据：上述 Java 测试、PostgreSQL Outbox/checkpoint 记录和真实 Redis Stream 消费结果；Redis 不可达时必须标记 unavailable。

验证：JDK 8 下 `mvn -f backend/pom.xml -Dtest=AggregationCheckpointServiceTest,TelemetryAggregationOutboxIntegrationTest,TelemetryAggregationReplayIntegrationTest test`；预期 Redis 故障不影响原始事件受理，恢复后每个窗口结果唯一且 checkpoint 单调推进。

停止条件：Redis 成为查询事实源、消息包含运行正文、发布失败回滚已受理事件、checkpoint 在聚合提交前推进或重复通知产生重复指标时停止。

回滚：停用 Dispatcher/消费者并保留 PENDING/RETRY Outbox；恢复后从最后成功 checkpoint 重放，不清空 Stream 代替恢复。

### Task 7：实现指标定义、聚合查询和比较有效性

Slice：`slice-observability-metrics-comparison`

需求：`requirement-skill-observability-metrics`、`requirement-observability-comparison-validity`

依赖：Task 5、Task 6

文件：Create `backend/src/main/java/com/km/skillhub/telemetry/service/MetricAggregationService.java`、`MetricQueryService.java`、`ObservabilityComparisonService.java`、`telemetry/controller/ObservabilityMetricController.java`、`ObservabilityComparisonController.java`、`telemetry/model/query/MetricQuery.java`、`ComparisonRequest.java`、`telemetry/model/vo/MetricSeriesVO.java`、`MetricDefinitionVO.java`、`ComparisonResultVO.java`、`telemetry/domain/MetricDefinition.java`、`ComparisonValidity.java`；Test `backend/src/test/java/com/km/skillhub/telemetry/MetricAggregationServiceTest.java`、`ObservabilityComparisonServiceTest.java`、`backend/src/test/java/com/km/skillhub/integration/ObservabilityMetricQueryIntegrationTest.java`。

目标：按 Skill、版本、运行时、模型、任务标签、项目、环境和时间窗口返回带定义版本、分子、分母、样本及完整性状态的指标，并拒绝误导性比较。

实现：聚合覆盖调用量、成功/错误、判定通过率、判定覆盖率、Token、成本、延迟和触发率；只有存在业务回执或脚本真值时输出“业务准确率”，仅 LLM Judge 时为“判定通过率”；比较固定或展示版本、运行时、模型参数、工具环境、任务集、判定器和样本，默认每组至少 30 个有效样本，否则返回 `comparable=false` 和中文原因。

测试/验证类型：unit、integration、contract。

测试场景：完整指标、零分母、缺少成本、缺少真值、仅 Judge、样本 29/30、条件一致/不一致、迟到数据和跨范围过滤。

测试文件或替代证据：上述 Java 测试、固定聚合夹具和 API 响应契约。

验证：JDK 8 下 `mvn -f backend/pom.xml -Dtest=MetricAggregationServiceTest,ObservabilityComparisonServiceTest,ObservabilityMetricQueryIntegrationTest test`；预期所有指标包含范围/条件/分子/分母，不可比结果不返回差值结论。

停止条件：无真值结果标为业务准确率、零分母产生数值、条件不一致仍给比较结论、前端需要自行推导权威计算时停止。

回滚：关闭指标/比较路由并保留原始事件及聚合定义版本；修复定义后按版本重算，不覆盖旧定义证据。

### Task 8：实现 Vue 运行观测工作台

Slice：`slice-runtime-observability-console`

需求：`requirement-skill-invocation-version-attribution`、`requirement-trace-dual-view`、`requirement-skill-observability-metrics`、`requirement-observability-comparison-validity`、`requirement-platform-observability`

依赖：Task 5、Task 7

文件：Create `frontend/src/modules/runtime-observability/api/observabilityApi.ts`、`types/observability.ts`、`services/observabilityDisplayText.ts`、`components/ObservabilityFilters.vue`、`TraceTree.vue`、`InvocationTable.vue`、`MetricDefinitionPanel.vue`、`ComparisonValidityPanel.vue`、`PipelineStatusPanel.vue`、`frontend/src/pages/runtime-observability/ObservabilityWorkspacePage.vue`、`TraceDetailPage.vue`、`InvocationDetailPage.vue`；Modify `frontend/src/router/index.ts`、`frontend/src/components/AppShell.vue`、`frontend/src/components/layout/SideNavigation.vue`；Test `frontend/tests/unit/observabilityDisplayText.test.ts`、`frontend/tests/unit/observabilityApi.test.ts`、`frontend/tests/integration/runtime-observability-console.test.ts`。

目标：通过一个“运行观测”侧边栏入口和页签展示 Agent 链路、Skill 调用、运行指标、条件比较及平台管道，支持 Trace/调用双向跳转和可复现筛选。

实现：路由使用 `/observability?tab=traces|invocations|metrics|platform`，详情使用 `/observability/traces/:traceId` 和 `/observability/invocations/:invocationId`；筛选保存在 URL；页面完整呈现 loading、empty、partial、error、forbidden、unknown 和 aggregation-delayed；指标直接展示服务端定义、范围、分子/分母和可比原因，所有功能文字中文化。

测试/验证类型：frontend unit、integration、contract、build、manual。

测试场景：双向导航、未知版本、孤立 Span、时间/范围筛选、指标定义、不可比原因、聚合延迟、无权限、空列表、窄屏页签和长错误文本。

测试文件或替代证据：上述 Vue/Vitest 测试、生产构建；Playwright 可用时补充桌面/移动截图和点击流程，不可用时标记 unavailable。

验证：`npm --prefix frontend run test`、`npm --prefix frontend run build`；预期类型检查通过，中文状态准确，页面不重算指标且不会把平台管道失败混入 Agent 失败率。

停止条件：中英混合、版本未知显示为最新版本、不可比结果显示数值优劣、页面状态重叠或既有侧边栏/页签回归时停止。

回滚：隐藏运行观测导航和路由并恢复上一前端构建；后端接收与证据数据继续保留。

### Task 9：实现平台指标、分区维护和保留执行

Slice：`slice-observability-operations-retention`

需求：`requirement-runtime-data-minimization`、`requirement-platform-observability`、`requirement-tracker-buffered-upload`

依赖：Task 1、Task 2、Task 6

文件：Create `backend/src/main/java/com/km/skillhub/telemetry/service/TelemetryPlatformMetricService.java`、`telemetry/controller/TelemetryPlatformController.java`、`telemetry/job/RuntimeEventPartitionJob.java`、`RuntimeEventRetentionJob.java`、`telemetry/model/vo/TelemetryPipelineStatusVO.java`；Modify `backend/src/main/resources/application.yml`、`backend/pom.xml` 的 Micrometer/OpenTelemetry 兼容配置（仅在现有依赖不足时）；Test `backend/src/test/java/com/km/skillhub/telemetry/TelemetryPlatformMetricServiceTest.java`、`RuntimeEventRetentionJobTest.java`、`backend/src/test/java/com/km/skillhub/integration/TelemetryActuatorIntegrationTest.java`、`RuntimeEventRetentionIntegrationTest.java`。

目标：管理员能区分平台接收/队列/聚合失败与用户 Agent 运行失败；系统按有效保留策略预建分区并安全清理到期原始事件和去重记录。

实现：Micrometer 指标覆盖受理、重复、拒绝、事务延迟、Outbox 积压、Redis 失败、聚合延迟、checkpoint 落后、未知版本、脱敏/截断/丢弃、分区和查询；保留任务先写审计批次，再 detach/drop 满足 365 天策略的整分区，去重记录至少延后 7 天；缩短策略必须使用已审批版本，失败时保留数据并重试。

测试/验证类型：unit、integration、Actuator、retention、security。

测试场景：平台/Agent 失败分离、指标标签脱敏、未来分区预建、365/372 天边界、永久聚合不删除、缩短策略无审批、清理失败和重复任务。

测试文件或替代证据：上述 Java 测试、Actuator 指标样本、PostgreSQL 分区和审计记录。

验证：JDK 8 下 `mvn -f backend/pom.xml -Dtest=TelemetryPlatformMetricServiceTest,RuntimeEventRetentionJobTest,TelemetryActuatorIntegrationTest,RuntimeEventRetentionIntegrationTest test`；预期永久聚合不受影响，失败清理不丢数据，指标不含 Token/路径/运行正文。

停止条件：普通用户可触发清理、失败任务删除数据、去重早于补报窗口删除、平台指标携带敏感高基数正文或聚合指标被普通保留任务删除时停止。

回滚：禁用定时任务和平台页面入口；保留审计批次，已 detach 未 drop 的分区按记录重新 attach，已 drop 数据依赖正式备份恢复且因备份参数未确认不能宣称可恢复。

### Task 10：执行跨 Feature 端到端和容量证据验证

Slice：`slice-runtime-observability-end-to-end`

需求：本 Feature 十二条 `requirement-*`

依赖：Task 1 至 Task 9；资产治理 Task 37；安装恢复 Task 13

文件：Create `cli/test/integration/runtime-observability-flow.test.ts`、`backend/src/test/java/com/km/skillhub/integration/RuntimeObservabilityEndToEndTest.java`、`RuntimeObservabilityRecoveryIntegrationTest.java`、`frontend/tests/integration/runtime-observability-contract.test.ts`；Update `.product-development/features/feature-skill-runtime-observability/state.md` 验证记录；容量样本结果写入 Feature verification 阶段证据，不在本 Task 伪造生产阈值。

目标：串联目标 Agent fixture、Collector、脱敏、spool、OTLP、PostgreSQL、Redis 通知、投影、指标和 Vue 契约，证明断网补报、全局幂等、版本归因和双视图闭环。

实现：使用临时用户目录、测试 Token、PostgreSQL 15 和真实 Redis；模拟上传响应丢失、Redis 停止、聚合器重启、重复/乱序/迟到事件、未知版本和敏感输入；记录固定规模容量样本的事件大小、写入吞吐、索引增长和聚合延迟，结果仅作为后续容量决策输入。

测试/验证类型：e2e、contract、integration、recovery、build、performance-sample、static。

测试场景：三类运行时正常链路、离线补报、重复受理、部分拒绝、Token 越权、未知版本、Trace/调用双向定位、指标定义、不可比比较、Redis 恢复、保留边界和中文控制台。

测试文件或替代证据：上述 CLI/Java/Vue 测试，Maven Surefire、Vitest/CLI 报告、PostgreSQL/Redis 状态和容量样本记录；真实运行时缺失时对应主机 PoC 标记 unavailable。

验证：`npm --prefix cli test`、`npm --prefix cli run build`、JDK 8 下 `mvn -f backend/pom.xml test`、`npm --prefix frontend run test`、`npm --prefix frontend run build`、`git diff --check`；预期全部自动化契约通过，环境失败逐项归因，不以静态检查替代真实 Redis/PostgreSQL 证据。

停止条件：任一需求没有证据、全量测试存在未归因失败、重复补报重复计数、敏感内容出现在任一存储/日志/响应、跨 Feature 摘要不一致或容量样本被写成生产承诺时停止。

回滚：按 Task 9 至 Task 2 逆序关闭页面、任务、聚合和接收，再禁用 Collector；保留 V14、原始事件、spool、审计和 checkpoint，数据库只采用前向兼容修复。

## 4. 顺序、检查点和出口

执行顺序为资产治理 Task 33-37 -> 安装恢复 Task 9-13 -> 本 Feature Task 1-3 -> Task 4A（均已完成）-> Task 4B -> 安装恢复 Task 14-16 -> Task 4C -> 本 Feature Task 5-10。Task 8 可在 Task 7 契约稳定后与 Task 9 分别验证，但不能跳过 Task 10 的统一回归。

每个 Slice 必须记录 `validation`、需求、命令、预期、实际、状态和证据路径。公共 API、Token 权限、V14 持久化、保留策略、运行时配置或跨 Feature `version_digest` 契约发生变化时立即停止，回到设计确认。

## 5. 风险与未执行项

- PostgreSQL 365 天容量、备份窗口和恢复时间没有生产数据；Task 10 只能提供测量样本，生产发布仍需要容量、备份、RPO/RTO 和灾备确认。
- Redis 环境曾不可用；涉及 Redis 的测试失败必须归类为环境失败或实现缺陷，不能把 PostgreSQL 可查询等同于聚合已恢复。
- Codex、VS Code、Cursor、Windsurf 和 Claude Code 的最终支持版本必须逐项做主机 PoC；fixture 契约不能冒充真实主机验证。
- ClickHouse 不是首期依赖，也不预实现双写；只有达到经压测和运维评审确认的容量阈值后，才进入独立需求与设计。
- CR-026 当前只固化设计和实施计划；在双产物确认并重新进入 implementation 前，不修改本地 Listener、运行时配置或 VSIX 源码，不提交或发布。

## 6. CR-026 本地 Collector 实施增量

原 Task 4 的转换器、fixture 和契约测试记为 Task 4A，状态保持已完成。以下任务补齐原计划遗漏的真实 HTTP 入口、进程生命周期和主机证据，不重做 Task 4A。

### Task 4B：实现受鉴权本地 Collector 服务和生命周期

Slice：`slice-local-collector-ingress-lifecycle`

需求：`requirement-agent-runtime-event-collection`、`requirement-cli-runtime-collection`、`requirement-cli-runtime-buffer-recovery`、`requirement-cli-runtime-data-minimization`

依赖：Task 3、Task 4A

文件：Create `cli/src/telemetry/local-collector/local-collector-server.ts`、`collector-router.ts`、`collector-auth.ts`、`collector-process-manager.ts`、`collector-config-store.ts`、`cli/src/commands/collector.ts`；Modify `cli/src/commands/telemetry.ts`、`cli/src/index.ts`、`cli/src/telemetry/collectors/collector-registry.ts`、`cli/src/telemetry/spool/jsonl-spool.ts`；Test `cli/test/unit/telemetry/local-collector/collector-auth.test.ts`、`collector-router.test.ts`、`collector-process-manager.test.ts`、`cli/test/integration/local-collector-server.test.ts`、`collector-command.test.ts`。

目标：提供仅绑定 `127.0.0.1:43191` 的本地服务和 `collector-start`、`collector-stop`、`collector-status` 动作，将三类真实入口安全路由到 Task 4A 转换器和 Task 3 spool。

实现：配置/密钥/PID/锁保存到 `<user-home>/.skillhub/collector/`；密钥至少 256 bit、当前用户权限和常量时间比较；`/hook`、`/v1/logs`、`/ide-event` 要求本地密钥，`/health` 只返回最小存活信息；正文默认最大 1 MiB并在解析前限制；运行时上下文从受管安装状态读取；仅接受固定 Content-Type、来源、事件类型和结构化白名单。服务不创建远程客户端，不读取 API Token，只调用 CollectorRegistry、脱敏和 JsonlSpool。PID 同时保存启动标识，停止前复核身份；未知端口占用返回稳定错误，绝不结束未知进程。

测试/验证类型：unit、integration、security、process、build。

测试场景：回环绑定、非回环不可达、健康最小响应、三路由成功、无密钥/错密钥、1 MiB 临界和超限、坏 JSON/OTLP、来源伪造、未知运行时、禁止正文字段、路径/Token 脱敏、spool 不可写、稳定重复事件、陈旧 PID/锁、未知端口占用、启动超时、身份不匹配停止和进程恢复。

测试文件或替代证据：上述 CLI 测试使用临时用户目录、随机可用测试端口和子进程；固定端口 `43191` 只在主机 PoC 使用，避免自动化争用。

验证：`npm --prefix cli test`、`npm --prefix cli run build`、`git diff --check`；预期全部入口在写 spool 后才返回 `202`，敏感正文不落盘，端口/进程异常有稳定中文与 JSON 结果。

停止条件：监听非回环地址、入口可绕过鉴权、服务读取远程 API Token/访问网络、超限正文仍被解析、未知进程被结束、部分写盘返回成功或 Collector 异常改变调用方退出码时停止。

回滚：移除本地服务动作并停止身份匹配的受管进程；保留 Task 3 spool/checkpoint、Task 4A 转换器和受管配置备份，不删除任何服务端事件。

### Task 4C：执行真实入口、异常隔离和主机 PoC

Slice：`slice-real-runtime-ingress-proof`

需求：`requirement-agent-runtime-event-collection`、`requirement-cli-runtime-collection`、`requirement-cli-runtime-buffer-recovery`、`requirement-cli-runtime-data-minimization`

依赖：Task 4B；安装恢复 Task 14、Task 15、Task 16

文件：Create `cli/test/integration/real-ingress-contract.test.ts`、`docs/product-development/features/feature-skill-runtime-observability/evidence/real-ingress-poc.md`；Modify only when a failed contract proves an implementation defect in Task 4A/4B or installation Task 14-16 files.

目标：证明真实 Hook、OTLP 和 VSIX 不只是配置或 fixture，而能经过受鉴权本地入口形成标准事件并进入同一 spool，同时目标 Agent/编辑器在 Collector 故障时继续工作。

实现：自动化以子进程启动 Collector，分别发送安装产物实际生成的 Hook、OTLP 和 VSIX envelope，核对事件 ID、来源、受管上下文、privacyActions 和 spool 增量；重复发送验证稳定 ID。主机 PoC 使用当前已检测的 Codex 和 VS Code 执行一次真实操作，记录版本、命令、时间、脱敏后的事件摘要和失败隔离结果；缺失运行时逐项标记 unavailable。

测试/验证类型：integration、contract、security、manual、build。

测试场景：Codex Hook 成功/Collector 停止/错误密钥，Claude/Codex OTLP JSON，VSIX 文件修改与终端生命周期 envelope，重复请求，Collector 重启，spool 写失败，原始 prompt/代码/终端正文和绝对路径扫描。

测试文件或替代证据：`real-ingress-contract.test.ts` 和 `evidence/real-ingress-poc.md`；fixture 只能作为协议输入，不能作为真实主机通过证据。

验证：`npm --prefix cli test`、`npm --prefix cli run build`、`skillhub telemetry collector-status --json`、一次真实 Codex 任务、一次 VS Code 可观察事件及 `git diff --check`；预期 spool 出现可归因来源且无禁止正文，Collector 停止时 Agent/编辑器操作结果不变。

停止条件：任一已标记支持的可用主机入口无法形成事件、错误密钥仍受理、敏感正文落盘、重复请求生成不稳定 ID、故障影响 Agent/编辑器操作或自动化结果被当作缺失运行时的主机证据时停止。

回滚：按安装恢复 Task 16 恢复受管运行时配置，停止 Collector 并保留未上传 spool；Task 5-10 在 Task 4C 通过前不得开始。
