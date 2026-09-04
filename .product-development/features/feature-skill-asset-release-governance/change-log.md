# Feature Change Log

## CR-001

- Date: 2026-09-01
- User request: Create formal requirements with configurable runtime, release, rollback, and retention defaults.
- Type: requirement-clarification
- Reason: Formalize the asset and release governance Feature from the confirmed Work Item decomposition.
- Affected requirements: all requirements in requirement.md
- Affected design: none
- Affected tasks: none
- Code scope: none
- Approval status: approved
- Verification status: not-run
- Replacement relation: none

## CR-002

- Date: 2026-09-01
- User request: 对 `feature-skill-asset-release-governance` 再次进行设计。
- Type: design-change
- Reason: 在已确认的 Feature 拆分、可配置发布策略和分层保留策略基础上形成正式设计与实施计划。
- Affected requirements: all requirements in requirement.md
- Affected design: create design.md v1
- Affected tasks: create implementation-plan.md v1
- Code scope: none; greenfield design only
- Approval status: approved
- Verification status: not-run
- Replacement relation: none

## CR-003

- Date: 2026-09-01
- User feedback: 指出设计阶段必须先检查 `docs` 下的架构和实施标准基线。
- Type: documentation-correction
- Reason: 设计基线检查未在正式设计写入前完成；补充阻塞记录，防止无基线草稿被当作正式方案。
- Affected requirements: none
- Affected design: design.md v1 marked invalid draft pending baselines
- Affected tasks: implementation-plan.md v1 marked invalid draft pending baselines
- Code scope: none
- Approval status: approved
- Verification status: pass for repository path inventory; unavailable for Python checker because Python runtime is unavailable
- Replacement relation: supersedes CR-002 as the active design handoff state

## CR-004

- Date: 2026-09-01
- User request: 创建 Java 后端和 Vue 前端架构基线，并调整 `AGENTS.md`。
- Type: documentation-correction
- Reason: 按设计基线规则补充架构文档和项目级架构路由；实施标准入口仍缺失，正式设计继续阻塞。
- Affected requirements: none
- Affected design: current design.md remains invalid draft pending baseline confirmation
- Affected tasks: current implementation-plan.md remains invalid draft pending baseline confirmation
- Code scope: none
- Approval status: approved for draft baseline creation; baseline confirmation pending
- Verification status: not-run
- Replacement relation: none

## CR-005

- Date: 2026-09-01
- User request: 参考 `kmplm-development-process` 的架构与实施规范，补充 SKILL HUB 数据库设计并更新 `AGENTS.md` 路由。
- Type: documentation-correction
- Reason: 建立 Java/Vue/数据库实现基线入口，按 SKILL HUB 四个 Feature 的总体需求设计事务数据、高吞吐运行数据和制品存储边界。
- Affected requirements: `requirement-skill-asset-registration`, `requirement-skill-metadata-completeness`, `requirement-skill-catalog-search`, `requirement-skill-version-immutability`, `requirement-skill-lifecycle-state`, `requirement-skill-release-scope`, `requirement-skill-release-gate`, `requirement-skill-governance-audit`, `requirement-skill-data-retention`
- Affected design: current design.md remains invalid draft until baseline confirmation
- Affected tasks: current implementation-plan.md remains invalid draft until baseline confirmation
- Code scope: none
- Approval status: approved for draft baseline creation; baseline confirmation pending
- Verification status: not-run
- Replacement relation: none

## CR-006

- Date: 2026-09-01
- User request: 将数据库从 MySQL 调整为 PostgreSQL 15。
- Type: architecture-baseline-correction
- Reason: 统一 SKILL HUB 事务数据库技术基线，并将数据库类型、结构化字段、布尔字段、时间字段和当前绑定唯一索引调整为 PostgreSQL 15 语义。
- Affected requirements: `requirement-skill-asset-registration`, `requirement-skill-metadata-completeness`, `requirement-skill-catalog-search`, `requirement-skill-version-immutability`, `requirement-skill-lifecycle-state`, `requirement-skill-release-scope`, `requirement-skill-release-gate`, `requirement-skill-governance-audit`, `requirement-skill-data-retention`
- Affected design: current design.md remains invalid draft until baseline confirmation
- Affected tasks: current implementation-plan.md remains invalid draft until baseline confirmation
- Code scope: none
- Approval status: approved for draft baseline correction; baseline confirmation pending
- Verification status: not-run
- Replacement relation: supersedes the PostgreSQL-related parts of CR-005

