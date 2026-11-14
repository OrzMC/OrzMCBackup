package com.jokerhub.orzmc

import com.jokerhub.orzmc.mca.McaReader
import com.jokerhub.orzmc.util.CompressionKind
import com.jokerhub.orzmc.util.McaMemoryBuilder
import com.jokerhub.orzmc.util.TestTmp
import com.jokerhub.orzmc.world.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.streams.toList

class OptimizerOutputModeTest {
    private fun createWorldWithEntries(chunks: List<McaMemoryBuilder.MemChunk>): Path {
        val world = TestTmp.createTempDirectory("optimizer-world-")
        Files.createDirectories(world.resolve("region"))
        val data = McaMemoryBuilder.buildMca(chunks)
        Files.write(world.resolve("region").resolve("r.0.0.mca"), data)
        return world
    }

    @Test
    fun `zipOutput creates zip and removes output directory`() {
        val input =
            createWorldWithEntries(
                listOf(McaMemoryBuilder.MemChunk(index = 0, inhabited = 1000, kind = CompressionKind.RAW)),
            )
        val out = TestTmp.createTempDirectory("optimizer-out-zip-")
        val parent = out.parent
        val before = Files.list(parent).use { s -> s.filter { it.toString().endsWith(".zip") }.toList() }
        val report =
            Optimizer.run(
                OptimizerRequest(
                    input = input,
                    output = out,
                    filter = FilterOptions(inhabitedThresholdSeconds = 0),
                    outputOptions = OutputOptions(zipOutput = true, force = true),
                ),
            )
        val after = Files.list(parent).use { s -> s.filter { it.toString().endsWith(".zip") }.toList() }
        val created = after.filter { p -> before.none { it.toString() == p.toString() } }
        assertEquals(1, report.processedChunks)
        assertEquals(1, created.size)
        assertFalse(Files.exists(out))
        created.forEach { Files.deleteIfExists(it) }
        Cleaner.deleteTreeWithRetry(input, 5, 10)
    }

    @Test
    fun `inPlace replaces input with filtered output`() {
        val input =
            createWorldWithEntries(
                listOf(
                    McaMemoryBuilder.MemChunk(index = 0, inhabited = 0, kind = CompressionKind.RAW),
                    McaMemoryBuilder.MemChunk(index = 1, inhabited = 100000, kind = CompressionKind.RAW),
                ),
            )
        val report =
            Optimizer.run(
                OptimizerRequest(
                    input = input,
                    output = null,
                    filter = FilterOptions(inhabitedThresholdSeconds = 1000),
                    outputOptions = OutputOptions(inPlace = true),
                ),
            )
        val region = input.resolve("region").resolve("r.0.0.mca")
        val count = McaReader.open(region.toString()).use { it.entries().size }
        assertEquals(2, report.processedChunks)
        assertEquals(1, report.removedChunks)
        assertEquals(1, count)
        Cleaner.deleteTreeWithRetry(input, 5, 10)
    }

    @Test
    fun `zipOutput contains valid mca files`() {
        val input =
            createWorldWithEntries(
                listOf(
                    McaMemoryBuilder.MemChunk(index = 0, inhabited = 1000, kind = CompressionKind.RAW),
                    McaMemoryBuilder.MemChunk(index = 1, inhabited = 1000, kind = CompressionKind.RAW),
                ),
            )
        val out = TestTmp.createTempDirectory("optimizer-out-zip-verify-")
        val parent = out.parent
        val before = Files.list(parent).use { s -> s.filter { it.toString().endsWith(".zip") }.toList() }
        Optimizer.run(
            OptimizerRequest(
                input = input,
                output = out,
                filter = FilterOptions(inhabitedThresholdSeconds = 0),
                outputOptions = OutputOptions(zipOutput = true, force = true),
            ),
        )
        val after = Files.list(parent).use { s -> s.filter { it.toString().endsWith(".zip") }.toList() }
        val zipFiles = after.filter { p -> before.none { it.toString() == p.toString() } }
        assertEquals(1, zipFiles.size, "should create exactly one zip file")
        val zipPath = zipFiles.first()

        // Verify ZIP file content
        ZipFile(zipPath.toFile()).use { zip ->
            val entries = zip.entries().asSequence().toList()
            val mcaEntries = entries.filter { it.name.endsWith(".mca") }
            assertTrue(mcaEntries.isNotEmpty(), "zip should contain .mca files")
            val regionEntry = mcaEntries.find { it.name.contains("region") }
            assertTrue(regionEntry != null, "zip should contain region directory")

            // Read an MCA file from inside the ZIP and verify it has entries
            val mcaEntry = mcaEntries.first()
            val mcaBytes = zip.getInputStream(mcaEntry).use { it.readBytes() }
            assertTrue(mcaBytes.size >= 8192, "mca file inside zip should be >= 8192 bytes")
        }
        assertFalse(Files.exists(out), "output directory should be removed after zip")
        zipFiles.forEach { Files.deleteIfExists(it) }
        Cleaner.deleteTreeWithRetry(input, 5, 10)
    }
}
