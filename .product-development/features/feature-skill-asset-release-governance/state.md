# Feature State

feature: feature-skill-asset-release-governance
work_item: work-skill-hub-platform
decomposition_status: confirmed
artifact_dir: docs/product-development/features/feature-skill-asset-release-governance
control_dir: .product-development/features/feature-skill-asset-release-governance
current_phase: implementation
requirement_status: confirmed
design_status: confirmed
implementation_status: completed
review_status: not-requested
verification_status: in-progress
release_check_status: not-requested
requirement_version: v4
design_version: v5
plan_version: v5
active_change: CR-021
last_test_result: pass
feedback_status: none

## Work Item Boundary

- work_item: work-skill-hub-platform
- scope_decision: multi-feature
- allowed_requirements: requirement-skill-asset-registration, requirement-skill-metadata-completeness, requirement-skill-catalog-search, requirement-skill-version-immutability, requirement-skill-lifecycle-state, requirement-skill-release-scope, requirement-skill-release-gate, requirement-skill-governance-audit, requirement-skill-data-retention, requirement-skill-api-token-access
- forbidden_requirements: runtime event collection, installation execution, evaluation execution, OAuth2/SSO/CLI Device Flow/S3/microservice architecture
- dependency_features: none

## Confirmed Decisions

- Four Feature split and dependencies confirmed by user on 2026-09-01.
- Release and retention policies use configurable defaults and versioned audit records.
- Design uses an independent governance domain with optional iflytek capability adapters; gray traffic execution and runtime rollback remain downstream responsibilities.
- PostgreSQL 15 is the authoritative transaction database; PostgreSQL-specific types and indexes must be used in the database baseline.
- JDK 8 is mandatory; all selected versions must be Java 8 compatible. The current baseline uses Spring Boot 2.7.18, Spring MVC 5.3.31, Spring Security 5.7.11, MyBatis-Plus 3.5.5, PostgreSQL JDBC 42.2.27, Flyway 8.5.13, Redis 6.2.14, OpenTelemetry Java 1.32.0, Micrometer 1.10.13, Docker Compose 2.24.6, Kubernetes 1.28.15 and Helm 3.14.4. Authentication is account/password plus server session only.
- 《阿里巴巴 Java 开发手册》采用嵩山版；用户已确认 v0.4-draft 后端、v0.3-draft 前端、v0.3-draft 实施规范入口和 v0.4-draft 数据库规范作为设计输入，并允许容量、备份等参数保留待确认。数据库随后仅做了支持本 Feature 的最小 v0.5-draft 增量，仍保持 draft 状态。
- CR-017 adds API Token Bearer authentication for automation clients; it does not replace or alter the browser account/password Session flow and does not include CLI storage.

## Source Map

- docs/research/skill-hub-research.md:17, 54, 62, 95, 100-106, 143-156
- docs/research/skill-hub-research.md:206-218

## Source Facts

- No existing business source, API, persistence model, build script or test entry exists in the repository; design modules and implementation paths are greenfield targets.
- Stable cross-Feature association is `version_digest`; missing association must remain explicit and must not use `latest`.
- iflytek SkillHub is a reuse candidate for Registry, version, review, RBAC and scanning, but direct production reuse has not been validated.

## Implementation Source Map

- `backend/pom.xml`: Java 8-compatible Spring Boot, MyBatis-Plus, Flyway, PostgreSQL JDBC and Redis starter dependencies.
- `backend/src/main/java/com/km/skillhub/asset`: import request idempotency, artifact reference and failure trace.
- `backend/src/main/java/com/km/skillhub/version`: digest lookup and lifecycle state machine.
- `backend/src/main/java/com/km/skillhub/release`, `gate`, `policy`, `audit`, `governance`, `integration`: release scope, gate evidence, policy inheritance, approval separation, audit, retention, Outbox and Redis Streams adapter.
- `backend/src/main/resources/db/migration/V1__create_governance_scope.sql` through `V6__protect_immutable_governance_records.sql`: PostgreSQL 15 schema and immutable content protection.
- `backend/src/test/java/com/km/skillhub/integration/OutboxDeliveryIntegrationTest.java`: real PostgreSQL-to-Redis Streams Outbox delivery validation.
- `frontend/src/pages/asset-governance`, `frontend/src/modules/asset-governance`: catalog, release decision, policy, audit and governance components.
- `backend/src/test/java/com/km/skillhub/service` and `frontend/tests/unit`: executable lifecycle, gate, gray observation, retention, downstream contract and catalog response checks.