## CR-007

- Date: 2026-09-02
- User request: 其他技术栈参考 iflytek SkillHub 所用的技术栈。
- Type: architecture-baseline-correction
- Reason: 将 iflytek SkillHub 已由调研报告证实的 Java 21、Spring Boot 模块化单体、Redis Streams、OAuth2/CLI Device Flow/API Token、OpenTelemetry/Micrometer、Docker Compose、Kubernetes 和 Helm 纳入 SKILL HUB 技术基线参考；保留用户已确认的 Vue 3 前端选型。
- Affected requirements: all requirements in requirement.md
- Affected design: current design.md remains invalid draft until baseline confirmation
- Affected tasks: current implementation-plan.md remains invalid draft until baseline confirmation
- Code scope: none
- Approval status: approved for draft baseline correction; baseline confirmation pending
- Verification status: not-run
- Replacement relation: supersedes the corresponding technology-stack assumptions in CR-005 and CR-006

## CR-008

- Date: 2026-09-02
- User request: JDK 必须是 8；其他技术栈按 JDK 8 确定版本；去掉 OAuth2 和统一单点登录，仅支持账户密码登录。
- Type: architecture-baseline-correction
- Reason: 将 Java 运行时从 JDK 21 收敛到 JDK 8 兼容基线，固定 Spring Boot 2.7.18、Spring MVC 5.3.31、Spring Security 5.7.11、MyBatis-Plus 3.5.5、PostgreSQL JDBC 42.2.27、Flyway 8.5.13、Redis 6.2.14、OpenTelemetry Java 1.32.0、Micrometer 1.10.13、Docker Compose 2.24.6、Kubernetes 1.28.15 和 Helm 3.14.4；认证收敛为账户密码和服务端会话。
- Affected requirements: all requirements in requirement.md
- Affected design: current design.md remains invalid draft until baseline confirmation
- Affected tasks: current implementation-plan.md remains invalid draft until baseline confirmation
- Code scope: none
- Approval status: approved for draft baseline correction; baseline confirmation pending
- Verification status: not-run
- Replacement relation: supersedes the JDK 21 and OAuth2-related assumptions in CR-007

## CR-009

- Date: 2026-09-02
- User confirmation: 采用《阿里巴巴 Java 开发手册》嵩山版，并确认 JDK 8、Spring Boot 2.7.18、Spring MVC 5.3.31、Spring Security 5.7.11、MyBatis-Plus 3.5.5、PostgreSQL 15、Redis Streams、Vue 3、TypeScript、账户密码和服务端 Session 基线；确认四份 draft 基线可作为设计输入。
- Type: design-baseline-confirmation-and-redesign
- Reason: 解除设计基线阻塞，重新形成可供用户评审的资产与发布治理设计双产物。
- Affected requirements: all requirements in `requirement.md`
- Affected design: replace invalid v1 draft with `design.md` v2
- Affected tasks: replace invalid v1 draft with `implementation-plan.md` v2
- Code scope: none; greenfield design only
- Approval status: baseline confirmed; design and plan confirmation pending
- Verification status: not-run
- Replacement relation: supersedes CR-003 through CR-008 as the active design handoff for this Feature

## CR-010

- Date: 2026-09-02
- User confirmation: 确认 `design.md` v2、`implementation-plan.md` v2 和数据库规范 `v0.5-draft` 的导入失败留痕、`PENDING_GRAY` 增量内容。
- Type: design-confirmation
- Reason: 固化资产与发布治理方案和实施计划，解除设计阶段确认阻塞。
- Affected requirements: all requirements in `requirement.md`
- Affected design: `design.md` v2 confirmed
- Affected tasks: `implementation-plan.md` v2 confirmed
- Code scope: none; implementation not started
- Approval status: approved
- Verification status: documentation checks passed; feature tests not-run because the repository is greenfield
- Replacement relation: confirms CR-009 design handoff

## CR-013

