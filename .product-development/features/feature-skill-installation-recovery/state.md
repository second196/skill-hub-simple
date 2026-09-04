# Feature State

feature: feature-skill-installation-recovery
work_item: work-skill-hub-platform
decomposition_status: confirmed
artifact_dir: docs/product-development/features/feature-skill-installation-recovery
control_dir: .product-development/features/feature-skill-installation-recovery
current_phase: implementation
requirement_status: confirmed
design_status: confirmed
implementation_status: complete
review_status: not-requested
verification_status: not-requested
release_check_status: not-requested
requirement_version: v2
design_version: v2.1
plan_version: v2.2
active_change: CR-026
last_test_result: pass
feedback_status: resolved

## Work Item Boundary

- work_item: work-skill-hub-platform
- scope_decision: multi-feature
- allowed_requirements: requirement-skill-distribution-installation, requirement-skill-tracker-companion-installation, requirement-skill-installation-instance, requirement-skill-version-switching, requirement-skill-installation-failure-recovery, requirement-skill-emergency-revocation, requirement-cli-agent-integration-installation, requirement-cli-agent-integration-check, requirement-cli-agent-integration-recovery
- forbidden_requirements: asset registration, release gate definition, runtime metric aggregation, evaluation execution
- dependency_features: feature-skill-asset-release-governance

## Confirmed Decisions

- Four Feature split and dependencies confirmed by user on 2026-09-01.
- Runtime matrix is configurable; initial defaults are documented in requirement.md.
- CR-022 adds CLI-driven Agent adapter installation, diagnosis and recovery while preserving the backend orchestration/runtime adapter execution boundary.
- User confirmed requirement v1 as the design input on 2026-09-02.
- User confirmed the boundary: Java backend orchestration, runtime adapter execution, and Vue console presentation.
- User confirmed the Task 13 plan increment: actual runtime version, CLI server registration/events, first-offline pending registration, register-before-replay, install/repair reporting and strictly read-only status.
- CR-026 confirms that installation owns Local Collector provisioning/recovery, managed local-secret distribution, authenticated Hook/OTLP configuration and real-event VSIX installation; runtime observability owns listener routing, event semantics, privacy and spool.

## Source Map

- `backend/src/main/java/com/km/skillhub/installation/service/InstallationOrchestrationService.java`: control plane already validates published bindings/runtime capability and emits idempotent installation commands.
- `backend/src/main/java/com/km/skillhub/integration/runtime/RuntimeInstaller.java` and `RuntimeInstallerRegistry.java`: runtime execution is an interface boundary; no concrete host installer is registered.
- `backend/src/main/java/com/km/skillhub/installation/controller/InstallationEventController.java`: runtime receipts already enter the installation state machine through a server endpoint.
- `D:/program/witty-skill-insight/bin/cli.js` and `scripts/agent-trace-collectors/codex`: Witty uses Node-based install/status commands plus Bash/PowerShell wrappers and local collector self-checks.
- repository fact: the new SkillHub CLI must execute only whitelisted adapter operations on the local host and must not add remote arbitrary-command execution to the Java backend.
- docs/research/skill-hub-research.md:54-68, 95-108, 143-156
- `backend/src/main/java/com/km/skillhub/release/service/ReleaseScopeService.java:29-63`: only published versions can be bound to a scope; current binding is scope-aware.
- `backend/src/main/java/com/km/skillhub/integration/downstream/InstallationReleaseEventPublisher.java`: existing release-to-installation event bridge validates a concrete version digest.
- `backend/src/main/java/com/km/skillhub/integration/event/RedisStreamsOutboxDispatcher.java`: governance and installation command Outbox records are published to configurable Redis Streams with retry state.
- `backend/src/main/java/com/km/skillhub/integration/runtime/InstallationEventConsumer.java`: runtime receipts are deduplicated and delegated to the installation progress state machine.
- `backend/src/main/resources/db/migration/V8__create_installation_recovery_metadata.sql` and `V9__add_installation_event_stream.sql`: installation tables, event idempotency constraints and configurable stream key are migrated.
- `cli/src/telemetry/runtime-integration-reporter.ts`: persists registration intent before network access, registers by scope/runtime/target and replays stable events only after an integration ID is available.
- `cli/src/telemetry/integration-event-spool.ts`: accepts first-offline events without an integration ID and replays each runtime in event-sequence order.
- `cli/src/telemetry/collector-installer.ts`: serializes shared Collector configuration, creates/reads the local secret, safely reloads the managed process and restores the previous multi-runtime configuration on failure.
- `cli/src/adapters/codex/codex-cli-adapter.ts` and `cli/src/adapters/codex/assets/collector-assets.ts`: prepare the authenticated Collector before writes, inject local OTel headers and make the Hook read `collector.secret` at runtime with bounded failure isolation.
- `cli/src/adapters/claude/claude-code-otlp-adapter.ts`: prepares the Collector and owns only the managed Claude OTLP endpoint/header keys while preserving user configuration.
- `cli/src/commands/telemetry.ts`: derives a non-reversible telemetry partition from the API Token and injects the shared Collector provisioner without exposing the Token to adapters.
- `frontend/src/modules/installation-recovery/components/RuntimeIntegrationPanel.vue`: Session-based read-only server projection with explicit loading, empty, forbidden and error states.

