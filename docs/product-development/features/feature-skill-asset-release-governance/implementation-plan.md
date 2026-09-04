---
title: Skill 资产与发布治理实施计划
description: 按可运行 Slice 实现 Skill 资产、版本、发布门禁、权限、审计和保留治理
audience:
  - product-development
owner: product-development
status: confirmed
lastReviewed: 2026-09-03
sourceType: manual
---

# 实施计划：Skill 资产与发布治理

> 当前实施计划版本：v6；对应 `requirement.md` v5 和 `design.md` v6。v5 已实施，CR-022 增量已由用户确认并进入 implementation。

## 1. 实施前约束

- 当前仓库已有资产治理、安装恢复、API Token、Vue 控制台、Flyway V1-V11 和测试入口；CR-022 只新增 CLI 上传链路及其必要的 V12/服务端增量，不重写既有实现。
- 后端严格使用 JDK 8、Spring Boot 2.7.18、Spring MVC 5.3.31、Spring Security 5.7.11、MyBatis-Plus 3.5.5、Maven 3.8.8、PostgreSQL 15、Flyway 8.5.13 和 Redis Streams 6.2.14。
- 前端使用 Vue 3、TypeScript、Vite、Vue Router 和 Pinia；组件、类型和注释遵循已确认标准。
- 认证只实现账户密码 + BCrypt + 服务端 Session + HttpOnly/Secure/SameSite Cookie，不实现 OAuth2、统一单点登录、CLI Device Flow 或 API Token。
- 版本、策略、决策、证据和审计不可原地修改；跨 Feature 只使用 `version_digest`，不使用 `latest`。
- 制品使用可替换的本地 `ArtifactStore` 实现，不引入 S3；搜索优先使用 PostgreSQL 可重建查询/投影，不擅自选定外部搜索供应商。
- 单个包大小、制品根目录、容量、备份、RPO/RTO、正式包名、官网精确品牌色和组织身份来源未确认；相关任务必须保留配置和适配器边界。

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

以上路径以当前仓库已存在的 `backend/`、`frontend/` 为事实基线；`cli/` 尚不存在，由 Task 33 创建。实施前必须再次核对计划中的现有符号与新增路径。

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
文件：Create `catalog/controller/AssetCatalogController.java`、`catalog/service/AssetCatalogService.java`、`catalog/mapper/AssetCatalogMapper.java`、`catalog/model/vo/AssetDetailVO.java`、`integration/search/CatalogProjection.java`；Update `version/mapper/SkillVersionMapper.java` for authorized version lists; Create `frontend/src/modules/asset-governance/api/assetApi.ts`、`types/asset.ts`、`components/AssetFilter.vue`、`components/AssetTable.vue`、`components/AssetImportForm.vue`、`components/ImportResultPanel.vue`、`components/VersionList.vue`、`pages/asset-governance/AssetCatalogPage.vue`、`AssetImportPage.vue`、`AssetDetailPage.vue`、`VersionDetailPage.vue` and `router/index.ts`; Create `backend/src/test/java/com/km/skillhub/integration/AssetCatalogIntegrationTest.java`、`frontend/tests/integration/asset-catalog.spec.ts`。

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
## 19. CR-012 增量实施说明

Add `backend/src/main/resources/db/migration/V7__seed_default_governance_accounts.sql` and `backend/src/test/java/com/km/skillhub/integration/DefaultAccountSeedIntegrationTest.java`. The migration creates `admin/admin123` and `user/user123` as BCrypt-backed, enabled accounts, assigns the system governance scope and RBAC roles, and does not overwrite existing passwords. The integration test verifies both password hashes and role bindings against PostgreSQL 15.

## 20. CR-015 增量实施计划

### Task 11：Skill 包导入、本地制品读取和文件清单

Slice：`slice-skill-package-content`

需求：`requirement-skill-package-content`、`requirement-skill-file-browse-download`

文件：Update `asset/*` and `integration/artifact/*`; create `content/controller/*`、`content/service/*`、`content/model/*`; create Flyway `V10__align_skillhub_registry_governance.sql`; add package import, manifest, path traversal, local file read and download tests.

目标：支持真实多文件 Skill 包导入、文件清单、文本预览、单文件下载和版本包下载；失败阶段均有导入留痕。

验证：`mvn -f backend/pom.xml -Dtest=SkillPackageImportServiceTest,ArtifactContentServiceTest test`；`npm --prefix frontend run test -- --run`。包解析、非法路径、摘要不匹配、越权和大文件边界必须有测试；本地制品根目录使用配置项，不引入 S3。

