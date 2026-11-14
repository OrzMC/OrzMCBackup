package com.jokerhub.orzmc.patterns

import com.jokerhub.orzmc.mca.McaEntry

/**
 * Pattern that keeps every chunk inside an inclusive rectangle of **global** chunk coordinates.
 *
 * Bounds are normalized, so any two corners may be passed in any order
 * (`RangePattern(10, 10, 0, 0)` == `RangePattern(0, 0, 10, 10)`). This matches the semantics of
 * the original Aternos/Thanos `RangePattern` that this project's pattern layer is modelled on.
 *
 * Useful for protecting the spawn area or a fixed build region independently of
 * InhabitedTime, e.g.:
 *
 * ```
 * Optimizer.run(
 *     OptimizerRequest(
 *         input = world,
 *         output = out,
 *         filter = FilterOptions(extraPatterns = listOf(RangePattern(-8, -8, 8, 8))),
 *     ),
 * )
 * ```
 *
 * @param startX first corner X (chunk coordinates, inclusive)
 * @param startZ first corner Z (chunk coordinates, inclusive)
 * @param endX second corner X (chunk coordinates, inclusive)
 * @param endZ second corner Z (chunk coordinates, inclusive)
 */
class RangePattern(
    startX: Int,
    startZ: Int,
    endX: Int,
    endZ: Int,
) : ChunkPattern {
    private val minX = minOf(startX, endX)
    private val maxX = maxOf(startX, endX)
    private val minZ = minOf(startZ, endZ)
    private val maxZ = maxOf(startZ, endZ)

    override fun matches(entry: McaEntry): Boolean {
        val x = entry.globalX()
        val z = entry.globalZ()
        return x in minX..maxX && z in minZ..maxZ
    }
}