## Design Baseline Check

- status: pass
- present: `docs/product-development/architecture/backend-architecture.md` v0.5-draft, `frontend-architecture.md` v0.3-draft, `docs/product-development/standards/implementation/index.md` v0.4-draft, `java-best-practices.md` active (嵩山版), `component-standard.md` active, `typescript-best-practices.md` active and `database-design.md` v0.6-draft.
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
- [x] Task 6：离线回执去重、Outbox 重试和运行观测事件代码已完成；`OutboxDeliveryIntegrationTest` 已使用现有 Memurai 进程和显式 `127.0.0.1:6379` 重跑通过。
- [x] Task 7：紧急撤回绑定阻断、逐实例操作和撤回回执状态；`EmergencyRevocationServiceTest` 与撤回状态定向测试在 JDK 8 下通过。
- [x] Task 8：Vue 安装列表、详情、Tracker/健康状态、操作处理和中文状态展示；前端测试与生产构建通过。
- [x] CR-022: create design.md v2-draft and implementation-plan.md v2-draft.
- [x] CR-022: human confirmation of v2 design pair.
- [x] CR-022 Task 9: runtime integration metadata and server contract; PostgreSQL V13 and JDK 8 targeted tests passed.
- [x] CR-022 Task 10: CLI adapter registry, atomic configuration/recovery framework, read-only diagnostics and idempotent offline event spool.
- [x] CR-022 Task 11: Codex CLI Hook/OTel adapter, bundled collector assets and VS Code/Cursor/Windsurf VSIX adapters.
- [x] CR-022 Task 12: Claude Code OTLP managed configuration, read-only diagnostics, conflict protection and atomic repair.
- [x] CR-022 Task 13: actual runtime version, CLI register-before-replay bridge, first-offline pending registration, Vue runtime integration tab and vertical contract evidence.
- [x] CR-026 Task 14: managed Collector process, local secret and authenticated Hook/OTLP configuration; CLI regression, build and security checks passed.
- [x] CR-026 Task 15: event-producing VSIX with bounded failure isolation；真实结构化编辑器事件、Collector 映射、确定性 VSIX 和 Mock 编辑器/HTTP 契约验证已完成。
- [x] CR-026 Task 16: installation/repair probe, recovery and real-host integration；真实 Codex Hook、VS Code 扩展、Collector 探针、停止隔离和 repair 恢复已完成。

## Verification Status

