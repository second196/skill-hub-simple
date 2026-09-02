---
title: Skill 资产与发布治理实施计划
description: 按可运行 Slice 实现 Skill 资产、版本、发布门禁、权限、审计和保留治理
audience:
  - product-development
owner: product-development
status: draft
lastReviewed: 2026-09-02
sourceType: manual
---

# 实施计划：Skill 资产与发布治理

> 计划版本：v2；对应需求 `requirement.md` v1 和设计 `design.md` v2。本计划在设计确认后执行，当前不代表已开始实现。

## 1. 实施前约束

- 当前仓库是 greenfield，没有可复用业务源码、构建文件、迁移历史、测试入口或真实 API。
- 后端严格使用 JDK 8、Spring Boot 2.7.18、Spring MVC 5.3.31、Spring Security 5.7.11、MyBatis-Plus 3.5.5、Maven 3.8.8、PostgreSQL 15、Flyway 8.5.13 和 Redis Streams 6.2.14。
- 前端使用 Vue 3、TypeScript、Vite、Vue Router 和 Pinia；组件、类型和注释遵循已确认标准。
- 认证只实现账户密码 + BCrypt + 服务端 Session + HttpOnly/Secure/SameSite Cookie，不实现 OAuth2、统一单点登录、CLI Device Flow 或 API Token。
- 版本、策略、决策、证据和审计不可原地修改；跨 Feature 只使用 `version_digest`，不使用 `latest`。
- 对象存储、搜索、分析存储、容量、备份、RPO/RTO、正式包名和组织身份来源未确认；相关任务必须保留适配器边界，不得擅自选定供应商。

## 2. 文件到事实映射

| 事实/入口 | 目标文件 | 责任 |
| --- | --- | --- |
| 应用启动、依赖和环境配置 | `backend/pom.xml`、`backend/src/main/java/com/km/skillhub/SkillHubApplication.java`、`backend/src/main/resources/application-*.yml` | 工程和运行基线 |
| Session、账户和 RBAC | `backend/src/main/java/com/km/skillhub/config/SecurityConfig.java`、`governance/*`、`controller/session/*` | 登录和授权 |
| 导入、来源、清单和版本 | `asset/*`、`version/*`、`integration/artifact/*` | 资产生命周期 |
| PostgreSQL 表、约束和迁移 | `backend/src/main/resources/db/migration/V1__...sql` 至 `V5__...sql` | 权威治理数据 |
| 门禁、策略、灰度和发布 | `gate/*`、`policy/*`、`release/*` | 决策和范围绑定 |
| 审计和跨 Feature 事件 | `audit/*`、`integration/downstream/*`、`governance_event_outbox` Mapper | 追溯和可靠投递 |
| Vue 页面、API 和类型 | `frontend/src/pages/asset-governance/*`、`frontend/src/modules/asset-governance/*` | 控制台交互 |
| 后端测试 | `backend/src/test/java/com/km/skillhub/{service,mapper,integration,contract}/` | 规则、迁移和契约验证 |
| 前端测试 | `frontend/tests/{unit,integration,contract,e2e}/` | 页面和契约验证 |

以上路径来自 `backend-architecture.md` 和 `frontend-architecture.md` 的 greenfield 目录设计；创建前不得假设这些文件已经存在。

## 3. Slice 与任务顺序

### Task 1：建立 JDK 8 工程与账户 Session 基线

Slice：`slice-account-session-governance`
需求：`requirement-skill-governance-audit`
依赖：无
文件：Create `backend/pom.xml`、`backend/src/main/java/com/km/skillhub/SkillHubApplication.java`、`config/SecurityConfig.java`、`governance/controller/SessionController.java`、`governance/service/SessionService.java`、`governance/model/entity/PrincipalEntity.java`、`backend/src/test/java/com/km/skillhub/integration/SessionSecurityIntegrationTest.java`；Create `frontend/package.json`、`frontend/src/router/index.ts`、`frontend/src/stores/sessionStore.ts`、`frontend/src/pages/LoginPage.vue`。

目标：用户可以用账户密码登录和退出；服务端创建 Session，越权请求得到稳定拒绝；系统不产生 Token/OAuth2 入口。