回滚：生产只执行前向迁移；异常制品标记失败或下线，不删除不可变版本；代码回退到上一个 ArtifactStore 实现。

### Task 12：Skill 发现搜索、命名空间详情和元数据展示

Slice：`slice-skill-discovery-search`

需求：`requirement-skill-discovery-search`、`requirement-skill-namespace-governance`、`requirement-skill-metadata-completeness`

文件：Update `catalog/*`; create `discovery/*`、`namespace/*`; extend mapper/query types and catalog frontend modules; add authorized search, namespace, member and detail contract tests.

目标：按名称、描述、标签、命名空间、状态和更新时间稳定分页查询；用户只能看到有权限的资产和命名空间；详情展示来源、许可、依赖、运行时和缺失状态。

验证：`mvn -f backend/pom.xml -Dtest=SkillDiscoveryIntegrationTest,NamespaceAuthorizationTest test`；`npm --prefix frontend run test`；验证空结果、索引延迟、越权、成员角色和分页稳定性。

回滚：保留既有 `/api/v1/assets` 查询契约，新增发现接口可独立关闭；不删除命名空间历史和授权审计。

### Task 13：语义化版本、标签和版本比较

Slice：`slice-version-tags-comparison`

需求：`requirement-skill-semantic-version-tags`、`requirement-skill-version-comparison`

文件：Update `version/*`、`catalog/*`; create tag history mapper/service and comparison VO; add `/space/:namespace/:slug/versions/compare` frontend page and tests.

目标：支持语义化版本、`latest/stable/beta` 标签解析和明确 digest 的文件/元数据比较；不可分发状态自动解除标签目标。

验证：`mvn -f backend/pom.xml -Dtest=SemanticVersionServiceTest,VersionComparisonServiceTest test`；前端版本比较组件测试和生产构建；验证非法版本、跨资产比较、撤回后标签解析和二进制文件差异。

回滚：标签变更只能通过新的历史记录修正；版本内容、digest 和既有生命周期记录不回写。

### Task 14：发布审核和生命周期工作台

Slice：`slice-publish-review-lifecycle`

需求：`requirement-skill-publish-review-lifecycle`、`requirement-skill-release-gate`、`requirement-skill-governance-audit`

文件：Update `release/*`、`gate/*`、`audit/*`; create `review/*`; add publish/review pages, service tests and approval separation integration tests.

目标：候选版本可以提交审核；审核人可查看文件、门禁、风险并通过/拒绝/撤回；归档、恢复和重新发布遵循已有状态机、门禁和审计。

验证：`mvn -f backend/pom.xml -Dtest=ReviewLifecycleServiceTest,ApprovalSeparationIntegrationTest test`；前端审核工作台测试；验证申请人不能审批、缺证阻断、撤回范围可见和恢复不绕过门禁。

回滚：恢复上一已审核发布绑定，保留审核、决策和审计记录；不物理删除版本。

### Task 15：管理员账户、命名空间、标签和审计页面

Slice：`slice-admin-governance-console`

需求：`requirement-governance-account-management`、`requirement-skill-namespace-governance`、`requirement-skill-governance-console-branding`

文件：Update `governance/*`、`audit/*`; create `admin/*`; add `/admin/accounts`、`/admin/namespaces`、`/admin/labels` and dashboard namespace/member routes; add authorization and UI tests.

目标：管理员可管理账户状态、命名空间、成员、标签和审计；停用账户下一次鉴权失效；普通用户无法进入管理员写操作。

验证：`mvn -f backend/pom.xml -Dtest=AdminGovernanceAuthorizationTest,DisabledAccountSessionTest test`；`npm --prefix frontend run test`；验证管理员/普通用户权限和审计完整性。

回滚：恢复上一个前端路由和权限策略；保留停用、成员、标签和审计历史。

### Task 16：参考项目交互结构和公司品牌主题

Slice：`slice-branded-discovery-console`

需求：`requirement-skill-governance-console-branding`

文件：Update `frontend/src/router/index.ts`、`frontend/src/styles.css`、`frontend/src/App.vue`; create `pages/discovery`、`pages/skill`、`pages/namespace`、`pages/review`、`pages/admin`; add display-text and responsive route tests.

目标：形成参考项目同类的发现/详情/版本/治理信息架构，全部界面文案为中文；品牌色集中为可配置 CSS token，桌面和移动布局可用。

