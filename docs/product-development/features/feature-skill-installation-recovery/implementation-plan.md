---
title: Skill 获取安装与失败回退实施计划
description: Skill 安装域、运行时适配器契约、失败恢复和 Vue 控制台的可执行实施计划
audience:
  - product-development
owner: product-development
status: confirmed
lastReviewed: 2026-09-03
sourceType: manual
---

# 实施计划：Skill 获取安装与失败回退

> 计划版本：v2.2，与 `design.md v2.1` 和 `requirement.md v2` 对齐。Task 1-8 和 CR-022 Task 9-13 的完成状态保持不变；本版增加已确认的 CR-026 Task 14-16。

## 1. 实施约束

- 遵循 `docs/product-development/architecture/backend-architecture.md` v0.5-draft、`frontend-architecture.md` v0.3-draft、`standards/implementation/index.md` v0.4-draft、`java-best-practices.md`、`component-standard.md`、`typescript-best-practices.md` 和 `database-design.md` v0.6-draft。
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
- 数据库规范 v0.6-draft 已固定 V13 运行时接入元数据的最低关联、唯一性和事件追加边界；实施时仍须通过 PostgreSQL 15 迁移与事件去重测试证明具体 DDL。
- 本文只定义实现计划，当前不自动启动实现、不创建运行时代理、不执行真实数据迁移、不提交或发布。

## 5. CR-022 CLI 运行时接入实施计划

### Task 9：创建运行时接入元数据和服务端契约

Slice：`slice-runtime-integration-control-plane`

需求：`requirement-cli-agent-integration-installation`、`requirement-cli-agent-integration-check`、`requirement-cli-agent-integration-recovery`

依赖：资产治理 Task 33 CLI 认证基线；现有安装恢复 Task 1-8

文件：Create `backend/src/main/resources/db/migration/V13__create_runtime_integration_metadata.sql`、`backend/src/main/java/com/km/skillhub/installation/model/entity/RuntimeIntegrationInstanceEntity.java`、`RuntimeIntegrationEventEntity.java`、`installation/mapper/RuntimeIntegrationInstanceMapper.java`、`RuntimeIntegrationEventMapper.java`、`installation/model/dto/RuntimeIntegrationRegistration.java`、`RuntimeIntegrationEventRequest.java`、`installation/model/vo/RuntimeIntegrationVO.java`、`installation/service/RuntimeIntegrationService.java`、`installation/controller/RuntimeIntegrationController.java`；Modify `backend/src/main/java/com/km/skillhub/token/security/ApiTokenScopeFilter.java`；Test `RuntimeIntegrationServiceTest.java`、`RuntimeIntegrationIntegrationTest.java`。

目标：CLI 可以使用 `telemetry:write` 幂等登记接入实例和事件，Vue 用户可按范围查询状态，服务端不保存本地配置正文、Token 或绝对路径。

实现：V13 对 `(scope_id,runtime_key,target_key)`、`event_id` 建唯一约束；事件按序追加，旧事件不能覆盖新状态；Bearer 写入同时校验账号启用和范围权限，Session 查询复用既有 RBAC。遵循 backend v0.5-draft、database v0.6-draft 和 Java 嵩山版规范。

测试/验证类型：migration、unit、integration、security。

测试场景：首次登记、重复事件、乱序事件、跨范围越权、账号停用、敏感字段拒绝、Session 查询和 Bearer 作用域不足。

测试文件或替代证据：上述 Java 测试和 PostgreSQL 15 空库/存量 V1-V13 迁移报告。

验证：JDK 8 下 `mvn -f backend/pom.xml -Dtest=RuntimeIntegrationServiceTest,RuntimeIntegrationIntegrationTest test`；预期唯一约束、范围授权和事件幂等通过。

停止条件：现有 `installation_instance` 不变量被放宽、Token/路径正文入库、旧事件覆盖新状态或 Flyway 版本冲突时停止。

回滚：关闭接入 API 并恢复应用；保留 V13 表和追加事件，使用后续兼容迁移修正，不删除状态历史。

### Task 10：实现 CLI 适配器注册表、原子配置和恢复框架

Slice：`slice-local-adapter-safety-framework`

需求：`requirement-cli-agent-integration-installation`、`requirement-cli-agent-integration-check`、`requirement-cli-agent-integration-recovery`

依赖：Task 9

