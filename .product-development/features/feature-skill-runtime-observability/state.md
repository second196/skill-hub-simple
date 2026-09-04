# Feature State

feature: feature-skill-runtime-observability
work_item: work-skill-hub-platform
decomposition_status: confirmed
artifact_dir: docs/product-development/features/feature-skill-runtime-observability
control_dir: .product-development/features/feature-skill-runtime-observability
current_phase: implementation
requirement_status: confirmed
design_status: confirmed
implementation_status: in-progress
review_status: not-requested
verification_status: not-requested
release_check_status: not-requested
requirement_version: v2
design_version: v1.1
plan_version: v1.4
active_change: CR-026
last_test_result: pass
feedback_status: resolved

## Work Item Boundary

- work_item: work-skill-hub-platform
- scope_decision: multi-feature
- allowed_requirements: requirement-agent-runtime-event-collection, requirement-skill-invocation-version-attribution, requirement-tracker-buffered-upload, requirement-trace-dual-view, requirement-skill-observability-metrics, requirement-observability-comparison-validity, requirement-runtime-data-minimization, requirement-platform-observability, requirement-cli-runtime-collection, requirement-cli-runtime-upload, requirement-cli-runtime-buffer-recovery, requirement-cli-runtime-data-minimization
- forbidden_requirements: asset registration, package publication, installation orchestration, evaluation decisions, release approval, arbitrary remote command execution
- dependency_features: feature-skill-asset-release-governance, feature-skill-installation-recovery

## Confirmed Decisions

- [DEC-001] Four Feature split and dependencies confirmed by user on 2026-09-01.
- [DEC-002] CR-022 confirms SkillHub CLI as the client entry for adapter-based collection, OTLP upload, local buffering/recovery and pre-upload data minimization.
- [DEC-003] First-release authoritative runtime-event, ingest-batch, Trace/invocation projection, metric and checkpoint storage is PostgreSQL 15; Redis Streams only carries asynchronous aggregation notifications and is not a source of truth.
- [DEC-004] Raw runtime events default to 365 days, server deduplication defaults to at least 372 days, CLI spool defaults to 7 days and aggregate metrics default to permanent retention; policies remain configurable and auditable.
- [DEC-005] ClickHouse is only a later capacity-driven migration boundary. Capacity, backup, RPO/RTO and disaster-recovery parameters remain pending and prevent a production release conclusion.
- [DEC-006] Task 1 plan v1.1 adds the existing root Mapper scan configuration so telemetry Mapper interfaces are registered consistently in production and integration tests.
- [DEC-007] CR-025 confirms plan v1.3: Task 4 uses `npm --prefix cli test` plus `npm --prefix cli run build` because the CLI test script does not support positional name filters.
- [DEC-008] CR-026 confirms a loopback-only authenticated Local Collector with 1 MiB default request limit, managed process lifecycle, real VSIX events and cross-Feature order Task 4B -> installation Task 14-16 -> Task 4C; Task 4A remains completed.

## Source Map