验证：`npm --prefix frontend run test`；`npm --prefix frontend run build`；`git diff --check`；官网精确色值不可验证时，记录为 `unavailable`，不把默认 token 说明为官网事实。

回滚：恢复上一版路由和样式 token，不影响后端数据和治理状态。

### Task 17：增量整体契约验证

Slice：`slice-skillhub-alignment-contract`

需求：全部 CR-015 增量需求

文件：Create/update backend integration and frontend contract/e2e tests; update `state.md` with each task result.

目标：从登录、发现、上传、详情、文件预览、版本比较、审核、发布、命名空间和管理员治理串联验证，不验证被明确排除的 OAuth、S3、微服务和社交功能。

验证：后端定向 Maven 测试、前端 Vitest、生产构建、路由检查、迁移验证和 `git diff --check`；Redis 不可用时只将受影响 Outbox 测试标记 `unavailable`，不得掩盖其他失败。

停止条件：任何越权返回、版本不可变性破坏、文件路径穿越、审批人分离失效、中文界面回退为英文或品牌样式溢出时停止并回退到对应 Slice。

### Task 18：企业级应用壳和导航

Slice：slice-enterprise-console-shell

需求：requirement-skill-governance-console-branding

依赖：Task 16

文件：更新 frontend/src/components/AppShell.vue、frontend/src/router/index.ts、frontend/src/styles.css；必要时在 frontend/src/components/layout/ 创建共享布局组件，并更新壳层和路由测试。

目标：桌面端展示左侧分组导航、顶部工作栏和面包屑，移动端可通过抽屉访问同一导航；保留旧业务路由和服务端 Session。

验证：npm --prefix frontend run test、npm --prefix frontend run build、浏览器桌面/移动截图与导航交互检查。

回滚：恢复上一版应用壳和主题样式，不影响后端和治理数据。

### Task 19：公共页面视觉和工作概览

Slice：slice-enterprise-console-pages

需求：requirement-skill-governance-console-branding、requirement-skill-catalog-search、requirement-skill-publish-review-lifecycle

依赖：Task 18

文件：更新 frontend/src/styles.css 和 frontend/src/pages/ 下受影响页面；已有接口可以支持摘要且不需要新增后端契约时，创建 frontend/src/pages/dashboard/DashboardPage.vue。

目标：资产、发现、版本、审核、发布、安装和系统管理页面统一使用页面标题、筛选工具栏、表格/详情、状态反馈和高风险操作确认布局；工作概览只聚合已有查询结果。

验证：页面组件测试、前端类型检查、生产构建、主要路由访问和响应式布局检查。

回滚：按页面恢复旧模板和样式；不得回滚或修改既有业务接口。

### Task 20：全量中文化和企业化验收

Slice：slice-enterprise-console-localization-verification

需求：requirement-skill-governance-console-branding、requirement-skill-governance-audit

依赖：Task 19

文件：更新用户可见标签、显示文本映射和 frontend/tests/；增加路由/中文文案检查和浏览器证据。

目标：功能文案、错误提示、状态标签、操作按钮、空状态和权限提示统一为中文；技术标识、路由和摘要值保持接口兼容。

验证：npm --prefix frontend run test、npm --prefix frontend run build、git diff --check、浏览器截图和关键流程人工交互验证。

停止条件：发现乱码、英文功能文案、导航不可达、移动端内容重叠、关键操作越权或 API 契约变化时停止。

## 21. 本轮实施检查点

- Task 11-13 已落地：真实 ZIP 导入、本地制品读取、文件清单、授权发现、命名空间查询、语义化版本标签和摘要比较。
- Task 14 已落地最小闭环：审核申请、审核队列、通过/拒绝/撤回、申请人与审核人分离；审核通过不会绕过发布门禁。
- Task 15 已落地管理员闭环：账户启停、命名空间成员增删/角色调整、版本标签查询和移除；所有写操作追加审计。
- Task 16 已落地：统一中文工作台导航、发现/资产/审核/安装/治理/管理入口、企业蓝色 CSS token 和移动端布局。
- Task 17 定向验证已通过，完整浏览器 E2E 尚未配置；官网精确色值仍为 `unavailable`，不宣称 token 与官网精确一致。

## 22. CR-017 API Token 增量实施计划

> 增量计划版本：v5；对应需求 `requirement.md` v4 和设计 `design.md` v5。用户已明确授权在当前工程实现，暂不实现 CLI。

