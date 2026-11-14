#!/usr/bin/env python3
"""语言无关的 MCA 读取工具（仅用于一致性校验，不属于任何实现）。

只依赖标准库。所有比较口径都以此处为准（见 spec/SPEC.md WOS-2 / WOS-12）。
"""
from __future__ import annotations

import hashlib
import struct

SECTOR = 4096
HEADER = 2 * SECTOR  # 位置表 + 时间戳表


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def file_sha256(path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def read_slots(path) -> list[dict]:
    """Return [{index, payload_sha256, modified_time}] for every non-empty slot.

    payload = the chunk entry exactly as stored: 4-byte length + 1-byte method
    (+ optional custom-method name) + compressed data, i.e. `length + 4` bytes
    starting at the slot's sector offset. Sector padding is *not* included, so
    the hash is independent of layout.
    """
    with open(path, "rb") as f:
        header = f.read(HEADER)
        if len(header) < HEADER:
            raise ValueError(f"region file shorter than 8 KiB: {path}")
        location = header[:SECTOR]
        timestamps = header[SECTOR:HEADER]
        slots = []
        for i in range(1024):
            value = struct.unpack_from(">I", location, i * 4)[0]
            start_sector, sector_count = value >> 8, value & 0xFF
            if start_sector == 0 or sector_count == 0:
                continue
            offset = start_sector * SECTOR
            f.seek(offset)
            length_bytes = f.read(4)
            if len(length_bytes) != 4:
                raise ValueError(f"truncated chunk header in {path} slot {i}")
            length = struct.unpack(">i", length_bytes)[0]
            if length <= 0:
                raise ValueError(f"invalid chunk length {length} in {path} slot {i}")
            payload = length_bytes + f.read(length)
            if len(payload) != 4 + length:
                raise ValueError(f"truncated chunk payload in {path} slot {i}")
            slots.append(
                {
                    "index": i,
                    "payload_sha256": sha256(payload),
                    "modified_time": struct.unpack_from(">i", timestamps, i * 4)[0],
                }
            )
        return slots


def is_region_mca(path) -> bool:
    """True for `*.mca` files directly inside a directory named `region`."""
    return path.suffix == ".mca" and path.parent.name == "region"
