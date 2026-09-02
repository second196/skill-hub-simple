#!/usr/bin/env python3
"""Check the architecture and implementation baselines required by design."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path


ARCHITECTURE_FILES = {
    "backend": "backend-architecture.md",
    "frontend": "frontend-architecture.md",
}


def non_empty(path: Path) -> bool:
    return path.is_file() and bool(path.read_text(encoding="utf-8").strip())


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", type=Path, default=Path.cwd())
    parser.add_argument("--layers", nargs="+", choices=["backend", "frontend"], required=True)
    parser.add_argument("--standards", nargs="*", default=[])
    args = parser.parse_args()

    root = args.repo_root.resolve()
    architecture = root / "docs" / "product-development" / "architecture"
    standards = root / "docs" / "product-development" / "standards" / "implementation"
    errors: list[str] = []

    for directory, label in ((architecture, "architecture"), (standards, "implementation standards")):
        if not directory.is_dir():
            errors.append(f"Missing {label} directory: {directory.relative_to(root)}")
        elif label == "implementation standards" and not non_empty(directory / "index.md"):
            errors.append(f"Missing or empty {label} index: {(directory / 'index.md').relative_to(root)}")
        elif label == "architecture" and (directory / "index.md").is_file() and not non_empty(directory / "index.md"):
            errors.append(f"Empty architecture index: {(directory / 'index.md').relative_to(root)}")

    for layer in set(args.layers):
        path = architecture / ARCHITECTURE_FILES[layer]
        if not non_empty(path):
            errors.append(f"Missing or empty {layer} architecture baseline: {path.relative_to(root)}")

    standards_root = standards.resolve()
    for relative in args.standards:
        candidate = Path(relative)
        if candidate.is_absolute() or ".." in candidate.parts:
            errors.append(f"Implementation standard must be relative to its directory: {relative}")
            continue
        path = standards / candidate
        try:
            path.resolve().relative_to(standards_root)
        except ValueError:
            errors.append(f"Implementation standard escapes its directory: {relative}")
            continue
        if not non_empty(path):
            errors.append(f"Missing or empty implementation standard: {path.relative_to(root)}")

    if errors:
        for error in errors:
            print(f"ERROR: {error}")
        return 1
    print("PASS: design architecture and implementation baselines are present")
    return 0


if __name__ == "__main__":
    sys.exit(main())
