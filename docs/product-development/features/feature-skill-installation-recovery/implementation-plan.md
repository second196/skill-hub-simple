---
title: Skill 获取安装与失败回退实施计划
description: Skill 安装域、运行时适配器契约、失败恢复和 Vue 控制台的可执行实施计划
audience:
  - product-development
owner: product-development
status: active
lastReviewed: 2026-09-02
sourceType: manual
---

# 实施计划：Skill 获取安装与失败回退

> 计划版本：v1，与 `design.md v1` 和 `requirement.md v1` 对齐。用户已确认需求输入、技术边界、方案和实施计划，可按任务顺序执行。

## 1. 实施约束

- 遵循 `docs/product-development/architecture/backend-architecture.md v0.4-draft`、`frontend-architecture.md v0.3-draft`、`standards/implementation/index.md v0.3-draft`、`java-best-practices.md`、`component-standard.md`、`typescript-best-practices.md` 和 `database-design.md v0.5-draft`。
- 后端使用 JDK 8、Spring Boot 2.7.18、Spring MVC 5.3.31、Spring Security 5.7.11、MyBatis-Plus 3.5.5、PostgreSQL 15、Flyway 8.5.13、Redis Streams 6.2.14。
- 控制面只编排和记录；实际安装、Tracker 配置、健康检查和本地回退由运行时适配器执行。
- 每个 Task 必须保留 `version_digest`、`operation_id`、`request_id`、`event_id` 和审计关联；出现需求、公共契约或权限变化时停止并回到设计确认。

## 2. Slice 与任务

### Task 1：创建安装域数据库模型和状态枚举

- Slice：`slice-installation-persistence-baseline`
- 需求：`requirement-skill-installation-instance`、`requirement-skill-version-switching`
- 文件：创建 `backend/src/main/resources/db/migration/V8__create_installation_recovery_metadata.sql`；创建 `backend/src/main/java/com/km/skillhub/installation/model/entity/InstallationInstanceEntity.java`、`TrackerBindingEntity.java`、`InstallationOperationEntity.java`、`InstallationOperationEventEntity.java`；创建 `backend/src/main/java/com/km/skillhub/installation/model/InstallationState.java`、`OperationType.java`、`FailureStage.java`；创建对应 `mapper/` 接口和 `backend/src/test/java/com/km/skillhub/mapper/InstallationMapperTest.java`。
- 目标：创建 `installation_instance`、`tracker_binding`、`installation_operation` 以及事件幂等记录；建立当前实例唯一约束、版本摘要索引、操作查询索引和事件 `event_id + sequence` 约束。
- 实现细节：使用 PostgreSQL 15 `BIGINT identity`、`TIMESTAMP(3) WITH TIME ZONE`、`JSONB` 和 `row_version`；不对资产/版本复制内容，保存逻辑关联；历史操作不可物理删除。
- 验证：`mvn -f backend/pom.xml -Dtest=InstallationMapperTest test`；使用 PostgreSQL 15 空库执行 Flyway，预期迁移成功且同一目标不会出现两个当前实例。
- 停止条件：迁移不能空库执行、事件无法去重、不同摘要被合并或删除规则绕过时停止。
- 回滚：停止应用后回退未发布的 V8 迁移文件；若已进入共享环境，按兼容迁移新增修复，不修改已执行脚本、不删除安装历史。

### Task 2：实现安装请求、发布校验和安装实例查询

- Slice：`slice-installation-request-orchestration`
- 需求：`requirement-skill-distribution-installation`、`requirement-skill-installation-instance`
- 文件：创建 `backend/src/main/java/com/km/skillhub/installation/controller/InstallationController.java`、`RuntimeMatrixController.java`；创建 `installation/service/InstallationOrchestrationService.java`、`RuntimeMatrixService.java`；创建 `installation/mapper/InstallationInstanceMapper.java`、`InstallationOperationMapper.java`；创建 `model/dto/InstallationRequest.java`、`model/vo/InstallationOperationVO.java`、`InstallationInstanceVO.java`、`model/query/InstallationQuery.java`；复用 `ReleaseScopeService`、`LifecycleService` 和 `AuthorizationService` 的公开服务边界；测试 `InstallationOrchestrationServiceTest.java`、`InstallationControllerIntegrationTest.java`。
- 目标：校验 Session、角色、范围、`PUBLISHED` 绑定、撤回状态、运行时启用状态和能力矩阵；对 `X-Request-Id` 幂等返回原操作；创建实例和操作后返回明确状态。
- 验证：`mvn -f backend/pom.xml -Dtest=InstallationOrchestrationServiceTest,InstallationControllerIntegrationTest test`；预期未发布版本、范围不匹配、Claude Code OTLP 安装能力不支持时阻断并返回稳定错误码。
- 停止条件：使用 `latest` 补齐版本、越权创建操作、重复请求生成多个操作或错误暴露内部路径时停止。
- 回滚：关闭安装 API 入口并恢复上一构建；保留已创建操作和审计，不删除历史。