- Date: 2026-09-02
- User request: Make the interface entirely Chinese instead of mixing Chinese and English.
- Type: implementation-increment
- Reason: Localize all governance-console labels, states, scopes, audit values and error fallbacks while keeping technical identifiers unchanged.
- Affected requirements: `requirement-skill-governance-audit`
- Affected design: frontend display-label mapping
- Affected tasks: Task 9 governance console pages
- Code scope: `frontend/src/pages`, `frontend/src/modules/asset-governance`, `frontend/tests/unit/displayText.test.ts`
- Approval status: approved by explicit user request
- Verification status: frontend tests and production build passed
- Replacement relation: none

## CR-012

- Date: 2026-09-02
- User request: Provide the system administrator `admin/admin123` and ordinary user `user/user123` default accounts.
- Type: implementation-increment
- Reason: Complete the first-run account/password login baseline with idempotent account initialization and RBAC bindings.
- Affected requirements: `requirement-skill-governance-audit`
- Affected design: default account initialization and RBAC seed behavior
- Affected tasks: Task 1 account/session baseline
- Code scope: `backend/src/main/resources/db/migration/V7__seed_default_governance_accounts.sql`, `backend/src/test/java/com/km/skillhub/integration/DefaultAccountSeedIntegrationTest.java`
- Approval status: approved by explicit user request
- Verification status: account seed test passed; full suite has a Redis connection environment failure
- Replacement relation: none

## CR-011

- Date: 2026-09-02
- User request: 按已确认的 `design.md v2` 和 `implementation-plan.md v2` 实现资产与发布治理。
- Type: implementation-start
- Reason: 将账户 Session、资产/版本、发布门禁、范围绑定、审计、保留策略和控制台从设计落到 Java/Vue/PostgreSQL/Redis Streams 工程骨架。
- Affected requirements: all requirements in `requirement.md`
- Affected design: none; implementation follows the confirmed v2 baseline
- Affected tasks: Tasks 1-10
- Code scope: `backend/`, `frontend/`, `AGENTS.md`
- Approval status: user-authorized implementation
- Verification status: backend unit tests and frontend build/tests pass; PostgreSQL, Redis and JDK 8 runtime validation unavailable locally
- Replacement relation: none

## CR-014

- Date: 2026-09-02
- User request: 按正确的界面流程补充资产导入页面、资产详情/版本管理页面，并在 `/assets` 增加“上传 Skill”入口。
- Type: implementation-increment
- Reason: 将已确认设计中的资产注册/导入、导入结果、资产详情和版本生命周期管理补齐为可操作的控制台流程。
- Affected requirements: `requirement-skill-asset-registration`, `requirement-skill-metadata-completeness`, `requirement-skill-catalog-search`, `requirement-skill-version-immutability`, `requirement-skill-lifecycle-state`
- Affected design: `/assets`、`/assets/:assetId`、`/assets/:assetId/versions/:versionDigest` routes already defined in `design.md` v2
- Affected tasks: Task 5 catalog and Vue asset pages
- Code scope: `backend/src/main/java/com/km/skillhub/catalog`, `backend/src/main/java/com/km/skillhub/mapper/version/SkillVersionMapper.java`, `frontend/src/pages/asset-governance`, `frontend/src/modules/asset-governance`, `frontend/src/router/index.ts`, `frontend/src/styles.css`
- Approval status: approved by explicit user request
- Verification status: frontend tests/build passed; backend compile passed; full Maven suite has the known Redis environment failure
- Replacement relation: none

## CR-015

- Date: 2026-09-02
- User request: 确认这个增量需求、设计和实施流程；界面需要与参考项目一致，主题色与公司一致，额外功能不需要一样。
- Type: scope-change
- Reason: 对齐 `D:\program\skillhub` 中与账户治理、Skill 资产发现、版本内容和发布治理直接相关的业务能力，并加入中文界面和可替换企业品牌主题约束。
- Affected requirements: `requirement-skill-package-content`, `requirement-skill-semantic-version-tags`, `requirement-skill-discovery-search`, `requirement-skill-file-browse-download`, `requirement-skill-version-comparison`, `requirement-skill-publish-review-lifecycle`, `requirement-skill-namespace-governance`, `requirement-governance-account-management`, `requirement-skill-governance-console-branding`
- Affected design: `design.md` v3 CR-015 increment
- Affected tasks: Tasks 11-17 in `implementation-plan.md` v3
- Code scope: `backend/` package content/discovery/namespace/review/admin extensions; PostgreSQL V10; `frontend/src/pages`, `frontend/src/modules`, `frontend/src/router/index.ts`, `frontend/src/styles.css`
- Approval status: approved by explicit user confirmation
- Verification status: not-run
- Replacement relation: extends v2/v2.1 without replacing prior completed behavior