实现：按 Java 规范分离 Controller、Service、Mapper 和 DO/DTO/VO；BCrypt 保存密码哈希；Session Cookie 设置 HttpOnly、Secure、SameSite；登录失败统一错误，不回显账号是否存在；加入 CSRF、Session 失效和请求关联 ID；前端只保存当前会话状态，不保存长期凭据。

测试/验证类型：`integration`、`security`、`static`。
测试场景：正确密码、错误密码、过期 Session、缺少角色、跨范围访问、Cookie 属性、重复登录、登出后访问、检查依赖中无 OAuth2/Token 认证入口。
测试文件或替代证据：`backend/src/test/java/com/km/skillhub/integration/SessionSecurityIntegrationTest.java`、`frontend/tests/integration/session.spec.ts`；依赖实现后执行。
验证：`mvn -f backend/pom.xml -DskipTests=false test`；预期认证集成测试通过且依赖/配置检查没有被禁止认证方案。
停止条件：Session、CSRF 或范围授权失败时停止后续业务模块。
回滚：删除本 Task 新增的工程骨架和认证配置，恢复空仓库状态；不删除既有文档。

### Task 2：创建 PostgreSQL 15 治理模式和迁移入口

Slice：`slice-governance-schema`
需求：`requirement-skill-asset-registration`、`requirement-skill-metadata-completeness`、`requirement-skill-data-retention`
依赖：Task 1
文件：Create `backend/src/main/resources/db/migration/V1__create_governance_scope.sql`、`V2__create_skill_asset_version_import.sql`、`V3__create_release_gate_policy.sql`、`V4__create_authorization_audit.sql`、`V5__create_cross_feature_metadata.sql`；Create corresponding `model/entity/*Entity.java` and Mapper interfaces under `mapper/{asset,version,policy,release,governance,audit}/`；Create `backend/src/test/java/com/km/skillhub/mapper/GovernanceMigrationIntegrationTest.java`。

目标：空 PostgreSQL 15 数据库可建立治理表、外键/逻辑关联、索引和追加式对象约束。

实现：落地 `skill_asset`、`skill_import_attempt`、`skill_artifact`、`skill_version`、`skill_version_manifest`、运行时兼容、范围、角色、策略、绑定、决策、证据、审批、审计和 Outbox；使用 BIGINT identity、UTC 时区、VARCHAR 状态、JSONB 条件；为 `request_id`、`version_digest` 和 active binding 建立唯一约束；用部分唯一索引保证同一范围只有一个当前版本；禁止删除不可变表。

测试/验证类型：`migration`、`integration`、`static`。
测试场景：空库顺序迁移、重复 request ID、重复摘要、两个当前 binding、缺失外键、追加表更新/删除权限、策略和保留记录。
测试文件或替代证据：`GovernanceMigrationIntegrationTest.java`；需要 PostgreSQL 15 测试服务，未提供时标记 `unavailable`，不得标记通过。
验证：`mvn -f backend/pom.xml -Dtest=GovernanceMigrationIntegrationTest test`；预期 Flyway 空库成功，约束拒绝非法数据。
停止条件：迁移不能在空库执行或不变量无法由数据库约束表达时停止。
回滚：在测试数据库销毁后重建；生产只执行经评审的前向兼容迁移，不修改已执行 Flyway 脚本。

### Task 3：实现资产导入、失败留痕和不可变版本

Slice：`slice-asset-import-version`
需求：`requirement-skill-asset-registration`、`requirement-skill-metadata-completeness`、`requirement-skill-version-immutability`
依赖：Task 2
文件：Create `asset/controller/AssetImportController.java`、`asset/service/AssetImportService.java`、`asset/service/impl/AssetImportServiceImpl.java`、`asset/mapper/SkillImportAttemptMapper.java`、`asset/mapper/SkillAssetMapper.java`、`version/service/SkillVersionService.java`、`version/mapper/SkillVersionMapper.java`、`integration/artifact/ArtifactStore.java`、`model/dto/AssetImportRequest.java`、`model/vo/ImportAttemptVO.java`、`model/vo/SkillVersionVO.java`；Create `backend/src/test/java/com/km/skillhub/service/AssetImportServiceTest.java` and `integration/AssetImportIntegrationTest.java`。

目标：授权用户可注册/导入 Skill；成功结果可查询；来源不可读、必要内容缺失或摘要失败时记录失败阶段和原因，不能生成可发布版本。