## Design Baseline Check

- status: pass
- present: `docs/product-development/architecture/index.md`, `backend-architecture.md` v0.4-draft, `frontend-architecture.md` v0.3-draft, `docs/product-development/standards/implementation/index.md` v0.3-draft, `database-design.md` v0.5-draft, `java-best-practices.md` (嵩山版), `component-standard.md`, `typescript-best-practices.md`, `typescript-doc-style-guide.md`.
- status: pass after user confirmation on 2026-09-02; the baselines remain draft documents but are valid inputs for this Feature design.
- impact: the Feature affects backend governance, frontend governance console, PostgreSQL 15 persistence and cross-Feature contracts; all relevant baseline paths are now present.
- rule: design must cite the confirmed draft versions and keep capacity, backup, RPO/RTO, storage products and formal package name as explicit pending items.

## Task Progress

- [x] Create v1 requirement.md.
- [x] Create v1 design.md and implementation-plan.md as invalid drafts.
- [x] Create draft backend and frontend architecture baselines.
- [x] Create draft architecture and implementation standard entry points.
- [x] Create SKILL HUB database design draft.
- [x] Confirm architecture and implementation baseline versions and applicability.
- [x] Re-enter design and implementation planning using the confirmed baseline.
- [x] Obtain user confirmation of design v2, implementation plan v2 and the database v0.5-draft increment.
- [x] Task 1: implement account/password, BCrypt, CSRF and server Session baseline.
- [x] Task 2: implement PostgreSQL 15 Flyway schema V1-V6 and append-only content protection.
- [x] Task 3: implement asset import, failure trace, request-id idempotency and SHA-256 version digest.
- [x] Task 4: implement immutable version content boundary and lifecycle state machine.
- [x] Task 5: complete the authorized catalog flow with import, asset detail and version management pages.
- [x] Task 6: implement company/project/environment release binding and current-binding uniqueness.
- [x] Task 7: implement policy snapshots, evidence persistence and gate decision states.
- [x] Task 8: implement configurable gray observation, rollback decision and Redis Streams Outbox adapter.
- [x] Task 9: implement RBAC approval separation, audit query and governance console pages.
- [x] Task 10: implement configurable/versioned retention policy defaults and unit validation.
- [x] Complete PostgreSQL 15, Redis Streams and Outbox integration validation in the isolated local environment.
- [x] CR-012: seed the requested `admin` and `user` accounts with BCrypt hashes and verified RBAC bindings.
- [x] CR-013: localize the governance console UI to Chinese and add display-label mapping tests.
- [x] CR-014: complete the catalog flow with Skill import, asset detail and version management pages.
- [x] CR-015 Task 11: implement real Skill package content, local artifact retrieval and file manifest.
- [x] CR-015 Task 12: implement Skill discovery search, namespace detail and metadata display.
- [x] CR-015 Task 13: implement semantic versions, tags and version comparison.
- [x] CR-015 Task 14: implement publish review and lifecycle workbench.
- [x] CR-015 Task 15: implement administrator account, namespace, tag and audit pages.
- [x] CR-015 Task 16: align console information architecture and company brand theme.
- [ ] CR-015 Task 17: execute incremental contract verification.
- [x] Defect fix: replace row-expanding authorization joins with EXISTS for catalog, discovery, namespace and review queries; add regression coverage for multi-role accounts.
- [x] CR-016 Task 18: implement enterprise console shell, grouped sidebar and responsive top workbar.
- [x] CR-016 Task 19: implement shared enterprise page layout and work overview.
- [x] CR-016 Task 20: complete Chinese display audit and visual/route verification.
- [x] CR-017 Task 21: implement Token data model and lifecycle API.
- [x] CR-017 Task 22: implement Bearer authentication and scope isolation.
- [x] CR-017 Task 23: implement Vue Token management console.
- [x] CR-017 Task 24: execute API Token contract verification.
- [x] CR-018: align the Token management console with the reference SkillHub layout, dialogs and Chinese copy.
- [x] CR-019: align the Token navigation label with the reference project's "访问凭证" terminology.
- [x] CR-020 Task 25: align reference navigation, dashboard, my skills, publish and token routes.
- [x] CR-020 Task 26: align search, skill detail, file browsing, version comparison and review dialogs.
- [x] CR-020 Task 27: align namespace, governance center, admin split pages and settings routes.
- [x] CR-020 Task 28: execute frontend contract, build and visual substitute checks.

