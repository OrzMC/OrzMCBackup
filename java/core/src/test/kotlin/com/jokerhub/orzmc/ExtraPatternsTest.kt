package com.jokerhub.orzmc

import com.jokerhub.orzmc.patterns.RangePattern
import com.jokerhub.orzmc.util.CompressionKind
import com.jokerhub.orzmc.util.McaMemoryBuilder
import com.jokerhub.orzmc.util.McaMemoryBuilder.MemChunk
import com.jokerhub.orzmc.world.FilterOptions
import com.jokerhub.orzmc.world.IOOptions
import com.jokerhub.orzmc.world.MemoryFS
import com.jokerhub.orzmc.world.MemoryMcaIOFactory
import com.jokerhub.orzmc.world.Optimizer
import com.jokerhub.orzmc.world.OptimizerRequest
import com.jokerhub.orzmc.world.OptimizerRequestBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class ExtraPatternsTest {
    private val fs = MemoryFS()
    private val world = Paths.get("/mem/extra-patterns-world")

    /** Two chunks with InhabitedTime 0: (0,0) at slot 0 and (1,1) at slot 33. */
    private fun buildWorld(): MemoryFS {
        fs.createDirectories(world)
        fs.createDirectories(world.resolve("region"))
        val bytes =
            McaMemoryBuilder.buildMca(
                listOf(
                    MemChunk(0, 0, CompressionKind.RAW),
                    MemChunk(33, 0, CompressionKind.RAW),
                ),
            )
        fs.write(world.resolve("region").resolve("r.0.0.mca"), bytes)
        return fs
    }

    private fun request(
        out: String,
        filter: FilterOptions,
    ) = OptimizerRequest(
        input = world,
        output = Paths.get(out),
        filter = filter,
        io = IOOptions(fs = fs, ioFactory = MemoryMcaIOFactory()),
    )

    @Test
    fun `without extra patterns both idle chunks are removed`() {
        buildWorld()
        val report = Optimizer.run(request("/mem/extra-out-default", FilterOptions(inhabitedThresholdSeconds = 300)))
        // processedChunks counts every chunk visited; kept = processed - removed.
        assertEquals(2, report.processedChunks)
        assertEquals(2, report.removedChunks)
    }

    @Test
    fun `extra pattern keeps a chunk the threshold would remove`() {
        buildWorld()
        val filter =
            FilterOptions(
                inhabitedThresholdSeconds = 300,
                extraPatterns = listOf(RangePattern(1, 1, 1, 1)),
            )
        val report = Optimizer.run(request("/mem/extra-out-range", filter))
        assertEquals(2, report.processedChunks)
        assertEquals(1, report.removedChunks, "the (1,1) chunk must be kept by RangePattern")
        assertEquals(0, report.errors.size)
    }

    @Test
    fun `builder addPattern appends to extra patterns`() {
        buildWorld()
        val memFs = fs // the builder has its own `fs` property, so capture the memory FS explicitly
        val builder =
            OptimizerRequestBuilder(world, Paths.get("/mem/extra-out-builder")).apply {
                inhabitedThresholdSeconds = 300
                io = IOOptions(fs = memFs, ioFactory = MemoryMcaIOFactory())
                addPattern(RangePattern(0, 0, 0, 0))
            }
        val report = Optimizer.run(builder.build())
        assertEquals(1, report.removedChunks, "the (0,0) chunk must be kept")
        assertEquals(1, builder.extraPatterns.size)
    }
}
