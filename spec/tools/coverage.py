#!/usr/bin/env python3
"""条款 ↔ 向量 覆盖矩阵（report-only 工具）。

解析 `spec/SPEC.md` 的条款定义（行首 `- **WOS-x.y**`）与 `spec/vectors/*/params.json` 的
`clauses` 字段，输出每个条款对应的向量；没有被任何向量覆盖的条款会被列出来（WOS-12.3 的
"条款必须有可执行验证"检查）。

用法：
    python3 spec/tools/coverage.py [--strict]

`--strict`：存在未覆盖条款时退出码 1（CI 可选用；当前默认只报告）。
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

SPEC_DIR = Path(__file__).resolve().parents[1]
SPEC = SPEC_DIR / "SPEC.md"
VECTORS = SPEC_DIR / "vectors"

CLAUSE_DEF = re.compile(r"^-\s+\*\*(WOS-\d+\.\d+)\*\*", re.MULTILINE)


def clauses() -> list[str]:
    return CLAUSE_DEF.findall(SPEC.read_text(encoding="utf-8"))


def vector_map() -> dict[str, list[str]]:
    mapping: dict[str, list[str]] = {}
    for params in sorted(VECTORS.glob("*/params.json")):
        data = json.loads(params.read_text(encoding="utf-8"))
        name = data.get("vector", params.parent.name)
        for clause in data.get("clauses", []):
            mapping.setdefault(clause, []).append(name)
    return mapping


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--strict", action="store_true", help="exit 1 when a clause has no vector")
    args = ap.parse_args()

    defined = clauses()
    mapping = vector_map()

    print(f"{'clause':10s} vectors")
    print("-" * 60)
    uncovered = []
    for clause in defined:
        vectors = sorted(mapping.get(clause, []))
        if vectors:
            print(f"{clause:10s} {', '.join(vectors)}")
        else:
            uncovered.append(clause)
            print(f"{clause:10s} (none)")

    unknown = sorted(set(mapping) - set(defined))
    print()
    print(f"clauses: {len(defined)}  covered: {len(defined) - len(uncovered)}  uncovered: {len(uncovered)}")
    if uncovered:
        print("uncovered: " + ", ".join(uncovered))
    if unknown:
        print("::warning::vectors reference undefined clauses: " + ", ".join(unknown))

    return 1 if (args.strict and uncovered) else 0


if __name__ == "__main__":
    raise SystemExit(main())