### Task 21：Token 数据模型和生命周期服务

Slice：`slice-api-token-lifecycle`

需求：`requirement-skill-api-token-access`

文件：新增 `backend/src/main/resources/db/migration/V11__create_api_token.sql`、`backend/src/main/java/com/km/skillhub/token/model/`、`token/mapper/ApiTokenMapper.java`、`token/service/ApiTokenService.java` 和 `token/controller/ApiTokenController.java`；新增服务/迁移定向测试。

目标：创建 Token 时只持久化摘要；支持当前账号 Token 列表、过期时间更新和不可逆撤销；名称、作用域和过期时间校验失败返回稳定错误。

验证：JDK 8 Maven 定向测试、Flyway V11 迁移检查、Token 原文不落库查询和 Token 生命周期接口测试。

回滚：停止应用后回退应用代码并保留前向兼容的 `api_token` 表；不得修改既有迁移。

### Task 22：Bearer 认证和作用域隔离

Slice：`slice-api-token-bearer-scope`

需求：`requirement-skill-api-token-access`、`requirement-skill-asset-registration`、`requirement-skill-catalog-search`

文件：新增 `backend/src/main/java/com/km/skillhub/token/security/ApiTokenAuthenticationFilter.java`、`ApiTokenScopeFilter.java` 及认证测试；更新 `backend/src/main/java/com/km/skillhub/config/SecurityConfig.java`。

目标：Bearer Token 可调用既有资产读取和 ZIP 导入接口；缺少/错误/过期/撤销 Token 为 401，作用域不足为 403；Session 请求仍要求网页写操作 CSRF，Bearer 写请求不依赖 Cookie CSRF。

验证：Spring MockMvc Bearer 契约测试、Session CSRF 回归测试、作用域矩阵测试和账号停用测试。

回滚：移除新增过滤器注册和作用域限制，保留 Token 表与服务层以便前向兼容。

## 6. CR-020 参考 SkillHub 前端对齐增量

### Task 25：主导航、控制台、我的技能、发布和访问凭证

Slice：`slice-reference-dashboard-publish`

需求：`requirement-skill-governance-console-branding`、`requirement-skill-catalog-search`、`requirement-skill-package-content`、`requirement-skill-api-token-access`

文件：更新 `frontend/src/components/layout/SideNavigation.vue`、`frontend/src/components/AppShell.vue`、`frontend/src/pages/dashboard/DashboardPage.vue`、`frontend/src/pages/account/TokenManagementPage.vue`、`frontend/src/router/index.ts`；新增 `frontend/src/pages/dashboard/MySkillsPage.vue`、`frontend/src/pages/dashboard/PublishPage.vue`。

目标：侧边栏文字与参考项目一致，具备控制台、我的技能、发布、我的命名空间、治理中心、审核管理、访问凭证和设置入口；我的技能使用状态 Tab 和确认弹窗，发布使用命名空间/可见性/ZIP/发布前检查/风险确认链路，访问凭证保持既有真实 API。

验证：`npm --prefix frontend run test`、`npm --prefix frontend run build`、路由静态检查、`git diff --check`。

回滚：移除新增页面和路由，恢复旧导航入口；不修改后端 API、认证、Token 数据和资产治理状态。

### Task 26：搜索、技能详情、文件浏览、版本比较和审核交互

Slice：`slice-reference-skill-review-interaction`

需求：`requirement-skill-catalog-search`、`requirement-skill-file-browse-download`、`requirement-skill-version-comparison`、`requirement-skill-publish-review-lifecycle`

文件：更新 `frontend/src/pages/discovery/SkillSearchPage.vue`、`frontend/src/pages/skill/SkillDetailPage.vue`、`frontend/src/pages/skill/SkillVersionComparePage.vue`、`frontend/src/pages/review/ReviewWorkbenchPage.vue`、`frontend/src/modules/discovery/api.ts`；新增统一弹窗/文件预览所需的 Vue 组件和测试。

目标：搜索支持排序、标签、命名空间、结果计数和分页；详情支持 README、文件树、预览弹窗、下载、安装命令和生命周期操作；版本比较兼容参考路径；审核支持分类/状态 Tab、详情查看、意见弹窗和确认弹窗。不得出现收藏、评分或举报入口。

验证：前端页面组件测试、搜索参数测试、文件预览异常测试、`npm --prefix frontend run build`、`git diff --check`。

回滚：恢复旧页面实现，保留现有后端内容和审核接口。

