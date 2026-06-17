package com.jokerhub.orzmc

import com.jokerhub.orzmc.util.CompressionKind
import com.jokerhub.orzmc.util.McaMemoryBuilder
import com.jokerhub.orzmc.util.TestTmp
import com.jokerhub.orzmc.world.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class OptimizerInputValidationTest {
    private fun createWorldWithEntry(): Path {
        val world = TestTmp.createTempDirectory("optimizer-world-input-")
        Files.createDirectories(world.resolve("region"))
        val data = McaMemoryBuilder.buildSingleEntryMca(0, 1000, CompressionKind.RAW)
        Files.write(world.resolve("region").resolve("r.0.0.mca"), data)
        return world
    }

    @Test
    fun `input is not directory returns input error`() {
        val input = Files.createTempFile("optimizer-input-file-", ".tmp")
        val out = TestTmp.createTempDirectory("optimizer-out-input-")
        val report =
            Optimizer.run(
                OptimizerRequest(
                    input = input,
                    output = out,
                    filter = FilterOptions(inhabitedThresholdSeconds = 0),
                ),
            )
        assertEquals(0, report.processedChunks)
        assertTrue(report.errors.any { it.kind == "Input" })
        Files.deleteIfExists(input)
        Cleaner.deleteTreeWithRetry(out, 5, 10)
    }

    @Test
    fun `output missing without inPlace returns output error`() {
        val input = createWorldWithEntry()
        val report =
            Optimizer.run(
                OptimizerRequest(
                    input = input,
                    output = null,
                    filter = FilterOptions(inhabitedThresholdSeconds = 0),
                ),
            )
        assertEquals(0, report.processedChunks)
        assertTrue(report.errors.any { it.kind == "Output" })
        Cleaner.deleteTreeWithRetry(input, 5, 10)
    }
}
