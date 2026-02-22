package com.jokerhub.orzmc

import com.jokerhub.orzmc.util.CompressionKind
import com.jokerhub.orzmc.util.McaMemoryBuilder
import com.jokerhub.orzmc.world.*
import org.junit.jupiter.api.Assertions.assertEquals
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
}
