# Feature Change Log

## CR-001

- Date: 2026-09-01
- User request: Create formal requirements with configurable runtime, release, rollback, and retention defaults.
- Type: requirement-clarification
- Reason: Formalize the installation and recovery Feature from the confirmed Work Item decomposition.
- Affected requirements: all requirements in requirement.md
- Affected design: none
- Affected tasks: none
- Code scope: none
- Approval status: approved
- Verification status: not-run
- Replacement relation: none

## CR-002

- Date: 2026-09-02
- User confirmation: 确认 `requirement.md v1` 作为设计输入，并确认“后端编排、运行时适配器实际安装、Vue 控制台展示”的边界。
- Type: design-start
- Reason: 需求基线和安装执行边界已确认，形成安装、Tracker 配对、版本切换、失败恢复和紧急撤回的方案双产物。
- Affected requirements: all requirements in `requirement.md`
- Affected design: create `design.md v1-draft`
- Affected tasks: create `implementation-plan.md v1-draft`
- Code scope: none; design only
- Approval status: requirement input approved; design and implementation plan confirmation pending
- Verification status: document structure and coverage checks pending
- Replacement relation: none

## CR-003

- Date: 2026-09-02
- User confirmation: 确认 `design.md v1` 和 `implementation-plan.md v1`。
- Type: design-confirmation
- Reason: 固化安装、Tracker 配对、版本切换、失败恢复和紧急撤回方案，解除设计阶段确认阻塞。
- Affected requirements: all requirements in `requirement.md`
- Affected design: `design.md v1` confirmed
- Affected tasks: `implementation-plan.md v1` confirmed
- Code scope: none; implementation not started
- Approval status: approved
- Verification status: document structure, coverage and `git diff --check` passed
- Replacement relation: confirms CR-002 design handoff

## CR-004

- Date: 2026-09-02
- User request: 开始实施 `feature-skill-installation-recovery`。
- Type: implementation-start
- Reason: 按已确认的 `design.md v1` 和 `implementation-plan.md v1` 开始实现安装域。
- Affected requirements: all requirements in `requirement.md`
- Affected design: none
- Affected tasks: Task 1 through Task 8, executed sequentially
- Code scope: `backend/`, `frontend/` and installation Feature tests
- Approval status: approved by explicit user request
- Verification status: Task 1 in progress
- Replacement relation: none

## CR-022

- Date: 2026-09-03
- User request: SkillHub CLI supports target Agent plugin, Hook, Collector and OTLP configuration installation, checking and recovery.
- Type: requirement-change
- Reason: Bring the Witty installation guide, adapter checks and failure recovery chain into the installation Feature while preserving backend orchestration and runtime adapter execution boundaries.
- Affected requirements: `requirement-cli-agent-integration-installation`, `requirement-cli-agent-integration-check`, `requirement-cli-agent-integration-recovery`
- Affected design: `design.md` v2 remains unchanged; `implementation-plan.md` is incremented from v2 to v2.1
- Affected tasks: Task 9-12 remain unchanged; Task 13 additionally covers actual runtime version reporting, CLI server registration/event APIs, first-offline pending registration, register-before-replay ordering and install/repair reporting while keeping status read-only
- Code scope: Task 13 CLI reporting bridge, Vue runtime integration tab and CLI/Java/Vue vertical contract tests
- Approval status: approved; user confirmed the v2 design pair and ordered implementation on 2026-09-03, then explicitly confirmed the Task 13 v2.1 plan increment
- Verification status: document checks pass, 9/9 requirements appear in design and plan, all coverage rows are covered, and V12/V13/V14 ownership is consistent; host-level installation not run
- Replacement relation: extends CR-004 without replacing the original Skill/Tracker installation and recovery requirements

## CR-026

- Date: 2026-09-03
- User request: confirm the cross-Feature design correction for the missing `127.0.0.1:43191` listener lifecycle and event-producing VSIX
- Type: design-correction
- Reason: completed Task 11-13 install Hook/OTLP/VSIX assets, but installation did not provision an authenticated local Collector or prove that VSIX emits real events
- Affected requirements: `requirement-cli-agent-integration-installation`, `requirement-cli-agent-integration-check`, `requirement-cli-agent-integration-recovery`
- Affected design: `design.md v2.1` adds Collector ownership, managed local secret, Hook/OTLP headers, VSIX event contract, diagnosis and rollback
- Affected tasks: `implementation-plan.md v2.2` adds Task 14-16; completed Task 9-13 remain unchanged
- Code scope: CLI Collector installer/health, Codex and Claude adapter configuration, VSIX assets, telemetry command wiring and host integration evidence
- Approval status: approved by user on 2026-09-03 together with runtime-observability `design.md v1.1` and `implementation-plan.md v1.4`
- Verification status: pass; installation 9/9 requirements appear in design and plan, Task 14-16 contain all required execution fields, versions and cross-Feature order are consistent and `git diff --check` reports no whitespace errors; implementation tests and host PoC not run
- Replacement relation: incrementally corrects CR-022 without changing backend orchestration, server registration, Vue status or existing Task 9-13 evidence
