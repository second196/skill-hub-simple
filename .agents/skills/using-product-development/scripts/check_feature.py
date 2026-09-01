#!/usr/bin/env python3
"""Validate the structural invariants of one Feature."""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path


REQUIRED_STATE = (
    "current_phase",
    "feature",
    "work_item",
    "decomposition_status",
    "artifact_dir",
    "control_dir",
    "requirement_version",
    "design_version",
    "plan_version",
    "last_test_result",
    "feedback_status",
)
PHASES = {
    "requirement",
    "design",
    "implementation",
    "review",
    "verification",
    "release-check",
    "documentation",
}
DECOMPOSITION_STATUSES = {
    "draft",
    "confirmed",
    "superseded",
    "blocked",
    "needs-confirmation",
}
REQUIREMENT_RE = re.compile(r"\brequirement-[a-z0-9]+(?:-[a-z0-9]+)*\b")
FEATURE_RE = re.compile(r"\bfeature-[a-z0-9]+(?:-[a-z0-9]+)+\b")
LEGACY_REQUIREMENT_RE = re.compile(r"\bREQ-(?:[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*)\b")
NUMERIC_REQUIREMENT_RE = re.compile(r"\brequirement-\d+(?:-\d+)*\b")
GENERIC_SLUGS = {"id", "req", "task", "new", "feature", "test", "todo"}
TEST_RESULTS = {"not-run", "pass", "fail", "unavailable"}
FEEDBACK_STATUSES = {"none", "open", "awaiting-confirmation", "resolved", "accepted-risk"}
FEEDBACK_CLASSIFICATIONS = {
    "implementation-defect",
    "design-defect",
    "plan-defect",
    "requirement-change",
    "test-defect",
    "environment-failure",
    "flaky-or-timeout",
    "none",
}


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def field(text: str, name: str) -> str | None:
    match = re.search(rf"^\s*(?:-\s*)?{re.escape(name)}\s*:\s*([^\n\r]+)", text, re.MULTILINE)
    return match.group(1).strip() if match else None


def default_control_dir(feature: Path) -> Path | None:
    parts = feature.resolve().parts
    marker = ("docs", "product-development", "features")
    for index in range(len(parts) - len(marker) + 1):
        if parts[index : index + len(marker)] == marker:
            repo_root = Path(*parts[:index])
            return repo_root / ".product-development" / "features" / feature.name
    return None


def default_work_item_dir(feature: Path, work_item: str) -> Path | None:
    parts = feature.resolve().parts
    marker = ("docs", "product-development", "features")
    for index in range(len(parts) - len(marker) + 1):
        if parts[index : index + len(marker)] == marker:
            repo_root = Path(*parts[:index])
            return repo_root / "docs" / "product-development" / "work-items" / work_item
    return None


def validate_feature_name(feature: Path) -> str | None:
    match = re.fullmatch(r"feature-([a-z0-9]+(?:-[a-z0-9]+)+)", feature.name, re.IGNORECASE)
    if not match:
        return "Feature directory must use feature-<meaningful-object>-<action> format"
    slug = match.group(1).lower()
    tokens = slug.split("-")
    if all(token.isdigit() for token in tokens):
        return "Feature directory cannot contain only numbers"
    if re.fullmatch(r"(?:req|task)-\d+", slug) or slug in {"new-feature", "test"}:
        return "Feature directory cannot use a generic REQ, Task, new-feature, or test name"
    meaningful_tokens = [token for token in tokens if token not in GENERIC_SLUGS and not token.isdigit()]
    if len(meaningful_tokens) < 2:
        return "Feature directory must contain meaningful object and action words"
    return None