### Task 27：命名空间、治理中心、管理拆分和设置

Slice：`slice-reference-governance-settings`

需求：`requirement-skill-namespace-governance`、`requirement-governance-account-management`、`requirement-skill-governance-console-branding`

文件：新增命名空间列表/成员/审核、治理中心、管理员用户/命名空间/标签/审计和安全/个人/通知设置页面；更新 `frontend/src/router/index.ts`、导航和面包屑；扩展管理员 API 与可验证的只读状态映射。

目标：补齐参考项目页面入口和布局；已有后端能力使用真实调用，缺少写接口的设置或资料能力展示中文的未开放状态，不伪造提交成功；所有危险操作使用确认弹窗，保留 `/admin`、`/reviews` 等当前路由兼容。

验证：页面路由和权限静态检查、组件交互测试、`npm --prefix frontend run test`、`npm --prefix frontend run build`。

回滚：移除新增页面路由，旧管理页和治理页继续可访问；不修改 PostgreSQL 迁移。

### Task 28：对齐增量回归验证

Slice：`slice-reference-console-verification`

需求：CR-020 涉及的全部需求。

验证：前端单元测试、生产构建、`git diff --check`；浏览器截图/交互验证在 Playwright 可用时执行，否则标记 `unavailable`，不将静态阅读写成通过。

停止条件：排除项重新出现、中文界面出现未映射英文、真实接口契约错误、弹窗动作绕过权限或危险操作无确认。

### Task 23：Vue Token 管理控制台

Slice：`slice-api-token-console`

需求：`requirement-skill-api-token-access`、`requirement-skill-governance-console-branding`

文件：新增 `frontend/src/modules/token/api/tokenApi.ts`、`frontend/src/modules/token/types/token.ts`、`frontend/src/pages/account/TokenManagementPage.vue`；更新 `frontend/src/router/index.ts`、`frontend/src/components/layout/SideNavigation.vue`、`frontend/src/components/AppShell.vue`、相关样式和前端测试。

目标：在 `/account/tokens` 按参考 SkillHub 的页面结构提供全中文 Token 清单；侧边栏入口使用 `访问凭证`，页面使用 `Token 管理` 页面头部、`API Tokens` 清单卡片、`创建新 Token` 创建弹窗、一次性原文展示/复制、期限编辑弹窗和删除二次确认，保留作用域与状态辅助信息，并沿用企业蓝色控制台布局和 Session CSRF。业务文案使用“访问凭证”，凭证本体统一称为 API Token，不使用“Token 密码”或“API 密钥”。

验证：`npm --prefix frontend run test`、`npm --prefix frontend run build`、Token 页面类型与路由检查。

回滚：恢复 Token 页面路由和导航，不影响后端 Token 元数据。

### Task 24：CR-017 整体契约验证

Slice：`slice-api-token-contract-verification`

需求：`requirement-skill-api-token-access` 及其关联资产读取/导入需求

目标：串联创建 Token、一次性读取、Bearer 读取资产、Bearer 导入 ZIP、作用域拒绝、过期/撤销拒绝和网页 Session 回归。

验证：后端定向 Maven 测试、前端 Vitest、生产构建、`git diff --check`、Flyway 迁移检查；Redis 相关既有环境失败单独记录，不与 Token 功能结论混淆。

停止条件：Token 原文持久化或出现在日志、作用域越权、Session CSRF 回归、账号停用后 Bearer 仍可用、前端 Token 误显示历史原文时停止。

## 7. CR-021 侧边栏收敛与页签承载实施计划

### Task 30：企业侧边栏入口收敛

Slice：`slice-enterprise-sidebar-convergence`

需求：`requirement-skill-governance-console-branding`

文件：更新 `frontend/src/components/layout/SideNavigation.vue`、`frontend/src/styles.css`、`frontend/src/components/AppShell.vue`。

目标：移除导航项前置单字文字，保留高频入口，合并治理、系统管理和设置的低频入口，并隐藏侧边栏滚动条视觉；桌面端和移动抽屉均保持可访问。

验证：侧边栏静态入口检查、全中文文案检查、`npm --prefix frontend run build`、`git diff --check`。

回滚：恢复 CR-020 侧边栏模板和样式，不影响业务页面、API、权限和数据。

### Task 31：聚合页面页签承载

Slice：`slice-governance-admin-settings-tabs`

需求：`requirement-skill-governance-console-branding`、`requirement-skill-governance-audit`、`requirement-governance-account-management`

