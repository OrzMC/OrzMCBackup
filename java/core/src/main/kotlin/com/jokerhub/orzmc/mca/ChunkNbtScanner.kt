package com.jokerhub.orzmc.mca

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Structure-aware scanner for the few NBT fields the optimizer needs (WOS-3.1/WOS-3.4).
 *
 * Only the chunk root compound is walked, without materialising it: each tag's payload is
 * skipped by length. `InhabitedTime` is accepted **only** at the chunk root (1.18+) or at
 * `root.Level` (≤1.17) — a same-named tag nested elsewhere (e.g. inside `structures`, a block
 * entity or a mod payload) MUST be ignored, otherwise a chance byte sequence could decide
 * whether a chunk is kept.
 *
 * Any structural problem (truncated data, absurd lengths, unknown tag id) makes the scan
 * return `null`; callers then fall back to the "safe keep" rule (WOS-3.3).
 */
object ChunkNbtScanner {
    const val INHABITED_TIME = "InhabitedTime"

    private const val TAG_END: Int = 0
    private const val TAG_LONG: Int = 4

    /** Root-level or `Level`-level `InhabitedTime`, or null when absent/unparseable. */
    fun findInhabitedTime(data: ByteArray): Long? {
        val reader = Reader(data)
        return try {
            reader.readRootInhabited()
        } catch (_: Exception) {
            null
        }
    }

    private class Reader(
        private val data: ByteArray,
    ) {
        private var pos = 0

        fun readRootInhabited(): Long? {
            val rootType = u8()
            if (rootType != 10) return null // chunk payload must be a compound
            skipString() // root name
            return readCompoundBody(depth = 0, parentName = null)
        }

        /** Walks the entries of the compound at the current position. */
        private fun readCompoundBody(
            depth: Int,
            parentName: String?,
        ): Long? {
            while (true) {
                val type = u8()
                if (type == TAG_END) return null
                val name = readString()
                val accepted =
                    type == TAG_LONG &&
                        name == INHABITED_TIME &&
                        (depth == 0 || (depth == 1 && parentName == "Level"))
                if (accepted) {
                    val v = i64()
                    skipRest()
                    return v
                }
                if (type == 10 && depth == 0 && name == "Level") {
                    val nested = readCompoundBody(depth = 1, parentName = "Level")
                    if (nested != null) {
                        skipRest()
                        return nested
                    }
                    continue
                }
                skipPayload(type)
            }
        }

        /** After the value of interest we still parse the rest so truncation is detected. */
        private fun skipRest() {
            while (true) {
                val type = u8()
                if (type == TAG_END) return
                skipString()
                skipPayload(type)
            }
        }

        private fun skipPayload(type: Int) {
            when (type) {
                1 -> skip(1)
                2 -> skip(2)
                3 -> skip(4)
                4 -> skip(8)
                5 -> skip(4)
                6 -> skip(8)
                7 -> skip(i32().also { requireArray(it) })
                8 -> skipString()
                9 -> {
                    val elemType = u8()
                    val count = i32()
                    if (count < 0) throw IllegalStateException("negative list length")
                    // Guard against absurd counts before looping (OOM/timeout protection).
                    if (count > data.size) throw IllegalStateException("list length $count exceeds payload")
                    repeat(count) { skipPayload(elemType) }
                }

                10 -> {
                    while (true) {
                        val t = u8()
                        if (t == TAG_END) break
                        skipString()
                        skipPayload(t)
                    }
                }

                11 -> skip(i32().also { requireArray(it) } * 4)
                12 -> {
                    val len = i32()
                    requireArray(len)
                    skip(len * 8)
                }

                else -> throw IllegalStateException("unsupported NBT tag id $type")
            }
        }

        private fun requireArray(len: Int) {
            if (len < 0) throw IllegalStateException("negative array length")
            if (len > data.size) throw IllegalStateException("array length $len exceeds payload")
        }

        private fun u8(): Int {
            if (pos >= data.size) throw IndexOutOfBoundsException("truncated NBT")
            return data[pos++].toInt() and 0xFF
        }

        private fun i32(): Int {
            requireAvailable(4)
            val v = ByteBuffer.wrap(data, pos, 4).order(ByteOrder.BIG_ENDIAN).int
            pos += 4
            return v
        }

        private fun i64(): Long {
            requireAvailable(8)
            val v = ByteBuffer.wrap(data, pos, 8).order(ByteOrder.BIG_ENDIAN).long
            pos += 8
            return v
        }

        private fun skipString() {
            val len = u16()
            skip(len)
        }

        private fun readString(): String {
            val len = u16()
            requireAvailable(len)
            val s = String(data, pos, len, Charsets.UTF_8)
            pos += len
            return s
        }

        private fun u16(): Int {
            requireAvailable(2)
            val v =
                ByteBuffer
                    .wrap(data, pos, 2)
                    .order(ByteOrder.BIG_ENDIAN)
                    .short
                    .toInt() and 0xFFFF
            pos += 2
            return v
        }

        private fun skip(n: Int) {
            if (n < 0) throw IllegalStateException("negative skip")
            requireAvailable(n)
            pos += n
        }

        private fun requireAvailable(n: Int) {
            if (n < 0 || pos + n > data.size) throw IndexOutOfBoundsException("truncated NBT")
        }
    }
}
