package com.jokerhub.orzmc.patterns

import com.jokerhub.orzmc.mca.McaEntry
import com.jokerhub.orzmc.mca.McaReader
import com.jokerhub.orzmc.util.CompressionKind
import com.jokerhub.orzmc.util.McaMemoryBuilder
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RangePatternTest {
    private fun entry(
        regionX: Int,
        regionZ: Int,
        index: Int,
    ): McaEntry {
        val bytes = McaMemoryBuilder.buildSingleEntryMca(index, 1000, CompressionKind.RAW)
        return McaReader.openFromBytes("r.$regionX.$regionZ.mca", bytes).get(index)!!
    }

    @Test
    fun `keeps chunks inside the inclusive rectangle`() {
        val pattern = RangePattern(1, 1, 3, 3)
        assertTrue(pattern.matches(entry(0, 0, 1 + 1 * 32)), "corner (1,1) is inside")
        assertTrue(pattern.matches(entry(0, 0, 3 + 3 * 32)), "corner (3,3) is inside")
        assertTrue(pattern.matches(entry(0, 0, 2 + 2 * 32)), "interior (2,2) is inside")
        assertFalse(pattern.matches(entry(0, 0, 0 + 0 * 32)), "(0,0) is outside")
        assertFalse(pattern.matches(entry(0, 0, 4 + 2 * 32)), "(4,2) is outside")
    }

    @Test
    fun `normalizes swapped corners`() {
        val swapped = RangePattern(3, 3, 1, 1)
        assertTrue(swapped.matches(entry(0, 0, 2 + 2 * 32)))
        assertFalse(swapped.matches(entry(0, 0, 0 + 0 * 32)))
    }

    @Test
    fun `matches negative global chunk coordinates`() {
        val pattern = RangePattern(-4, -4, -1, -1)
        // region (-1,-1) covers global chunks -32..-1; index 31 -> (-1,-1)
        assertTrue(pattern.matches(entry(-1, -1, 31 + 31 * 32)))
        assertFalse(pattern.matches(entry(0, 0, 0)), "(0,0) is outside the negative range")
    }

    @Test
    fun `matches across region borders`() {
        val pattern = RangePattern(-1, -1, 1, 1)
        assertTrue(pattern.matches(entry(-1, 0, 31 + 0 * 32)), "(-1,0) from region (-1,0)")
        assertTrue(pattern.matches(entry(0, -1, 0 + 31 * 32)), "(0,-1) from region (0,-1)")
        assertTrue(pattern.matches(entry(0, 0, 1 + 1 * 32)), "global (1,1) from region (0,0)")
    }
}
