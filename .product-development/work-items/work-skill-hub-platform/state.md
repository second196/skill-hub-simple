# Work Item State

work_item: work-skill-hub-platform
scope_decision: multi-feature
complexity: complex
decomposition_status: confirmed
feature_ids:
  - feature-skill-asset-release-governance
  - feature-skill-installation-recovery
  - feature-skill-runtime-observability
  - feature-skill-evaluation-evolution
feature_dependencies: asset-release -> installation-recovery -> runtime-observability; asset-release + runtime-observability -> evaluation-evolution; evaluation evidence feeds asset-release gates
requirement_mapping: docs/product-development/work-items/work-skill-hub-platform/decomposition.md
cross_feature_contracts: docs/product-development/work-items/work-skill-hub-platform/index.md
overall_acceptance: docs/product-development/work-items/work-skill-hub-platform/index.md#4
current_phase: implementation
active_change: CR-026
confirmed_by: user
confirmed_at: 2026-09-01
split_confirmed_by: user
split_confirmed_at: 2026-09-01

## Current conclusion

- Facts, boundaries, recommendations, and pending items were extracted from the research report.
- Four Feature IDs and their dependency relationships were confirmed by the user.
- Four formal Feature requirement.md files have been created; `feature-skill-asset-release-governance` has completed its v5 implementation, while CR-026 confirms installation design v2.1/plan v2.2 and runtime-observability design v1.1/plan v1.4.
- Runtime observability Task 1-3, Task 4A/4B and installation recovery Task 14 are complete; the next CR-026 implementation stop is installation recovery Task 15. Evaluation/evolution remains at requirement stage.
- `feature-skill-installation-recovery` v1 implementation reached Task 8 except for the recorded Redis evidence gap; its CR-022 installation increment Task 9-13 is implemented against confirmed design v2 and plan v2.1.
- CR-022 has expanded the confirmed requirement boundary to include a shared SkillHub CLI delivery channel: installation and adapter diagnosis belong to installation recovery, runtime collection and upload belong to runtime observability, and local Skill package validation/upload/review submission belong to asset governance.

## Pending confirmation

- [x] Confirm four Feature split and dependencies.
- [x] Confirm first-release runtime scope; runtime matrix remains configurable.
- [x] Confirm default rules for automatic release, canary, rollback, and data retention; all remain configurable.
- [x] Confirm creation of formal requirement.md files for all four Features.
- [x] Confirm installation recovery requirement v1 and its execution boundary.
- [x] Confirm installation recovery design.md and implementation-plan.md.
- [ ] Complete installation recovery implementation and verification.
- [x] Confirm CR-022 CLI increment and its three existing Feature ownership boundaries.
- [x] Complete CR-022 requirement review.
- [x] Enter design separately for the affected Features.
- [x] Confirm first-release runtime event storage as PostgreSQL 15 authoritative storage with Redis Streams aggregation notifications.
- [x] Create all three CR-022 design and implementation-plan draft pairs.
- [x] Confirm CR-022 design and implementation-plan increments for all three affected Features.
- [x] Confirm CR-026 local Collector/VSIX design correction for installation recovery and runtime observability.

## Verification status

- pass: CR-026 design exit; runtime-observability 12/12 and installation-recovery 9/9 requirement IDs appear in both design and plan, Task 4B/4C/14/15/16 fields are complete, versions and cross-Feature order are consistent, and `git diff --check` has no whitespace errors.
- pass: runtime-observability Task 4B Local Collector CLI regression has 92 tests with 91 passed, 0 failed and 1 Windows symlink-permission skip; CLI build, loopback/process/security scans, residue checks and `git diff --check` pass.
- pass: installation-recovery Task 14 managed Collector installation/authentication CLI regression has 101 tests with 100 passed, 0 failed and 1 Windows symlink-permission skip; CLI build, shared-runtime recovery/concurrency, local credential isolation, loopback/process/security scans, residue checks and `git diff --check` pass.
- pass: CR-022 design audit; asset 23/23, installation 9/9 and runtime-observability 12/12 requirement IDs appear in both design and plan; all coverage rows are covered; runtime plan has 10 complete Task records; V12/V13/V14 ownership and draft versions are consistent; active-document stale scan and `git diff --check` pass.
- pass: Feature lists match between overview and decomposition; 51 semantic requirement IDs are unique; paths and source indexes are present; formal requirement documents and control directories are present; git diff check has no whitespace errors.
- fail: none
- not-run: check_work_item.py
- unavailable: Python runtime is unavailable; the existing checker also contains an encoding-sensitive section matcher. The checker was not modified.

## CR-022 Handoff

- requirement_status: confirmed for the CLI increment
- affected_features: `feature-skill-installation-recovery`, `feature-skill-runtime-observability`, `feature-skill-asset-release-governance`
- credential_contract: browser account/password Session remains unchanged; CLI uses API Token Bearer with `telemetry:write` for runtime data and `skill:publish` for Skill package upload
- first_release_runtime: Codex CLI, VS Code/Cursor/Windsurf extensions and Claude Code OTLP; matrix remains configurable
- publish_semantics: CLI upload creates a draft by default; review submission and publication remain governed by existing gates and approval separation
- confirmed_storage: PostgreSQL 15 stores runtime events, batches, projections, aggregates and checkpoints; Redis Streams only carries aggregation notifications; raw events default to 365 days and CLI spool to 7 days
- pending_parameters: production capacity, backup, RPO/RTO, disaster recovery, ClickHouse threshold and final supported runtime versions
- current_design_order: asset governance -> installation recovery -> runtime observability
- design_status: all three CR-022 design pairs are confirmed; implementation is authorized in asset -> installation -> runtime-observability order
- implementation_handoff: asset Task 33-37, installation Task 9-14, runtime-observability Task 1-3 and Task 4A/4B are complete. Stop before installation Task 15; next order remains installation Task 15-16 -> runtime Task 4C -> runtime Task 5-10. The installation Feature's historical Task 6 evidence status remains tracked in its own state.
