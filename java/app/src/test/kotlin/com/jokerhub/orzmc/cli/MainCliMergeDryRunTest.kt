package com.jokerhub.orzmc.cli

import com.jokerhub.orzmc.util.CompressionKind
import com.jokerhub.orzmc.util.McaMemoryBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import picocli.CommandLine
import java.nio.file.Files
import java.nio.file.Path

class MainCliMergeDryRunTest {
    private val dim = "dimensions/minecraft/overworld"

    private fun writeMca(
        root: Path,
        kind: String,
        name: String,
        slots: List<Int>,
    ) {
        val dir = root.resolve(dim).resolve(kind)
        Files.createDirectories(dir)
        Files.write(
            dir.resolve(name),
            McaMemoryBuilder.buildMca(slots.map { McaMemoryBuilder.MemChunk(it, 1000L + it, CompressionKind.RAW) }),
        )
    }

    private fun runMerge(
        base: Path,
        patch: Path,
        out: Path,
        vararg extra: String,
    ): Int =
        CommandLine(MergeCommand()).execute(
            base.toString(),
            patch.toString(),
            out.toString(),
            "--progress-mode",
            "Off",
            *extra,
        )

    @Test
    fun `dry run neither creates the output directory nor changes existing data`() {
        val base = Files.createTempDirectory("merge-dry-base-")
        val patch = Files.createTempDirectory("merge-dry-patch-")
        val out = (base.parent ?: base).resolve("merge-dry-out-${System.nanoTime()}")
        val diffFile = (base.parent ?: base).resolve("merge-dry-diff-${System.nanoTime()}.csv")
        try {
            writeMca(base, "region", "r.0.0.mca", listOf(0, 1))
            Files.write(base.resolve("level.dat"), "base".toByteArray())
            writeMca(patch, "region", "r.0.0.mca", listOf(0))
            Files.write(patch.resolve("level.dat"), "patch".toByteArray())

            val exit = runMerge(base, patch, out, "--dry-run", "--report", "--diff-file", diffFile.toString())

            assertEquals(0, exit, "dry run without errors must exit 0")
            assertFalse(Files.exists(out), "dry run must not create the output directory")
            assertTrue(Files.exists(diffFile), "diff file must be written")
            val lines = Files.readAllLines(diffFile)
            assertTrue(lines[0].startsWith("region,slot,chunkX,chunkZ,source"), "csv header missing: ${lines[0]}")
            // slot 1 exists only in base -> one BASE row
            assertEquals(2, lines.size)
            assertTrue(lines[1].contains(",BASE,"), "expected a BASE row, got: ${lines[1]}")
        } finally {
            deleteQuietly(out, diffFile, base, patch)
        }
    }

    @Test
    fun `dry run reports overlap errors with exit code 1`() {
        val base = Files.createTempDirectory("merge-dry-overlap-")
        try {
            writeMca(base, "region", "r.0.0.mca", listOf(0))
            val exit = runMerge(base, base, base, "--dry-run")
            assertEquals(1, exit, "overlapping directories must fail even for a dry run")
        } finally {
            deleteQuietly(base)
        }
    }

    private fun deleteQuietly(vararg paths: Path) {
        paths.forEach { p ->
            if (!Files.exists(p)) return@forEach
            Files.walk(p).sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } }
        }
    }
}