文件：Create `cli/src/adapters/types.ts`、`adapter-registry.ts`、`cli/src/telemetry/collector-installer.ts`、`integration-health.ts`、`integration-event-spool.ts`、`cli/src/stores/runtime-integration-store.ts`、`cli/src/platform/atomic-file.ts`、`process-probe.ts`、`cli/src/commands/telemetry.ts`；Test `cli/test/unit/adapters/adapter-registry.test.ts`、`cli/test/unit/platform/atomic-file.test.ts`、`cli/test/integration/telemetry-command.test.ts`。

目标：三个 telemetry 子命令共享固定适配器协议、文件锁、快照、摘要保护、中文/JSON 输出和离线事件补报，不允许任意 Shell 动作。

实现：注册表只接受已编译 profile；计划阶段枚举准确写入目标；临时文件 fsync 后原子替换；回滚前比较本次写入摘要，检测并发修改则进入人工处理；status 不调用任何写 API；事件 spool 使用稳定 event ID 和当前用户权限。

测试/验证类型：unit、integration、security、build。

测试场景：未知运行时、重复安装、锁竞争、写入中断、用户并发修改、升级失败、快照恢复、只读 status、网络离线和输出脱敏。

测试文件或替代证据：上述 CLI 测试，全部使用临时 HOME 和假进程探测器。

验证：`npm --prefix cli test`、`npm --prefix cli run build`；预期未知运行时无副作用，status 前后文件摘要一致，失败恢复不覆盖用户并发修改。

停止条件：出现 `shell: true`、用户输入进入命令名/参数模板、状态命令产生写入或恢复覆盖未知改动时停止。

回滚：撤下 telemetry 命令和适配器注册；删除测试临时目录，用户真实状态目录只能由显式卸载/恢复命令处理。

### Task 11：实现 Codex CLI 和编辑器接入适配器

Slice：`slice-codex-runtime-integration`

需求：`requirement-cli-agent-integration-installation`、`requirement-cli-agent-integration-check`、`requirement-cli-agent-integration-recovery`

依赖：Task 10

文件：Create `cli/src/adapters/codex/codex-cli-adapter.ts`、`editor-extension-adapter.ts`、`cli/src/adapters/codex/assets/` 内 Collector 和 VSIX 构建输入、`cli/test/unit/adapters/codex-cli-adapter.test.ts`、`editor-extension-adapter.test.ts`、`cli/test/integration/codex-install-flow.test.ts`。

目标：安全合并 Codex Hook/OTel 配置，安装并校验 CLI 自带 Collector；对已安装的 VS Code、Cursor、Windsurf 执行固定 VSIX 安装并分别报告结果。

实现：使用 JSON/TOML 结构化修改和带标记受管块；摘要固定所有适配器文件；编辑器可执行文件来自固定白名单探测，参数数组内部构造且禁用 shell；Hook 信任显示 `ACTION_REQUIRED`。参考 Witty 行为但使用本产品目录、endpoint 和 Bearer header。

测试/验证类型：unit、integration、security、manual。

测试场景：空配置、已有无关配置、受管块升级、冲突 OTel、Hook 未信任、relay 不可达、一个编辑器失败、全部未安装和恢复旧块。

测试文件或替代证据：上述临时 HOME 测试；真实 Codex/编辑器验证记录为 manual，环境不存在时标记 unavailable。

验证：`npm --prefix cli test -- codex`、`npm --prefix cli run build`，随后在可用主机执行 `skillhub telemetry install --runtime codex-cli --dry-run` 和 `status`；预期不会改写无关配置，部分编辑器失败不汇总为全部成功。

停止条件：配置语法损坏、Hook 未信任却显示健康、外部 VSIX 摘要未校验或部分失败被隐藏时停止。

回滚：按安装状态恢复受管 Hook/OTel 块和旧 Collector；用户自有配置及其他扩展保持不变。

### Task 12：实现 Claude Code OTLP、诊断和修复

Slice：`slice-claude-otlp-diagnostics-repair`

需求：`requirement-cli-agent-integration-installation`、`requirement-cli-agent-integration-check`、`requirement-cli-agent-integration-recovery`

依赖：Task 10、Task 11

文件：Create `cli/src/adapters/claude/claude-code-otlp-adapter.ts`、`cli/test/unit/adapters/claude-code-otlp-adapter.test.ts`、`cli/test/integration/telemetry-repair-flow.test.ts`；Modify `cli/src/commands/telemetry.ts`、`cli/src/telemetry/integration-health.ts`。

