#!/usr/bin/env python3
"""Validate a Work Item decomposition and its Feature/requirement mapping."""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path


FEATURE_RE = re.compile(r"\bfeature-[a-z0-9]+(?:-[a-z0-9]+)+\b")
REQUIREMENT_RE = re.compile(r"\brequirement-[a-z0-9]+(?:-[a-z0-9]+)*\b")
LEGACY_REQUIREMENT_RE = re.compile(r"\bREQ-(?:[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*)\b")
NUMERIC_REQUIREMENT_RE = re.compile(r"\brequirement-\d+(?:-\d+)*\b")
STATUSES = {"draft", "confirmed", "superseded", "blocked", "needs-confirmation"}
SCOPE_DECISIONS = {"single-feature", "multi-feature", "needs-confirmation"}


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def field(text: str, name: str) -> str | None:
    match = re.search(rf"^\s*{re.escape(name)}\s*:\s*([^\n\r]+)", text, re.MULTILINE)
    return match.group(1).strip() if match else None


def semantic_requirement_ids(text: str) -> set[str]:
    return {
        requirement_id
        for requirement_id in REQUIREMENT_RE.findall(text)
        if requirement_id not in {"requirement-id", "requirement-semantic-name"}
    }


def mapping_section(text: str) -> str:
    match = re.search(r"(?ms)^##\s*需求映射\s*$\n(.*?)(?=^##\s|\Z)", text)
    return match.group(1) if match else ""


def requirement_mapping(text: str, feature_ids: set[str]) -> dict[str, set[str]]:
    mapping: dict[str, set[str]] = {}
    for line in mapping_section(text).splitlines():
        requirements = set(REQUIREMENT_RE.findall(line))
        features = set(FEATURE_RE.findall(line)) & feature_ids
        for requirement_id in requirements:
            mapping.setdefault(requirement_id, set()).update(features)
    return mapping


def repo_root(work_item: Path) -> Path | None:
    parts = work_item.resolve().parts
    marker = ("docs", "product-development", "work-items")
    for index in range(len(parts) - len(marker) + 1):
        if parts[index : index + len(marker)] == marker:
            return Path(*parts[:index])
    return None


def dependency_graph(index_text: str, feature_ids: set[str]) -> dict[str, set[str]]:
    graph = {feature_id: set() for feature_id in feature_ids}
    for line in index_text.splitlines():
        ids = FEATURE_RE.findall(line)
        if len(ids) > 1 and ids[0] in feature_ids:
            graph[ids[0]].update(feature_id for feature_id in ids[1:] if feature_id != ids[0])
    return graph