- pass: CR-026 design exit check; 9/9 requirement IDs appear in design v2.1 and plan v2.2, Task 14-16 fields are complete, cross-Feature order is consistent, and `git diff --check` has no whitespace errors.
- pass: CR-022 document check; all 9 requirement IDs appear in design.md v2-draft and implementation-plan.md v2-draft, all coverage rows are covered, and V13 ownership is consistent with V12/V14.
- pass: backend Java 8 compile; installation service tests 29 tests with 0 failures; `DefaultAccountSeedIntegrationTest` and `SessionSecurityIntegrationTest` 3 tests with 0 failures; PostgreSQL 15.18 Flyway validation with schema version 9; frontend 4 files/7 tests; `npm run build`; `git diff --check`.
- pass: CR-022 Task 9 JDK 8 targeted suite 13 tests with 0 failures; PostgreSQL 15.18 Flyway schema version 13; registration/event idempotency, stale sequence rejection, disabled account, cross-scope denial, Session query, Bearer scope and token/path redaction covered; `git diff --check` passed.
- pass: CR-022 Task 10 CLI suite 30 tests, 29 passed and 1 Windows symlink-permission skip; production TypeScript build passed; unknown runtime has no side effect, status is read-only, atomic lock/snapshot/concurrent-change protection and offline event idempotency are covered; safety scan found no dynamic Shell, `exec` or Token output.
- pass: CR-022 Task 11 CLI suite 38 tests, 37 passed and 1 Windows symlink-permission skip; production build and safety scan passed; Codex version gate, idempotent Hook/OTel merge, non-managed OTel conflict, Hook trust action, deterministic VSIX and per-editor result covered. Host read-only evidence: Codex 0.151.0-alpha.7.2 and VS Code detected, Cursor/Windsurf unavailable; no host installation executed.
- pass: CR-022 Task 12 CLI suite 47 tests, 46 passed and 1 Windows symlink-permission skip; production build, dynamic Shell/credential scan and whitespace scan passed. Claude 2.1.41 version gate, structured settings merge, custom endpoint conflict, six-part read-only diagnostics, 401/network classification, managed-key repair, failure rollback, concurrent-change protection and Token non-persistence are covered.
- pass: CR-022 Task 13 CLI suite 49 tests, 48 passed and 1 Windows symlink-permission skip; production TypeScript build passed. Actual runtime version registration, first-offline pending state, register-before-replay ordering, stable event replay, read-only status and Token non-persistence are covered.
- pass: CR-022 Task 13 JDK 8 `RuntimeIntegrationEndToEndTest` 1 test with 0 failures against PostgreSQL 15.18 and Flyway schema 13; ACTION_REQUIRED, failure, recovery, event idempotency, authorized list, forbidden detail and empty list are covered.
- pass: CR-022 Task 13 frontend 7 files/21 tests and production Vue build passed; Chinese runtime/status mapping, warning/danger tone, API parsing and the Skill installation/runtime integration tabs are covered. Dynamic Shell and credential persistence scans and `git diff --check` passed.
- pass: CR-026 Task 14 CLI regression has 101 tests with 100 passed, 0 failed and 1 Windows symlink-permission skip; production build passed. First secret/config creation, authenticated Codex Hook/OTLP and Claude OTLP, old unauthenticated managed-config upgrade, shared multi-runtime reload, reload failure recovery, concurrent configuration rejection, read-only status, Hook missing-secret zero exit and credential/output non-disclosure are covered. Static scans found loopback-only listener configuration, `shell: false`, no dynamic command execution, no port 43191 listener and no temporary workspace residue; `git diff --check` passed.
- fail: none.
- pass: CR-026 Task 16 real-host PoC; Memurai-backed Outbox rerun, managed Collector lifecycle, real Codex Hook event ingress, VS Code local VSIX installation and probe, protected-field check, Collector stop isolation and Codex repair recovery all passed.
- not-run: automated feature checker; full backend suite beyond the targeted Redis delivery test.
- unavailable: Python runtime is unavailable; Cursor、Windsurf 和 Claude Code 当前主机不可用，因此未执行对应主机安装；SkillHub 服务端真实登录凭据未提供，在线注册和补报未执行。

## Test Feedback

## Latest Validation

