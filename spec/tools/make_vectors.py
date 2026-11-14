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



def payload_of(entry: dict) -> bytes:
    """Serialized chunk entry the implementation must copy verbatim (WOS-6.1)."""
    return struct.pack(">i", 1 + len(entry["data"])) + bytes([entry["method"] & 0xFF]) + entry["data"]


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
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
