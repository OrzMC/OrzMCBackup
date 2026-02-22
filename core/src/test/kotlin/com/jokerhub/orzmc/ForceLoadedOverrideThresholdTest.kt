package com.jokerhub.orzmc

import com.jokerhub.orzmc.util.CompressionKind
import com.jokerhub.orzmc.util.McaMemoryBuilder
import com.jokerhub.orzmc.util.TestPaths
import com.jokerhub.orzmc.util.TestTmp
import com.jokerhub.orzmc.world.*
import com.jokerhub.orzmc.mca.McaReader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class ForceLoadedOverrideThresholdTest {
    @Test
    fun `force loaded chunk is kept even when below threshold`() {
        val forced = NbtForceLoader.parse(TestPaths.worldDataChunks().toFile())
        assertTrue(forced.isNotEmpty())
        val (cx, cz) = forced.first()
        val regionX = Math.floorDiv(cx, 32)
        val regionZ = Math.floorDiv(cz, 32)
        val index = Math.floorMod(cx, 32) + Math.floorMod(cz, 32) * 32
        val input = TestTmp.createTempDirectory("optimizer-force-")
        Files.createDirectories(input.resolve("region"))
        Files.createDirectories(input.resolve("data"))
        Files.copy(TestPaths.worldDataChunks(), input.resolve("data").resolve("chunks.dat"))
        val regionName = "r.${regionX}.${regionZ}.mca"
        val data = McaMemoryBuilder.buildSingleEntryMca(index, 0, CompressionKind.RAW)
        Files.write(input.resolve("region").resolve(regionName), data)
        val out = TestTmp.createTempDirectory("optimizer-force-out-")
        val report = Optimizer.run(
            OptimizerRequest(
                input = input,
                output = out,
                filter = FilterOptions(inhabitedThresholdSeconds = 999999),
                outputOptions = OutputOptions(force = true)
            )
        )
        val outRegion = out.resolve("region").resolve(regionName)
        val kept = McaReader.open(outRegion.toString()).use { it.entries().size }
        assertEquals(1, report.processedChunks)
        assertEquals(0, report.removedChunks)
        assertEquals(1, kept)
        Cleaner.deleteTreeWithRetry(out, 5, 10)
        Cleaner.deleteTreeWithRetry(input, 5, 10)
    }
}
