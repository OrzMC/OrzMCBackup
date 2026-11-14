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
        method_extra = b""
        if e["method"] == 127:  # CUSTOM: 2-byte name length + name precede the data
            name = e.get("custom_name", "unknown").encode()
            method_extra = struct.pack(">H", len(name)) + name
        payload = (
            struct.pack(">i", 1 + len(method_extra) + len(data))
            + bytes([e["method"] & 0xFF])
            + method_extra
            + data
        )
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



def payload_of(entry: dict) -> bytes:
    """Serialized chunk entry the implementation must copy verbatim (WOS-6.1)."""
    extra = b""
    if entry["method"] == 127:
        name = entry.get("custom_name", "unknown").encode()
        extra = struct.pack(">H", len(name)) + name
    return (
        struct.pack(">i", 1 + len(extra) + len(entry["data"]))
        + bytes([entry["method"] & 0xFF])
        + extra
        + entry["data"]
    )


def entries_from(spec: dict, mtime_base: int, active_payload: bool = True) -> list[dict]:
    """slot -> (inhabited, method) ; mtime = mtime_base + slot."""
    out = []
    for slot, (inhabited, method) in sorted(spec.items()):
        if method == METHOD_EXT_ZLIB:
            data = b""
        else:
            data = zlib_data(chunk_nbt(inhabited))
        out.append({"index": slot, "method": method, "data": data, "mtime": mtime_base + slot})
    return out


