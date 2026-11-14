#!/usr/bin/env python3
"""Derive a conformance manifest from a world directory.

The manifest is the *only* thing implementations are compared on (spec/SPEC.md
WOS-12.1). It is derived by this neutral reader — implementations never emit it
themselves, which keeps the tool free of implementation-specific code.

Usage:
    python3 spec/tools/manifest.py WORLD_DIR --out manifest.json [--vector NAME]
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import mca  # noqa: E402

SPEC_VERSION = "1.0"


def build_manifest(world: Path, vector: str | None) -> dict:
    files = []
    for path in sorted(p for p in world.rglob("*") if p.is_file()):
        rel = path.relative_to(world).as_posix()
        # An unparseable .mca (< 8 KiB header, e.g. a preserved corrupt region) cannot be
        # compared slot-by-slot, so it falls back to a whole-file hash — which is exactly
        # the "preserved verbatim" guarantee of WOS-6.7.
        if mca.is_slot_mca(path) and path.stat().st_size >= 2 * mca.SECTOR:
            entry = {"path": rel, "slots": mca.read_slots(path)}
        else:
            entry = {"path": rel, "sha256": mca.file_sha256(path)}
        files.append(entry)
    manifest = {"spec_version": SPEC_VERSION, "files": files}
    if vector:
        manifest["vector"] = vector
    return manifest


def main() -> int:
    ap = argparse.ArgumentParser(description="derive a conformance manifest from a world dir")
    ap.add_argument("world", type=Path)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--vector", default=None)
    args = ap.parse_args()

    if not args.world.is_dir():
        print(f"::error::not a directory: {args.world}", file=sys.stderr)
        return 2
    manifest = build_manifest(args.world, args.vector)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    slots = sum(len(f.get("slots", [])) for f in manifest["files"])
    print(f"manifest: {args.out} ({len(manifest['files'])} files, {slots} slots)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
