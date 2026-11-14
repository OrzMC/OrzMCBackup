#!/usr/bin/env python3
"""Generate the conformance vectors under spec/vectors/ (deterministic, no clock).

The *expected* manifests are hand-derived from SPEC.md clauses — never produced by
running an implementation (WOS-12.2). Payload hashes are computed from the input
bytes the implementations must copy verbatim (WOS-6.1/WOS-6.2).

Usage:
    python3 spec/tools/make_vectors.py [--force]
"""
from __future__ import annotations

import argparse
import gzip
import json
import shutil
import struct
import sys
import zlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import mca  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]  # spec/
VECTORS = ROOT / "vectors"

METHOD_ZLIB = 2
METHOD_EXT_ZLIB = -126
SECTOR = 4096


# ---------------------------------------------------------------- MCA writing


def chunk_nbt(inhabited: int | None) -> bytes:
    """Minimal chunk payload: a root compound, optionally with an InhabitedTime LONG."""
    body = b"\x0a" + struct.pack(">H", 0)  # TAG_Compound, empty name
    if inhabited is not None:
        name = b"InhabitedTime"
        body += b"\x04" + struct.pack(">H", len(name)) + name + struct.pack(">q", inhabited)
    return body + b"\x00"  # TAG_End


def build_mca(entries: list[dict]) -> bytes:
    """entries: [{index, method, data, mtime}] -> region file bytes (sector aligned)."""
    location = bytearray(SECTOR)
    timestamps = bytearray(SECTOR)
    payloads = bytearray()
    offset = 2 * SECTOR
    for e in entries:
        data = e["data"]
        payload = struct.pack(">i", 1 + len(data)) + bytes([e["method"] & 0xFF]) + data
        padding = (-len(payload)) % SECTOR
        start_sector = offset // SECTOR
        sectors = (len(payload) + padding) // SECTOR
        struct.pack_into(">I", location, e["index"] * 4, (start_sector << 8) | sectors)
        struct.pack_into(">i", timestamps, e["index"] * 4, e["mtime"])
        payloads += payload + b"\x00" * padding
        offset += len(payload) + padding
    return bytes(location) + bytes(timestamps) + bytes(payloads)


def zlib_data(raw: bytes) -> bytes:
    return zlib.compress(raw, 9)


# ---------------------------------------------------------------- chunks.dat


def packed_chunk(x: int, z: int) -> int:
    """vanilla ChunkPos.toLong(x, z): x in the low 32 bits, z in the high 32 bits."""
    return ((z & 0xFFFFFFFF) << 32) | (x & 0xFFFFFFFF)


def load_tag(name: bytes, value: int) -> bytes:
    return b"\x04" + struct.pack(">H", len(name)) + name + struct.pack(">q", value)


def chunks_dat(forced: list[tuple[int, int]]) -> bytes:
    """data/chunks.dat with a legacy `Forced` LongArray (one packed long per chunk)."""
    longs = b"".join(struct.pack(">q", packed_chunk(x, z)) for x, z in forced)
    data_compound = (
        b"\x0c"
        + struct.pack(">H", len(b"Forced"))
        + b"Forced"
        + struct.pack(">i", len(forced))
        + longs
        + b"\x00"
    )
    root = (
        b"\x0a"
        + struct.pack(">H", 0)
        + b"\x0a"
        + struct.pack(">H", len(b"data"))
        + b"data"
        + data_compound
        + b"\x00"
    )
    return gzip.compress(root, mtime=0)


# ---------------------------------------------------------------- vector 1


def vector_forceload(force: bool) -> None:
    """WOS-4.1/4.2/4.3/4.5: packed `Forced` longs decide which chunks survive."""
    name = "forceload-forced"
    dst = VECTORS / name
    if dst.exists():
        if not force:
            print(f"skip {name} (exists; use --force)")
            return
        shutil.rmtree(dst)

    forced = [(5, 0), (1, 2), (10, 20)]  # odd count: catches "two longs per chunk"
    forced_slots = sorted(z * 32 + x for x, z in forced)  # 5, 65, 650
    other_slots = [0, 100]

    entries = [
        {
            "index": slot,
            "method": METHOD_ZLIB,
            "data": zlib_data(chunk_nbt(0)),
            "mtime": 1_700_000_000 + slot,
        }
        for slot in sorted(forced_slots + other_slots)
    ]
    region = build_mca(entries)
    region_path = dst / "input" / "region" / "r.0.0.mca"
    region_path.parent.mkdir(parents=True, exist_ok=True)
    region_path.write_bytes(region)

    (dst / "input" / "data").mkdir(parents=True, exist_ok=True)
    (dst / "input" / "data" / "chunks.dat").write_bytes(chunks_dat(forced))
    (dst / "input" / "level.dat").write_bytes(b"conformance-level")

    write_vector(dst, name, "forceload-forced", ["WOS-4.1", "WOS-4.2", "WOS-4.3", "WOS-4.5"],
                 1_000_000, keep_slots=forced_slots, input_region=region_path,
                  input_entries=entries, forced=forced)