目标：受支持 Claude Code 版本可以安全写入 CLI 管理的 OTLP 环境配置，status 区分本地配置、spool、上传器、网络和服务端鉴权，repair 只修复受管内容。

实现：结构化合并 `~/.claude/settings.json` 的受管 `env` 键；已有不同 OTLP 目的地时返回冲突并要求人工确认；服务端探测不发送业务事件；repair 执行诊断、计划、快照、应用、自检和失败恢复完整状态机。

测试/验证类型：unit、integration、security、manual。

测试场景：支持/不支持版本、坏 JSON、自定义 OTLP 冲突、凭据失效、网络不可达、spool 不可写、上传器停止、修复成功、修复失败恢复和无旧配置人工处理。

测试文件或替代证据：上述 CLI 测试；真实 Claude Code 验证环境不可用时保留配置契约证据并标记 unavailable。

验证：`npm --prefix cli test -- claude telemetry-repair`、`npm --prefix cli run build`；预期冲突不覆盖、诊断无写副作用、修复失败恢复原配置。

停止条件：覆盖用户 OTLP 配置、凭据进入 settings 正文或日志、采集异常影响 Claude Code 退出码时停止。

回滚：恢复本次受管 env 键的先前值；保留用户其它 settings 和未上报事件 spool。

### Task 13：补充 Vue 接入状态并执行纵向验证

Slice：`slice-runtime-integration-console-contract`

需求：`requirement-cli-agent-integration-installation`、`requirement-cli-agent-integration-check`、`requirement-cli-agent-integration-recovery`

依赖：Task 9 至 Task 12

文件：Modify `cli/src/adapters/types.ts`、各首期运行时适配器、`cli/src/clients/skillhub-client.ts`、`cli/src/commands/telemetry.ts`、`cli/src/stores/runtime-integration-store.ts`、必要时 `cli/src/telemetry/integration-event-spool.ts`、`frontend/src/pages/installation-recovery/InstallationCatalogPage.vue`、`frontend/src/modules/installation-recovery/api/installationApi.ts`、`types/installation.ts`、`services/installationDisplayText.ts`；Create `cli/src/telemetry/runtime-integration-reporter.ts`、`frontend/src/modules/installation-recovery/components/RuntimeIntegrationPanel.vue`、`frontend/tests/unit/runtimeIntegrationDisplayText.test.ts`、`cli/test/integration/runtime-integration-contract.test.ts`、`backend/src/test/java/com/km/skillhub/integration/RuntimeIntegrationEndToEndTest.java`。

目标：运行时接入在安装管理页通过页签可查询；从 CLI 本地安装、失败恢复、离线补报到服务端状态展示形成闭环，所有用户文案为中文。

实现：`RuntimeIntegrationResult` 返回适配器探测到的实际运行时版本；`install` 和 `repair` 完成本地操作后调用统一 reporter，以 `(scopeId, runtimeKey, targetKey)` 幂等登记服务端接入实例并按稳定事件 ID 上报结果。首次离线时本地状态持久化待登记信息和不含 `integrationId` 的稳定事件，恢复后必须先登记取得 `integrationId`，再补报待发送事件；重复执行不能重复记账。`status` 保持严格只读，不写状态、不登记、不补报。前端只展示服务端状态和失败阶段，不推断本机健康；服务端旧事件去重；测试核对本地状态、服务端实例、事件和页面 DTO 一致。Token、Bearer Header、用户主目录和原始配置正文不得进入状态、spool、输出或服务端事件。

测试/验证类型：integration、contract、frontend unit、build、manual。

测试场景：真实运行时版本进入登记；在线安装幂等登记并发送稳定事件；首次离线持久化待登记状态；repair 恢复后先登记再补报；重复补报只发送一次；status 无写入和网络上报；Token 不进入状态、spool 和输出；Hook 待信任、配置冲突、恢复成功、恢复失败人工处理、无权限和空列表。

测试文件或替代证据：上述 CLI/Java/Vue 测试、构建产物和真实主机手工记录。

验证：`npm --prefix cli test`、JDK 8 下 `mvn -f backend/pom.xml -Dtest=RuntimeIntegrationEndToEndTest test`、`npm --prefix frontend run test`、`npm --prefix frontend run build`、`git diff --check`；真实运行时缺失时该部分标记 unavailable，自动化契约不得冒充主机验证。