实现：先写 `skill_import_attempt(STARTED)`，校验来源和必需内容，规范化清单/元数据后计算 SHA-256 `version_digest`，对象存储使用不可覆盖 URI；成功创建资产、制品、清单和 DRAFT/CANDIDATE 版本；失败在同一治理事务中写 FAILED、稳定失败码和脱敏原因；以 request ID 幂等；禁止更新既有版本字段，差异只读。

测试/验证类型：`unit`、`integration`。
测试场景：合法导入、无权限、来源超时、不可读文件、缺少主描述/必需文件、未知许可/依赖/运行时、对象存储失败、重复 request ID、内容变化生成新 digest、修改旧版本被拒。
测试文件或替代证据：`AssetImportServiceTest.java`、`AssetImportIntegrationTest.java`。
验证：`mvn -f backend/pom.xml -Dtest=AssetImportServiceTest,AssetImportIntegrationTest test`；预期每个失败均有 ImportAttempt，且没有 PUBLISHED 版本。
停止条件：任何失败路径生成可发布版本或重复请求产生第二个版本时停止。
回滚：删除仅存在于测试范围的对象和记录；生产通过状态下线/废弃，不物理删除不可变制品和版本。

### Task 4：实现生命周期状态机和版本查询

Slice：`slice-version-lifecycle`
需求：`requirement-skill-version-immutability`、`requirement-skill-lifecycle-state`
依赖：Task 3
文件：Create `version/domain/SkillLifecycle.java`、`version/service/LifecycleService.java`、`version/controller/SkillVersionController.java`、`version/model/VersionTransitionCommand.java`、`audit/service/AuditService.java`；Create `backend/src/test/java/com/km/skillhub/service/LifecycleServiceTest.java`、`integration/VersionImmutabilityIntegrationTest.java`。

目标：DRAFT、CANDIDATE、PUBLISHED、OFFLINE、EMERGENCY_REVOKED、DEPRECATED 转移规则明确，非法转移和默认分发过滤可验证。

实现：将转移表作为明确枚举/领域规则；每次转移校验操作者、原因、证据和当前 row version；状态变更追加审计；查询返回版本摘要、清单、来源、差异和状态原因；OFFLINE/撤回/废弃不得被解析为默认分发版本。

测试/验证类型：`unit`、`integration`。
测试场景：所有合法转移、门禁失败保持 CANDIDATE、非法回退、并发更新、撤回后查询、默认版本选择、缺失 digest 不绑定 latest。
测试文件或替代证据：`LifecycleServiceTest.java`、`VersionImmutabilityIntegrationTest.java`。
验证：`mvn -f backend/pom.xml -Dtest=LifecycleServiceTest,VersionImmutabilityIntegrationTest test`；预期状态和历史记录可还原。
停止条件：历史内容可被更新或非法状态能进入默认分发时停止。
回滚：恢复测试数据；生产使用新的状态决策修正，不直接改写历史转移记录。

### Task 5：实现授权目录搜索和 Vue 资产页面

Slice：`slice-authorized-asset-catalog`
需求：`requirement-skill-catalog-search`、`requirement-skill-metadata-completeness`
依赖：Task 4
文件：Create `catalog/controller/AssetCatalogController.java`、`catalog/service/AssetCatalogService.java`、`catalog/mapper/AssetCatalogMapper.java`、`integration/search/CatalogProjection.java`；Create `frontend/src/modules/asset-governance/api/assetApi.ts`、`types/asset.ts`、`components/AssetFilter.vue`、`components/AssetTable.vue`、`pages/asset-governance/AssetCatalogPage.vue`、`AssetDetailPage.vue`；Create `backend/src/test/java/com/km/skillhub/integration/AssetCatalogIntegrationTest.java`、`frontend/tests/integration/asset-catalog.spec.ts`。

目标：用户可在授权范围内按名称、描述、标签、来源、运行时、状态和版本查询，并看到完整性、版本、发布、评测和安装关联的缺失状态。

实现：查询先解析 Session 的范围权限，再用分页和稳定排序查询权威/派生索引；搜索索引只能由 Outbox 重建；前端 API 类型与后端 VO 分离，显式处理 loading/empty/partial/error/forbidden；组件职责单一，筛选、表格、详情和状态展示分开；不得把 unknown 渲染为确定值。