# ---------------------------------------------------------------- vector 2


def vector_decision(force: bool) -> None:
    """WOS-3.1/3.2/3.3/5.3: strict `>`, missing tag, external compression."""
    name = "decision-inhabited"
    dst = VECTORS / name
    if dst.exists():
        if not force:
            print(f"skip {name} (exists; use --force)")
            return
        shutil.rmtree(dst)

    threshold_ticks = 6000  # --inhabited-time-seconds 300
    # slot -> (inhabited or None, method)
    spec = {
        0: (0, METHOD_ZLIB),
        1: (threshold_ticks - 1, METHOD_ZLIB),
        2: (threshold_ticks, METHOD_ZLIB),  # == threshold -> removed (strict >)
        3: (threshold_ticks + 1, METHOD_ZLIB),  # > threshold -> kept
        4: (None, METHOD_ZLIB),  # no InhabitedTime tag -> kept
        5: (None, METHOD_EXT_ZLIB),  # external -> kept (removeUnknown=false)
    }
    entries = [
        {
            "index": slot,
            "method": method,
            "data": zlib_data(chunk_nbt(inhabited)) if inhabited is not None or method == METHOD_ZLIB else b"",
            "mtime": 1_700_000_000 + slot,
        }
        for slot, (inhabited, method) in sorted(spec.items())
    ]
    region_path = dst / "input" / "region" / "r.0.0.mca"
    region_path.parent.mkdir(parents=True, exist_ok=True)
    region_path.write_bytes(build_mca(entries))
    (dst / "input" / "level.dat").write_bytes(b"conformance-level")

    write_vector(dst, name, "decision-inhabited", ["WOS-3.1", "WOS-3.2", "WOS-3.3", "WOS-5.3"],
                 300, keep_slots=[3, 4, 5], input_region=region_path,
                  input_entries=entries, forced=[])


# ---------------------------------------------------------------- shared


def write_vector(dst, name, title, clauses, threshold_seconds, keep_slots,
                 input_region, input_entries, forced) -> None:
    params = {
        "vector": name,
        "title": title,
        "clauses": clauses,
        "command": "backup",
        "args": ["--inhabited-time-seconds", str(threshold_seconds)],
    }
    (dst / "params.json").write_text(json.dumps(params, indent=2) + "\n", encoding="utf-8")

    # Expected manifest is derived from SPEC semantics only: the kept slots are hand-listed,
    # payload hashes come from the input bytes (implementations must copy them verbatim).
    by_slot = {e["index"]: e for e in input_entries}
    slots = []
    for slot in keep_slots:
        entry = by_slot[slot]
        payload = struct.pack(">i", 1 + len(entry["data"])) + bytes([entry["method"] & 0xFF]) + entry["data"]
        slots.append(
            {
                "index": slot,
                "payload_sha256": mca.sha256(payload),
                "modified_time": entry["mtime"],
            }
        )

    files = [{"path": "region/r.0.0.mca", "slots": slots}]
    for misc in sorted((dst / "input").rglob("*")):
        if misc.is_file() and not mca.is_region_mca(misc):
            files.append(
                {
                    "path": misc.relative_to(dst / "input").as_posix(),
                    "sha256": mca.file_sha256(misc),
                }
            )
    files.sort(key=lambda f: f["path"])

    expected = {"spec_version": "1.0", "vector": name, "files": files}
    out = dst / "expected" / "manifest.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(expected, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    readme = [
        f"# {name}",
        "",
        f"覆盖条款：{', '.join(clauses)}",
        "",
        "## 输入",
        "",
        "- `input/` 是一个最小世界（仅 `region/r.0.0.mca` + `level.dat`"
        + ("，以及 `data/chunks.dat`" if forced else "")
        + "）",
        "- chunk payload 是最小 NBT（仅 `InhabitedTime`，或空 compound），ZLIB 压缩",
    ]
    if forced:
        readme += [
            f"- `data/chunks.dat` 的 `data.Forced` 含 **{len(forced)} 个 packed long**（奇数项）"
            f"＝强制加载坐标 {forced}",
            "- packed 规则：vanilla `ChunkPos.toLong(x,z)` = x 低 32 位、z 高 32 位、**每 long 一个 chunk**",
        ]
    readme += [
        "",
        "## 期望",
        "",
        f"- 参数：`{' '.join(params['args'])}`",
        f"- 保留槽位：{keep_slots}（其余删除；严格 `>`、缺失 tag 保留、外部压缩保留）",
        "- 保留槽位 payload 与时间戳必须与输入逐字节一致，杂项文件必须原样复制",
        "",
        "期望清单由本目录的生成脚本 `spec/tools/make_vectors.py` 按规范手工推导（不运行实现）。",
        "",
    ]
    (dst / "README.md").write_text("\n".join(readme), encoding="utf-8")
    print(f"generated {name}: kept {keep_slots}")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--force", action="store_true", help="overwrite existing vectors")
    args = ap.parse_args()
    VECTORS.mkdir(parents=True, exist_ok=True)
    vector_forceload(args.force)
    vector_decision(args.force)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
