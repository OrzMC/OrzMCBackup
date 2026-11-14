#!/usr/bin/env python3
"""Neutral comparator: expected manifest vs actual manifest (spec/SPEC.md WOS-12).

Strict on: file set, per-region slot set, per-slot payload SHA-256, per-slot
modified time, non-region file SHA-256.
Ignored (implementation-defined, WOS-11): sector layout, padding, zip naming,
log/progress text, temp dirs.

Usage:
    python3 spec/tools/compare.py EXPECTED.json ACTUAL.json [--max-report 50]
"""
from __future__ import annotations

import argparse
import json
import sys


def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def index(files):
    return {f["path"]: f for f in files}


def diff(expected: dict, actual: dict, max_report: int) -> list[str]:
    problems: list[str] = []

    exp_files = index(expected.get("files", []))
    act_files = index(actual.get("files", []))

    missing = sorted(set(exp_files) - set(act_files))
    extra = sorted(set(act_files) - set(exp_files))
    problems += [f"missing file: {p}" for p in missing[:max_report]]
    problems += [f"unexpected file: {p}" for p in extra[:max_report]]

    for path in sorted(set(exp_files) & set(act_files)):
        e, a = exp_files[path], act_files[path]
        if "slots" in e or "slots" in a:
            e_slots = {s["index"]: s for s in e.get("slots", [])}
            a_slots = {s["index"]: s for s in a.get("slots", [])}
            for idx in sorted(set(e_slots) - set(a_slots)):
                problems.append(f"{path}: slot {idx} missing (expected kept)")
            for idx in sorted(set(a_slots) - set(e_slots)):
                problems.append(f"{path}: slot {idx} unexpected (should have been removed)")
            for idx in sorted(set(e_slots) & set(a_slots)):
                es, as_ = e_slots[idx], a_slots[idx]
                if es["payload_sha256"] != as_["payload_sha256"]:
                    problems.append(f"{path}: slot {idx} payload differs")
                if es["modified_time"] != as_["modified_time"]:
                    problems.append(
                        f"{path}: slot {idx} modified_time {as_['modified_time']} != {es['modified_time']}"
                    )
        else:
            if e.get("sha256") != a.get("sha256"):
                problems.append(f"{path}: content differs")

    # Report counters are optional; compare them when both sides provide them.
    if "report" in expected and "report" in actual:
        for key in ("processedChunks", "removedChunks", "mergedRegions", "patchSlots", "baseSlots"):
            if key in expected["report"] or key in actual["report"]:
                if expected["report"].get(key) != actual["report"].get(key):
                    problems.append(
                        f"report.{key}: {actual['report'].get(key)} != {expected['report'].get(key)}"
                    )
        e_kinds = sorted(err["kind"] for err in expected["report"].get("errors", []))
        a_kinds = sorted(err["kind"] for err in actual["report"].get("errors", []))
        if e_kinds != a_kinds:
            problems.append(f"report.errors kinds: {a_kinds} != {e_kinds}")

    return problems


def main() -> int:
    ap = argparse.ArgumentParser(description="compare expected vs actual conformance manifests")
    ap.add_argument("expected")
    ap.add_argument("actual")
    ap.add_argument("--max-report", type=int, default=50)
    args = ap.parse_args()

    expected, actual = load(args.expected), load(args.actual)
    ev, av = expected.get("spec_version"), actual.get("spec_version")
    if ev != av:
        print(f"::error::spec_version mismatch: {av} != {ev}")
        return 1

    problems = diff(expected, actual, args.max_report)
    if not problems:
        slots = sum(len(f.get("slots", [])) for f in actual.get("files", []))
        print(f"[ok] {actual.get('vector', '')}: {len(actual.get('files', []))} files, {slots} slots")
        return 0

    print(f"[FAIL] {actual.get('vector', '')}: {len(problems)} difference(s)")
    for p in problems:
        print(f"  - {p}")
    if len(problems) > args.max_report:
        print(f"  ... ({len(problems) - args.max_report} more)")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