### CR-015 Implementation Checkpoint

- Date: 2026-09-02
- Completed: package content, discovery, namespace queries, semantic tags/comparison, review workbench, administrator governance APIs/pages, unified Chinese navigation and replaceable company-blue theme tokens.
- Verification: JDK 8 backend targeted tests and compile pass; frontend 13 unit tests and production build pass; PostgreSQL 15.18 Flyway v10 and login/authorization smoke checks pass.
- Limitations: full browser E2E is not configured; official website exact color verification is unavailable; no OAuth2/SSO/API Token/CLI Device Flow/S3/microservice capability was added.

## CR-016

- Date: 2026-09-02
- User request: 按已确认的企业级前端控制台方案实施，采用左侧导航、顶部工作栏、统一页面布局、企业蓝主题和全中文界面。
- Type: implementation-increment
- Reason: 当前控制台页面功能可用但缺少企业级信息架构、公共交互和一致视觉，需要对齐参考项目的控制台使用体验，同时保持现有后端和认证边界。
- Affected requirements: requirement-skill-governance-console-branding、requirement-skill-catalog-search、requirement-skill-publish-review-lifecycle、requirement-skill-governance-audit
- Affected design: design.md v4, section 22
- Affected tasks: Tasks 18-20 in implementation-plan.md v4
- Code scope: frontend/src/components、frontend/src/pages、frontend/src/router/index.ts、frontend/src/styles.css、frontend tests
- Approval status: approved by explicit user request
- Verification status: not-run
- Replacement relation: extends CR-015 without replacing backend contracts or completed governance behavior

## CR-017

- Date: 2026-09-02
- User request: 查证参考项目 Token 的业务链路，并先在当前工程实现相同的 Token 能力，暂不实现 CLI；保留账户密码登录，不引入 S3 和微服务。
- Type: scope-change
- Reason: 增加自动化客户端访问 Skill 资产目录、版本读取和 ZIP 导入所需的 API Token 能力；Token 仅负责身份和作用域校验，Skill 压缩仍由调用方负责。
- Affected requirements: `requirement-skill-api-token-access`, `requirement-skill-asset-registration`, `requirement-skill-catalog-search`
- Affected design: `design.md` CR-017 增量章节，当前实施版本 v5
- Affected tasks: Tasks 21-24 in `implementation-plan.md` CR-017 增量章节
- Code scope: `backend/src/main/java/com/km/skillhub/token`, `backend/src/main/resources/db/migration/V11__create_api_token.sql`, `backend/src/main/java/com/km/skillhub/config/SecurityConfig.java`, `frontend/src/modules/token`, `frontend/src/pages/account`, `frontend/src/router/index.ts`, `frontend/src/components/AppShell.vue`, `frontend/src/components/layout/SideNavigation.vue`, related tests
- Approval status: approved by explicit user request
- Verification status: implementation-stage targeted tests passed; Java 8 runtime unavailable in the current shell, and the unrelated Redis Streams full-suite failure remains separately recorded.
- Replacement relation: supersedes the API Token exclusion in the CR-015/CR-016 implementation boundary only; OAuth2, SSO, CLI Device Flow, S3 and microservice exclusions remain

## CR-018