- validation: validation-installation-task6-real-redis-outbox-rerun
- task: Task 6 Redis Outbox delivery integration
- requirement: requirement-skill-installation-failure-recovery
- command: `mvn -f backend/pom.xml -Dtest=OutboxDeliveryIntegrationTest test`
- environment: JDK 8 `1.8.0_342`; PostgreSQL 15.18 at `D:\program_env\postgresql`; Memurai 4.2.2 at `D:\program_env\redis`; PostgreSQL `5432` and Redis-compatible `6379` are reachable, `PING` returns `PONG`
- expected: a pending PostgreSQL Outbox record is published to its Redis Stream, marked `PUBLISHED`, and test cleanup completes
- historical actual: the first run published the record and reached `PUBLISHED`, then Redis stream deletion timed out; this was superseded by the explicit-host rerun below
- actual: with the existing `memurai.exe` process PID 4156 and explicit `SKILLHUB_REDIS_HOST=127.0.0.1`, `OutboxDeliveryIntegrationTest` completed with 1 test, 0 failures, 0 errors and Maven `BUILD SUCCESS`
- status: pass
- classification: none
- evidence: `backend/target/surefire-reports/com.km.skillhub.integration.OutboxDeliveryIntegrationTest.txt`
- decision: Task 6 Redis evidence is restored; advance to Task 15. The production dispatcher was not changed.

- validation: validation-installation-task15-editor-extension-real-events
- task: CR-026 Task 15 event-producing VSIX
- requirement: `requirement-cli-agent-integration-installation`, `requirement-cli-agent-integration-check`, `requirement-cli-agent-integration-recovery`
- command: `npm --prefix cli test`; `npm --prefix cli run build`; VSIX source/security scan; `git diff --check`
- expected: VSIX sends activation, document, terminal and task lifecycle events to authenticated `/ide-event`; no `/status` polling, user content, credential package embedding, unbounded queue or editor-blocking network failure
- actual: 105 CLI tests with 104 passed, 0 failed and 1 existing Windows symlink-permission skip; production TypeScript build passed; Mock editor/HTTP contract received seven lifecycle event types; package determinism and sensitive-field scans passed
- status: pass
- classification: none
- evidence: `cli/test/integration/editor-extension-event-contract.test.ts`, `cli/test/unit/adapters/editor-extension-adapter.test.ts`, `cli/test/unit/telemetry/collectors/editor-collector.test.ts`
- decision: Task 15 complete; advance to Task 16 installation/repair probe and real-host integration.

- validation: validation-installation-task6-real-redis-outbox-rerun-explicit-host
- command: `SKILLHUB_REDIS_HOST=127.0.0.1 SKILLHUB_REDIS_PORT=6379 mvn -f backend/pom.xml -Dtest=OutboxDeliveryIntegrationTest test`
- environment: existing `D:\program_env\redis\memurai.exe` PID 4156; JDK 8 `1.8.0_342`; PostgreSQL 15.18
- expected: Outbox delivery and test cleanup complete without timeout
- actual: 1 test passed; 0 failures; 0 errors; Maven `BUILD SUCCESS`
- status: pass
- evidence: `backend/target/surefire-reports/com.km.skillhub.integration.OutboxDeliveryIntegrationTest.txt`

- validation: `validation-installation-task16-real-host-ingress-recovery`
- task: Task 16 串联安装、修复、探针和真实主机接入
- requirement: `requirement-cli-agent-integration-installation`、`requirement-cli-agent-integration-check`、`requirement-cli-agent-integration-recovery`
- command: `npm --prefix cli test`; `npm --prefix cli run build`; JDK 8 下 `OutboxDeliveryIntegrationTest`; Collector/Codex/VS Code 主机 PoC; `git diff --check`
- environment: JDK 8 `1.8.0_342`; Node.js `v20.20.2`; Codex `0.151.0-alpha.7.2`; VS Code `1.124.2`; existing Memurai PID 4156 at `127.0.0.1:6379`
- expected: Collector healthy before runtime writes; real Hook/VS Code probe reaches local telemetry; failure does not block host; repair restores managed state; protected event fields are absent.
- actual: Outbox 1/1 passed; CLI 108 tests with 107 passed, 0 failed, 0 errors and 1 existing Windows symlink-permission skip; build and security checks passed; Codex Hook produced `SESSION_STARTED`; VS Code produced `EDITOR_EXTENSION_PROBE` and `EDITOR_EXTENSION_ACTIVATED`; Collector stop followed by Codex repair restored `HEALTHY`.
- status: pass
- classification: none
- evidence: `docs/product-development/features/feature-skill-installation-recovery/evidence/runtime-ingress-host-poc.md`
- decision: Task 16 complete; stop before runtime observability Task 4C until the next implementation continuation.