def validate_requirement_names(text: str, label: str, errors: list[str]) -> set[str]:
    if LEGACY_REQUIREMENT_RE.search(text):
        errors.append(f"{label} contains legacy REQ-* identifiers")
    if NUMERIC_REQUIREMENT_RE.search(text):
        errors.append(f"{label} contains numeric requirement identifiers")
    return {
        requirement_id
        for requirement_id in REQUIREMENT_RE.findall(text)
        if requirement_id not in {"requirement-id", "requirement-semantic-name"}
    }


def mapped_requirements(decomposition_text: str, feature_id: str) -> set[str]:
    match = re.search(r"(?ms)^##\s*需求映射\s*$\n(.*?)(?=^##\s|\Z)", decomposition_text)
    if not match:
        return set()
    mapped: set[str] = set()
    for line in match.group(1).splitlines():
        if feature_id in FEATURE_RE.findall(line):
            mapped.update(REQUIREMENT_RE.findall(line))
    return mapped


def validate_requirement_structure(text: str, errors: list[str]) -> None:
    required_headings = (
        "## 1. 版本修订记录",
        "## 2. 需求来源",
        "## 3. 功能描述",
        "## 4. 非功能性需求",
        "## 5. 需求评审记录",
    )
    for heading in required_headings:
        if heading not in text:
            errors.append(f"requirement.md is missing required heading: {heading}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("feature", type=Path, help="formal Feature artifact directory")
    parser.add_argument("--control-dir", type=Path, help="Feature control directory")
    parser.add_argument("--work-item-dir", type=Path, help="formal Work Item directory")
    parser.add_argument("--phase", choices=sorted(PHASES))
    args = parser.parse_args()

    feature = args.feature
    errors: list[str] = []
    if not feature.is_dir():
        return report([f"Feature directory does not exist: {feature}"])
    name_error = validate_feature_name(feature)
    if name_error:
        errors.append(name_error)

    control = args.control_dir or default_control_dir(feature)
    if control is None:
        errors.append("Cannot infer Feature control directory; use --control-dir")
        return report(errors)
    state = control / "state.md"
    state_text = ""
    if not state.is_file():
        errors.append("Missing Feature control file: state.md")
    else:
        state_text = read(state)
        for key in REQUIRED_STATE:
            if field(state_text, key) is None:
                errors.append(f"state.md is missing field: {key}")
        phase = field(state_text, "current_phase")
        if phase and phase not in PHASES:
            errors.append(f"Unknown current_phase: {phase}")
        decomposition_status = field(state_text, "decomposition_status")
        if decomposition_status and decomposition_status not in DECOMPOSITION_STATUSES:
            errors.append(f"Unknown decomposition_status: {decomposition_status}")
        last_test_result = field(state_text, "last_test_result")
        if last_test_result and last_test_result not in TEST_RESULTS:
            errors.append(f"Unknown last_test_result: {last_test_result}")
        feedback_status = field(state_text, "feedback_status")
        if feedback_status and feedback_status not in FEEDBACK_STATUSES:
            errors.append(f"Unknown feedback_status: {feedback_status}")
        classification = field(state_text, "classification")
        if classification and classification not in FEEDBACK_CLASSIFICATIONS:
            errors.append(f"Unknown test feedback classification: {classification}")
        if feedback_status and feedback_status != "none":
            for key in ("feedback", "source", "classification", "affected_requirements"):
                if field(state_text, key) in {None, "", "none"}:
                    errors.append(f"Active test feedback is missing field: {key}")
            if classification == "none":
                errors.append("Active test feedback cannot use classification: none")

    work_item = field(state_text, "work_item")
    work_item_dir = args.work_item_dir
    if work_item and work_item_dir is None:
        work_item_dir = default_work_item_dir(feature, work_item)
    if not work_item:
        errors.append("Feature must declare its Work Item in state.md")
    if work_item_dir is None:
        errors.append("Cannot infer Work Item directory; use --work-item-dir")
    elif not (work_item_dir / "decomposition.md").is_file():
        errors.append("Work Item is missing decomposition.md")
    elif field(read(work_item_dir / "decomposition.md"), "decomposition_status") != "confirmed":
        errors.append("Feature cannot enter a formal phase before Work Item decomposition is confirmed")
    decomposition_text = ""
    if work_item_dir is not None and (work_item_dir / "decomposition.md").is_file():
        decomposition_text = read(work_item_dir / "decomposition.md")
    if work_item and work_item_dir is not None and work_item_dir.name != work_item:
        errors.append("Feature state.md work_item does not match the selected Work Item directory")
    state_feature = field(state_text, "feature")
    if state_feature and state_feature != feature.name:
        errors.append("Feature state.md feature does not match the selected Feature directory")

    requirement_path = feature / "requirement.md"
    requirement_ids: set[str] = set()
    if requirement_path.is_file():
        requirement_text = read(requirement_path)
        requirement_ids = validate_requirement_names(requirement_text, "requirement.md", errors)
        if not requirement_ids:
            errors.append("requirement.md must contain at least one semantic requirement-* identifier")
        validate_requirement_structure(requirement_text, errors)
        if decomposition_text:
            mapped_ids = mapped_requirements(decomposition_text, feature.name)
            if mapped_ids and requirement_ids != mapped_ids:
                missing = sorted(mapped_ids - requirement_ids)
                extra = sorted(requirement_ids - mapped_ids)
                if missing:
                    errors.append("requirement.md is missing mapped requirement(s): " + ", ".join(missing))
                if extra:
                    errors.append("requirement.md contains requirements not mapped to this Feature: " + ", ".join(extra))
    elif args.phase in {"requirement", "design", "implementation"}:
        errors.append("Missing formal artifact: requirement.md")

    if args.phase == "requirement":
        if not (control / "change-log.md").is_file():
            errors.append("Requirement phase requires change-log.md")
    if args.phase == "design":
        for name in ("requirement.md", "design.md", "implementation-plan.md"):
            if not (feature / name).is_file():
                errors.append(f"Design phase is missing formal artifact: {name}")
        if not (control / "change-log.md").is_file():
            errors.append("Design phase requires change-log.md")
        for name in ("design.md", "implementation-plan.md"):
            path = feature / name
            if path.is_file():
                ids = validate_requirement_names(read(path), name, errors)
                if requirement_ids and not requirement_ids.issubset(ids):
                    errors.append(f"{name} does not reference every requirement-* from requirement.md")
        design_path = feature / "design.md"
        if design_path.is_file():
            design_text = read(design_path)
            if "可测试性" not in design_text and "验证策略" not in design_text:
                errors.append("design.md is missing testability or validation strategy")
        plan_path = feature / "implementation-plan.md"
        if plan_path.is_file():
            plan_text = read(plan_path)
            if "Slice" not in plan_text and "slice" not in plan_text:
                errors.append("implementation-plan.md must define implementation Slices")
            if "测试" not in plan_text and "替代验证" not in plan_text:
                errors.append("implementation-plan.md is missing test or alternative validation strategy")
            if "预期结果" not in plan_text and "expected" not in plan_text:
                errors.append("implementation-plan.md is missing expected validation results")
            if re.search(r"(?im)^\s*(?:TODO|TBD)\s*[:：]", plan_text) or re.search(
                r"(?i)\bimplement\s+later\b", plan_text
            ):
                errors.append("implementation-plan.md contains unresolved placeholders")
    if args.phase == "implementation":
        for name in ("requirement.md", "design.md", "implementation-plan.md"):
            if not (feature / name).is_file():
                errors.append(f"Implementation phase is missing prerequisite: {name}")
        if not (control / "change-log.md").is_file():
            errors.append("Implementation phase requires change-log.md")

    return report(errors)


def report(errors: list[str]) -> int:
    if errors:
        for error in errors:
            print(f"ERROR: {error}")
        return 1
    print("PASS: Feature structural invariants satisfied")
    return 0


if __name__ == "__main__":
    sys.exit(main())
