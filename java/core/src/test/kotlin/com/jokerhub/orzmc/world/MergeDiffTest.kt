package com.jokerhub.orzmc.world

import com.jokerhub.orzmc.util.CompressionKind
import com.jokerhub.orzmc.util.McaMemoryBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.nio.file.Paths

class MergeDiffTest {
    private val fs = MemoryFS()
    private val io = MemoryMcaIOFactory()
    private val base: Path = Paths.get("/mem/base")
    private val patch: Path = Paths.get("/mem/patch")
    private val out: Path = Paths.get("/mem/out")
    private val dim = "dimensions/minecraft/overworld"
    private val day = 86_400

    /** Region bytes with per-slot save timestamps overridden (header table 2). */
    private fun mca(
        slots: List<Int>,
        timestamps: List<Int>,
    ): ByteArray {
        val bytes =
            McaMemoryBuilder.buildMca(
                slots.map { McaMemoryBuilder.MemChunk(it, 1000L + it, CompressionKind.RAW) },
            )
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        slots.forEachIndexed { i, slot -> bb.putInt(4096 + slot * 4, timestamps[i]) }
        return bytes
    }

    private fun write(
        world: Path,
        kind: String,
        name: String,
        bytes: ByteArray,
    ) {
        fs.createDirectories(world)
        var dir = world
        dim.split("/").forEach { seg ->
            dir = dir.resolve(seg)
            fs.createDirectories(dir)
        }
        val target = dir.resolve(kind).resolve(name)
        fs.createDirectories(target.parent!!)
        fs.write(target, bytes)
    }

    private fun request(output: Path = out) =
        MergeRequest(
            base = base,
            patch = patch,
            output = output,
            io = IOOptions(fs = fs, ioFactory = io),
        )

    private fun buildWorlds() {
        // base: r.0.0 has slots 0,1,2 saved ~100 days before the patch era
        write(base, "region", "r.0.0.mca", mca(listOf(0, 1, 2), List(3) { 1000 }))
        // base-only region
        write(base, "region", "r.0.1.mca", mca(listOf(7), listOf(1000)))
        // patch: r.0.0 has slots 0 (updated) and 3 (new)
        write(patch, "region", "r.0.0.mca", mca(listOf(0, 3), listOf(2_000_000, 2_000_000)))
        // patch-only region
        write(patch, "region", "r.1.0.mca", mca(listOf(5), listOf(2_000_000)))
    }

    @Test
    fun `dry run counts slot sources and emits base-filled rows only`() {
        buildWorlds()
        val diff = MergeDiff.compute(request())

        assertEquals(1, diff.mergedRegions)
        assertEquals(1, diff.patchOnlyRegions)
        assertEquals(1, diff.baseOnlyRegions)
        // merged region: patch slots 0,3 ; patch-only region contributes its 1 slot
        assertEquals(3, diff.patchSlots)
        // merged region: base slots 1,2
        assertEquals(2, diff.baseSlots)
        assertEquals(1024L - 4, diff.emptySlots)

        assertEquals(2, diff.rows.size)
        val rows = diff.rows.sortedBy { it.slot }
        assertEquals(listOf(1, 2), rows.map { it.slot })
        assertTrue(rows.all { it.source == SlotSource.BASE })
        assertEquals(REGION_NAME, rows[0].region)
        assertEquals(1, rows[0].chunkX)
        assertEquals(0, rows[0].chunkZ)
        assertEquals(2, rows[1].chunkX)
        assertEquals(1000, rows[0].baseTime)
        assertNull(rows[0].patchTime)
        // patch era = P90 day of patch timestamps, rounded to end of day
        val expectedEra = (2_000_000L / day + 1) * day
        assertEquals(expectedEra, diff.patchEraEpochSeconds)
        assertEquals(expectedEra - 1000, rows[0].revertSeconds)
        assertTrue(diff.errors.isEmpty(), "no errors expected: ${diff.errors}")
    }

    @Test
    fun `dry run writes nothing to the output path`() {
        buildWorlds()
        MergeDiff.compute(request())
        assertFalse(fs.exists(out), "dry run must not create the output directory")
    }