测试/验证类型：`integration`、`component`、`contract`。
测试场景：多条件搜索、分页、空结果、投影延迟、范围越权、unknown/missing 字段、版本详情、长报告按需加载、重复刷新。
测试文件或替代证据：`AssetCatalogIntegrationTest.java`、`frontend/tests/integration/asset-catalog.spec.ts`。
验证：`mvn -f backend/pom.xml -Dtest=AssetCatalogIntegrationTest test`；`npm --prefix frontend run test`；预期无越权数据，列表不全量加载。
停止条件：查询返回未授权资产或前端把缺失数据猜测成 latest 时停止。
回滚：恢复上一查询/页面构建；不回滚 PostgreSQL 权威版本状态。

### Task 6：实现公司/项目/环境发布范围和并发绑定

Slice：`slice-release-scope-binding`
需求：`requirement-skill-release-scope`
依赖：Task 4
文件：Create `release/controller/ReleaseBindingController.java`、`release/service/ReleaseScopeService.java`、`release/mapper/ReleaseBindingMapper.java`、`governance/service/ScopeResolutionService.java`、`release/model/ReleaseTarget.java`；Create `backend/src/test/java/com/km/skillhub/integration/ReleaseScopeIntegrationTest.java`、`contract/ReleaseBindingContractTest.java`。

目标：已通过门禁的版本可以绑定到公司、项目和环境范围；同一范围只有一个当前版本，返回逐范围结果。

实现：校验目标范围和版本为 PUBLISHED/允许状态，按环境 > 项目 > 公司解析策略；使用事务锁、row version 和部分唯一索引更新旧 binding/插入新 binding；每个目标单独记录成功/失败和审计；范围冲突、权限不足和版本缺失均阻断，不使用 latest。

测试/验证类型：`integration`、`contract`。
测试场景：单范围发布、多范围部分失败、同一范围并发、越权范围、父子范围策略、撤回后新增绑定、重复请求。
测试文件或替代证据：`ReleaseScopeIntegrationTest.java`、`ReleaseBindingContractTest.java`。
验证：`mvn -f backend/pom.xml -Dtest=ReleaseScopeIntegrationTest,ReleaseBindingContractTest test`；预期没有两个 active binding，失败范围不污染成功范围。
停止条件：并发下出现两个当前版本或部分失败被返回为整体成功时停止。
回滚：撤销未生效 binding 并恢复旧 current binding；保留发布决策和审计。

### Task 7：实现策略版本、证据接入和门禁决策

Slice：`slice-release-gate-decision`
需求：`requirement-skill-release-gate`
依赖：Task 6
文件：Create `gate/controller/GateEvidenceController.java`、`gate/service/GateDecisionService.java`、`gate/domain/GateRuleEvaluator.java`、`gate/mapper/GateEvidenceMapper.java`、`policy/service/PolicyResolutionService.java`、`policy/mapper/ReleasePolicyMapper.java`、`release/model/ReleaseDecisionEntity.java`；Create `backend/src/test/java/com/km/skillhub/service/GateRuleEvaluatorTest.java`、`integration/ReleaseGateIntegrationTest.java`、`contract/EvidenceContractTest.java`。

目标：发布前必须校验静态扫描、评测、风险、审核和适用灰度证据；缺失、失败、过期、摘要不匹配或条件不可判断时阻断。

实现：外部证据先校验来源、version digest、evidence digest、条件和有效期，再关联决策；策略按环境/项目/公司解析并固化快照；自动发布只允许低风险白名单，且满足无高危/严重问题、无权限/外部写操作变化、无新运行时兼容变化、至少 30 个有效案例、回归和评分不低于基线；其他情况为 BLOCKED/PENDING_APPROVAL；审批人分离由 Service 和数据库约束共同校验。