- `backend/src/main/java/com/km/skillhub/integration/downstream/RuntimeEvidenceContract.java`: currently validates only a SHA-256 version digest; no telemetry ingest, event store, projection, aggregation or query implementation exists.
- `backend/src/main/java/com/km/skillhub/token/service/ApiTokenService.java` and `token/security/ApiTokenScopeFilter.java`: `telemetry:write` is implemented for runtime-integration routes; Task 2 must add the telemetry ingest route without changing Session behavior.
- `backend/src/main/java/com/km/skillhub/integration/event/RedisStreamsOutboxDispatcher.java`: existing PostgreSQL Outbox-to-Redis pattern is reusable; runtime telemetry requires its own Outbox and checkpoint semantics.
- `backend/src/main/resources/application.yml`: PostgreSQL and Redis configuration exists; no ClickHouse dependency or analysis-store configuration exists.
- `backend/src/main/resources/db/migration/V14__create_runtime_observability.sql`: PostgreSQL 15.18 schema 14 now contains the authoritative telemetry tables and UTC monthly partitions for 2026-09 through 2026-11; V14 is immutable after successful Flyway execution.
- `backend/src/main/java/com/km/skillhub/telemetry/store/PostgresTelemetryEventStore.java`: `appendBatch` atomically writes ingest batch, global deduplication, partitioned events and one aggregation Outbox notification per request.
- `backend/src/main/java/com/km/skillhub/SkillHubApplication.java`: the explicit root `@MapperScan` includes `com.km.skillhub.telemetry.mapper` under confirmed plan v1.1.
- `frontend/src/router/index.ts` and `frontend/src/components/AppShell.vue`: current Vue route and Chinese navigation entry points; no runtime-observability module exists.
- `cli/`: the shared Node.js 20 CLI now provides API Token access, runtime adapters, canonical event conversion, privacy filtering, JSONL spool/upload, the loopback-only authenticated Local Collector and installation-owned authenticated Hook/OTLP configuration; event-producing VSIX and host probe closure remain pending installation Task 15-16.
- `feature-skill-installation-recovery` CR-022 Task 9-13: runtime integration metadata, local adapters and Vue status are implemented; the older installation Task 6 Redis evidence gap remains tracked but does not change the confirmed runtime-observability implementation order.
- `D:/program/witty-skill-insight/scripts/agent-trace-collectors/`: reference evidence for stable IDs, JSONL spool, checkpoint, redaction, bounded retry and Codex/Claude adapters.

## Design Baseline Check

- status: pass
- architecture: `backend-architecture.md` v0.5-draft, `frontend-architecture.md` v0.3-draft and `architecture/index.md`.
- standards: `implementation/index.md` v0.4-draft, `database-design.md` v0.6-draft, Java best practices based on Alibaba Java Manual Songshan Edition, Vue component standard and both TypeScript standards.
- constraint: PostgreSQL 15 + Redis Streams storage boundary is confirmed; capacity, backup, RPO/RTO, disaster recovery and final runtime versions remain pending.

## Task Progress

- [x] Confirm requirement.md v2 and CR-022 ownership.
- [x] Confirm first-release PostgreSQL 15 + Redis Streams storage decision.
- [x] Create design.md v1-draft.
- [x] Create implementation-plan.md v1-draft with Task 1-10 and requirement coverage.
- [x] Human confirmation of design.md v1 and implementation-plan.md v1.
- [x] Task 1：建立 V14 运行观测权威数据模型（completed）。
- [x] Task 2：实现 OTLP JSON 接收、鉴权和受理结果（completed）。
- [x] Task 3：实现 CLI 标准事件、脱敏、spool 和补报上传（completed）。
- [x] Task 4A：Codex、编辑器和 Claude Code 事件转换器、fixture 与契约测试（completed）。
- [x] Task 4B：受鉴权本地 Collector 服务、路由和进程生命周期（completed）。
- [x] Installation Task 14：受管 Collector、本地密钥和 Hook/OTLP 鉴权配置（completed）。
- [ ] Installation Task 15-16：事件化 VSIX 和安装恢复闭环（design confirmed; implementation not started）。
- [ ] Task 4C：真实入口、故障隔离和主机 PoC（depends on Task 4B and installation Task 14-16）。
- [ ] Task 5-10：按已确认计划顺序待实施。

## Verification Status