### Task 3：实现运行时矩阵、制品访问和 Redis 命令契约

- Slice：`slice-runtime-adapter-command-contract`
- 需求：`requirement-skill-distribution-installation`、`requirement-skill-tracker-companion-installation`
- 文件：创建 `backend/src/main/java/com/km/skillhub/runtime/service/RuntimeCapabilityService.java`、`runtime/model/RuntimeCapability.java`、`runtime/model/RuntimeDefinitionVO.java`；创建 `backend/src/main/java/com/km/skillhub/integration/runtime/RuntimeInstaller.java`、`RuntimeInstallerRegistry.java`、`InstallationCommand.java`、`InstallationEvent.java`；创建 `integration/artifact/ArtifactAccessResolver.java`、`integration/event/InstallationCommandPublisher.java`；扩展 `GovernanceEventOutboxMapper` 或新增安装 Outbox Mapper；测试 `RuntimeCapabilityServiceTest.java`、`InstallationEventContractTest.java`。
- 目标：把 Codex CLI、VS Code、Cursor、Windsurf、Claude Code OTLP 和 Bash/PowerShell 作为可配置矩阵；控制面只生成带版本摘要的命令；命令和回执使用固定事件信封。
- 依赖：对象存储访问产品、适配器认证和通信协议未确认，本 Task 先实现接口、协议校验和参考适配器，不实现远程执行。
- 验证：`mvn -f backend/pom.xml -Dtest=RuntimeCapabilityServiceTest,InstallationEventContractTest test`；预期能力不匹配阻断，缺少版本摘要或非法序列被拒绝。
- 停止条件：命令包含长期凭据、允许任意脚本参数、事件使用 `latest` 或无法关联操作时停止。
- 回滚：禁用未验证运行时矩阵项和命令消费者；不删除已记录的能力配置和事件。

### Task 4：实现 Skill/Tracker 分阶段安装和健康确认

- Slice：`slice-skill-tracker-installation-verification`
- 需求：`requirement-skill-tracker-companion-installation`、`requirement-skill-version-switching`
- 文件：创建 `backend/src/main/java/com/km/skillhub/installation/domain/InstallationStateMachine.java`、`InstallationEligibilityPolicy.java`、`installation/service/InstallationProgressService.java`、`TrackerBindingService.java`、`installation/controller/InstallationEventController.java`；运行时适配器侧文件位置和语言待协议确认；测试 `InstallationStateMachineTest.java`、`TrackerBindingServiceTest.java`、`InstallationProgressIntegrationTest.java`。
- 目标：按下载、Skill 安装、Tracker 安装、配置和健康检查推进状态；Skill/Tracker 分别留痕；只有二者均就绪且健康确认通过才写总体健康。
- 验证：`mvn -f backend/pom.xml -Dtest=InstallationStateMachineTest,TrackerBindingServiceTest,InstallationProgressIntegrationTest test`；预期 Tracker 失败显示不完整，确认前旧版本仍为当前版本。
- 停止条件：部分安装被标记为健康、回执乱序覆盖新状态或未校验制品即可进入切换时停止。
- 回滚：停用状态推进消费者，保留最后一个一致状态；适配器按操作 ID执行补偿或人工处理。

### Task 5：实现版本切换、失败恢复和回退

- Slice：`slice-version-switch-and-recovery`
- 需求：`requirement-skill-version-switching`、`requirement-skill-installation-failure-recovery`
- 文件：创建 `backend/src/main/java/com/km/skillhub/installation/service/VersionSwitchService.java`、`InstallationRecoveryService.java`、`installation/domain/RecoveryDecision.java`；创建 `backend/src/test/java/com/km/skillhub/service/VersionSwitchServiceTest.java`、`InstallationRecoveryServiceTest.java`、`InstallationRecoveryIntegrationTest.java`。
- 目标：实现准备/确认/提交切换；下载、安装、配置、健康确认、停用旧版本和启用新版本失败时保持或恢复旧版本；无旧版本时进入 `REQUIRES_MANUAL`。
- 实现细节：本地事务只提交控制面状态；远程动作采用补偿命令；每个阶段保存失败阶段、错误码、脱敏原因、前后摘要和回退状态；重复回执返回原结果。
- 验证：`mvn -f backend/pom.xml -Dtest=VersionSwitchServiceTest,InstallationRecoveryServiceTest,InstallationRecoveryIntegrationTest test`；覆盖首次失败、旧版本恢复、回退失败、重复请求、乱序回执和并发切换。
- 停止条件：新版本在未确认时覆盖旧版本、回退失败仍显示成功或错误原因不可查询时停止。
- 回滚：暂停新版本切换命令；对未完成操作发送回退/人工处理指令；保留全部操作和审计记录。

### Task 6：实现离线事件补报、Outbox 重试和运行观测契约