文件：新增 `frontend/src/components/ui/PageTabs.vue`；更新治理中心、审核、发布策略、审计、系统管理、用户管理、命名空间管理、标签管理和设置页面，以及 `frontend/src/router/index.ts`。

目标：治理中心、系统管理和设置通过查询参数提供可复制链接的页签切换；旧路由继续可达；现有加载、错误、确认弹窗和权限行为保持不变，不伪造未开放接口。

验证：页签路由静态检查、前端组件测试、`npm --prefix frontend run test`、`npm --prefix frontend run build`。

回滚：去除聚合页签入口，保留原页面路由；不修改后端契约和 PostgreSQL 迁移。

### Task 32：对齐增量回归验证

Slice：`slice-sidebar-tabs-regression`

需求：CR-021 涉及的全部需求。

验证：导航排除项检查、页签切换和深链接检查、`npm --prefix frontend run test`、`npm --prefix frontend run build`、`git diff --check`；浏览器截图和真实点击验证在 Playwright 不可用时记录为 `unavailable`。

停止条件：侧边栏出现排除项、合并页面无法访问原功能、页签刷新丢失、中文文案回退为英文或移动端内容重叠。

## 8. CR-022 SkillHub CLI 上传实施计划

### Task 33：建立 CLI 工程、凭据和 Token 作用域基线

Slice：`slice-cli-authentication-baseline`

需求：`requirement-skill-api-token-access`

依赖：已实施的 CR-017 API Token

文件：Create `cli/package.json`、`cli/package-lock.json`、`cli/tsconfig.json`、`cli/src/index.ts`、`cli/src/shared/constants.ts`、`errors.ts`、`output.ts`、`cli/src/stores/config-store.ts`、`credentials-store.ts`、`cli/src/commands/login.ts`、`logout.ts`、`whoami.ts`、`cli/src/clients/skillhub-client.ts`；Modify `backend/src/main/java/com/km/skillhub/token/service/ApiTokenService.java` 的 `SUPPORTED_SCOPES`、`frontend/src/pages/account/TokenManagementPage.vue` 的作用域选项和中文说明；Test `cli/test/unit/stores/credentials-store.test.ts`、`cli/test/integration/auth-commands.test.ts`、现有 Token 测试。

目标：Node.js 20 + TypeScript CLI 可以按服务地址安全保存/删除 API Token，验证当前身份，并让控制台创建 `telemetry:write` Token；人工输出中文，`--json` 输出稳定错误码。

实现：锁定 TypeScript 5.4.2、`cac 6.7.14`、`fflate 0.8.2`、`zod 3.24.1`、`yaml 2.8.3`；`yaml` 从原计划的 2.4.5 升级到同一主版本安全补丁，以消除依赖审计发现的深层递归拒绝服务风险。凭据文件使用当前用户权限和原子替换；环境变量优先于凭据文件；所有输出只显示 Token 前缀。遵循 implementation index v0.4-draft、TypeScript 最佳实践和注释规范；CLI 测试使用 Node.js 20 内置 `node:test`，避免为测试引入存在已知漏洞的旧版 Vite/esbuild 链。

测试/验证类型：unit、integration、security、build。

测试场景：首次登录、覆盖同服务凭据、退出、失效/撤销 Token、账号停用、文件权限、环境变量优先级、中文/JSON 输出、四类作用域展示。

测试文件或替代证据：`cli/test/unit/stores/credentials-store.test.ts`、`cli/test/integration/auth-commands.test.ts`、`backend/src/test/java/com/km/skillhub/token/ApiTokenServiceTest.java`、`ApiTokenAuthenticationFilterTest.java`、`frontend/tests/unit/tokenApi.test.ts`。

验证：`npm --prefix cli test`、`npm --prefix cli run build`、`mvn -f backend/pom.xml -Dtest=ApiTokenServiceTest,ApiTokenAuthenticationFilterTest test`、`npm --prefix frontend run test`；预期凭据不出现在日志/JSON，四类作用域可创建且越权仍为 403。

停止条件：Token 明文进入日志、普通配置或测试快照，Session/CSRF 行为变化，或 CLI 可以绕过作用域时停止。

回滚：移除 CLI 认证入口并从允许列表撤下新增作用域；保留既有 Token 表和 v5 Session/Bearer 行为，不删除用户 Token。

### Task 34：实现目录/ZIP 校验和确定性打包

Slice：`slice-cli-skill-package-validation`

