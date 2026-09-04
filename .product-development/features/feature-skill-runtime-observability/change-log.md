# Feature Change Log

## CR-001

- Date: 2026-09-01
- User request: Create formal requirements with configurable runtime, release, rollback, and retention defaults.
- Type: requirement-clarification
- Reason: Formalize the runtime observability Feature from the confirmed Work Item decomposition.
- Affected requirements: all requirements in requirement.md
- Affected design: none
- Affected tasks: none
- Code scope: none
- Approval status: approved
- Verification status: not-run
- Replacement relation: none

## CR-022

- Date: 2026-09-03
- User request: SkillHub CLI supports runtime data collection, upload, offline buffering and recovery, aligned with the real Witty collection chain.
- Type: requirement-change
- Reason: Complete the client data path from target Agent platforms to SkillHub, with plugins/Hook/Collector, OTLP, local spool and server aggregation boundaries.
- Affected requirements: `requirement-cli-runtime-collection`, `requirement-cli-runtime-upload`, `requirement-cli-runtime-buffer-recovery`, `requirement-cli-runtime-data-minimization`
- Affected design: `design.md` v1 and `implementation-plan.md` v1 are confirmed; PostgreSQL 15 authoritative storage with Redis Streams notifications is confirmed
- Affected tasks: Task 1-10 for V14, secure OTLP ingest, CLI spool/upload, runtime collectors, attribution/dual views, aggregation, metrics, Vue console, retention and end-to-end evidence
- Code scope: no code changes in this round
- Approval status: approved; user confirmed the v1 design pair on 2026-09-03 and authorized ordered implementation
- Verification status: document checks pass, 12/12 requirements appear in design and plan, all coverage rows are covered, all 10 Tasks contain required fields, and V12/V13/V14 ownership is consistent; target Agent and OTLP end-to-end validation not run
- Replacement relation: extends CR-001 without replacing existing runtime observability requirements

## CR-023

- Date: 2026-09-03
- User request: Confirm the Task 1 plan increment.
- Type: plan-correction
- Reason: The root application uses an explicit `@MapperScan`; Task 1 omitted the startup configuration file required to register `com.km.skillhub.telemetry.mapper`.
- Affected requirements: `requirement-agent-runtime-event-collection`, `requirement-tracker-buffered-upload`, `requirement-cli-runtime-buffer-recovery`
- Affected design: none; `design.md v1` remains unchanged
- Affected tasks: Task 1 file list and Mapper registration step
- Code scope: add `com.km.skillhub.telemetry.mapper` to the existing scan list in `backend/src/main/java/com/km/skillhub/SkillHubApplication.java`
- Approval status: approved by user on 2026-09-03
- Verification status: pending Task 1 targeted integration tests and affected backend regression
- Replacement relation: `implementation-plan.md v1.1` incrementally replaces the Task 1 execution details in v1

## CR-024

- Date: 2026-09-03
- User request: Confirm the Task 3 plan increment.
- Type: plan-correction
- Reason: `npm --prefix cli test -- telemetry` appends `telemetry` as a path to `node --test` and fails with `Could not find 'cli/telemetry'`; the existing package script has no name-filter contract.
- Affected requirements: `requirement-tracker-buffered-upload`, `requirement-runtime-data-minimization`, `requirement-cli-runtime-upload`, `requirement-cli-runtime-buffer-recovery`, `requirement-cli-runtime-data-minimization`
- Affected design: none; `design.md v1` remains unchanged
- Affected tasks: Task 3 verification command only
- Code scope: none; the plan change replaces the invalid command with `npm --prefix cli test` and retains `npm --prefix cli run build`
- Approval status: approved by user on 2026-09-03
- Verification status: pass; corrected v1.2 CLI regression ran 68 tests with 67 passed and 1 environment-permission skip, CLI build passed, targeted backend telemetry tests passed 11/11 and full backend regression passed 90/90
- Replacement relation: `implementation-plan.md v1.2` incrementally replaces only the Task 3 verification command in v1.1

## CR-025

- Date: 2026-09-03
- User request: confirm the Task 4 verification-command correction discovered at the implementation checkpoint
- Type: plan-correction
- Reason: `npm --prefix cli test -- collectors runtime-collector-contract` appends two nonexistent paths to `node --test`; the package script has no test-name filter contract.
- Affected requirements: `requirement-agent-runtime-event-collection`, `requirement-cli-runtime-collection`, `requirement-cli-runtime-data-minimization`
- Affected design: none; `design.md v1` remains unchanged
- Affected tasks: Task 4 verification command only
- Code scope: the confirmed plan change replaces the invalid command with `npm --prefix cli test` and retains `npm --prefix cli run build`
- Approval status: approved by user on 2026-09-03
- Verification status: invalid v1.2 command reproduced after TypeScript compilation with `Could not find 'cli/collectors'`; corrected commands are pending Task 4 implementation
- Replacement relation: `implementation-plan.md v1.3` incrementally replaces only the Task 4 verification command in v1.2

## CR-026

- Date: 2026-09-03
- User request: implementation feedback discovered while executing the confirmed Task 4 runtime Collector plan
- Type: design-correction
- Reason: the installed Codex Hook and Claude Code OTLP configuration send to `127.0.0.1:43191`, but no local listener or process lifecycle exists; the current VSIX only polls `/status` and emits no runtime events. Fixture conversion cannot satisfy the real runtime collection acceptance path.
- Affected requirements: `requirement-agent-runtime-event-collection`, `requirement-cli-runtime-collection`, `requirement-cli-runtime-buffer-recovery`, `requirement-cli-runtime-data-minimization`
- Affected design: runtime-observability `design.md v1.1` and installation-recovery `design.md v2.1` define loopback listener ownership, lifecycle, local authentication, 1 MiB request limit, event-producing VSIX behavior and spool routing
- Affected tasks: runtime-observability Task 4 is split into completed Task 4A plus pending Task 4B/4C; installation-recovery adds Task 14-16 without rewriting completed Task 9-13
- Code scope: local Collector service and lifecycle, CLI command wiring, managed Hook/OTLP headers, event-producing editor extension assets and cross-Feature integration tests; no direct Collector remote/network/database writes and no remote command execution
- Approval status: approved by user on 2026-09-03; `design.md v1.1` and `implementation-plan.md v1.4` are confirmed with the linked installation design pair
- Verification status: pass; runtime 12/12 requirements appear in design and plan, Task 4B/4C contain all required execution fields, version/order/route/security contracts are consistent and `git diff --check` reports no whitespace errors; implementation tests not run
- Replacement relation: extends CR-022 and plan v1.3 without changing PostgreSQL 15, Redis Streams, Bearer upload, Session login or data-minimization decisions