## Verification Status

- pass: npm --prefix frontend run test; 4 frontend test files and 13 tests passed after updating the approved Chinese installation label.
- pass: npm --prefix frontend run build; Vue type check and Vite production build passed.
- pass: git diff --check and Vite HTTP check; source check passed and the development server returned HTTP 200.
- not-run: browser screenshot and interactive viewport verification; Playwright is not installed in frontend dependencies and no repository browser test entry is available.

- pass: JDK 8 targeted backend tests, backend Java 8 compile, frontend Vitest (13 tests), frontend production build, PostgreSQL 15.18 Flyway v10 migration, admin/user login and admin/user authorization smoke checks, and `git diff --check`.
- pass: `mvn -f backend/pom.xml -Dtest=AuthorizationQueryDeduplicationIntegrationTest test`; 2 tests passed, including catalog list uniqueness and asset detail HTTP 200 for an account with 8 scope roles.
- fail: full `mvn -f backend/pom.xml test`; 38 of 39 tests passed, while `OutboxDeliveryIntegrationTest.dispatchesPendingOutboxEventToRedisStream` failed after 121 seconds with expected 1 / actual 0.
- pass: Spring context started on port 18080 with 65 mapped routes; `admin` received 200 from `/api/v1/admin/accounts`, `user` received 403.
- not-run: full automated feature checker and end-to-end browser flow.
- unavailable: official website exact brand-color verification; current values are replaceable CSS tokens.
- unavailable: Redis Streams delivery validation in the current environment; the existing Outbox test failure is classified as `environment-failure` and is unrelated to the authorization query fix.

- pass: `mvn -f backend/pom.xml "-Dtest=com.km.skillhub.token.ApiTokenServiceTest,com.km.skillhub.token.ApiTokenAuthenticationFilterTest,com.km.skillhub.integration.ApiTokenIntegrationTest" test`; 9 tests passed, including PostgreSQL 15.18 Flyway V11 validation and Bearer scope/CSRF integration behavior.
- pass: `npm --prefix frontend run build`; Vue type check and Vite production build passed, including the Token management route bundle.
- pass: `npm --prefix frontend run test`; the existing frontend unit suite passed after CR-017 localization updates.
- unavailable: the current shell could not find `C:\Users\Administrator\.jdks\oracle\_open_jdk-8`; the CR-017 backend test process therefore ran on Java 21, while Maven source/target remains configured for Java 8 compatibility.
- pass: CR-018 `npm --prefix frontend run test`; 5 frontend test files and 16 tests passed.
- pass: CR-018 `npm --prefix frontend run build`; Vue type check and Vite production build passed, including the Token management route bundle.
- pass: CR-018 `git diff --check`; no whitespace errors were found.
- not-run: browser screenshot and interactive viewport verification; Playwright is not installed in frontend dependencies and no repository browser test entry is available.
- pass: CR-019 reference terminology check; the reference project uses `访问凭证`, `查看 API Tokens`, `Token 管理` and `管理 CLI 和 API 使用的访问凭证`, with no `Token 密码` or `API 密钥` label.
- pass: CR-020 `npm run test` in `frontend`; 5 test files and 16 tests passed.
- pass: CR-020 `npm run build` in `frontend`; Vue type check and Vite production build passed.
- pass: CR-020 `git diff --check`; no whitespace errors were found.
- pass: CR-020 static route/menu check; reference-aligned entries are reachable and excluded entries are absent from frontend source.
- pass: CR-020 native dialog check; no `window.confirm` or `window.prompt` remains in frontend source; confirmation, review opinion and file preview use `ModalDialog` or page controls.
- not-run: browser screenshot, responsive viewport and end-to-end interaction verification; Playwright is not installed and no repository browser test entry is available.
- unavailable: reference project backend capabilities absent from current contracts, including profile review, notification preference writes, namespace creation/member mutations and download statistics; corresponding pages show read-only or not-open status and do not simulate success.