需求：`requirement-cli-skill-package-validation`

依赖：Task 33

文件：Create `cli/src/services/skill-package-service.ts`、`cli/src/platform/archive.ts`、`paths.ts`、`cli/src/shared/types.ts`、`cli/test/unit/services/skill-package-service.test.ts`、`cli/test/unit/platform/archive.test.ts`、`cli/test/fixtures/skills/`。

目标：目录和 ZIP 经过同一套路径、大小、文件数、主描述和 frontmatter 校验，目录打包保留完整相对结构并生成稳定清单摘要。

实现：拒绝绝对/穿越/反斜杠逃逸/重复/符号链接路径；默认限制 10 MiB 压缩、100 MiB 解压、单文件 10 MiB、1000 文件、255 字符路径；使用 YAML + Zod 校验 `name`、`description`、语义版本；排序路径并固定 ZIP 元数据，不打包 `.git`、凭据和本地绝对路径。遵循 TypeScript v0.4-draft 入口规范。

测试/验证类型：unit、integration、security。

测试场景：有效目录、有效 ZIP、两者摘要一致、缺失 `SKILL.md`、坏 YAML、缺字段、非法版本、ZIP Slip、重复路径、符号链接、压缩膨胀、不可读文件和边界大小。

测试文件或替代证据：上述 CLI 单元测试和固定二进制夹具；夹具不得包含真实凭据。

验证：`npm --prefix cli test`；预期所有非法包在网络调用前失败，有效目录和等价 ZIP 产生相同规范化清单摘要。

停止条件：恶意路径可写出临时目录、限制只检查压缩大小、解析器执行任意标签，或摘要受 ZIP 时间戳影响时停止。

回滚：撤下 `publish` 命令入口并删除未发布 CLI 构建；不改变服务端资产数据。

### Task 35：实现服务端复检、草稿导入和三层幂等

Slice：`slice-server-draft-package-import`

需求：`requirement-cli-skill-package-validation`、`requirement-cli-skill-upload`、`requirement-cli-skill-upload-idempotency`

依赖：Task 34

文件：Create `backend/src/main/resources/db/migration/V12__extend_cli_package_import.sql`；Modify `backend/src/main/java/com/km/skillhub/asset/controller/AssetImportController.java`、`asset/model/dto/SkillPackageImportCommand.java`、`asset/model/entity/SkillImportAttemptEntity.java`、`asset/model/vo/ImportAttemptVO.java`、`asset/service/SkillPackageImportService.java`、`asset/service/impl/SkillPackageImportServiceImpl.java`、`mapper/asset/SkillImportAttemptMapper.java`；Create `asset/service/SkillPackageValidator.java`、`asset/model/vo/SkillPackageValidationVO.java`；Test `backend/src/test/java/com/km/skillhub/service/SkillPackageImportServiceTest.java`、Create `SkillPackageValidationTest.java` 和 `SkillPackageImportIntegrationTest.java`。

目标：服务端独立复检包内容，区分 `artifact_digest` 与规范化 `version_digest`，成功导入默认创建 `DRAFT`，重复请求返回同一结果且冲突请求被拒绝。

实现：V12 为 `skill_import_attempt` 增加 `request_digest`、`version_digest` 和查询索引；增加 `/package/validate` 无副作用接口；兼容现有 multipart 字段并拒绝其与 frontmatter 冲突；事务内写导入尝试、资产、制品、草稿版本和清单；同请求不同摘要返回 `IDEMPOTENCY_CONFLICT`，同版本不同内容返回 `VERSION_CONTENT_CONFLICT`；保存失败阶段和脱敏原因。不修改已执行 Flyway V1-V11；V13 归安装恢复的运行时接入元数据，运行观测使用 V14。

测试/验证类型：unit、integration、migration、security。

测试场景：客户端摘要伪造、ZIP 顺序差异、相同请求重试、并发重试、请求 ID 冲突、版本内容冲突、制品失败、数据库失败、默认草稿和旧页面兼容。

测试文件或替代证据：上述 Java 测试、PostgreSQL 15 空库迁移和现有前端 `assetApi.test.ts`。

验证：`mvn -f backend/pom.xml -Dtest=SkillPackageValidationTest,SkillPackageImportServiceTest,SkillPackageImportIntegrationTest test`、`npm --prefix frontend run test`；预期导入响应为 DRAFT，失败不产生候选/发布版本，重复请求只产生一个版本。