停止条件：CLI 与服务端状态不一致、离线事件重复记账、页面将待操作/部分失败显示为健康、出现英文功能文案或既有安装页回归时停止。

回滚：隐藏“运行时接入”页签并停止新登记；保留服务端历史和本地快照，按目标主机逐一恢复，不批量删除配置。

## 6. CR-026 Collector 安装与真实入口实施计划

### Task 14：接入本地 Collector 密钥、进程和 Hook/OTLP 配置

Slice：`slice-managed-collector-installation-auth`

需求：`requirement-cli-agent-integration-installation`、`requirement-cli-agent-integration-check`、`requirement-cli-agent-integration-recovery`

依赖：运行观测 Task 4B；本 Feature Task 10-12

文件：Modify `cli/src/telemetry/collector-installer.ts`、`cli/src/telemetry/integration-health.ts`、`cli/src/adapters/types.ts`、`cli/src/adapters/codex/assets/collector-assets.ts`、`cli/src/adapters/codex/codex-cli-adapter.ts`、`cli/src/adapters/claude/claude-code-otlp-adapter.ts`、`cli/src/commands/telemetry.ts`；Test `cli/test/unit/adapters/codex-cli-adapter.test.ts`、`claude-code-otlp-adapter.test.ts`、`cli/test/integration/codex-install-flow.test.ts`、`telemetry-repair-flow.test.ts`、`cli/test/integration/managed-collector-installation.test.ts`。

目标：安装/修复先建立受鉴权本地 Collector，再原子配置 Codex Hook/OTLP 和 Claude Code OTLP；status 只读且能区分进程、端口、协议、鉴权、配置和 spool 状态。

实现：调用 Task 4B `CollectorConfigStore` 和进程管理器，创建至少 256 bit 密钥并以当前用户权限保存；Hook 运行时读取密钥文件；Codex/Claude OTLP 写固定 endpoint、runtime key 和本地鉴权头。Claude settings 只允许在受管键保存本地 Collector 密钥，状态/输出/事件只保存摘要。Collector 探针通过前不修改运行时配置；未知端口占用返回人工处理，不结束进程。install/repair 失败按文件摘要恢复，不能停止其他运行时使用的健康 Collector。

测试/验证类型：unit、integration、security、process、build。

测试场景：首次密钥、重复安装、密钥文件权限、无鉴权旧配置升级、Hook 读密钥失败、OTLP Header、用户 exporter 冲突、Collector 启动失败、陈旧 PID、未知端口、多运行时共享、repair 恢复、并发修改和 status 零写入。

测试文件或替代证据：上述 CLI 测试使用临时用户目录、假运行时和随机测试端口；真实固定端口留给 Task 16 主机验证。

验证：`npm --prefix cli test`、`npm --prefix cli run build`、`git diff --check`；预期 SkillHub API Token 不进入配置，本地密钥不进入状态/输出/spool，Hook/OTLP 配置具备本地鉴权。

停止条件：Collector 未健康仍写配置、API Token 写入 Agent 配置、本地密钥被输出或上报、覆盖用户 exporter、未知进程被结束、status 写文件/发事件或回滚影响其他运行时时停止。

回滚：按摘要恢复 Hook/OTLP 受管键；仅在没有其他运行时依赖时停止身份匹配的 Collector，保留密钥、spool 和安装历史供再次修复。

### Task 15：将 VSIX 改为真实结构化事件发送器

Slice：`slice-editor-extension-real-events`

需求：`requirement-cli-agent-integration-installation`、`requirement-cli-agent-integration-check`、`requirement-cli-agent-integration-recovery`

依赖：Task 14；运行观测 Task 4B

文件：Modify `cli/src/adapters/codex/assets/vsix-assets.ts`、`cli/src/adapters/codex/editor-extension-adapter.ts`、`cli/test/unit/adapters/editor-extension-adapter.test.ts`、`cli/test/integration/codex-install-flow.test.ts`；Create `cli/test/integration/editor-extension-event-contract.test.ts`。

目标：VS Code/Cursor/Windsurf 扩展发送真实编辑器生命周期事件到 `/ide-event`，而不是只轮询 `/status`，并在 Collector 故障时不影响编辑器操作。

