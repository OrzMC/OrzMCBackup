#!/usr/bin/env python3
"""Compare the *common subset* of a run report against expected values (WOS-9.1/9.2).

Only fields that SPEC.md declares normative are compared; implementation-defined extras
(`keptChunks`, `beforeSize`, `durationMs`, …) are ignored.

Usage:
    python3 check_report.py EXPECTED.json ACTUAL.json
"""
from __future__ import annotations

import json
import sys


def main() -> int:
    expected = json.load(open(sys.argv[1], encoding="utf-8"))
    actual = json.load(open(sys.argv[2], encoding="utf-8"))
    problems = []

    for key in ("processedChunks", "removedChunks", "mergedRegions", "patchSlots", "baseSlots"):
        if key in expected and expected[key] != actual.get(key):
            problems.append(f"{key}: {actual.get(key)} != {expected[key]}")

    if "errorKinds" in expected:
        got = sorted(e.get("kind") for e in actual.get("errors", []))
        want = sorted(expected["errorKinds"])
        if got != want:
            problems.append(f"error kinds: {got} != {want}")

    if problems:
        print("[FAIL] report fields differ:")
        for p in problems:
            print(f"  - {p}")
        return 1
    print(f"[ok] report fields match (processed={actual.get('processedChunks')}, "
          f"removed={actual.get('removedChunks')})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