测试/验证类型：`unit`、`integration`、`contract`。
测试场景：全证据通过、证据缺失/过期/失败、摘要篡改、策略冲突、样本不足、评分下降、高风险变化、重复决策、审批申请人等于审批人。
测试文件或替代证据：`GateRuleEvaluatorTest.java`、`ReleaseGateIntegrationTest.java`、`EvidenceContractTest.java`。
验证：`mvn -f backend/pom.xml -Dtest=GateRuleEvaluatorTest,ReleaseGateIntegrationTest,EvidenceContractTest test`；预期阻断原因和策略版本可查询。
停止条件：无证据仍可 APPROVED、证据跨版本复用或审批分离失效时停止。
回滚：将未生效决策标记 REJECTED/BLOCKED；不删除证据和历史策略。

### Task 8：实现灰度观察、回退决策和下游事件契约

Slice：`slice-gray-observation-rollback-contract`
需求：`requirement-skill-release-gate`、`requirement-skill-release-scope`
依赖：Task 7
文件：Create `gate/service/GrayObservationService.java`、`release/service/RollbackDecisionService.java`、`integration/downstream/InstallationReleaseEventPublisher.java`、`integration/downstream/RuntimeEvidenceContract.java`、`integration/downstream/EvaluationEvidenceContract.java`、`audit/model/ReleaseEvidenceSnapshot.java`；Create `backend/src/test/java/com/km/skillhub/service/GrayObservationServiceTest.java`、`integration/OutboxDeliveryIntegrationTest.java`、`contract/CrossFeatureVersionContractTest.java`。

目标：按可配置参数产生灰度观察决策，观察达标才形成全量绑定；触发阈值时产生回退/撤回意图并可靠通知下游。

实现：默认灰度 10% 且向上取整至少 1 个实例，观察 24 小时且至少 30 次有效调用；错误率比基线增加 2 个百分点、相对增加 20%、基线为 0 时达到 2%、评分下降 5 个百分点或出现高危/严重问题时触发回退；门禁通过但观察未完成写 `PENDING_GRAY`；保存基线、分母、阈值、样本、策略版本和证据；Outbox 事务写入 Redis Streams，消费者按 event ID 幂等重试；事件中只引用明确 `version_digest`。

测试/验证类型：`unit`、`integration`、`contract`、`manual`。
测试场景：实例数 0/1/小数比例、观察未达样本、24 小时边界、三类错误率阈值、评分阈值、高危立即回退、重复事件、Redis 超时/积压、下游部分失败。
测试文件或替代证据：`GrayObservationServiceTest.java`、`OutboxDeliveryIntegrationTest.java`、`CrossFeatureVersionContractTest.java`；真实 Redis/下游不可用时用协议级替代证据并标记限制。
验证：`mvn -f backend/pom.xml -Dtest=GrayObservationServiceTest,OutboxDeliveryIntegrationTest,CrossFeatureVersionContractTest test`；预期 PENDING_GRAY 不被当作全量发布，回退原因完整且事件不重复。
停止条件：回退条件不稳定、PENDING_GRAY 能被安装 Feature 当成全量版本或重复消费重复计账时停止。
回滚：暂停自动发布/事件消费者，恢复上一策略版本；已提交的审计和决策保留。

### Task 9：实现 RBAC、审批人分离、审计和控制台治理页面

Slice：`slice-rbac-audit-console`
需求：`requirement-skill-governance-audit`
依赖：Task 7、Task 8
文件：Create `governance/service/AuthorizationService.java`、`governance/service/ApprovalSeparationService.java`、`audit/controller/AuditController.java`、`audit/service/AuditQueryService.java`、`audit/mapper/AuditLogMapper.java`；Create `frontend/src/modules/asset-governance/api/governanceApi.ts`、`types/governance.ts`、`components/GateEvidencePanel.vue`、`components/ScopeResultTable.vue`、`components/AuditTimeline.vue`、`pages/asset-governance/ReleaseDecisionPage.vue`、`PolicyPage.vue`、`AuditPage.vue`；Create `backend/src/test/java/com/km/skillhub/integration/AuthorizationAuditIntegrationTest.java`、`frontend/tests/e2e/governance-flow.spec.ts`。

目标：角色和范围控制导入、版本、发布、下线、撤回、评测证据和治理配置；关键操作可查询主体、时间、前后状态、原因和策略版本。

实现：后端每次写操作重新鉴权；人工申请人与审批人不得相同；机器主体使用明确 actor type；审计追加写入且按对象/范围/主体/时间分页；前端将证据、逐范围结果、审批分离和审计关联拆为单一职责组件，显式展示 forbidden/partial/error；高风险操作二次确认。