def dependency_cycles(graph: dict[str, set[str]]) -> list[list[str]]:
    visiting: set[str] = set()
    visited: set[str] = set()
    stack: list[str] = []
    cycles: list[list[str]] = []

    def visit(node: str) -> None:
        if node in visiting:
            start = stack.index(node)
            cycles.append(stack[start:] + [node])
            return
        if node in visited:
            return
        visiting.add(node)
        stack.append(node)
        for dependency in graph.get(node, set()):
            visit(dependency)
        stack.pop()
        visiting.remove(node)
        visited.add(node)

    for node in graph:
        visit(node)
    return cycles


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("work_item", type=Path, help="formal Work Item artifact directory")
    parser.add_argument("--features-dir", type=Path, help="Feature artifacts directory")
    parser.add_argument("--control-dir", type=Path, help="Work Item control directory")
    args = parser.parse_args()

    work_item = args.work_item
    errors: list[str] = []
    index_path = work_item / "index.md"
    decomposition_path = work_item / "decomposition.md"
    if not work_item.is_dir():
        return report([f"Work Item directory does not exist: {work_item}"])
    if not index_path.is_file():
        errors.append("Missing Work Item artifact: index.md")
    if not decomposition_path.is_file():
        errors.append("Missing Work Item artifact: decomposition.md")
    if errors:
        return report(errors)

    index_text = read(index_path)
    decomposition_text = read(decomposition_path)
    combined_text = index_text + "\n" + decomposition_text
    if LEGACY_REQUIREMENT_RE.search(combined_text):
        errors.append("Work Item contains legacy REQ-* identifiers")
    if NUMERIC_REQUIREMENT_RE.search(combined_text):
        errors.append("Work Item contains numeric requirement identifiers")

    complexity = field(decomposition_text, "complexity")
    scope_decision = field(decomposition_text, "scope_decision")
    decomposition_status = field(decomposition_text, "decomposition_status")
    if complexity not in {"simple", "moderate", "complex"}:
        errors.append("decomposition.md must declare complexity: simple | moderate | complex")
    if scope_decision not in SCOPE_DECISIONS:
        errors.append("decomposition.md must declare a valid scope_decision")
    if decomposition_status not in STATUSES:
        errors.append("decomposition.md must declare a valid decomposition_status")
    if "整体验收" not in combined_text and "overall_acceptance" not in combined_text:
        errors.append("Work Item must declare overall acceptance")

    feature_ids = set(FEATURE_RE.findall(index_text))
    if not feature_ids:
        errors.append("index.md must list at least one semantic Feature")
    if scope_decision == "single-feature" and len(feature_ids) != 1:
        errors.append("single-feature Work Item must list exactly one Feature")
    if scope_decision == "multi-feature" and len(feature_ids) < 2:
        errors.append("multi-feature Work Item must list at least two Features")
    decomposition_feature_ids = set(FEATURE_RE.findall(decomposition_text))
    if decomposition_feature_ids != feature_ids:
        errors.append("decomposition.md Feature list does not match index.md")
    if decomposition_status == "confirmed":
        if field(decomposition_text, "confirmed_by") is None:
            errors.append("confirmed decomposition must declare confirmed_by")
        if field(decomposition_text, "confirmed_at") is None:
            errors.append("confirmed decomposition must declare confirmed_at")

    requirement_ids = semantic_requirement_ids(decomposition_text)
    mapping = requirement_mapping(decomposition_text, feature_ids)
    if not mapping:
        errors.append("decomposition.md must contain a 需求映射 section with semantic requirement-* mappings")
    else:
        if set(mapping) != requirement_ids:
            errors.append("decomposition.md requirement IDs must be declared in the 需求映射 table")
        for requirement_id, owners in sorted(mapping.items()):
            if not owners:
                errors.append(f"Requirement {requirement_id} is not assigned to a listed Feature")
            elif len(owners) > 1:
                errors.append(f"Requirement {requirement_id} is assigned to multiple Features: {', '.join(sorted(owners))}")
        if decomposition_status == "confirmed" and set().union(*mapping.values()) != feature_ids:
            errors.append("Confirmed Work Item must assign at least one requirement to every Feature")

    root = repo_root(work_item)
    features_dir = args.features_dir
    if features_dir is None and root is not None:
        features_dir = root / "docs" / "product-development" / "features"
    control_dir = args.control_dir
    if control_dir is None and root is not None:
        control_dir = root / ".product-development" / "work-items" / work_item.name
    if control_dir is None:
        errors.append("Cannot infer Work Item control directory; use --control-dir")
    else:
        state_path = control_dir / "state.md"
        if not state_path.is_file():
            errors.append("Missing Work Item control file: state.md")
        else:
            state_text = read(state_path)
            for key in ("work_item", "scope_decision", "decomposition_status", "feature_ids"):
                if field(state_text, key) is None:
                    errors.append(f"Work Item state.md is missing field: {key}")
            if field(state_text, "work_item") and field(state_text, "work_item") != work_item.name:
                errors.append("Work Item state.md work_item does not match the selected directory")
            state_feature_ids = set(FEATURE_RE.findall(field(state_text, "feature_ids") or ""))
            if state_feature_ids != feature_ids:
                errors.append("Work Item state.md feature_ids does not match index.md")
            if field(state_text, "scope_decision") != scope_decision:
                errors.append("Work Item state.md and decomposition.md have different scope_decision")
            if field(state_text, "decomposition_status") != decomposition_status:
                errors.append("Work Item state.md and decomposition.md have different decomposition_status")

    graph = dependency_graph(index_text, feature_ids)
    unknown_dependencies = sorted({dependency for dependencies in graph.values() for dependency in dependencies - feature_ids})
    if unknown_dependencies:
        errors.append("Feature dependency references unknown Feature(s): " + ", ".join(unknown_dependencies))
    for cycle in dependency_cycles(graph):
        errors.append("Feature dependency cycle: " + " -> ".join(cycle))

    mapped_ids: set[str] = set()
    if features_dir is None:
        errors.append("Cannot infer Feature artifacts directory; use --features-dir")
    else:
        for feature_id in sorted(feature_ids):
            requirement_path = features_dir / feature_id / "requirement.md"
            if not requirement_path.is_file():
                if decomposition_status == "confirmed":
                    errors.append(f"Missing requirement.md for {feature_id}")
                continue
            feature_text = read(requirement_path)
            if LEGACY_REQUIREMENT_RE.search(feature_text):
                errors.append(f"{feature_id}/requirement.md contains legacy REQ-* identifiers")
            if NUMERIC_REQUIREMENT_RE.search(feature_text):
                errors.append(f"{feature_id}/requirement.md contains numeric requirement identifiers")
            feature_ids_in_doc = semantic_requirement_ids(feature_text)
            mapped_ids.update(feature_ids_in_doc)
            unknown = sorted(feature_ids_in_doc - requirement_ids)
            if unknown:
                errors.append(f"{feature_id}/requirement.md has unmapped requirement(s): " + ", ".join(unknown))

    missing = sorted(requirement_ids - mapped_ids)
    if missing and decomposition_status == "confirmed":
        errors.append("Work Item requirement mapping is missing from Feature requirement.md: " + ", ".join(missing))

    return report(errors)


def report(errors: list[str]) -> int:
    if errors:
        for error in errors:
            print(f"ERROR: {error}")
        return 1
    print("PASS: Work Item decomposition and mapping are structurally valid")
    return 0


if __name__ == "__main__":
    sys.exit(main())