- pass: CR-026 design exit check; 12/12 requirement IDs appear in design v1.1 and plan v1.4, Task 4B/4C fields are complete, cross-Feature order and local route/security contracts are consistent, and `git diff --check` has no whitespace errors.
- pass: JDK 8 compile; Flyway schema 14 validation; Task 1 targeted PostgreSQL tests 3/3; Task 2/3 targeted telemetry tests 11/11; Redis `PING`; existing Outbox Redis integration 1/1; Task 4 CLI regression 77 tests with 76 passed and 1 environment-permission skip; CLI build; full backend regression 90/90; Chinese CLI help; privacy/network scan and `git diff --check`.
- pass: Task 4B CLI regression has 92 tests with 91 passed, 0 failed and 1 Windows symlink-permission skip; CLI build passes; loopback, local authentication, 1 MiB pre-parse limit, Hook/OTLP/IDE routing, spool failure, stable event, process lifecycle, stale lock, unknown port, startup timeout and identity-mismatch stop paths are covered. Static scans find `shell: false`, no non-loopback listener, no remote/API Token client in Collector, no process/port/temp-directory residue, and `git diff --check` passes.
- pass: installation Task 14 cross-Feature dependency is complete; CLI regression has 101 tests with 100 passed, 0 failed and 1 Windows symlink-permission skip, and authenticated Hook/OTLP configuration, shared Collector recovery/concurrency and credential isolation are covered.
- fail: historical v1.2 Task 4 command `npm --prefix cli test -- collectors runtime-collector-contract` passed nonexistent paths to `node --test` and failed with `Could not find 'cli/collectors'`; CR-025 resolved the plan defect.
- fail: historical static check found no local listener at `127.0.0.1:43191`; Task 4B resolves the listener gap. The event-producing VSIX remains intentionally pending installation Task 14-16 before Task 4C.
- not-run: installation Task 15-16, CR-026 cross-Feature real-ingress/VSIX integration tests and Task 5-10 implementation tests.
- unavailable: production capacity, backup/RPO/RTO evidence and real-host verification for Claude Code, Cursor and Windsurf; only Codex and VS Code executables are detected locally.

## Test Feedback Index

- validation: validation-runtime-collector-contract
- feedback: feedback-cli-collector-test-command-is-not-a-filter
- classification: plan-defect
- source: validation-runtime-collector-contract
- affected_requirements: requirement-agent-runtime-event-collection, requirement-cli-runtime-collection, requirement-cli-runtime-data-minimization
- feedback_status: resolved by approved CR-025 and plan v1.3

- validation: validation-real-runtime-collector-ingress
- feedback: feedback-local-collector-ingress-lifecycle-missing
- classification: design-defect
- source: static source/port inspection after `validation-runtime-collector-contract`
- affected_requirements: requirement-agent-runtime-event-collection, requirement-cli-runtime-collection, requirement-cli-runtime-buffer-recovery, requirement-cli-runtime-data-minimization
- affected_artifacts: runtime-observability design/plan, installation-recovery design/plan, Collector code and integration tests
- feedback_status: resolved by approved CR-026, runtime design v1.1 and plan v1.4, and passing Task 4B implementation evidence

- validation: validation-local-collector-process-lifecycle
- feedback: feedback-stale-lock-test-process-probe
- classification: test-defect
- source: Task 4B full CLI regression
- affected_requirements: requirement-cli-runtime-buffer-recovery
- affected_artifacts: `cli/test/integration/local-collector-server.test.ts`
- feedback_status: resolved; the fixture now treats only the synthetic stale PID as absent and delegates real child PIDs to the operating-system process probe

## Current Boundary

- allowed: Task 4B control-state closure only; no further source modification is authorized at this stop point.
- forbidden: installation adapter/Hook/OTLP/VSIX changes, Java/Vue, Task 4C, Task 5-10, evaluation, commit and release until separately requested.

## Blockers and Handoff

- V14 has been applied successfully and must not be edited; any later database correction requires a forward-compatible migration.
- Task 1 `slice-runtime-event-authoritative-store`, Task 2 `slice-secure-otlp-ingest` and Task 3 `slice-cli-durable-telemetry-upload` are complete. Task 3 provides canonical events, client/server privacy evidence merging, JSONL spool/checkpoint, bounded retry, terminal-result validation, retention/capacity drop reporting, `telemetry flush/status` and isolated stable request IDs.
- Completed task: Task 4B `slice-local-collector-ingress-lifecycle` provides authenticated Hook/OTLP/IDE loopback ingress, managed configuration/secret/PID/lock state, shared canonical conversion/spool routing and `collector-start`/`collector-stop`/`collector-status`.
- Evidence: `npm --prefix cli test` passes 91/92 with only the environment-permission symlink skip; `npm --prefix cli run build`, security scans and `git diff --check` pass. No process listens on `127.0.0.1:43191`, no Collector worker remains and no Task 4B temporary directory remains after tests.
- Next stop: installation recovery Task 15; Task 4C remains blocked by installation Task 15-16 and must not start early.
- Remaining pending parameters do not block design confirmation, but must be resolved before a production release conclusion.