测试/验证类型：`integration`、`e2e`、`security`。
测试场景：角色矩阵、公司/项目/环境继承、越权、申请人审批自己、机器自动发布、审计前后状态、审计分页、部分成功、紧急撤回确认。
测试文件或替代证据：`AuthorizationAuditIntegrationTest.java`、`frontend/tests/e2e/governance-flow.spec.ts`。
验证：`mvn -f backend/pom.xml -Dtest=AuthorizationAuditIntegrationTest test`；`npm --prefix frontend run test:e2e`；预期拒绝结果和审计记录一致。
停止条件：任何越权写入、审批人分离失效或前端把部分失败显示成整体成功时停止。
回滚：恢复上一前端构建和权限策略版本；不删除审计记录。

### Task 10：实现保留策略版本和最终设计验证

Slice：`slice-configurable-retention-policy`
需求：`requirement-skill-data-retention`
依赖：Task 2、Task 9
文件：Create `policy/service/RetentionPolicyService.java`、`policy/controller/RetentionPolicyController.java`、`policy/mapper/RetentionPolicyMapper.java`、`policy/model/RetentionPolicyVersionEntity.java`、`backend/src/test/java/com/km/skillhub/service/RetentionPolicyServiceTest.java`、`integration/RetentionPolicyIntegrationTest.java`；Update `docs/product-development/features/feature-skill-asset-release-governance/design.md` and `implementation-plan.md` only when verified behavior changes.

目标：管理员可按公司/项目/环境配置并版本化数据保留规则；默认制品/不可变版本/评测报告/发布决策/审计永久保留，原始运行事件 365 天，聚合指标永久保留。

实现：按数据类别和范围解析策略，环境 > 项目 > 公司；修改插入新版本、设置生效时间、审批主体和审计；缩短策略前执行影响检查，删除/归档任务使用受限主体并记录批次；永久数据无普通删除 API；本 Feature 只提供运行观测域可消费的保留契约。

测试/验证类型：`unit`、`integration`、`migration`。
测试场景：默认值、范围覆盖、同级冲突、未来生效、缩短策略无权限、旧策略不可改、永久数据删除拒绝、原始事件 365 天契约。
测试文件或替代证据：`RetentionPolicyServiceTest.java`、`RetentionPolicyIntegrationTest.java`；真实删除任务尚未属于本 Feature，使用策略/权限/审计替代证据。
验证：`mvn -f backend/pom.xml -Dtest=RetentionPolicyServiceTest,RetentionPolicyIntegrationTest test`；预期策略版本不可更新，调整有审计，永久类别没有删除入口。
停止条件：策略修改覆盖历史、普通用户可删除永久数据或未配置规则被隐式放行时停止。
回滚：恢复上一已审核策略版本并暂停删除任务；保留新版本和失败审计。

## 4. Slice 完成门槛

每个 Slice 必须完成：

1. 需求、设计、计划中的语义化 ID 一致。
2. 正常、异常、边界和重复请求场景至少有一条自动化或有理由的替代验证。
3. PostgreSQL/Redis/对象存储等依赖不可用时记录 `unavailable` 和环境失败原因，不标记 `pass`。
4. `version_digest`、策略版本、决策 ID、审计 ID 和 request ID 可在结果中完整关联。
5. `git diff --check`、路径检查、依赖/版本检查和对应测试通过后才可进入下一个 Slice。
6. 发现需求变化、公共 API 变化、跨 Feature 责任不清或设计无法实现时停止，并回到对应阶段确认。

## 5. 计划风险和待确认项

- `com.km.skillhub` 只是架构基线中的包名模板，正式公司反向域名确认前不得发布。
- 对象存储、搜索、分析存储、备份、容量、分区、RPO/RTO 和灾备缺少真实环境依据；相关任务只能实现接口和替代测试。
- iflytek Registry/Scanner/RBAC 的真实版本、扩展点、许可证和供应链尚未验证；适配器 PoC 失败不应阻塞独立领域实现。
- 评测有效案例、评分基线和运行事件质量由其他 Feature 提供；条件缺失时本 Feature 必须阻断自动发布。
- 任何部署、合并、发布、公共 API 变更和真实数据迁移都需要单独人工确认，不由本计划自动授权。