- Date: 2026-09-03
- User request: 将当前 Token 管理界面与 `D:\program\skillhub` 参考项目的布局和文字对齐。
- Type: implementation-increment
- Reason: 对齐参考项目的令牌清单页面、创建弹窗、期限编辑弹窗、删除确认交互和中文文案，同时保留当前工程的作用域能力和账户 Session/CSRF 边界。
- Affected requirements: `requirement-skill-api-token-access`, `requirement-skill-governance-console-branding`
- Affected design: `design.md` CR-017 API Token 前端管理页面描述
- Affected tasks: CR-017 Task 23 Vue Token 管理控制台
- Code scope: `frontend/src/pages/account/TokenManagementPage.vue`, `frontend/src/styles.css`
- Approval status: approved by explicit user request
- Verification status: pass for frontend tests (5 files, 16 tests), Vue type check, Vite production build and `git diff --check`; browser screenshot and interactive viewport verification remain not-run because Playwright is unavailable.
- Replacement relation: extends CR-017 presentation behavior without changing API or authentication contracts

## CR-019

- Date: 2026-09-03
- User request: 核对参考项目侧边栏和 Token 相关名称，确认并调整当前项目的术语。
- Type: implementation-increment
- Reason: 参考项目使用“访问凭证”描述业务入口，页面使用“Token 管理”和“API Tokens”；不存在“Token 密码”或“API 密钥”作为 Token 管理名称。当前项目侧边栏、面包屑、作用域说明、错误提示和审计显示统一改为“访问凭证”。
- Affected requirements: `requirement-skill-api-token-access`, `requirement-skill-governance-console-branding`
- Affected design: `design.md` CR-017 前端管理页面术语说明
- Affected tasks: CR-017 Task 23 Vue Token 管理控制台
- Code scope: `frontend/src/components/layout/SideNavigation.vue`, `frontend/src/components/AppShell.vue`, `frontend/src/pages/account/TokenManagementPage.vue`, `frontend/src/modules/token/api/tokenApi.ts`, `frontend/src/modules/asset-governance/services/displayText.ts`, `frontend/tests/unit/tokenApi.test.ts`, `docs/product-development/features/feature-skill-asset-release-governance/design.md`, `docs/product-development/features/feature-skill-asset-release-governance/implementation-plan.md`
- Approval status: approved by explicit user request
- Verification status: pass; reference terminology checked, 5 frontend test files and 16 tests passed, Vue type check and Vite production build passed, and `git diff --check` passed.
- Replacement relation: extends CR-018 terminology alignment without changing API, authentication, scope or database contracts.

## CR-020

- Date: 2026-09-03
- User request: 按照参考项目 `D:\program\skillhub` 核对并对齐前端页面、菜单文字、业务链路、交互和弹窗。
- Type: implementation-increment
- Reason: 当前工程已具备治理扩展，但用户端信息架构与参考 SkillHub 差异较大；本增量补齐控制台、我的技能、发布、命名空间、治理中心、管理拆分、设置和详情页交互，并保持企业蓝主题及全中文界面。
- Affected requirements: `requirement-skill-governance-console-branding`, `requirement-skill-catalog-search`, `requirement-skill-package-content`, `requirement-skill-file-browse-download`, `requirement-skill-version-comparison`, `requirement-skill-publish-review-lifecycle`, `requirement-skill-namespace-governance`, `requirement-governance-account-management`, `requirement-skill-api-token-access`
- Affected design: `design.md` CR-020 increment
- Affected tasks: CR-020 Tasks 25-29 in `implementation-plan.md`
- Code scope: `frontend/src/components`, `frontend/src/pages`, `frontend/src/modules/discovery`, `frontend/src/router/index.ts`, `frontend/src/styles.css`, frontend unit tests
- Explicit exclusions: 推广管理、举报管理、账号合并、收藏与评分、只看已收藏、收藏筛选以及收藏/评分/举报交互；不改变账户密码 Session、CSRF、PostgreSQL 15、本地制品存储、API Token、CLI、S3、OAuth2/SSO 和微服务边界。
- Approval status: approved by explicit user implementation request
- Verification status: not-run
- Replacement relation: extends CR-019 without replacing completed governance and token behavior.

## CR-021