实现：扩展订阅激活/停用、文档修改计数、终端打开/关闭和任务开始/结束等稳定 API；envelope 只包含 schema、runtime key、稳定会话/事件 ID、事件类型、时间、语言/终端类型等允许字段。扩展从受管文件读取本地密钥，使用短超时和有界队列；禁止文件名/路径、代码差异、命令/输出、窗口标题和工作区名称。安装器生成摘要固定 VSIX，并对每个编辑器单独返回安装与探针结果。

测试/验证类型：unit、contract、security、integration、build。

测试场景：确定性 VSIX、三个 runtime key、激活/文档/终端/任务事件、密钥缺失、401、Collector 停止、队列满、扩展停用释放资源、敏感字段扫描、单编辑器失败和重复安装。

测试文件或替代证据：`editor-extension-event-contract.test.ts` 运行提取后的扩展发送核心并使用 Mock VS Code/HTTP；真实 VS Code 事件在 Task 16 记录。

验证：`npm --prefix cli test`、`npm --prefix cli run build`、`git diff --check`；预期 VSIX 不再周期请求 `/status`，可观察事件形成无正文 `/ide-event` 请求，失败不会抛回编辑器命令。

停止条件：扩展读取或发送代码/路径/终端正文、密钥编译进 VSIX、无限队列/重试、Collector 失败阻塞操作、未安装编辑器被标记成功或生成包摘要不稳定时停止。

回滚：恢复上一摘要固定 VSIX 或卸载本次受管扩展；不删除其他扩展和用户设置，Collector 与其他运行时保持运行。

### Task 16：串联安装、修复、探针和真实主机接入

Slice：`slice-runtime-adapter-real-ingress-recovery`

需求：`requirement-cli-agent-integration-installation`、`requirement-cli-agent-integration-check`、`requirement-cli-agent-integration-recovery`

依赖：Task 14、Task 15；运行观测 Task 4B

文件：Modify `cli/src/commands/telemetry.ts`、`cli/src/telemetry/collector-installer.ts`、`cli/src/telemetry/integration-health.ts`、`cli/src/telemetry/runtime-integration-reporter.ts`、必要的首期运行时适配器；Create `cli/test/integration/runtime-ingress-install-repair.test.ts`、`docs/product-development/features/feature-skill-installation-recovery/evidence/runtime-ingress-host-poc.md`。

目标：形成“Collector 健康 -> 运行时配置 -> 真实探针进入 spool -> 本地状态 -> 服务端登记”的安装闭环，并证明崩溃、陈旧状态和配置失败可恢复。

实现：install/repair 统一调用 Collector 生命周期，写配置后发送不含用户内容的探针并核对请求关联 ID或 spool 增量；只有探针成功才报告本地采集 ACTIVE。服务端仍只接收配置摘要和阶段结果。status 不启动进程、不发送探针、不登记/补报。主机 PoC 对当前检测到的 Codex 与 VS Code 执行安装、真实事件、Collector 停止隔离和 repair 恢复；其他运行时按实际环境记录 unavailable。

测试/验证类型：integration、contract、security、manual、build。

测试场景：首次安装、幂等重装、探针成功/401/503、Collector 崩溃、陈旧 PID/锁、配置失败恢复、用户并发修改、服务端离线登记补报、多运行时部分成功、status 只读和敏感数据全链路扫描。

测试文件或替代证据：`runtime-ingress-install-repair.test.ts`、现有 Task 13 纵向契约和 `evidence/runtime-ingress-host-poc.md`；fixture 或 Mock 结果不得替代主机 PoC。

验证：`npm --prefix cli test`、`npm --prefix cli run build`、一次真实 Codex Hook、一次真实 VS Code 事件、`skillhub telemetry status --runtime codex-cli --json`、`skillhub telemetry collector-status --json`、`git diff --check`；预期真实事件进入 spool，Collector 停止不改变 Agent/编辑器结果，repair 后恢复。

停止条件：健康仅由 `/health` 推断、探针未入 spool 却显示 ACTIVE、status 产生写入、失败回滚覆盖用户修改、部分失败显示整体成功、主机证据缺失仍标记支持或敏感数据进入任何状态/输出时停止。

回滚：逐运行时恢复受管快照并禁用事件发送；确认无运行时依赖后停止 Collector，保留 spool、checkpoint、服务端接入历史和审计。

Task 14-16 完成后回到运行观测 Task 4C 做跨 Feature 真实入口验收；Task 4C 通过前不得开始运行观测 Task 5-10。安装 Task 1-13 的完成状态和 Task 6 Redis 证据缺口均保持不变。