    @Test
    fun `include-patch-rows adds patch rows and empty rows stay opt-in`() {
        buildWorlds()
        val diff = MergeDiff.compute(request(), MergeDiffOptions(includePatchRows = true))
        // rows: slot 0 (patch), slot 3 (patch), slots 1,2 (base) -> 4 rows
        assertEquals(4, diff.rows.size)
        assertEquals(2, diff.rows.count { it.source == SlotSource.PATCH })
        assertTrue(diff.rows.filter { it.source == SlotSource.PATCH }.all { it.patchTime == 2_000_000 })
        assertEquals(0, diff.rows.count { it.source == SlotSource.EMPTY })

        val withEmpty = MergeDiff.compute(request(), MergeDiffOptions(includeEmptyRows = true))
        assertEquals(1024 - 4 + 2, withEmpty.rows.size)
    }

    @Test
    fun `max rows truncates the row list but keeps counts exact`() {
        buildWorlds()
        val diff = MergeDiff.compute(request(), MergeDiffOptions(maxRows = 1))
        assertEquals(1, diff.rows.size)
        assertTrue(diff.rowsTruncated)
        assertEquals(2, diff.baseSlots)
    }

    @Test
    fun `unreadable base region is reported as a data gap`() {
        write(base, "region", "r.0.0.mca", mca(listOf(0, 1), listOf(1000, 1000)))
        write(patch, "region", "r.0.0.mca", mca(listOf(0), listOf(2_000_000)))
        fs.write(base.resolve(dim).resolve("region").resolve("r.0.0.mca"), ByteArray(100))

        val diff = MergeDiff.compute(request())

        assertEquals(1, diff.unreadableBaseRegions.size)
        assertEquals(1, diff.patchSlots)
        assertEquals(0, diff.baseSlots)
        assertTrue(diff.errors.any { it.kind == "MCA" }, "corrupt base region must be recorded")
    }

    @Test
    fun `unreadable patch region falls back to the base file`() {
        write(base, "region", "r.0.0.mca", mca(listOf(0, 1), listOf(1000, 1000)))
        fs.createDirectories(patch)
        fs.createDirectories(patch.resolve(dim).resolve("region"))
        fs.write(patch.resolve(dim).resolve("region").resolve("r.0.0.mca"), ByteArray(100))

        val diff = MergeDiff.compute(request())

        assertEquals(1, diff.unreadablePatchRegions.size)
        assertEquals(2, diff.baseSlots, "whole base file is carried over by a real merge")
        assertTrue(diff.errors.any { it.kind == "MCA" })
    }

    @Test
    fun `overlapping output is rejected before any scan`() {
        buildWorlds()
        val diff = MergeDiff.compute(request(output = base))
        assertTrue(diff.errors.any { it.kind == "Input" })
        assertEquals(0, diff.mergedRegions)
        assertTrue(diff.rows.isEmpty())
    }

    @Test
    fun `csv json and text renderings are well formed`() {
        buildWorlds()
        val diff = MergeDiff.compute(request(), MergeDiffOptions(includePatchRows = true))

        // lines() keeps a trailing empty element when the text ends with a newline
        val csv = MergeDiffIO.toCsv(diff).trimEnd('\n').lines()
        assertEquals(CSV_HEADER, csv[0])

        assertEquals(1 + diff.rows.size, csv.size)
        assertTrue(csv.drop(1).all { it.split(",").size == 11 })

        val json = MergeDiffIO.toJson(diff)
        assertTrue(json.startsWith("{\"patchSlots\":3"))
        assertTrue(json.contains("\"patchEraEpochSeconds\":${diff.patchEraEpochSeconds}"))
        assertTrue(json.contains("\"rows\":[{\"region\":\"$REGION_NAME\""))
        assertTrue(json.endsWith("]}"))

        val text = MergeDiffIO.toText(diff)
        assertTrue(text.contains("Merge dry run"))
        assertTrue(text.contains("baseSlots(revert to older copy)=2"))
    }

    private companion object {
        const val REGION_NAME = "dimensions/minecraft/overworld/region/r.0.0.mca"
        const val CSV_HEADER =
            "region,slot,chunkX,chunkZ,source,baseEpoch,baseUtc,patchEpoch,patchUtc,revertSeconds,revertDays"
    }
}