## Test Feedback Index

- feedback: feedback-authorized-query-duplicate-rows
- source: user report and `AuthorizationQueryDeduplicationIntegrationTest`
- classification: implementation-defect
- affected_requirements: requirement-skill-catalog-search, requirement-skill-metadata-completeness
- decision: use EXISTS authorization predicates so multiple roles in one scope cannot duplicate business rows or break selectOne detail queries.
- feedback_status: resolved
- validation: validation-authorized-query-deduplication

## Current Boundary

- allowed: asset registration, metadata, catalog/discovery, package content, immutable versions/tags/comparison, namespace governance, release governance, RBAC, account administration, audit, retention policy, API Token lifecycle, Bearer scope isolation, design and implementation.
- forbidden: runtime adapters, installation scripts, evaluation runner, OAuth2/SSO/CLI Device Flow/S3/microservice architecture.

## CR-016 Checkpoint

- completed: enterprise console shell, grouped sidebar, top workbar, mobile navigation drawer, work overview, shared enterprise visual system, page spacing/table/form/status styles, and visible Chinese terminology cleanup.
- changed: frontend/src/components/AppShell.vue, frontend/src/components/layout/SideNavigation.vue, frontend/src/pages/dashboard/DashboardPage.vue, frontend/src/router/index.ts, frontend/src/styles.css, affected governance/discovery/installation pages and display-text tests.
- verification: frontend tests and production build pass; Vite responds on port 5173; browser screenshot verification not-run because Playwright is unavailable.
- next: restart the backend process on port 8080 before testing the new authorization classes, then complete CR-015 Task 17 browser/contract coverage when the browser test tool is available.

## Blockers and Handoff

- Previous implementation completed the requested catalog flow. CR-015 Tasks 11-16 are implemented; Task 17 remains open only for full browser contract coverage. Website exact color values remain unavailable and are represented by configurable brand tokens.
- The reported duplicate catalog rows and asset detail error were caused by the same account having 8 roles in one scope; the authorization query fix is implemented and validated against PostgreSQL 15.18. The running process on port 8080 must be restarted to load the new classes.
- The full suite still requires Redis Streams availability for `OutboxDeliveryIntegrationTest`; no code change was made for that unrelated environment failure.

## Current Task

- task: CR-021 侧边栏收敛与页签承载
- slice: slice-sidebar-tabs-regression
- status: completed
- allowed_files: frontend/src/components, frontend/src/pages, frontend/src/router/index.ts, frontend/src/styles.css, frontend/tests/unit, CR-021 control and feature documents
- next_stop: stop at the implementation-stage handoff; do not enter review, release-check or commit without explicit user instruction

## CR-021 Checkpoint

- started: 2026-09-03; scope is limited to the frontend sidebar information architecture and page-tab aggregation.
- approved: explicit user implementation request; no backend, authentication, authorization, persistence or excluded social capability changes are authorized.
- current_task: CR-021 implementation and regression verification completed.
- pending_tasks: none for CR-021.
- verification: frontend tests, production build, static exclusion checks and diff check passed; browser screenshot and interactive viewport verification are unavailable because Playwright is not installed.

## CR-021 Verification

- pass: `npm --prefix frontend run test`; 6 test files and 18 tests passed, including the sidebar exclusion and aggregate-tab structure checks.
- pass: `npm --prefix frontend run build`; Vue type check and Vite production build passed.
- pass: `git diff --check`; no whitespace errors were found.
- pass: static source check; 前置单字导航标记、推广管理、举报管理、账号合并、收藏和评分相关入口均未出现在 `frontend/src`。
- unavailable: browser screenshot, responsive viewport and real click-flow verification; Playwright is not installed in the frontend dependencies and no browser test entry is available.
- resolved feedback: the first structure-test attempt used unavailable Node typings and CSS `?raw` behavior; the test was narrowed to Vite-supported Vue source imports without adding dependencies, then the full suite passed.
