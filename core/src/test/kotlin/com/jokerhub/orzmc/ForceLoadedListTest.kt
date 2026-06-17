package com.jokerhub.orzmc

import com.jokerhub.orzmc.mca.McaReader
import com.jokerhub.orzmc.patterns.ListPattern
import com.jokerhub.orzmc.world.NbtForceLoader
import com.jokerhub.orzmc.util.TestPaths
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ForceLoadedListTest {
    @Test
    fun `forced coords should match some entries (legacy chunks_dat format)`() {
        val forced = NbtForceLoader.parse(TestPaths.worldDataChunks().toFile())
        val pattern = ListPattern(forced.map { it.first to it.second })
        val entries = McaReader.open(TestPaths.worldRegion("r.0.0.mca").toString()).use { it.entries() }
        val anyMatch = entries.any { pattern.matches(it) }
        assertTrue(anyMatch, "expected at least one entry to be forced-loaded")
    }

    @Test
    fun `forced coords in 26 dot 1 format chunk_tickets_dat`() {
        val ticketsFile = TestPaths.world26_1Dimension("overworld", "data/minecraft/chunk_tickets.dat")
        val forced = NbtForceLoader.parse(ticketsFile.toFile())
        assertEquals(4, forced.size, "expected 4 forced chunks from chunk_tickets.dat")
        assertTrue(forced.contains(0 to 0))
        assertTrue(forced.contains(0 to 1))
        assertTrue(forced.contains(1 to 0))
        assertTrue(forced.contains(1 to 1))
    }

    @Test
    fun `forced coords from 26 dot 1 format match entries in the region`() {
        val ticketsFile = TestPaths.world26_1Dimension("overworld", "data/minecraft/chunk_tickets.dat")
        val forced = NbtForceLoader.parse(ticketsFile.toFile())
        val pattern = ListPattern(forced.map { it.first to it.second })
        val entries = McaReader.open(
            TestPaths.world26_1Dimension("overworld", "region/r.0.0.mca").toString()
        ).use { it.entries() }
        val anyMatch = entries.any { pattern.matches(it) }
        assertTrue(anyMatch, "expected at least one entry to match 26.1+ forced chunks")
    }
}
