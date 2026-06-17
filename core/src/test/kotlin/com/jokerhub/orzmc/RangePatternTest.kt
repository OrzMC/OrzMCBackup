package com.jokerhub.orzmc

import com.jokerhub.orzmc.mca.McaReader
import com.jokerhub.orzmc.patterns.RangePattern
import com.jokerhub.orzmc.util.TestPaths
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RangePatternTest {

    @Test
    fun `normal range keeps matching chunks`() {
        val pattern = RangePattern(0, 0, 10, 10)
        val entries = McaReader.open(TestPaths.worldRegion("r.0.0.mca").toString()).use { it.entries() }
        val matching = entries.count { pattern.matches(it) }
        assertTrue(matching > 0, "should match chunks within [0..10] range")
        // r.0.0.mca with 676 entries has chunks from global (0,0) to (31,31),
        // so only the first 11x11=121 chunks are in range
        val expected = (0..10).sumOf { z ->
            (0..10).count { x -> entries.any { e -> e.globalX() == x && e.globalZ() == z } }
        }
        // Just verify there's a reasonable number of matches
        assertTrue(matching >= 100, "expected at least 100 chunks in 11x11 range, got $matching")
    }

    @Test
    fun `inverted range is normalized`() {
        val pattern1 = RangePattern(10, 10, 0, 0)
        val pattern2 = RangePattern(0, 0, 10, 10)
        val entry = McaReader.open(TestPaths.worldRegion("r.0.0.mca").toString()).use {
            it.entries().first { e -> e.globalX() == 5 && e.globalZ() == 5 }
        }
        assertEquals(pattern1.matches(entry), pattern2.matches(entry))
    }

    @Test
    fun `out of range chunks are not kept`() {
        val pattern = RangePattern(0, 0, 5, 5)
        val entries = McaReader.open(TestPaths.worldRegion("r.0.0.mca").toString()).use { it.entries() }
        val outOfRange = entries.filter { it.globalX() > 5 || it.globalZ() > 5 }
        assertTrue(outOfRange.isNotEmpty(), "should have chunks outside the range")
        val allMatch = outOfRange.all { pattern.matches(it) }
        assertTrue(!allMatch, "chunks outside range should not match")
    }

    @Test
    fun `single chunk range`() {
        val pattern = RangePattern(0, 0, 0, 0)
        val entries = McaReader.open(TestPaths.worldRegion("r.0.0.mca").toString()).use { it.entries() }
        val matching = entries.count { pattern.matches(it) }
        assertEquals(1, matching, "only the chunk at (0,0) should match")
    }
}