- Slice：`slice-installation-event-replay-contract`
- 需求：`requirement-skill-installation-failure-recovery`
- 文件：创建 `backend/src/main/java/com/km/skillhub/integration/runtime/InstallationEventConsumer.java`、`InstallationEventDeduplicationService.java`、`InstallationObservabilityEventPublisher.java`；扩展 `RedisStreamsOutboxDispatcher.java` 的安装命令流和失败重试状态；创建 `backend/src/test/java/com/km/skillhub/integration/InstallationEventReplayIntegrationTest.java`、`RedisInstallationContractTest.java`。
- 目标：控制面按事件 ID 和操作序列幂等处理；适配器离线回执可补报；安装成功、切换、回退、健康变化和撤回事件向运行观测 Feature 输出明确版本摘要。
- 验证：`mvn -f backend/pom.xml -Dtest=InstallationEventReplayIntegrationTest,RedisInstallationContractTest test`；真实 Redis 不可用时记录环境失败，不得标记集成通过。
- 停止条件：重复事件重复记账、旧事件覆盖新状态、Outbox 标记发布但无失败恢复或下游收到未绑定版本时停止。
- 回滚：暂停事件消费者和下游发布；保留待处理事件，恢复上一兼容消费者版本。

### Task 7：实现紧急撤回和逐实例结果

- Slice：`slice-emergency-revocation`
- 需求：`requirement-skill-emergency-revocation`
- 文件：创建 `backend/src/main/java/com/km/skillhub/installation/service/EmergencyRevocationService.java`、`installation/model/dto/RevocationRequest.java`、`installation/model/vo/RevocationResultVO.java`；创建 `backend/src/test/java/com/km/skillhub/service/EmergencyRevocationServiceTest.java`、`EmergencyRevocationIntegrationTest.java`。
- 目标：管理员按范围撤回；先阻断新增安装，再逐实例发出撤回命令；返回成功、失败和未执行实例，部分失败不得聚合为成功。
- 验证：`mvn -f backend/pom.xml -Dtest=EmergencyRevocationServiceTest,EmergencyRevocationIntegrationTest test`；覆盖越权、重复撤回、跨范围、实例停用失败和撤回后新增安装阻断。
- 停止条件：撤回后仍能创建新安装、越权跨范围操作或部分失败显示为全部成功时停止。
- 回滚：暂停撤回消费者并人工复核未完成实例；不自动恢复被撤回版本，不删除审计。

### Task 8：实现 Vue 安装控制台和前后端契约测试

- Slice：`slice-installation-console`
- 需求：六条安装域需求的用户可观察部分。
- 文件：创建 `frontend/src/modules/installation-recovery/api/installationApi.ts`、`types/installation.ts`、`services/installationDisplayText.ts`、`components/InstallationStatusPanel.vue`、`components/TrackerStatusPanel.vue`、`components/OperationTimeline.vue`；创建 `frontend/src/pages/installation-recovery/InstallationCatalogPage.vue`、`InstallationDetailPage.vue`；更新 `frontend/src/router/index.ts`；创建 `frontend/tests/unit/installationDisplayText.test.ts`、`frontend/tests/integration/installation-console.test.ts`。
- 目标：展示安装实例、Skill/Tracker 分状态、阶段进度、失败原因、回退状态、人工处理和逐实例撤回结果；所有用户可见文案使用中文；前端不复制后端权限和状态机规则。
- 验证：`npm --prefix frontend run test`、`npm --prefix frontend run build`；预期加载、空数据、无权限、失败、部分成功和人工处理均有明确界面状态。
- 停止条件：把部分失败显示为整体成功、在前端推断当前版本、显示英文用户文案或绕过后端授权时停止。
- 回滚：恢复上一前端构建并隐藏未具备后端能力的路由入口；保留后端操作记录。

## 3. 顺序、依赖和出口

执行顺序为 Task 1 -> Task 2 -> Task 3 -> Task 4 -> Task 5 -> Task 6 -> Task 7 -> Task 8。Task 3、Task 4 需要运行时适配器协议事实；Task 6 需要 Redis Streams 环境或协议级替代验证；Task 8 依赖稳定 API 和状态枚举。

每个 Slice 完成前必须满足：需求 ID、设计响应和任务一致；正常/异常/重复场景有测试；PostgreSQL/Redis 不可用时如实记录；`version_digest`、操作 ID、事件 ID 和审计 ID 可追踪；`git diff --check` 和对应命令通过。任何公共 API、权限、持久化、运行时协议或需求范围变化都必须停止并回到设计确认。

## 4. 风险与未执行项

- 当前没有真实运行时安装代理源码，不能在本计划确认前承诺 Codex CLI、编辑器扩展或 Bash/PowerShell 的主机级安装成功。
- 适配器语言、通信认证、命令签名、制品访问、超时/重试、离线队列容量、备份和 RPO/RTO 待确认。
- 数据库规范 v0.5-draft 只提供安装表最低关联要求；Task 1 必须先完成字段和事件去重约束评审，必要时升级数据库规范增量版本。
- 本文只定义实现计划，当前不自动启动实现、不创建运行时代理、不执行真实数据迁移、不提交或发布。
