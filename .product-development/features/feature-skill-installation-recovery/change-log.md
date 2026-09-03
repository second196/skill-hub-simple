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
