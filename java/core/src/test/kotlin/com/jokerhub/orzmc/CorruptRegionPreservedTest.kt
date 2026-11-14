package com.jokerhub.orzmc

import com.jokerhub.orzmc.world.FilterOptions
import com.jokerhub.orzmc.world.IOOptions
import com.jokerhub.orzmc.world.MemoryFS
import com.jokerhub.orzmc.world.MemoryMcaIOFactory
import com.jokerhub.orzmc.world.Optimizer
import com.jokerhub.orzmc.world.OptimizerRequest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * WOS-6.7: an unparseable region (and its entities/poi siblings) must be preserved verbatim in
 * the output — a backup may never lose data just because a file could not be parsed.
 */
class CorruptRegionPreservedTest {
    private val fs = MemoryFS()
    private val world = Paths.get("/mem/corrupt-world")
    private val out = Paths.get("/mem/corrupt-out")

    @Test
    fun `unparseable region and siblings are copied verbatim`() {
        val corruptRegion = ByteArray(64) { 0 } + "corrupt-region".toByteArray()
        val corruptEntities = ByteArray(32) { 1 } + "corrupt-entities".toByteArray()

        fs.createDirectories(world)
        fs.createDirectories(world.resolve("region"))
        fs.createDirectories(world.resolve("entities"))
        fs.write(world.resolve("region").resolve("r.0.0.mca"), corruptRegion)
        fs.write(world.resolve("entities").resolve("r.0.0.mca"), corruptEntities)
        fs.write(world.resolve("level.dat"), "level".toByteArray())

        val report =
            Optimizer.run(
                OptimizerRequest(
                    input = world,
                    output = out,
                    filter = FilterOptions(inhabitedThresholdSeconds = 300),
                    io = IOOptions(fs = fs, ioFactory = MemoryMcaIOFactory()),
                ),
            )

        assertTrue(report.errors.any { it.kind == "MCA" }, "corrupt region must be recorded: ${report.errors}")
        assertArrayEquals(
            corruptRegion,
            fs.read(out.resolve("region").resolve("r.0.0.mca")),
            "corrupt region must be preserved byte-for-byte",
        )
        assertArrayEquals(
            corruptEntities,
            fs.read(out.resolve("entities").resolve("r.0.0.mca")),
            "entities sibling of an unparseable region must be preserved",
        )
    }

    @Test
    fun `fully pruned but parseable region produces no file`() {
        fs.createDirectories(world)
        fs.createDirectories(world.resolve("region"))
        val bytes =
            com.jokerhub.orzmc.util.McaMemoryBuilder.buildMca(
                listOf(
                    com.jokerhub.orzmc.util.McaMemoryBuilder.MemChunk(
                        0,
                        0,
                        com.jokerhub.orzmc.util.CompressionKind.ZLIB,
                    ),
                ),
            )
        fs.write(world.resolve("region").resolve("r.0.0.mca"), bytes)

        val report =
            Optimizer.run(
                OptimizerRequest(
                    input = world,
                    output = Paths.get("/mem/pruned-out"),
                    filter = FilterOptions(inhabitedThresholdSeconds = 300),
                    io = IOOptions(fs = fs, ioFactory = MemoryMcaIOFactory()),
                ),
            )

        assertEquals(1, report.removedChunks)
        assertFalse(
            fs.exists(Paths.get("/mem/pruned-out/region/r.0.0.mca")),
            "lazy writer must not create an empty .mca (WOS-6.4)",
        )
    }
}
