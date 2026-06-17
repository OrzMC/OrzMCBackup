package com.jokerhub.orzmc.patterns

import com.jokerhub.orzmc.mca.McaEntry

/**
 * Pattern that keeps chunks within a rectangular area in global chunk coordinates.
 *
 * @param startX minimum X coordinate (inclusive, auto-normalized)
 * @param startZ minimum Z coordinate (inclusive, auto-normalized)
 * @param endX maximum X coordinate (inclusive, auto-normalized)
 * @param endZ maximum Z coordinate (inclusive, auto-normalized)
 */
class RangePattern(
    startX: Int,
    startZ: Int,
    endX: Int,
    endZ: Int,
) : ChunkPattern {

    private val minX = minOf(startX, endX)
    private val minZ = minOf(startZ, endZ)
    private val maxX = maxOf(startX, endX)
    private val maxZ = maxOf(startZ, endZ)

    override fun matches(entry: McaEntry): Boolean {
        val gx = entry.globalX()
        val gz = entry.globalZ()
        return gx in minX..maxX && gz in minZ..maxZ
    }
}