- Date: 2026-09-03
- User request: 去掉侧边栏滚动条视觉、移除菜单项前置单字文字，并将过多的治理、管理和设置入口合并为页面内页签。
- Type: implementation-increment
- Reason: 当前侧边栏信息密度过高，低频入口分散且导航项前置单字文字不符合企业控制台的简洁导航样式；通过聚合页面降低认知负担，同时保留全部已有业务可达性。
- Affected requirements: `requirement-skill-governance-console-branding`、`requirement-skill-governance-audit`、`requirement-governance-account-management`
- Affected design: `design.md` CR-021 increment
- Affected tasks: CR-021 Tasks 30-32 in `implementation-plan.md`
- Code scope: `frontend/src/components/layout/SideNavigation.vue`、`frontend/src/components/AppShell.vue`、`frontend/src/components/ui/PageTabs.vue`、聚合页面及相关业务页面、`frontend/src/router/index.ts`、`frontend/src/styles.css`、frontend unit tests
- Explicit exclusions: 推广管理、举报管理、账号合并、收藏与评分、只看已收藏、收藏筛选以及收藏/评分/举报交互；不改变账户密码 Session、CSRF、PostgreSQL 15、本地制品存储、API Token、CLI、S3、OAuth2/SSO 和微服务边界。
- Approval status: approved by explicit user implementation request
- Verification status: pass for frontend tests, production build, static exclusion checks and diff check; browser screenshot and interactive viewport verification remain unavailable because Playwright is not installed.
- Replacement relation: extends CR-020 without replacing existing routes or backend contracts。

## CR-022

- 日期：2026-09-03
- 用户请求：SkillHub CLI 支持将现有 Skill 目录或 ZIP 上传到 SkillHub，并支持采集数据和上报平台的统一凭据契约。
- 类型：requirement-change
- 原因：将参考项目中的自动化客户端能力对齐到当前资产治理，补齐本地校验、打包、服务端二次校验、幂等和提交审核链路。
- 影响需求：`requirement-cli-skill-package-validation`、`requirement-cli-skill-upload`、`requirement-cli-skill-upload-idempotency`、`requirement-cli-skill-review-submit`、`requirement-skill-api-token-access`
- 影响方案：`design.md` v6 和 `implementation-plan.md` v6 已由用户统一确认
- 影响任务：Task 33-37，依次覆盖 CLI 凭据、包校验、V12 服务端复检与草稿导入、发布/审核命令和纵向契约验证
- 代码范围：本轮无代码变更
- 审批状态：approved；用户于 2026-09-03 确认 v6 方案双产物并授权按顺序实施
- 验证状态：文档检查通过，23/23 需求进入设计和计划，覆盖状态均为 covered，V12/V13/V14 归属一致；CLI 和端到端上传尚未实现
- 替代关系：替代 CR-020/CR-021 中 CLI 排除项，不替代 OAuth2、S3、微服务等排除项

## CR-023

- Date: 2026-09-03
- User request: 当前 Vue 前端必须与参考项目 `D:\program\skillhub\web` 的页面布局和交互效果一致。
- Type: implementation-defect
- Reason: 源码复核确认 CR-020 Tasks 25-28 虽被标记完成，但公共反馈、发布包交互、详情文件浏览、版本差异、审核详情、命名空间及管理页面仍未达到已确认的 CR-020 验收目标。
- Affected requirements: `requirement-skill-governance-console-branding`, `requirement-skill-catalog-search`, `requirement-skill-package-content`, `requirement-skill-file-browse-download`, `requirement-skill-version-comparison`, `requirement-skill-publish-review-lifecycle`, `requirement-skill-namespace-governance`, `requirement-governance-account-management`, `requirement-skill-api-token-access`
- Affected design: reopens the confirmed `design.md` CR-020 increment without changing its architecture or exclusions.
- Affected tasks: reopens CR-020 Tasks 25-28 in `implementation-plan.md`.
- Code scope: `frontend/src/components`, `frontend/src/pages`, `frontend/src/modules/discovery`, `frontend/src/router/index.ts`, `frontend/src/styles.css`, frontend tests and browser verification setup.
- Explicit exclusions: 推广管理、举报管理、账号合并、收藏与评分、只看已收藏、收藏筛选以及收藏/评分/举报交互；不增加 OAuth2/SSO、S3 或微服务；缺少后端契约的写操作不得模拟成功。
- Approval status: approved by explicit user implementation request.
- Verification status: in-progress.
- Replacement relation: corrects CR-020 completion status and preserves CR-021 sidebar aggregation and company-blue theme decisions.
