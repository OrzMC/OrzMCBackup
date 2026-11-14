package com.jokerhub.orzmc.mca

import java.io.ByteArrayInputStream
import java.io.InputStream
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
    fun findInhabitedTime(data: ByteArray): Long? = findInhabitedTime(ByteArrayInputStream(data))

    /**
     * Streaming variant (WOS-10.2): reads only as far as the tag, so callers that already stream the
     * decompressed chunk (see [McaEntry.openDataStream]) keep memory bounded.
     *
     * NBT structure problems (truncation, absurd lengths, unknown tag id) return null, which makes the
     * caller fall back to "safe keep" (WOS-3.3). I/O and decompression failures — including the
     * decompression limit — propagate on purpose, so they are reported as errors instead of being
     * silently treated as "no tag".
     */
    fun scan(input: InputStream): ScanResult {
        val reader = Reader(input)
        return try {
            when (val v = reader.readRootInhabited()) {
                null -> ScanResult.Absent
                else -> ScanResult.Found(v)
            }
        } catch (e: MalformedNbt) {
            ScanResult.Malformed(e.message ?: "malformed chunk NBT")
        }
    }

    fun findInhabitedTime(input: InputStream): Long? =
        when (val r = scan(input)) {
            is ScanResult.Found -> r.value
            else -> null
        }

    /** Result of a structural scan. */
    sealed interface ScanResult {
        /** No `InhabitedTime` at the accepted positions (WOS-3.3 applies). */
        data object Absent : ScanResult

        data class Found(
            val value: Long,
        ) : ScanResult

        /** The payload is not valid chunk NBT; callers should keep it and report an error (WOS-6.6). */
        data class Malformed(
            val reason: String,
        ) : ScanResult
    }

    /** Structural NBT problem: the caller may safely fall back to "no tag". */
    private class MalformedNbt(
        message: String,
    ) : RuntimeException(message)

    private class Reader(
        private val input: InputStream,
    ) {
        private var pos = 0L

        fun readRootInhabited(): Long? {
            val rootType = u8()
            if (rootType != 10) throw MalformedNbt("chunk payload is not an NBT compound")
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
                7 -> skip(arrayLen())
                8 -> skipString()
                9 -> {
                    val elemType = u8()
                    val count = i32()
                    if (count < 0) throw MalformedNbt("negative list length")
                    if (count > MAX_LIST_ELEMENTS) throw MalformedNbt("list length $count is absurd")
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

                11 -> skip(arrayLen() * 4L)
                12 -> skip(arrayLen() * 8L)
                else -> throw MalformedNbt("unsupported NBT tag id $type")
            }
        }

        private fun arrayLen(): Long {
            val len = i32().toLong()
            if (len < 0) throw MalformedNbt("negative array length")
            if (len > MAX_ARRAY_LEN) throw MalformedNbt("array length $len is absurd")
            return len
        }

        private fun u8(): Int {
            val v = readByte()
            if (v < 0) throw MalformedNbt("truncated NBT")
            return v
        }

        private fun i32(): Int {
            val buf = readBytes(4)
            return ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN).int
        }

        private fun i64(): Long {
            val buf = readBytes(8)
            return ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN).long
        }

        private fun u16(): Int {
            val buf = readBytes(2)
            return ByteBuffer
                .wrap(buf)
                .order(ByteOrder.BIG_ENDIAN)
                .short
                .toInt() and 0xFFFF
        }

        private fun skipString() {
            skip(u16().toLong())
        }

        private fun readString(): String {
            val len = u16()
            return String(readBytes(len), Charsets.UTF_8)
        }

        private fun readByte(): Int {
            val v = input.read()
            if (v >= 0) pos++
            return v
        }

        private fun readBytes(n: Int): ByteArray {
            if (n < 0) throw MalformedNbt("negative read length")
            val out = ByteArray(n)
            var read = 0
            while (read < n) {
                val r = input.read(out, read, n - read)
                if (r < 0) throw MalformedNbt("truncated NBT")
                read += r
            }
            pos += n
            return out
        }

        private fun skip(n: Long) {
            if (n < 0) throw MalformedNbt("negative skip")
            var remaining = n
            val scratch = ByteArray(8192)
            while (remaining > 0) {
                val r = input.read(scratch, 0, minOf(remaining, scratch.size.toLong()).toInt())
                if (r < 0) throw MalformedNbt("truncated NBT")
                remaining -= r
                pos += r
            }
        }
    }

    private const val MAX_ARRAY_LEN = 10L * 1024 * 1024
    private const val MAX_LIST_ELEMENTS = 10_000_000
}
