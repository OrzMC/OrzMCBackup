package com.jokerhub.orzmc

import com.jokerhub.orzmc.util.CompressionKind
import com.jokerhub.orzmc.util.McaMemoryBuilder
import com.jokerhub.orzmc.world.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class MemoryE2ETest {
    @Test
    fun `end-to-end optimize with MemoryFS and MemoryMcaIOFactory`() {
        val fs = MemoryFS()
        val world = java.nio.file.Paths.get("/mem/world")
        fs.createDirectories(world)
        fs.createDirectories(world.resolve("region"))
        val data = McaMemoryBuilder.buildSingleEntryMca(0, 1000, CompressionKind.RAW)
        fs.write(world.resolve("region").resolve("r.0.0.mca"), data)
        val out = java.nio.file.Paths.get("/mem/out")
        val request = OptimizerRequest(
            input = world,
            output = out,
            filter = FilterOptions(inhabitedThresholdSeconds = 0),
            io = IOOptions(fs = fs, ioFactory = MemoryMcaIOFactory())
        )
        val report = Optimizer.run(request)
        assertEquals(1, report.processedChunks)
        assertEquals(0, report.removedChunks)
        val outFile = out.resolve("region").resolve("r.0.0.mca")
        val realDir = fs.toRealPath(out.resolve("region"))
        assertTrue(Files.exists(realDir))
        val real = fs.toRealPath(outFile)
        assertTrue(Files.size(real) >= 8192)
    }

    @Test
    fun `dry-run mode processes chunks without writing output`() {
        val fs = MemoryFS()
        val world = java.nio.file.Paths.get("/mem/dryrun-world")
        fs.createDirectories(world)
        fs.createDirectories(world.resolve("region"))
        val data = McaMemoryBuilder.buildSingleEntryMca(0, 1000, CompressionKind.RAW)
        fs.write(world.resolve("region").resolve("r.0.0.mca"), data)
        val out = java.nio.file.Paths.get("/mem/dryrun-out")
        val request = OptimizerRequest(
            input = world,
            output = out,
            filter = FilterOptions(inhabitedThresholdSeconds = 0),
            outputOptions = OutputOptions(dryRun = true),
            io = IOOptions(fs = fs, ioFactory = MemoryMcaIOFactory())
        )
        val report = Optimizer.run(request)
        // Should report correct chunk counts
        assertEquals(1, report.processedChunks)
        assertEquals(0, report.removedChunks)
        // Should NOT have written output files
        assertFalse(fs.exists(out.resolve("region").resolve("r.0.0.mca")),
            "dry-run should not write output files")
    }

    @Test
    fun `dry-run mode removes nothing`() {
        val fs = MemoryFS()
        val world = java.nio.file.Paths.get("/mem/dryrun-remove")
        fs.createDirectories(world)
        fs.createDirectories(world.resolve("region"))
        val data = McaMemoryBuilder.buildSingleEntryMca(5, 100, CompressionKind.RAW)
        fs.write(world.resolve("region").resolve("r.0.0.mca"), data)
        val out = java.nio.file.Paths.get("/mem/dryrun-out2")
        val request = OptimizerRequest(
            input = world,
            output = out,
            filter = FilterOptions(inhabitedThresholdSeconds = 99999),
            outputOptions = OutputOptions(dryRun = true),
            io = IOOptions(fs = fs, ioFactory = MemoryMcaIOFactory())
        )
        val report = Optimizer.run(request)
        // Should report removed chunks
        assertTrue(report.removedChunks > 0, "chunks below threshold should be counted as removed")
        // But original world should remain intact
        assertTrue(fs.exists(world.resolve("region").resolve("r.0.0.mca")),
            "original world should remain untouched in dry-run")
        // No output MCA file should exist (directory may exist as structural prep)
        assertFalse(fs.exists(out.resolve("region").resolve("r.0.0.mca")),
            "no output MCA file should exist in dry-run")
    }
}