停止条件：服务端信任客户端摘要、历史版本被更新、失败包进入候选、Flyway 版本冲突或现有上传页面契约破坏时停止。

回滚：关闭校验/CLI 入口并恢复 v5 服务代码；保留向前兼容字段、导入留痕和已创建草稿，不回写历史摘要。

### Task 36：实现发布命令和显式审核提交

Slice：`slice-cli-publish-review-submit`

需求：`requirement-cli-skill-upload`、`requirement-cli-skill-review-submit`、`requirement-skill-api-token-access`

依赖：Task 35

文件：Create `cli/src/commands/publish.ts` 及其命令注册、扩展 `cli/src/clients/skillhub-client.ts`；Modify `backend/src/main/java/com/km/skillhub/review/service/ReviewService.java`、`review/controller/ReviewController.java`、`version/service/LifecycleService.java` 的受控调用边界、`token/security/ApiTokenScopeFilter.java`；Test `cli/test/integration/publish-command.test.ts`、`publish-dry-run.test.ts`、`backend/src/test/java/com/km/skillhub/service/ReviewServiceTest.java`、`backend/src/test/java/com/km/skillhub/integration/ApiTokenIntegrationTest.java`。

目标：`skillhub publish` 显示上传进度和草稿结果，`--dry-run` 不写数据，`--submit-review` 在门禁满足时原子进入候选并创建待审核任务。

实现：网络/429/5xx 使用同一请求 ID 有界退避；上传和审核分别返回结果；ReviewService 对 DRAFT 执行范围、元数据、扫描、评测、门禁和待处理任务检查，再原子转 CANDIDATE；申请人仍不能审批自己。CLI 结果包含资产 ID、版本摘要、状态和控制台相对地址。

测试/验证类型：integration、contract、security、build。

测试场景：仅校验、仅上传、上传并提交、门禁缺失、重复提交、权限不足、Token 过期、网络中断后重试、上传成功但审核失败。

测试文件或替代证据：上述 CLI/Java 集成测试和 Mock HTTP 服务。

验证：`npm --prefix cli test`、`npm --prefix cli run build`、`mvn -f backend/pom.xml -Dtest=ReviewServiceTest,ApiTokenIntegrationTest test`；预期默认草稿不发布，审核失败保留可查询草稿，申请人与审批人分离不回归。

停止条件：上传自动发布、审核失败删除草稿、CLI 绕过门禁或错误输出泄露 Token 时停止。

回滚：隐藏 `--submit-review` 并恢复旧审核入口；保留草稿和审核审计，不删除不可变版本。

### Task 37：执行资产 CLI 纵向契约验证

Slice：`slice-cli-asset-governance-contract`

需求：`requirement-skill-api-token-access`、`requirement-cli-skill-package-validation`、`requirement-cli-skill-upload`、`requirement-cli-skill-upload-idempotency`、`requirement-cli-skill-review-submit`

依赖：Task 33 至 Task 36

文件：Create/update `cli/test/integration/asset-governance-flow.test.ts`、`backend/src/test/java/com/km/skillhub/integration/SkillCliContractIntegrationTest.java`，并更新对应 Feature `state.md` 验证记录。

目标：串联 Token、目录校验、打包、服务端复检、草稿导入、重复补偿、审核申请和控制台查询，证明 CLI 只是治理入口而非发布绕过通道。

实现：使用临时用户目录和测试 Token，启动真实 Spring 测试应用与 PostgreSQL 15；模拟首次上传响应丢失后使用原请求 ID重试；核对资产、版本、导入尝试、审核和审计唯一性。

测试/验证类型：contract、integration、migration、build、static。

测试场景：正常闭环、恶意包、重试、版本冲突、Token 作用域、账号停用、审核门禁缺失和中文/JSON 输出。

测试文件或替代证据：上述端到端契约测试、Maven Surefire、Vitest/CLI 测试报告和 `git diff --check`。

验证：`npm --prefix cli test`、`npm --prefix cli run build`、JDK 8 下 `mvn -f backend/pom.xml test`、`npm --prefix frontend run test`、`npm --prefix frontend run build`、`git diff --check`；预期全部通过，Redis 环境失败必须单独归因，不能掩盖资产链路失败。

停止条件：任一需求没有对应证据、全量测试出现未归因失败、版本/审核/审计重复，或现有网页上传回归时停止。

回滚：按 Task 33 至 Task 36 逆序回退应用和 CLI；数据库只使用前向兼容修复，保留所有草稿、失败导入和审计证据。
