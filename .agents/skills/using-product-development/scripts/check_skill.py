#!/usr/bin/env python3
"""检查 using-product-development 的自包含结构。"""

from __future__ import annotations

import re
import sys
from pathlib import Path


REQUIRED_FILES = (
    "SKILL.md",
    "agents/openai.yaml",
    "references/collaboration/rules.md",
    "references/collaboration/stage-entry.md",
    "references/collaboration/transparency.md",
    "references/shared/feature-state.md",
    "references/shared/change-management.md",
    "references/shared/artifact-contracts.md",
    "references/shared/artifact-locations.md",
    "references/shared/work-item-contract.md",
    "references/shared/specialized-route-loading.md",
    "references/methods/brainstorming.md",
    "references/methods/writing-plans.md",
    "references/methods/executing-plans.md",
    "references/methods/subagent-development.md",
    "references/workflow/overview.md",
    "references/workflow/decomposition.md",
    "references/workflow/requirement.md",
    "references/workflow/design.md",
    "references/workflow/implementation.md",
    "references/workflow/review.md",
    "references/workflow/verification.md",
    "references/workflow/release-check.md",
    "references/workflow/documentation.md",
    "scripts/check_feature.py",
    "scripts/check_work_item.py",
)


def main() -> int:
    root = Path(__file__).resolve().parent.parent
    errors: list[str] = []
    skill_path = root / "SKILL.md"

    for relative in REQUIRED_FILES:
        if not (root / relative).is_file():
            errors.append(f"缺少必需文件：{relative}")

    if skill_path.is_file():
        text = skill_path.read_text(encoding="utf-8")
        if len(text.splitlines()) > 500:
            errors.append("SKILL.md 超过 500 行")
        match = re.match(r"^---\n(.*?)\n---\n", text, re.DOTALL)
        if not match:
            errors.append("SKILL.md 的 frontmatter 无效")
        else:
            frontmatter = match.group(1)
            if not re.search(r"^name:\s*\S+", frontmatter, re.MULTILINE):
                errors.append("frontmatter 缺少 name")
            if not re.search(r"^description:\s*\S+", frontmatter, re.MULTILINE):
                errors.append("frontmatter 缺少 description")
        if re.search(r"(?im)^\s*(?:TODO|TBD)\s*[:：]", text):
            errors.append("SKILL.md 包含未处理的占位内容")
        for required_phrase in ("Work Item", "requirement-*", "decomposition.md"):
            if required_phrase not in text:
                errors.append(f"SKILL.md 缺少复杂需求治理规则：{required_phrase}")

    for relative in REQUIRED_FILES[2:]:
        path = root / relative
        if path.suffix == ".md" and path.is_file():
            content = path.read_text(encoding="utf-8")
            if re.search(r"(?im)^\s*(?:TODO|TBD)\s*[:：]", content):
                errors.append(f"参考文件包含未处理的占位内容：{relative}")

    if errors:
        for error in errors:
            print(f"错误：{error}")
        return 1
    print("通过：Skill 自包含结构检查")
    return 0


if __name__ == "__main__":
    sys.exit(main())