- validation: `validation-installation-recovery-jdk8-and-frontend`
- feedback: `feedback-redis-outbox-environment-unavailable`
- classification: `environment-failure`
- source: `backend/target/surefire-reports/com.km.skillhub.integration.OutboxDeliveryIntegrationTest.txt`
- affected_requirements: `requirement-skill-installation-failure-recovery`
- decision: restore Memurai or provide a reachable Redis endpoint, then rerun the Redis integration test; do not mark the Feature implementation complete before that evidence exists.

## Plan Feedback

- validation: `validation-runtime-integration-vertical-contract`
- feedback: `feedback-runtime-integration-client-bridge-missing`
- classification: `plan-defect`
- source: Task 13 source-map review and `rg -n "runtime-integrations|integrationId|flush" cli/src`
- affected_requirements: `requirement-cli-agent-integration-installation`, `requirement-cli-agent-integration-recovery`
- affected_artifacts: `implementation-plan.md`, CLI production code and Task 13 contract tests
- baseline_versions: requirement=v2, design=v2, plan=v2.1
- decision: resolved by the approved v2.1 plan increment and the implemented CLI registration/reporting bridge, pending-registration persistence and runtime-version contract.
- approval: approved
- feedback_status: resolved

## Task 14 Validation Record

- validation: `validation-managed-collector-installation-auth`
- requirement: `requirement-cli-agent-integration-installation`, `requirement-cli-agent-integration-check`, `requirement-cli-agent-integration-recovery`
- slice: `slice-managed-collector-installation-auth`
- task: `task-managed-collector-installation-auth`
- kind: unit, integration, security, process, build
- test_path: `cli/test/integration/managed-collector-installation.test.ts`, `cli/test/unit/adapters/codex-cli-adapter.test.ts`, `cli/test/unit/adapters/claude-code-otlp-adapter.test.ts`, `cli/test/integration/codex-install-flow.test.ts`, `cli/test/integration/telemetry-repair-flow.test.ts`
- command: `npm --prefix cli test`; `npm --prefix cli run build`; loopback/dynamic-command/credential/residue scans; `git diff --check`
- expected: Collector is healthy and authenticated before Agent config writes; shared runtime config survives reload/recovery; status is read-only; API Token/local secret are not output or uploaded.
- actual: 100/101 tests passed with one Windows symlink-permission skip, build and all security/residue/diff checks passed.
- status: pass
- evidence: CLI TAP output and the five Task 14 test files above.

## Current Boundary

- allowed: Task 16 evidence and control-state closure; next continuation may enter runtime observability Task 4C.
- forbidden: runtime event aggregation, Java/Vue, evaluation runner, arbitrary remote command execution, Skill package upload and release.

## Blockers and Handoff

- Task 1 through Task 8 and CR-022 Task 9-13 are complete with targeted evidence. CR-026 Task 14-16 are complete; Task 6 Redis evidence was restored with the existing Memurai process.
- CR-022 `design.md` v2 and `implementation-plan.md` v2.1 remain implemented. CR-026 `design.md` v2.1 and `implementation-plan.md` v2.2 are confirmed; runtime-observability Task 4B and installation Task 14-15 are complete.
- Task 16 is complete with real Codex/VS Code and Memurai-backed evidence; the next ordered stop is runtime observability Task 4C. Historical Redis environment feedback is superseded by the explicit-host rerun.

## Current Task

- task: Task 16 installation/repair probe and real-host integration
- slice: slice-runtime-adapter-real-ingress-recovery
- status: complete
- allowed_files: Task 16 source, tests, host evidence and control-state closure
- next_stop: runtime observability Task 4C after explicit implementation continuation
