# Feature State

feature: feature-skill-installation-recovery
work_item: work-skill-hub-platform
decomposition_status: confirmed
artifact_dir: docs/product-development/features/feature-skill-installation-recovery
control_dir: .product-development/features/feature-skill-installation-recovery
current_phase: implementation
requirement_status: confirmed
design_status: confirmed
implementation_status: in-progress
review_status: not-requested
verification_status: not-requested
release_check_status: not-requested
requirement_version: v1
design_version: v1
plan_version: v1
active_change: CR-006
last_test_result: unavailable
feedback_status: open

## Work Item Boundary

- work_item: work-skill-hub-platform
- scope_decision: multi-feature
- allowed_requirements: requirement-skill-distribution-installation, requirement-skill-tracker-companion-installation, requirement-skill-installation-instance, requirement-skill-version-switching, requirement-skill-installation-failure-recovery, requirement-skill-emergency-revocation
- forbidden_requirements: asset registration, release gate definition, runtime metric aggregation, evaluation execution
- dependency_features: feature-skill-asset-release-governance

## Confirmed Decisions

- Four Feature split and dependencies confirmed by user on 2026-09-01.
- Runtime matrix is configurable; initial defaults are documented in requirement.md.
- User confirmed requirement v1 as the design input on 2026-09-02.
- User confirmed the boundary: Java backend orchestration, runtime adapter execution, and Vue console presentation.

## Source Map

- docs/research/skill-hub-research.md:54-68, 95-108, 143-156
- `backend/src/main/java/com/km/skillhub/release/service/ReleaseScopeService.java:29-63`: only published versions can be bound to a scope; current binding is scope-aware.
- `backend/src/main/java/com/km/skillhub/integration/downstream/InstallationReleaseEventPublisher.java`: existing release-to-installation event bridge validates a concrete version digest.
- `backend/src/main/java/com/km/skillhub/integration/event/RedisStreamsOutboxDispatcher.java`: governance and installation command Outbox records are published to configurable Redis Streams with retry state.
- `backend/src/main/java/com/km/skillhub/integration/runtime/InstallationEventConsumer.java`: runtime receipts are deduplicated and delegated to the installation progress state machine.
- `backend/src/main/resources/db/migration/V8__create_installation_recovery_metadata.sql` and `V9__add_installation_event_stream.sql`: installation tables, event idempotency constraints and configurable stream key are migrated.

## Design Baseline Check

- status: pass
- present: `docs/product-development/architecture/backend-architecture.md` v0.4-draft, `frontend-architecture.md` v0.3-draft, `docs/product-development/standards/implementation/index.md` v0.3-draft, `java-best-practices.md` active (嵩山版), `component-standard.md` active, `typescript-best-practices.md` active and `database-design.md` v0.5-draft.
- applicability: backend orchestration, Vue console, PostgreSQL 15 persistence, Redis Streams and cross-Feature contracts.
- constraint: actual host installation stays in runtime adapters; adapter protocol, object storage, capacity, retry/timeout, backup and RPO/RTO remain pending.

## Task Progress

- [x] Create v1 requirement.md.
- [x] Confirm requirement v1 and design boundary.
- [x] Create v1-draft design.md and implementation-plan.md.
- [x] Human design and implementation-plan review.
- [x] Task 1：安装域数据库模型和状态枚举；V8 在 PostgreSQL 15.18 执行成功，Java 8 定向测试通过。
- [x] Task 2：安装请求、发布校验和安装实例查询；JDK 8 定向测试 3/3 通过。
- [x] Task 3：运行时矩阵、制品访问和 Redis 命令契约；服务容器扫描和 JDK 8 定向回归测试通过。
- [x] Task 4：Skill/Tracker 分阶段安装、事件顺序校验和健康确认；`InstallationProgressServiceTest`、`InstallationStateMachineTest`、`TrackerBindingServiceTest` 在 JDK 8 下通过。
- [x] Task 5：版本切换、失败恢复和回退编排；`InstallationOrchestrationServiceTest` 与状态机定向测试在 JDK 8 下通过。
- [ ] Task 6：离线回执去重、Outbox 重试和运行观测事件代码已完成；`OutboxDeliveryIntegrationTest` 因 Redis 环境不可用未通过，待环境恢复后重跑。
- [x] Task 7：紧急撤回绑定阻断、逐实例操作和撤回回执状态；`EmergencyRevocationServiceTest` 与撤回状态定向测试在 JDK 8 下通过。
- [x] Task 8：Vue 安装列表、详情、Tracker/健康状态、操作处理和中文状态展示；前端测试与生产构建通过。

## Verification Status

- pass: backend Java 8 compile; installation service tests 29 tests with 0 failures; `DefaultAccountSeedIntegrationTest` and `SessionSecurityIntegrationTest` 3 tests with 0 failures; PostgreSQL 15.18 Flyway validation with schema version 9; frontend 4 files/7 tests; `npm run build`; `git diff --check`.
- fail: none.
- not-run: automated feature checker; host-level runtime adapter installation; full backend suite including Redis delivery after environment recovery.
- unavailable: `OutboxDeliveryIntegrationTest` cannot connect to Redis because the Memurai service is stopped and port 6379 is unavailable; a temporary Memurai attempt on 6380 also did not become ready; Python runtime is unavailable.

## Test Feedback

- validation: `validation-installation-recovery-jdk8-and-frontend`
- feedback: `feedback-redis-outbox-environment-unavailable`
- classification: `environment-failure`
- source: `backend/target/surefire-reports/com.km.skillhub.integration.OutboxDeliveryIntegrationTest.txt`
- affected_requirements: `requirement-skill-installation-failure-recovery`
- decision: restore Memurai or provide a reachable Redis endpoint, then rerun the Redis integration test; do not mark the Feature implementation complete before that evidence exists.

## Current Boundary

- allowed: installation domain persistence, orchestration, runtime adapter contracts, Tracker pairing, instance state, version switching, recovery, rollback, emergency revocation and Vue console implementation.
- forbidden: runtime event collection implementation, evaluation runner, arbitrary remote command execution and release.

## Blockers and Handoff

- Task 1 through Task 5, Task 7 and Task 8 implementation completed with targeted evidence. Task 6 remains open pending Redis integration evidence. Host-level installation remains an external runtime-adapter responsibility; no adapter protocol or remote execution implementation was added.
