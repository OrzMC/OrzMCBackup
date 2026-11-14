package com.jokerhub.orzmc.patterns

import com.jokerhub.orzmc.mca.ChunkNbtScanner
import com.jokerhub.orzmc.mca.McaEntry

/**
 * Pattern that keeps chunks whose `InhabitedTime` NBT value meets the threshold.
 *
 * Reads the value with [ChunkNbtScanner], which walks only the chunk root compound: the tag is
 * accepted at the root (1.18+) or at `root.Level` (≤1.17) and **ignored** anywhere else
 * (WOS-3.1/WOS-3.4). External-compressed and unparseable chunks are handled according to
 * [removeUnknown].
 *
 * A chunk is kept when its `InhabitedTime` is **strictly greater than**
 * [threshold] (>, not >=). This means setting [threshold] to 0 removes chunks
 * that were generated but never visited. This matches the semantics of the
 * original Aternos/Thanos implementation.
 *
 * @param threshold minimum InhabitedTime in game ticks (20 ticks = 1 second);
 *   chunks with `InhabitedTime > threshold` are kept
 * @param removeUnknown if true, external/unparseable chunks are removed; otherwise they are kept
 */
class InhabitedTimePattern(
    private val threshold: Long,
    private val removeUnknown: Boolean,
) : ChunkPattern {
    override fun matches(entry: McaEntry): Boolean {
        if (entry.isExternal()) return !removeUnknown
        val data = entry.allDataUncompressed()
        if (data.isEmpty()) return !removeUnknown
        val t = ChunkNbtScanner.findInhabitedTime(data)
        return t?.let { it > threshold } ?: (!removeUnknown)
    }
}
