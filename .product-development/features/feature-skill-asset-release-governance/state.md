# Feature State

feature: feature-skill-asset-release-governance
work_item: work-skill-hub-platform
decomposition_status: confirmed
artifact_dir: docs/product-development/features/feature-skill-asset-release-governance
control_dir: .product-development/features/feature-skill-asset-release-governance
current_phase: requirement
requirement_status: draft
design_status: not-started
implementation_status: not-started
review_status: not-requested
verification_status: not-requested
release_check_status: not-requested
requirement_version: v1
design_version: n/a
plan_version: n/a
active_change: CR-001
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

## Source Map

- docs/research/skill-hub-research.md:17, 54, 62, 95, 100-106, 143-156

## Task Progress

- [x] Create v1 requirement.md.
- [ ] Human requirement review.

## Verification Status

- pass: requirement.md has required sections and semantic requirement IDs.
- fail: none
- not-run: automated feature checker and human review.
- unavailable: Python runtime is unavailable.

## Current Boundary

- allowed: asset registration, metadata, catalog, immutable versions, release governance, RBAC, audit, retention policy.
- forbidden: runtime adapters, installation scripts, evaluation runner, implementation design.

## Blockers and Handoff

- Human requirement review is pending.