def write_mca(path: Path, entries: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(build_mca(entries))


def slots_manifest(entries: list[dict], slots: list[int]) -> list[dict]:
    by_slot = {e["index"]: e for e in entries}
    return [
        {"index": slot, "payload_sha256": mca.sha256(payload_of(by_slot[slot])), "modified_time": by_slot[slot]["mtime"]}
        for slot in sorted(slots)
    ]


def write_expected(dst: Path, name: str, files: list[dict], readme_lines: list[str]) -> None:
    files = sorted(files, key=lambda f: f["path"])
    out = dst / "expected" / "manifest.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(
        json.dumps({"spec_version": "1.0", "vector": name, "files": files}, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    (dst / "README.md").write_text("\n".join(readme_lines) + "\n", encoding="utf-8")


# ---------------------------------------------------------------- vector 3


def vector_merge(force: bool) -> None:
    """WOS-8.2/8.3/8.4/8.5/8.7: slot union, patch wins, entities/poi lockstep, misc overlay."""
    name = "merge-basic"
    dst = VECTORS / name
    if dst.exists():
        if not force:
            print(f"skip {name} (exists; use --force)")
            return
        shutil.rmtree(dst)

    base_mtime, patch_mtime = 1_600_000_000, 1_700_000_000
    b_r0 = entries_from({0: (0, METHOD_ZLIB), 1: (0, METHOD_ZLIB), 33: (0, METHOD_ZLIB)}, base_mtime)
    p_r0 = entries_from({0: (777, METHOD_ZLIB), 33: (777, METHOD_ZLIB)}, patch_mtime)
    b_r7 = entries_from({7: (0, METHOD_ZLIB)}, base_mtime)
    p_r5 = entries_from({5: (777, METHOD_ZLIB)}, patch_mtime)
    b_e0 = entries_from({0: (0, METHOD_ZLIB), 1: (0, METHOD_ZLIB)}, base_mtime)
    p_e0 = entries_from({0: (777, METHOD_ZLIB), 33: (777, METHOD_ZLIB)}, patch_mtime)
    p_e5 = entries_from({5: (777, METHOD_ZLIB)}, patch_mtime)
    b_p0 = entries_from({1: (0, METHOD_ZLIB)}, base_mtime)
    p_p0 = entries_from({33: (777, METHOD_ZLIB)}, patch_mtime)

    write_mca(dst / "input" / "base" / "region" / "r.0.0.mca", b_r0)
    write_mca(dst / "input" / "base" / "region" / "r.0.1.mca", b_r7)
    write_mca(dst / "input" / "base" / "entities" / "r.0.0.mca", b_e0)
    write_mca(dst / "input" / "base" / "poi" / "r.0.0.mca", b_p0)
    (dst / "input" / "base" / "level.dat").write_bytes(b"base-level")
    (dst / "input" / "base" / "data").mkdir(parents=True, exist_ok=True)
    (dst / "input" / "base" / "data" / "marker.txt").write_bytes(b"base-marker")

    write_mca(dst / "input" / "patch" / "region" / "r.0.0.mca", p_r0)
    write_mca(dst / "input" / "patch" / "region" / "r.1.0.mca", p_r5)
    write_mca(dst / "input" / "patch" / "entities" / "r.0.0.mca", p_e0)
    write_mca(dst / "input" / "patch" / "entities" / "r.1.0.mca", p_e5)
    write_mca(dst / "input" / "patch" / "poi" / "r.0.0.mca", p_p0)
    (dst / "input" / "patch" / "level.dat").write_bytes(b"patch-level")

    (dst / "params.json").write_text(
        json.dumps(
            {"vector": name, "title": "merge-basic", "command": "merge",
             "clauses": ["WOS-8.2", "WOS-8.3", "WOS-8.4", "WOS-8.5", "WOS-8.7"], "args": []},
            indent=2,
        ) + "\n",
        encoding="utf-8",
    )

    files = [
        {"path": "region/r.0.0.mca", "slots": slots_manifest(p_r0, [0, 33])[:1] + slots_manifest(b_r0, [1]) + slots_manifest(p_r0, [33])},
        {"path": "region/r.0.1.mca", "slots": slots_manifest(b_r7, [7])},
        {"path": "region/r.1.0.mca", "slots": slots_manifest(p_r5, [5])},
        {"path": "entities/r.0.0.mca", "slots": slots_manifest(p_e0, [0]) + slots_manifest(b_e0, [1]) + slots_manifest(p_e0, [33])},
        {"path": "entities/r.1.0.mca", "slots": slots_manifest(p_e5, [5])},
        {"path": "poi/r.0.0.mca", "slots": slots_manifest(b_p0, [1]) + slots_manifest(p_p0, [33])},
        {"path": "level.dat", "sha256": mca.sha256(b"patch-level")},
        {"path": "data/marker.txt", "sha256": mca.sha256(b"base-marker")},
    ]
    readme = [
        f"# {name}", "",
        "覆盖条款：WOS-8.2（patch 优先）、WOS-8.3（entities/poi 锁步）、WOS-8.4（全空不生成）、",
        "WOS-8.5（只在一侧的 region 原样复制 + 兄弟文件）、WOS-8.7（杂项取 patch）", "",
        "## 输入", "",
        "- `input/base`：`region/r.0.0.mca` 槽位 {0,1,33}、`region/r.0.1.mca` 槽位 {7}、",
        "  `entities/r.0.0.mca` {0,1}、`poi/r.0.0.mca` {1}、`level.dat`=`base-level`、`data/marker.txt`",
        "- `input/patch`：`region/r.0.0.mca` 槽位 {0,33}、`region/r.1.0.mca` {5}、",
        "  `entities/r.0.0.mca` {0,33}、`entities/r.1.0.mca` {5}、`poi/r.0.0.mca` {33}、`level.dat`=`patch-level`", "",
        "## 期望", "",
        "- `region/r.0.0.mca`：0 ← patch、1 ← base（patch 没有该槽）、33 ← patch",
        "- `entities/r.0.0.mca`：0 ← patch、1 ← base、33 ← patch；`poi/r.0.0.mca`：1 ← base、33 ← patch",
        "- 槽位 0 在 patch 无 poi → 输出 poi 无槽位 0（锁步，不得回退 base）",
        "- `region/r.0.1.mca`（仅 base）与 `region/r.1.0.mca`（仅 patch，含兄弟 entities）原样复制",
        "- `level.dat` ← patch，`data/marker.txt` ← base", "",
        "期望清单由 `spec/tools/make_vectors.py` 按条款推导：每个槽位的 payload/时间戳取自它应来自的那一侧输入。",
        "",
    ]
    write_expected(dst, name, files, readme)
    print(f"generated {name}")


# ---------------------------------------------------------------- vector 4


def vector_world_basic(force: bool) -> None:
    """WOS-6.4/6.5/7.3/7.4: lazy writer, lockstep, misc sidecars, session.lock excluded."""
    name = "world-basic"
    dst = VECTORS / name
    if dst.exists():
        if not force:
            print(f"skip {name} (exists; use --force)")
            return
        shutil.rmtree(dst)

    pruned = entries_from({0: (0, METHOD_ZLIB)}, 1_650_000_000)
    kept = entries_from({3: (7_000, METHOD_ZLIB)}, 1_650_000_000)
    ent = entries_from({3: (7_000, METHOD_ZLIB)}, 1_650_000_000)

    write_mca(dst / "input" / "region" / "r.0.0.mca", pruned)
    write_mca(dst / "input" / "region" / "r.0.1.mca", kept)
    write_mca(dst / "input" / "entities" / "r.0.1.mca", ent)
    (dst / "input" / "region" / "r.0.1.mca.bak").write_bytes(b"region-backup")
    (dst / "input" / "region" / "notes.txt").write_bytes(b"notes")
    (dst / "input" / "session.lock").write_bytes(b"stale-lock")
    (dst / "input" / "level.dat").write_bytes(b"level")
    (dst / "input" / "datapacks").mkdir(parents=True, exist_ok=True)
    (dst / "input" / "datapacks" / "pack.mcmeta").write_bytes(b'{"pack":{}}')

    (dst / "params.json").write_text(
        json.dumps(
            {"vector": name, "title": "world-basic", "command": "backup",
             "clauses": ["WOS-6.4", "WOS-6.5", "WOS-7.3", "WOS-7.4"],
             "args": ["--inhabited-time-seconds", "300"]},
            indent=2,
        ) + "\n",
        encoding="utf-8",
    )

    files = [
        {"path": "region/r.0.1.mca", "slots": slots_manifest(kept, [3])},
        {"path": "entities/r.0.1.mca", "slots": slots_manifest(ent, [3])},
        {"path": "region/r.0.1.mca.bak", "sha256": mca.sha256(b"region-backup")},
        {"path": "region/notes.txt", "sha256": mca.sha256(b"notes")},
        {"path": "level.dat", "sha256": mca.sha256(b"level")},
        {"path": "datapacks/pack.mcmeta", "sha256": mca.sha256(b'{"pack":{}}')},
    ]
    readme = [
        f"# {name}", "",
        "覆盖条款：WOS-6.4（全空 region 不生成文件）、WOS-6.5（entities 锁步）、",
        "WOS-7.3（region 下非 `.mca` 当杂项）、WOS-7.4（`session.lock` 不复制）", "",
        "## 输入", "",
        "- `region/r.0.0.mca` 槽位 0（`InhabitedTime=0`）→ 全部被删 → **输出不得存在该文件**",
        "- `region/r.0.1.mca` 槽位 3（`InhabitedTime=7000` > 6000）→ 保留；`entities/r.0.1.mca` 槽位 3 锁步保留",
        "- `region/r.0.1.mca.bak`、`region/notes.txt` → 杂项原样复制",
        "- `session.lock` → **不得出现**在输出；`level.dat`、`datapacks/pack.mcmeta` 复制", "",
        "## 期望", "",
        "- 输出文件集合：`region/r.0.1.mca`、`entities/r.0.1.mca`、`region/r.0.1.mca.bak`、",
        "  `region/notes.txt`、`level.dat`、`datapacks/pack.mcmeta`（**无** `r.0.0.mca`、**无** `session.lock`）",
        "",
    ]
    write_expected(dst, name, files, readme)
    print(f"generated {name}")


# ---------------------------------------------------------------- vector 5


def vector_cli_dryrun(force: bool) -> None:
    """WOS-9.5: --dry-run writes nothing and never touches the input."""
    name = "cli-dry-run"
    dst = VECTORS / name
    if dst.exists():
        if not force:
            print(f"skip {name} (exists; use --force)")
            return
        shutil.rmtree(dst)

    kept = entries_from({0: (7_000, METHOD_ZLIB)}, 1_650_000_000)
    write_mca(dst / "input" / "region" / "r.0.0.mca", kept)
    (dst / "input" / "level.dat").write_bytes(b"level")
    (dst / "params.json").write_text(
        json.dumps(
            {"vector": name, "title": "cli-dry-run", "command": "backup",
             "clauses": ["WOS-9.5"], "args": ["--inhabited-time-seconds", "300"],
             "dry_run": True, "expect_no_output": True},
            indent=2,
        ) + "\n",
        encoding="utf-8",
    )
    (dst / "README.md").write_text(
        "\n".join([
            f"# {name}", "",
            "覆盖条款：WOS-9.5（`--dry-run` 不得创建/修改/删除任何输出路径）", "",
            "## 输入与参数", "",
            "- `input/` 最小世界（`region/r.0.0.mca` 槽位 0 会被保留）",
            "- 参数：`--dry-run --inhabited-time-seconds 300`", "",
            "## 期望", "",
            "- 输出目录 **不得存在**（运行器断言）；输入目录的清单在运行前后必须完全一致",
            "",
        ]),
        encoding="utf-8",
    )
    print(f"generated {name} (no expected manifest: expects no output)")




# ---------------------------------------------------------------- vector 6


def vector_errors(force: bool) -> None:
    """WOS-6.7: unparseable region (and its siblings) is preserved verbatim, never dropped."""
    name = "errors-corrupt-region"
    dst = VECTORS / name
    if dst.exists():
        if not force:
            print(f"skip {name} (exists; use --force)")
            return
        shutil.rmtree(dst)

    pruned = entries_from({0: (0, METHOD_ZLIB)}, 1_650_000_000)
    write_mca(dst / "input" / "region" / "r.0.0.mca", pruned)
    corrupt_region = b"\x00" * 64 + b"corrupt-region-bytes"
    corrupt_entities = b"\x01" * 32 + b"corrupt-entities-bytes"
    region_dir = dst / "input" / "region"
    entities_dir = dst / "input" / "entities"
    entities_dir.mkdir(parents=True, exist_ok=True)
    (region_dir / "r.0.1.mca").write_bytes(corrupt_region)
    (entities_dir / "r.0.1.mca").write_bytes(corrupt_entities)
    (dst / "input" / "level.dat").write_bytes(b"level")

    (dst / "params.json").write_text(
        json.dumps(
            {"vector": name, "title": "errors-corrupt-region", "command": "backup",
             "clauses": ["WOS-6.7", "WOS-9.3", "WOS-9.4"],
             "args": ["--inhabited-time-seconds", "300"]},
            indent=2,
        ) + "\n",
        encoding="utf-8",
    )
    files = [
        {"path": "region/r.0.1.mca", "sha256": mca.sha256(corrupt_region)},
        {"path": "entities/r.0.1.mca", "sha256": mca.sha256(corrupt_entities)},
        {"path": "level.dat", "sha256": mca.sha256(b"level")},
    ]
    readme = [
        f"# {name}", "",
        "覆盖条款：WOS-6.7（无法解析的 region 必须原样保留）、WOS-9.3（记录错误）、WOS-9.4（非 strict 退出码 0）", "",
        "## 输入", "",
        "- `region/r.0.0.mca` 槽位 0（`InhabitedTime=0`）→ 正常流程删除，输出无该文件",
        "- `region/r.0.1.mca` 与 `entities/r.0.1.mca` 为 **损坏文件**（长度 < 8 KiB）→ 无法解析", "",
        "## 期望", "",
        "- 两个损坏文件 **原样出现在输出**（字节完全一致）——备份不得因为解析失败而丢数据",
        "- 合法但被裁剪的 `region/r.0.0.mca` 不生成；`level.dat` 复制",
        "- 错误被记录（`kind` 属 `MCA`），非 strict 下退出码仍为 0", "",
    ]
    write_expected(dst, name, files, readme)
    print(f"generated {name}")



# ---------------------------------------------------------------- vector 7


def nbt_string(value: str) -> bytes:
    raw = value.encode()
    return struct.pack(">H", len(raw)) + raw


def chunk_tickets_dat(forced: list[tuple[int, int]]) -> bytes:
    """data/minecraft/chunk_tickets.dat: `data.tickets[]` with type minecraft:forced."""
    tickets = b""
    for x, z in forced:
        # TAG_List of compounds: elements carry no tag/name header, just their entries + TAG_End
        tickets += (
            b"\x08" + nbt_string("type") + nbt_string("minecraft:forced")
            + b"\x0b" + nbt_string("chunk_pos") + struct.pack(">i", 2) + struct.pack(">ii", x, z)
            + b"\x00"
        )
    data_compound = (
        b"\x09" + nbt_string("tickets") + b"\x0a" + struct.pack(">i", len(forced)) + tickets + b"\x00"
    )
    root = (
        b"\x0a" + struct.pack(">H", 0)
        + b"\x0a" + nbt_string("data") + data_compound + b"\x00"
    )
    return gzip.compress(root, mtime=0)


def vector_world_26_1(force: bool) -> None:
    """WOS-7.1/7.2/4.1: 26.1+ nested dimensions, world-level misc, chunk_tickets."""
    name = "world-26-1"
    dst = VECTORS / name
    if dst.exists():
        if not force:
            print(f"skip {name} (exists; use --force)")
            return
        shutil.rmtree(dst)

    dim = dst / "input" / "dimensions" / "minecraft"
    overworld = entries_from({0: (0, METHOD_ZLIB), 33: (0, METHOD_ZLIB)}, 1_650_000_000)
    nether = entries_from({5: (0, METHOD_ZLIB)}, 1_650_000_000)
    write_mca(dim / "overworld" / "region" / "r.0.0.mca", overworld)
    write_mca(dim / "the_nether" / "region" / "r.0.0.mca", nether)
    (dim / "overworld" / "data" / "minecraft").mkdir(parents=True, exist_ok=True)
    tickets = chunk_tickets_dat([(1, 1)])
    (dim / "overworld" / "data" / "minecraft" / "chunk_tickets.dat").write_bytes(tickets)
    (dst / "input" / "level.dat").write_bytes(b"mega-level")
    (dst / "input" / "players").mkdir(parents=True, exist_ok=True)
    (dst / "input" / "players" / "uuid.dat").write_bytes(b"player")

    (dst / "params.json").write_text(
        json.dumps(
            {"vector": name, "title": "world-26-1", "command": "backup",
             "clauses": ["WOS-7.1", "WOS-7.2", "WOS-4.1", "WOS-4.2"],
             "args": ["--inhabited-time-seconds", "1000000"]},
            indent=2,
        ) + "\n",
        encoding="utf-8",
    )
    files = [
        {"path": "levels_placeholder", "sha256": ""},
    ]
    files = [
        {"path": "dimensions/minecraft/overworld/region/r.0.0.mca", "slots": slots_manifest(overworld, [33])},
        {"path": "dimensions/minecraft/overworld/data/minecraft/chunk_tickets.dat", "sha256": mca.sha256(tickets)},
        {"path": "level.dat", "sha256": mca.sha256(b"mega-level")},
        {"path": "players/uuid.dat", "sha256": mca.sha256(b"player")},
    ]
    readme = [
        f"# {name}", "",
        "覆盖条款：WOS-7.1（递归发现 `dimensions/` 维度）、WOS-7.2（26.1+ 世界级杂项）、",
        "WOS-4.1/4.2（`chunk_tickets.dat` 的 `tickets[].chunk_pos` 与 `minecraft:forced`）", "",
        "## 输入", "",
        "- `dimensions/minecraft/overworld/region/r.0.0.mca` 槽位 {0, 33}（`InhabitedTime=0`）",
        "- `dimensions/minecraft/overworld/data/minecraft/chunk_tickets.dat` 强制加载 (1,1) → 槽位 33",
        "- `dimensions/minecraft/the_nether/region/r.0.0.mca` 槽位 5（无强制加载）",
        "- 世界级：`level.dat`、`players/uuid.dat`（必须复制，位于维度祖先目录）", "",
        "## 期望", "",
        "- overworld：只保留槽位 33（强制加载）；the_nether：全部删除 → **不生成 region 文件**（WOS-6.4）",
        "- `level.dat` 与 `players/uuid.dat` 复制；`chunk_tickets.dat` 作为维度内杂项复制", "",
    ]
    files = [f for f in files if f["path"] != "levels_placeholder"]
    write_expected(dst, name, files, readme)
    print(f"generated {name}")



# ---------------------------------------------------------------- LZ4 + xxHash32

LZ4_XXHASH_SEED = 0x9747B28C
P1, P2, P3, P4, P5 = 2654435761, 2246822519, 3266489917, 668265263, 374761393


def _rotl(x: int, r: int) -> int:
    return ((x << r) | (x >> (32 - r))) & 0xFFFFFFFF


def xxh32(data: bytes, seed: int) -> int:
    """Reference xxHash32 (matches jpountz XXHashFactory.hash32 used by both implementations)."""
    n = len(data)
    i = 0
    if n >= 16:
        v1 = (seed + P1 + P2) & 0xFFFFFFFF
        v2 = (seed + P2) & 0xFFFFFFFF
        v3 = seed & 0xFFFFFFFF
        v4 = (seed - P1) & 0xFFFFFFFF
        while i <= n - 16:
            for k, v in enumerate((v1, v2, v3, v4)):
                lane = struct.unpack_from("<I", data, i + 4 * k)[0]
                v = (v + lane * P2) & 0xFFFFFFFF
                v = _rotl(v, 13) * P1 & 0xFFFFFFFF
                if k == 0:
                    v1 = v
                elif k == 1:
                    v2 = v
                elif k == 2:
                    v3 = v
                else:
                    v4 = v
            i += 16
        h = (_rotl(v1, 1) + _rotl(v2, 7) + _rotl(v3, 12) + _rotl(v4, 18)) & 0xFFFFFFFF
    else:
        h = (seed + P5) & 0xFFFFFFFF
    h = (h + n) & 0xFFFFFFFF
    while i <= n - 4:
        h = (h + struct.unpack_from("<I", data, i)[0] * P3) & 0xFFFFFFFF
        h = _rotl(h, 17) * P4 & 0xFFFFFFFF
        i += 4
    while i < n:
        h = (h + data[i] * P5) & 0xFFFFFFFF
        h = _rotl(h, 11) * P1 & 0xFFFFFFFF
        i += 1
    h ^= h >> 15
    h = h * P2 & 0xFFFFFFFF
    h ^= h >> 13
    h = h * P3 & 0xFFFFFFFF
    h ^= h >> 16
    return h


def lz4_raw_block(raw: bytes) -> bytes:
    """A single LZ4 chunk block stored uncompressed (token 0x10), with the MC checksum."""
    checksum = xxh32(raw, LZ4_XXHASH_SEED) & 0x0FFFFFFF
    return (
        b"LZ4Block"
        + bytes([0x10])
        + struct.pack("<i", len(raw))
        + struct.pack("<i", len(raw))
        + struct.pack("<I", checksum)
        + raw
    )


METHOD_GZIP = 1
METHOD_LZ4 = 4
METHOD_RAW = 3


def data_for(method: int, inhabited) -> bytes:
    raw = chunk_nbt(inhabited)
    if method == METHOD_ZLIB:
        return zlib_data(raw)
    if method == METHOD_GZIP:
        return gzip.compress(raw, mtime=0)
    if method == METHOD_RAW:
        return raw
    if method == METHOD_LZ4:
        return lz4_raw_block(raw)
    if method == METHOD_EXT_ZLIB:
        return b""
    raise ValueError(method)


def entries_mixed(spec: dict, mtime_base: int) -> list[dict]:
    """slot -> (inhabited, method) with the payload encoded per method."""
    return [
        {"index": slot, "method": method, "data": data_for(method, inhabited), "mtime": mtime_base + slot}
        for slot, (inhabited, method) in sorted(spec.items())
    ]


# ---------------------------------------------------------------- vector 8


def vector_compression(force: bool) -> None:
    """WOS-2.4/6.1: RAW/GZIP/ZLIB/LZ4 are all readable and payloads are copied verbatim."""
    name = "compression-matrix"
    dst = VECTORS / name
    if dst.exists():
        if not force:
            print(f"skip {name} (exists; use --force)")
            return
        shutil.rmtree(dst)

    spec = {
        0: (7_000, METHOD_RAW),
        1: (7_000, METHOD_GZIP),
        2: (7_000, METHOD_ZLIB),
        3: (7_000, METHOD_LZ4),
        4: (0, METHOD_ZLIB),  # below threshold -> removed
    }
    entries = entries_mixed(spec, 1_650_000_000)
    write_mca(dst / "input" / "region" / "r.0.0.mca", entries)
    (dst / "input" / "level.dat").write_bytes(b"level")
    (dst / "params.json").write_text(
        json.dumps(
            {"vector": name, "title": "compression-matrix", "command": "backup",
             "clauses": ["WOS-2.4", "WOS-6.1"], "args": ["--inhabited-time-seconds", "300"]},
            indent=2,
        ) + "\n",
        encoding="utf-8",
    )
    files = [
        {"path": "region/r.0.0.mca", "slots": slots_manifest(entries, [0, 1, 2, 3])},
        {"path": "level.dat", "sha256": mca.sha256(b"level")},
    ]
    readme = [
        f"# {name}", "",
        "覆盖条款：WOS-2.4（压缩方法码表 RAW=3/GZIP=1/ZLIB=2/LZ4=4）、WOS-6.1（payload 原字节透传）", "",
        "## 输入", "",
        "- 槽位 0 RAW、1 GZIP、2 ZLIB、3 LZ4（单个 `LZ4Block`，token `0x10` 未压缩块 + xxHash 校验）",
        "  的 `InhabitedTime` 均为 7000；槽位 4 为 ZLIB 且 `InhabitedTime=0`",
        "- 所有 chunk 的 NBT 内容相同（仅压缩方式不同）", "",
        "## 期望", "",
        "- 保留槽位 {0,1,2,3}，删除槽位 4",
        "- 保留槽位的 **payload 必须与输入逐字节一致**（不得重压缩/重编码）",
        "- 这一条同时验证：读得懂四种压缩（否则 7000 读不到 → 被误删）；不重编码（否则哈希变化）", "",
    ]
    write_expected(dst, name, files, readme)
    print(f"generated {name}")


# ---------------------------------------------------------------- vector 9


def vector_threshold_zero(force: bool) -> None:
    """WOS-3.2: `-t 0` removes only never-ticked chunks (strictly greater than 0)."""
    name = "threshold-zero"
    dst = VECTORS / name
    if dst.exists():
        if not force:
            print(f"skip {name} (exists; use --force)")
            return
        shutil.rmtree(dst)

    spec = {0: (0, METHOD_ZLIB), 1: (1, METHOD_ZLIB), 2: (0, METHOD_ZLIB)}
    entries = entries_mixed(spec, 1_650_000_000)
    write_mca(dst / "input" / "region" / "r.0.0.mca", entries)
    (dst / "input" / "level.dat").write_bytes(b"level")
    (dst / "params.json").write_text(
        json.dumps(
            {"vector": name, "title": "threshold-zero", "command": "backup",
             "clauses": ["WOS-3.2"], "args": ["--inhabited-time-seconds", "0"]},
            indent=2,
        ) + "\n",
        encoding="utf-8",
    )
    files = [
        {"path": "region/r.0.0.mca", "slots": slots_manifest(entries, [1])},
        {"path": "level.dat", "sha256": mca.sha256(b"level")},
    ]
    readme = [
        f"# {name}", "",
        "覆盖条款：WOS-3.2（严格 `>`；`threshold = 0` 只移除 `InhabitedTime == 0` 的区块）", "",
        "## 输入", "",
        "- 槽位 0：`InhabitedTime=0`、槽位 1：`=1`、槽位 2：`=0`",
        "- 参数：`--inhabited-time-seconds 0`", "",
        "## 期望", "",
        "- 只保留槽位 {1}；槽位 0 与 2 被删除（0 不大于 0）", "",
    ]
    write_expected(dst, name, files, readme)
    print(f"generated {name}")


# ---------------------------------------------------------------- vector 10


def vector_remove_unknown(force: bool) -> None:
    """WOS-5.2/5.3: `--remove-unknown` drops external chunks, normal chunks unaffected."""
    name = "remove-unknown"
    dst = VECTORS / name
    if dst.exists():
        if not force:
            print(f"skip {name} (exists; use --force)")
            return
        shutil.rmtree(dst)

    spec = {
        0: (0, METHOD_ZLIB),  # removed by threshold
        1: (None, METHOD_EXT_ZLIB),  # external -> removed because --remove-unknown
        2: (7_000, METHOD_ZLIB),  # kept
    }
    entries = entries_mixed(spec, 1_650_000_000)
    write_mca(dst / "input" / "region" / "r.0.0.mca", entries)
    (dst / "input" / "level.dat").write_bytes(b"level")
    (dst / "params.json").write_text(
        json.dumps(
            {"vector": name, "title": "remove-unknown", "command": "backup",
             "clauses": ["WOS-5.1", "WOS-5.2", "WOS-5.3"],
             "args": ["--inhabited-time-seconds", "300", "--remove-unknown"]},
            indent=2,
        ) + "\n",
        encoding="utf-8",
    )
    files = [
        {"path": "region/r.0.0.mca", "slots": slots_manifest(entries, [2])},
        {"path": "level.dat", "sha256": mca.sha256(b"level")},
    ]
    readme = [
        f"# {name}", "",
        "覆盖条款：WOS-5.2（`removeUnknown` 只影响未知/外部区块）、WOS-5.3（外部存储按开关处置）", "",
        "## 输入", "",
        "- 槽位 0 ZLIB `InhabitedTime=0`、槽位 1 外部存储（method `-126`，无内联数据）、",
        "  槽位 2 ZLIB `InhabitedTime=7000`",
        "- 参数：`--inhabited-time-seconds 300 --remove-unknown`", "",
        "## 期望", "",
        "- 只保留槽位 {2}：槽位 0 被阈值删除，槽位 1 因 `--remove-unknown` 被删除",
        "- 对照：`decision-inhabited` 向量证明**不带**该开关时外部区块必须保留", "",
    ]
    write_expected(dst, name, files, readme)
    print(f"generated {name}")



# ---------------------------------------------------------------- vector 11


def vector_merge_parallel(force: bool) -> None:
    """WOS-8.8: merge output is byte-identical at `--parallelism 4`."""
    name = "merge-parallel"
    src = VECTORS / "merge-basic"
    dst = VECTORS / name
    if dst.exists():
        if not force:
            print(f"skip {name} (exists; use --force)")
            return
        shutil.rmtree(dst)
    if not src.exists():
        print(f"skip {name} (requires {src.name}; run the full generator)")
        return
    shutil.copytree(src / "input", dst / "input")
    (dst / "expected").mkdir(parents=True, exist_ok=True)
    expected = json.loads((src / "expected" / "manifest.json").read_text(encoding="utf-8"))
    expected["vector"] = name
    (dst / "expected" / "manifest.json").write_text(
        json.dumps(expected, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    (dst / "params.json").write_text(
        json.dumps(
            {"vector": name, "title": "merge-parallel", "command": "merge",
             "clauses": ["WOS-8.8"], "args": ["--parallelism", "4"]},
            indent=2,
        ) + "\n",
        encoding="utf-8",
    )
    (dst / "README.md").write_text(
        "\n".join([
            f"# {name}", "",
            "覆盖条款：WOS-8.8（并行度不得影响输出字节）", "",
            "## 输入", "",
            "- 与 `merge-basic` 完全相同的 base/patch",
            "- 参数：`--parallelism 4`", "",
            "## 期望", "",
            "- 输出清单必须与 `merge-basic` 完全一致（同一份期望清单，逐槽位 payload/时间戳相同）",
            "",
        ]),
        encoding="utf-8",
    )
    print(f"generated {name}")


# ---------------------------------------------------------------- vector 12


def vector_merge_corrupt_patch(force: bool) -> None:
    """WOS-8.6: an unreadable patch region falls back to the base file byte-for-byte."""
    name = "merge-corrupt-patch"
    dst = VECTORS / name
    if dst.exists():
        if not force:
            print(f"skip {name} (exists; use --force)")
            return
        shutil.rmtree(dst)

    base_region = entries_from({0: (0, METHOD_ZLIB), 1: (0, METHOD_ZLIB)}, 1_600_000_000)
    base_entities = entries_from({0: (0, METHOD_ZLIB)}, 1_600_000_000)
    write_mca(dst / "input" / "base" / "region" / "r.0.0.mca", base_region)
    write_mca(dst / "input" / "base" / "entities" / "r.0.0.mca", base_entities)
    (dst / "input" / "base" / "level.dat").write_bytes(b"base-level")

    corrupt = b"\x00" * 48 + b"corrupt-patch-region"
    patch_dir = dst / "input" / "patch" / "region"
    patch_dir.mkdir(parents=True, exist_ok=True)
    (patch_dir / "r.0.0.mca").write_bytes(corrupt)
    (dst / "input" / "patch" / "level.dat").write_bytes(b"patch-level")

    (dst / "params.json").write_text(
        json.dumps(
            {"vector": name, "title": "merge-corrupt-patch", "command": "merge",
             "clauses": ["WOS-8.6"], "args": []},
            indent=2,
        ) + "\n",
        encoding="utf-8",
    )
    files = [
        {"path": "region/r.0.0.mca", "slots": slots_manifest(base_region, [0, 1])},
        {"path": "entities/r.0.0.mca", "slots": slots_manifest(base_entities, [0])},
        {"path": "level.dat", "sha256": mca.sha256(b"patch-level")},
    ]
    readme = [
        f"# {name}", "",
        "覆盖条款：WOS-8.6（patch 无法读取时整文件回退 base）", "",
        "## 输入", "",
        "- `base/region/r.0.0.mca` 槽位 {0,1}、`base/entities/r.0.0.mca` 槽位 {0}",
        "- `patch/region/r.0.0.mca` 为损坏文件（< 8 KiB）；`patch/level.dat` 正常（较新）", "",
        "## 期望", "",
        "- patch 不可读时按 WOS-8.6 回退 base：输出 `region/r.0.0.mca` 的槽位即 base 的 {0,1}",
        "  （payload 与时间戳取自 base 文件，`entities` 同理）；`level.dat` 仍取 patch 版本",
        "- 若实现改成保留损坏的 patch 文件，本向量会红（与 WOS-8.6 冲突，应改规范而不是改向量）",
        "",
    ]
    write_expected(dst, name, files, readme)
    print(f"generated {name}")


# ---------------------------------------------------------------- vector 13


def vector_overlap(force: bool) -> None:
    """WOS-10.1/8.1: overlapping input/output is rejected before any write."""
    name = "overlap-rejected"
    dst = VECTORS / name
    if dst.exists():
        if not force:
            print(f"skip {name} (exists; use --force)")
            return
        shutil.rmtree(dst)

    entries = entries_from({0: (7_000, METHOD_ZLIB)}, 1_650_000_000)
    write_mca(dst / "input" / "region" / "r.0.0.mca", entries)
    (dst / "input" / "level.dat").write_bytes(b"level")
    (dst / "params.json").write_text(
        json.dumps(
            {"vector": name, "title": "overlap-rejected", "command": "backup",
             "clauses": ["WOS-10.1"], "args": ["--inhabited-time-seconds", "300", "--strict"],
             "output": "input", "expect_exit": 1},
            indent=2,
        ) + "\n",
        encoding="utf-8",
    )
    (dst / "README.md").write_text(
        "\n".join([
            f"# {name}", "",
            "覆盖条款：WOS-10.1（输入与输出重叠必须被拒绝）、WOS-8.1（merge 同理）", "",
            "## 输入与参数", "",
            "- `input/` 最小世界；**OUTPUT 直接指向 INPUT 本身**",
            "- 参数：`--inhabited-time-seconds 300 --strict`（strict 下拒绝即退出码 1）", "",
            "## 期望", "",
            "- 退出码 1（在写入前拒绝）",
            "- 输入目录清单在运行前后完全一致（不得被 `--force` 清空）",
            "- 无期望清单：本向量只断言「拒绝 + 不动源数据」",
            "",
        ]),
        encoding="utf-8",
    )
    print(f"generated {name}")



# ---------------------------------------------------------------- vector 14


def vector_mcc_external(force: bool) -> None:
    """WOS-2.5: external chunk data lives in `.mcc`; kept by default, file copied as misc."""
    name = "mcc-external"
    dst = VECTORS / name
    if dst.exists():
        if not force:
            print(f"skip {name} (exists; use --force)")
            return
        shutil.rmtree(dst)

    spec = {0: (None, METHOD_EXT_ZLIB), 1: (0, METHOD_ZLIB)}
    entries = entries_from(spec, 1_650_000_000)
    write_mca(dst / "input" / "region" / "r.0.0.mca", entries)
    mcc = b"external-chunk-payload"
    (dst / "input" / "region" / "r.0.0.mcc").write_bytes(mcc)
    (dst / "input" / "level.dat").write_bytes(b"level")
    (dst / "params.json").write_text(
        json.dumps(
            {"vector": name, "title": "mcc-external", "command": "backup",
             "clauses": ["WOS-2.5", "WOS-5.3", "WOS-7.3"],
             "args": ["--inhabited-time-seconds", "300"]},
            indent=2,
        ) + "\n",
        encoding="utf-8",
    )
    files = [
        {"path": "region/r.0.0.mca", "slots": slots_manifest(entries, [0])},
        {"path": "region/r.0.0.mcc", "sha256": mca.sha256(mcc)},
        {"path": "level.dat", "sha256": mca.sha256(b"level")},
    ]
    readme = [
        f"# {name}", "",
        "覆盖条款：WOS-2.5（外部存储区块不做内联解压、字节原样保留）、WOS-5.3（默认保留）、",
        "WOS-7.3（`region/` 下的 `.mcc` 属杂项，必须随输出复制）", "",
        "## 输入", "",
        "- 槽位 0：外部存储（method `-126`，无内联数据）；槽位 1：ZLIB 且 `InhabitedTime=0`",
        "- `region/r.0.0.mcc`：外部数据文件", "",
        "## 期望", "",
        "- 保留槽位 {0}（`removeUnknown=false`）；槽位 1 被阈值删除",
        "- `region/r.0.0.mcc` **原样复制**（否则恢复世界时外部区块内容缺失）",
        "",
    ]
    write_expected(dst, name, files, readme)
    print(f"generated {name}")


# ---------------------------------------------------------------- vector 15


def vector_corrupt_chunk(force: bool) -> None:
    """WOS-6.6: an unreadable chunk keeps its raw bytes instead of being dropped."""
    name = "corrupt-chunk"
    dst = VECTORS / name
    if dst.exists():
        if not force:
            print(f"skip {name} (exists; use --force)")
            return
        shutil.rmtree(dst)

    bad_method = 9  # undefined compression method: decompression must fail
    entries = [
        {"index": 0, "method": bad_method, "data": chunk_nbt(7_000), "mtime": 1_650_000_000},
        {"index": 1, "method": METHOD_ZLIB, "data": data_for(METHOD_ZLIB, 7_000), "mtime": 1_650_000_001},
    ]
    write_mca(dst / "input" / "region" / "r.0.0.mca", entries)
    (dst / "input" / "level.dat").write_bytes(b"level")
    (dst / "params.json").write_text(
        json.dumps(
            {"vector": name, "title": "corrupt-chunk", "command": "backup",
             "clauses": ["WOS-6.6"], "args": ["--inhabited-time-seconds", "300"]},
            indent=2,
        ) + "\n",
        encoding="utf-8",
    )
    files = [
        {"path": "region/r.0.0.mca", "slots": slots_manifest(entries, [0, 1])},
        {"path": "level.dat", "sha256": mca.sha256(b"level")},
    ]
    readme = [
        f"# {name}", "",
        "覆盖条款：WOS-6.6（无法解析的 chunk 保留原字节，记录错误但不删除）", "",
        "## 输入", "",
        "- 槽位 0：压缩方法 `9`（未定义）→ 解压必然失败，无法读到 `InhabitedTime`",
        "- 槽位 1：正常 ZLIB，`InhabitedTime=7000`", "",
        "## 期望", "",
        "- 两个槽位都保留；槽位 0 的 payload 与输入**逐字节一致**（不得被丢弃，也不得被改写）",
        "",
    ]
    write_expected(dst, name, files, readme)
    print(f"generated {name}")


# ---------------------------------------------------------------- vector 16


def vector_symlink_world(force: bool) -> None:
    """WOS-7.5: a symlinked world root is followed for discovery/count/copy."""
    name = "symlink-world"
    dst = VECTORS / name
    if dst.exists():
        if not force:
            print(f"skip {name} (exists; use --force)")
            return
        shutil.rmtree(dst)

    entries = entries_from({0: (7_000, METHOD_ZLIB)}, 1_650_000_000)
    write_mca(dst / "input" / "region" / "r.0.0.mca", entries)
    (dst / "input" / "level.dat").write_bytes(b"level")
    (dst / "params.json").write_text(
        json.dumps(
            {"vector": name, "title": "symlink-world", "command": "backup", "world": "symlink",
             "clauses": ["WOS-7.5"], "args": ["--inhabited-time-seconds", "300"]},
            indent=2,
        ) + "\n",
        encoding="utf-8",
    )
    files = [
        {"path": "region/r.0.0.mca", "slots": slots_manifest(entries, [0])},
        {"path": "level.dat", "sha256": mca.sha256(b"level")},
    ]
    readme = [
        f"# {name}", "",
        "覆盖条款：WOS-7.5（世界根为符号链接时必须跟随链接目标完成发现/计数/复制）", "",
        "## 输入与参数", "",
        "- `input/` 是一个普通世界；**运行器会创建 `input` 的符号链接并把它作为 WORLD_DIR**",
        "- 参数：`--inhabited-time-seconds 300`", "",
        "## 期望", "",
        "- 输出与直接传入真实目录完全相同（槽位 0 保留、`level.dat` 复制）",
        "- 反证：若不跟随链接，维度发现为空 → 输出无 region 文件，本向量变红",
        "",
    ]
    write_expected(dst, name, files, readme)
    print(f"generated {name}")


# ---------------------------------------------------------------- vector 17


def vector_report_fields(force: bool) -> None:
    """WOS-9.1/9.2: report counters have the same meaning across implementations."""
    name = "report-fields"
    dst = VECTORS / name
    if dst.exists():
        if not force:
            print(f"skip {name} (exists; use --force)")
            return
        shutil.rmtree(dst)

    spec = {0: (0, METHOD_ZLIB), 1: (7_000, METHOD_ZLIB), 2: (0, METHOD_ZLIB), 3: (7_000, METHOD_ZLIB)}
    entries = entries_from(spec, 1_650_000_000)
    write_mca(dst / "input" / "region" / "r.0.0.mca", entries)
    (dst / "input" / "level.dat").write_bytes(b"level")
    (dst / "params.json").write_text(
        json.dumps(
            {
                "vector": name, "title": "report-fields", "command": "backup",
                "clauses": ["WOS-9.1", "WOS-9.2"],
                "args": ["--inhabited-time-seconds", "300"],
                "report": {"processedChunks": 4, "removedChunks": 2, "errorKinds": []},
            },
            indent=2,
        ) + "\n",
        encoding="utf-8",
    )
    files = [
        {"path": "region/r.0.0.mca", "slots": slots_manifest(entries, [1, 3])},
        {"path": "level.dat", "sha256": mca.sha256(b"level")},
    ]
    readme = [
        f"# {name}", "",
        "覆盖条款：WOS-9.1（`processedChunks` = 访问过的区块总数，保留数 = processed − removed）、",
        "WOS-9.2（报告字段语义跨实现一致）", "",
        "## 输入", "",
        "- 4 个区块：槽位 0/2 的 `InhabitedTime=0`（删除），槽位 1/3 为 7000（保留）", "",
        "## 期望", "",
        "- `processedChunks=4`、`removedChunks=2`、无错误",
        "- 输出保留槽位 {1,3}",
        "- 只比较规范声明的字段；`keptChunks`/`beforeSize`/`durationMs` 等属实现定义（WOS-11）",
        "",
    ]
    write_expected(dst, name, files, readme)
    print(f"generated {name}")



# ---------------------------------------------------------------- vector 18


def vector_custom_compression(force: bool) -> None:
    """WOS-2.6: CUSTOM(127) compression cannot be decompressed -> keep, never drop."""
    name = "custom-compression"
    dst = VECTORS / name
    if dst.exists():
        if not force:
            print(f"skip {name} (exists; use --force)")
            return
        shutil.rmtree(dst)

    entries = [
        {
            "index": 0,
            "method": 127,
            "custom_name": "test:custom",
            "data": b"opaque-custom-payload",
            "mtime": 1_650_000_000,
        },
        {"index": 1, "method": METHOD_ZLIB, "data": data_for(METHOD_ZLIB, 0), "mtime": 1_650_000_001},
    ]
    write_mca(dst / "input" / "region" / "r.0.0.mca", entries)
    (dst / "input" / "level.dat").write_bytes(b"level")
    (dst / "params.json").write_text(
        json.dumps(
            {"vector": name, "title": "custom-compression", "command": "backup",
             "clauses": ["WOS-2.6"], "args": ["--inhabited-time-seconds", "300"]},
            indent=2,
        ) + "\n",
        encoding="utf-8",
    )
    files = [
        {"path": "region/r.0.0.mca", "slots": slots_manifest(entries, [0])},
        {"path": "level.dat", "sha256": mca.sha256(b"level")},
    ]
    readme = [
        f"# {name}", "",
        "覆盖条款：WOS-2.6（`CUSTOM(127)` 压缩无法解压 → 必须安全保留，不得视为可删除）", "",
        "## 输入", "",
        "- 槽位 0：method `127` + 自定义方法名 `test:custom` + 不透明数据",
        "- 槽位 1：正常 ZLIB 且 `InhabitedTime=0`（应被删除）", "",
        "## 期望", "",
        "- 保留槽位 {0}（payload 逐字节一致，含 2 字节方法名长度与名称）；槽位 1 删除",
        "",
    ]
    write_expected(dst, name, files, readme)
    print(f"generated {name}")


# ---------------------------------------------------------------- vector 19


def vector_merge_overlap(force: bool) -> None:
    """WOS-8.1: merge rejects an output that aliases base/patch, before any write."""
    name = "merge-overlap"
    dst = VECTORS / name
    if dst.exists():
        if not force:
            print(f"skip {name} (exists; use --force)")
            return
        shutil.rmtree(dst)

    base = entries_from({0: (0, METHOD_ZLIB)}, 1_600_000_000)
    patch = entries_from({0: (777, METHOD_ZLIB)}, 1_700_000_000)
    write_mca(dst / "input" / "base" / "region" / "r.0.0.mca", base)
    write_mca(dst / "input" / "patch" / "region" / "r.0.0.mca", patch)
    (dst / "input" / "base" / "level.dat").write_bytes(b"base-level")
    (dst / "input" / "patch" / "level.dat").write_bytes(b"patch-level")
    (dst / "params.json").write_text(
        json.dumps(
            {"vector": name, "title": "merge-overlap", "command": "merge",
             "clauses": ["WOS-8.1"], "args": [], "output": "base", "expect_exit": 1},
            indent=2,
        ) + "\n",
        encoding="utf-8",
    )
    (dst / "README.md").write_text(
        "\n".join([
            f"# {name}", "",
            "覆盖条款：WOS-8.1（base/patch/output 三者互不重叠，拒绝先于任何写入）", "",
            "## 输入与参数", "",
            "- `input/base`、`input/patch` 各含一个 region；**OUTPUT 直接指向 `input/base`**",
            "",
            "## 期望", "",
            "- 退出码 1；base 与 patch 的清单在运行前后完全一致（不得被 `--force` 清空）",
            "",
        ]),
        encoding="utf-8",
    )
    print(f"generated {name}")



# ---------------------------------------------------------------- vector 20


def vector_forceload_corrupt(force: bool) -> None:
    """WOS-4.4: an unreadable force-load file means "no force-loaded chunks" (never a crash)."""
    name = "forceload-corrupt"
    dst = VECTORS / name
    if dst.exists():
        if not force:
            print(f"skip {name} (exists; use --force)")
            return
        shutil.rmtree(dst)

    entries = entries_from({0: (0, METHOD_ZLIB), 1: (7_000, METHOD_ZLIB)}, 1_650_000_000)
    write_mca(dst / "input" / "region" / "r.0.0.mca", entries)
    (dst / "input" / "data").mkdir(parents=True, exist_ok=True)
    (dst / "input" / "data" / "chunks.dat").write_bytes(b"not-gzip-not-nbt")
    (dst / "input" / "level.dat").write_bytes(b"level")
    (dst / "params.json").write_text(
        json.dumps(
            {"vector": name, "title": "forceload-corrupt", "command": "backup",
             "clauses": ["WOS-4.4"], "args": ["--inhabited-time-seconds", "300"]},
            indent=2,
        ) + "\n",
        encoding="utf-8",
    )
    files = [
        {"path": "region/r.0.0.mca", "slots": slots_manifest(entries, [1])},
        {"path": "data/chunks.dat", "sha256": mca.sha256(b"not-gzip-not-nbt")},
        {"path": "level.dat", "sha256": mca.sha256(b"level")},
    ]
    readme = [
        f"# {name}", "",
        "覆盖条款：WOS-4.4（强制加载文件解析失败 → 视为没有强制加载区块，不得中断或误删）", "",
        "## 输入", "",
        "- `data/chunks.dat` 是**非 gzip/NBT 垃圾数据**",
        "- 槽位 0 `InhabitedTime=0`（删除）、槽位 1 `=7000`（保留）", "",
        "## 期望", "",
        "- 按阈值正常处理：保留槽位 {1}；`data/chunks.dat` 作为杂项原样复制",
        "- 反证：若解析失败被当成「全部区块受保护」或直接崩溃，本向量会红",
        "",
    ]
    files[0] = {"path": "region/r.0.0.mca", "slots": slots_manifest(entries, [1])}
    readme[-3] = "- 按阈值正常处理：保留槽位 {1}；`data/chunks.dat` 作为杂项原样复制"
    write_expected(dst, name, files, readme)
    print(f"generated {name}")


# ---------------------------------------------------------------- vector 21


def vector_decompress_bomb(force: bool) -> None:
    """WOS-10.2: an oversized decompression is handled safely (keep original bytes)."""
    name = "decompress-bomb"
    dst = VECTORS / name
    if dst.exists():
        if not force:
            print(f"skip {name} (exists; use --force)")
            return
        shutil.rmtree(dst)

    # 65 MiB of zeros compress to ~64 KiB: passes the 8 MiB *compressed* limit but must not be
    # decompressed unbounded.
    bomb = zlib.compress(b"\x00" * (65 * 1024 * 1024), 9)
    entries = [
        {"index": 0, "method": METHOD_ZLIB, "data": bomb, "mtime": 1_650_000_000},
        {"index": 1, "method": METHOD_ZLIB, "data": data_for(METHOD_ZLIB, 7_000), "mtime": 1_650_000_001},
    ]
    write_mca(dst / "input" / "region" / "r.0.0.mca", entries)
    (dst / "input" / "level.dat").write_bytes(b"level")
    (dst / "params.json").write_text(
        json.dumps(
            {"vector": name, "title": "decompress-bomb", "command": "backup",
             "clauses": ["WOS-10.2"], "args": ["--inhabited-time-seconds", "300"]},
            indent=2,
        ) + "\n",
        encoding="utf-8",
    )
    files = [
        {"path": "region/r.0.0.mca", "slots": slots_manifest(entries, [0, 1])},
        {"path": "level.dat", "sha256": mca.sha256(b"level")},
    ]
    readme = [
        f"# {name}", "",
        "覆盖条款：WOS-10.2（解压总量上限；超限走安全保留路径，不 OOM、不丢数据）", "",
        "## 输入", "",
        "- 槽位 0：ZLIB 压缩的 65 MiB 零字节（压缩后约 64 KiB，未超 8 MiB 压缩上限）",
        "- 槽位 1：正常 ZLIB `InhabitedTime=7000`", "",
        "## 期望", "",
        "- 两个槽位都保留：槽位 0 因无法安全解压（超上限或读不到 `InhabitedTime`）而安全保留原字节",
        "",
    ]
    write_expected(dst, name, files, readme)
    print(f"generated {name}")



# ---------------------------------------------------------------- vector 22


def nbt_str(name: str) -> bytes:
    raw = name.encode()
    return struct.pack(">H", len(raw)) + raw


def nbt_long_tag(name: str, value: int) -> bytes:
    return b"\x04" + nbt_str(name) + struct.pack(">q", value)


def nbt_compound(entries: bytes) -> bytes:
    return entries + b"\x00"


def nbt_compound_tag(name: str, entries: bytes) -> bytes:
    return b"\x0a" + nbt_str(name) + nbt_compound(entries)


def nbt_root(entries: bytes) -> bytes:
    return b"\x0a" + struct.pack(">H", 0) + nbt_compound(entries)


def vector_inhabited_nested(force: bool) -> None:
    """WOS-3.1/3.4: only root-level (or root.Level) InhabitedTime counts; nested decoys are ignored."""
    name = "inhabited-nested"
    dst = VECTORS / name
    if dst.exists():
        if not force:
            print(f"skip {name} (exists; use --force)")
            return
        shutil.rmtree(dst)

    # slot 0: a decoy InhabitedTime=999999 inside `structures` appears BEFORE the real root tag (0).
    #         A byte scan keeps this chunk; the spec says drop it.
    decoy = nbt_compound_tag("structures", nbt_long_tag("InhabitedTime", 999_999))
    slot0 = nbt_root(decoy + nbt_long_tag("InhabitedTime", 0))
    # slot 1: legacy (<=1.17) layout: root.Level.InhabitedTime = 7000 -> kept
    slot1 = nbt_root(nbt_compound_tag("Level", nbt_long_tag("InhabitedTime", 7_000)))

    entries = [
        {"index": 0, "method": METHOD_ZLIB, "data": zlib_data(slot0), "mtime": 1_650_000_000},
        {"index": 1, "method": METHOD_ZLIB, "data": zlib_data(slot1), "mtime": 1_650_000_001},
    ]
    write_mca(dst / "input" / "region" / "r.0.0.mca", entries)
    (dst / "input" / "level.dat").write_bytes(b"level")
    (dst / "params.json").write_text(
        json.dumps(
            {"vector": name, "title": "inhabited-nested", "command": "backup",
             "clauses": ["WOS-3.1", "WOS-3.4"], "args": ["--inhabited-time-seconds", "300"]},
            indent=2,
        ) + "\n",
        encoding="utf-8",
    )
    files = [
        {"path": "region/r.0.0.mca", "slots": slots_manifest(entries, [1])},
        {"path": "level.dat", "sha256": mca.sha256(b"level")},
    ]
    readme = [
        f"# {name}", "",
        "覆盖条款：WOS-3.1（`InhabitedTime` 在 chunk 根；≤1.17 为 `root.Level`）、",
        "WOS-3.4（只接受根层或 `root.Level` 的同名 tag，其它嵌套必须忽略；无法解析时安全保留）", "",
        "## 输入", "",
        "- 槽位 0：`structures` 子 compound 里先出现一个 **诱饵** `InhabitedTime=999999`，",
        "  真正的根层 `InhabitedTime=0` 在其后",
        "- 槽位 1：旧版布局 `Level.InhabitedTime=7000`", "",
        "## 期望", "",
        "- 只保留槽位 {1}：槽位 0 的根层值为 0（诱饵必须被忽略）；槽位 1 走 `Level` 兼容路径",
        "- 反证：纯字节扫描会把槽位 0 判为 999999（保留）→ 本向量变红", "",
    ]
    write_expected(dst, name, files, readme)
    print(f"generated {name}")



# ---------------------------------------------------------------- vector 23


def vector_force_symlink(force: bool) -> None:
    """WOS-10.3: `--force` on a non-empty output must not follow symlinks when deleting."""
    name = "force-output-symlink"
    dst = VECTORS / name
    if dst.exists():
        if not force:
            print(f"skip {name} (exists; use --force)")
            return
        shutil.rmtree(dst)

    entries = entries_from({0: (7_000, METHOD_ZLIB)}, 1_650_000_000)
    write_mca(dst / "input" / "region" / "r.0.0.mca", entries)
    (dst / "input" / "level.dat").write_bytes(b"level")
    (dst / "params.json").write_text(
        json.dumps(
            {"vector": name, "title": "force-output-symlink", "command": "backup",
             "clauses": ["WOS-10.3"], "args": ["--inhabited-time-seconds", "300"],
             "force_victim": True},
            indent=2,
        ) + "\n",
        encoding="utf-8",
    )
    files = [
        {"path": "region/r.0.0.mca", "slots": slots_manifest(entries, [0])},
        {"path": "level.dat", "sha256": mca.sha256(b"level")},
    ]
    readme = [
        f"# {name}", "",
        "覆盖条款：WOS-10.3（`--force` 清空输出目录时不得跟随符号链接，避免删掉链接目标）", "",
        "## 输入与运行器准备", "",
        "- `input/` 一个最小世界（槽位 0 会被保留）",
        "- 运行器在运行前创建：`out/junk.dat`、`out/link -> victim/`、`victim/data.txt`",
        "- 参数：`--inhabited-time-seconds 300 --force`（输出非空，必须 force）", "",
        "## 期望", "",
        "- 输出目录被重建为处理后世界：只有 `region/r.0.0.mca` + `level.dat`（junk 与 link 都应消失）",
        "- **`victim/data.txt` 必须仍存在**（删除操作只删链接本身，不进入目标）",
        "",
    ]
    write_expected(dst, name, files, readme)
    print(f"generated {name}")


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
        if misc.is_file() and not mca.is_slot_mca(misc):
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
    vector_merge(args.force)
    vector_world_basic(args.force)
    vector_cli_dryrun(args.force)
    vector_errors(args.force)
    vector_world_26_1(args.force)
    vector_compression(args.force)
    vector_threshold_zero(args.force)
    vector_remove_unknown(args.force)
    # Every real-world vector implicitly verifies the structural clauses below (it reads a
    # genuine .mca, copies payloads verbatim and preserves per-slot timestamps).
    structural = ["WOS-1.1", "WOS-1.2", "WOS-2.1", "WOS-2.2", "WOS-2.3", "WOS-6.2", "WOS-6.3"]
    for params in sorted(VECTORS.glob("*/params.json")):
        data = json.loads(params.read_text(encoding="utf-8"))
        if data.get("vector") in ("cli-dry-run", "overlap-rejected"):
            continue
        data["clauses"] = sorted(set(data.get("clauses", [])) | set(structural))
        params.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")

    vector_merge_parallel(args.force)
    vector_merge_corrupt_patch(args.force)
    vector_overlap(args.force)
    vector_mcc_external(args.force)
    vector_corrupt_chunk(args.force)
    vector_symlink_world(args.force)
    vector_report_fields(args.force)
    vector_custom_compression(args.force)
    vector_merge_overlap(args.force)
    vector_forceload_corrupt(args.force)
    vector_decompress_bomb(args.force)
    vector_inhabited_nested(args.force)
    vector_force_symlink(args.force)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
