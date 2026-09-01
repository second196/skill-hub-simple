#!/usr/bin/env python3
"""检查一个研发 Feature 的结构不变量。"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path


REQUIRED_STATE = (
    "current_phase",
    "feature",
    "artifact_dir",
    "control_dir",
    "requirement_version",
    "design_version",
    "plan_version",
)
PHASES = {"requirement", "design", "implementation", "review", "verification", "release-check", "documentation"}
GENERIC_SLUGS = {"id", "req", "task", "new", "feature", "test", "todo"}


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def default_control_dir(feature: Path) -> Path | None:
    """根据默认正式产物路径推导流程控制目录。"""
    parts = feature.resolve().parts
    marker = ("docs", "product-development", "features")
    for index in range(len(parts) - len(marker) + 1):
        if parts[index : index + len(marker)] == marker:
            repo_root = Path(*parts[:index])
            return repo_root / ".product-development" / "features" / feature.name
    return None


def validate_feature_name(feature: Path) -> str | None:
    """拒绝无法表达需求含义的 Feature 目录名。"""
    match = re.fullmatch(r"feature-([a-z0-9]+(?:-[a-z0-9]+)+)", feature.name, re.IGNORECASE)
    if not match:
        return "Feature 目录必须使用 feature-<对象>-<动作> 格式"
    slug = match.group(1).lower()
    tokens = slug.split("-")
    if all(token.isdigit() for token in tokens):
        return "Feature 目录不能只有数字"
    if re.fullmatch(r"(?:req|task)-\d+", slug) or slug in {"new-feature", "test"}:
        return "Feature 目录不能使用 REQ、Task、new-feature 或 test 作为唯一含义"
    meaningful_tokens = [token for token in tokens if token not in GENERIC_SLUGS and not token.isdigit()]
    if len(meaningful_tokens) < 2:
        return "Feature 目录至少要包含需求对象和动作两个有意义词"
    return None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("feature", type=Path, help="正式 Feature 产物目录")
    parser.add_argument(
        "--control-dir",
        type=Path,
        help="流程控制目录；默认从 docs/product-development/features 路径推导",
    )
    parser.add_argument("--phase", choices=sorted(PHASES))
    args = parser.parse_args()

    feature = args.feature
    control = args.control_dir or default_control_dir(feature)
    errors: list[str] = []
    if control is None:
        errors.append("无法从 Feature 路径推导控制目录，请使用 --control-dir")
        return report(errors)
    state = control / "state.md"

    if not feature.is_dir():
        errors.append(f"Feature 目录不存在：{feature}")
        return report(errors)
    name_error = validate_feature_name(feature)
    if name_error:
        errors.append(name_error)

    if not state.is_file():
        errors.append("缺少 state.md")
    else:
        state_text = read(state)
        for key in REQUIRED_STATE:
            if not re.search(rf"^\s*{re.escape(key)}\s*:", state_text, re.MULTILINE):
                errors.append(f"state.md 缺少字段：{key}")
        phase_match = re.search(r"^\s*current_phase\s*:\s*([^\s]+)", state_text, re.MULTILINE)
        if phase_match and phase_match.group(1) not in PHASES:
            errors.append(f"未知的 current_phase：{phase_match.group(1)}")

    if args.phase == "requirement":
        if not (feature / "requirement.md").is_file():
            errors.append("需求阶段需要正式产物：requirement.md")
        if not (control / "change-log.md").is_file():
            errors.append("需求阶段需要控制产物：change-log.md")
    if args.phase == "design":
        for name in ("requirement.md", "design.md", "implementation-plan.md"):
            if not (feature / name).is_file():
                errors.append(f"方案阶段缺少正式产物：{name}")
        if not (control / "change-log.md").is_file():
            errors.append("方案阶段缺少控制产物：change-log.md")
        design_path = feature / "design.md"
        plan_path = feature / "implementation-plan.md"
        if design_path.is_file():
            design_text = read(design_path)
            if "REQ-ID" not in design_text and "需求" not in design_text:
                errors.append("design.md 不包含需求覆盖部分")
        if plan_path.is_file():
            plan_text = read(plan_path)
            if "REQ-" not in plan_text and "REQ-ID" not in plan_text:
                errors.append("implementation-plan.md 没有引用需求 ID")
            if re.search(r"(?im)^\s*(?:TODO|TBD)\s*[:：]", plan_text) or re.search(
                r"(?i)\bimplement\s+later\b", plan_text
            ):
                errors.append("implementation-plan.md 包含未处理的占位内容")
    if args.phase == "implementation":
        for name in ("requirement.md", "design.md", "implementation-plan.md"):
            if not (feature / name).is_file():
                errors.append(f"实现阶段缺少前置产物：{name}")
        if not (control / "change-log.md").is_file():
            errors.append("实现阶段缺少控制记录：change-log.md")

    return report(errors)


def report(errors: list[str]) -> int:
    if errors:
        for error in errors:
            print(f"错误：{error}")
        return 1
    print("通过：Feature 结构不变量满足")
    return 0


if __name__ == "__main__":
    sys.exit(main())
