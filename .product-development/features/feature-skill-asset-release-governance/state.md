# Feature State

feature: feature-skill-asset-release-governance
work_item: work-skill-hub-platform
decomposition_status: confirmed
artifact_dir: docs/product-development/features/feature-skill-asset-release-governance
control_dir: .product-development/features/feature-skill-asset-release-governance
current_phase: design
requirement_status: draft
design_status: blocked
implementation_status: not-started
review_status: not-requested
verification_status: not-requested
release_check_status: not-requested
requirement_version: v1
design_version: v1
plan_version: v1
active_change: CR-008
last_test_result: not-run
feedback_status: none

## Work Item Boundary

- work_item: work-skill-hub-platform
- scope_decision: multi-feature
- allowed_requirements: requirement-skill-asset-registration, requirement-skill-metadata-completeness, requirement-skill-catalog-search, requirement-skill-version-immutability, requirement-skill-lifecycle-state, requirement-skill-release-scope, requirement-skill-release-gate, requirement-skill-governance-audit, requirement-skill-data-retention
- forbidden_requirements: runtime event collection, installation execution, evaluation execution
- dependency_features: none

## Confirmed Decisions

- Four Feature split and dependencies confirmed by user on 2026-09-01.
- Release and retention policies use configurable defaults and versioned audit records.
- Design uses an independent governance domain with optional iflytek capability adapters; gray traffic execution and runtime rollback remain downstream responsibilities.
- PostgreSQL 15 is the authoritative transaction database; PostgreSQL-specific types and indexes must be used in the database baseline.
- JDK 8 is mandatory; all selected versions must be Java 8 compatible. The current baseline uses Spring Boot 2.7.18, Spring MVC 5.3.31, Spring Security 5.7.11, MyBatis-Plus 3.5.5, PostgreSQL JDBC 42.2.27, Flyway 8.5.13, Redis 6.2.14, OpenTelemetry Java 1.32.0, Micrometer 1.10.13, Docker Compose 2.24.6, Kubernetes 1.28.15 and Helm 3.14.4. Authentication is account/password plus server session only.

## Source Map

- docs/research/skill-hub-research.md:17, 54, 62, 95, 100-106, 143-156
- docs/research/skill-hub-research.md:206-218

## Source Facts

- No existing business source, API, persistence model, build script or test entry exists in the repository; design modules and implementation paths are greenfield targets.
- Stable cross-Feature association is `version_digest`; missing association must remain explicit and must not use `latest`.
- iflytek SkillHub is a reuse candidate for Registry, version, review, RBAC and scanning, but direct production reuse has not been validated.

## Design Baseline Check

- status: blocked
- present: `docs/product-development/architecture/index.md`, `backend-architecture.md` v0.4-draft, `frontend-architecture.md` v0.3-draft, `docs/product-development/standards/implementation/index.md` v0.3-draft, `database-design.md` v0.4-draft, `java-best-practices.md`, `component-standard.md`, `typescript-best-practices.md`, `typescript-doc-style-guide.md`.
- status: still blocked; architecture and implementation baselines are drafts and require user confirmation before formal design.
- impact: the Feature affects backend governance, frontend governance console, PostgreSQL 15 persistence and cross-Feature contracts; all relevant baseline paths are now present.
- rule: do not use draft baselines as confirmed design input; database model must follow `database-design.md` after its scope and assumptions are confirmed.

## Task Progress

- [x] Create v1 requirement.md.
- [x] Create v1 design.md and implementation-plan.md as invalid drafts.
- [x] Create draft backend and frontend architecture baselines.
- [x] Create draft architecture and implementation standard entry points.
- [x] Create SKILL HUB database design draft.
- [ ] Confirm architecture and implementation baseline versions and applicability.
- [ ] Re-enter formal design after baseline confirmation.

## Verification Status

- pass: requirement.md has required sections and semantic requirement IDs.
- fail: none
- not-run: automated feature checker, design/plan review and implementation tests.
- unavailable: Python runtime is unavailable.

## Current Boundary

- allowed: asset registration, metadata, catalog, immutable versions, release governance, RBAC, audit, retention policy, design and implementation planning.
- forbidden: runtime adapters, installation scripts, evaluation runner, code implementation, review, verification and release-check.

## Blockers and Handoff

- Formal design is blocked because required architecture and implementation baselines are still drafts and require user confirmation; current design and plan are invalid drafts and cannot be used for implementation.
