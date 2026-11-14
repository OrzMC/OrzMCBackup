package com.jokerhub.orzmc

import com.jokerhub.orzmc.util.TestPaths
import com.jokerhub.orzmc.util.TestTmp
import com.jokerhub.orzmc.world.*
import com.jokerhub.orzmc.world.Cleaner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path

class OptimizerApiTest {
    private fun fsFail(e: FileSystemException): Nothing {
        val msg = "FileSystemException: file=${e.file} other=${e.otherFile} reason=${e.reason} msg=${e.message}"
        System.err.println(msg)
        throw AssertionError(msg, e)
    }

    private fun logInput(input: Path) {
        val region = input.resolve("region")
        System.err.println("Input: $input exists=${Files.exists(input)} region=$region exists=${Files.exists(region)}")
        if (Files.exists(region)) {
            val mcaCount =
                try {
                    Files.list(region).use { s -> s.filter { it.fileName.toString().endsWith(".mca") }.count() }
                } catch (e: FileSystemException) {
                    fsFail(e)
                }
            System.err.println("region/*.mca count: $mcaCount")
        }
    }

    private fun copyDir(
        src: Path,
        dst: Path,
    ) {
        Files.createDirectories(dst)
        Files.walk(src).use { s ->
            s.forEach { p ->
                val rel = src.relativize(p)
                val target = dst.resolve(rel.toString())
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target)
                } else {
                    Files.copy(p, target)
                }
            }
        }
    }

    @Test
    fun `run with empty output directory returns report with progress`() {
        val input = TestPaths.world()
        logInput(input)
        val region = input.resolve("region")
        val mcaCount =
            try {
                Files.list(region).use { s -> s.filter { it.fileName.toString().endsWith(".mca") }.count() }
            } catch (e: FileSystemException) {
                fsFail(e)
            }
        assertTrue(mcaCount > 0)
        val tmpOut = TestTmp.createTempDirectory("optimizer-out-")
        val report =
            try {
                Optimizer.run(
                    OptimizerRequest(
                        input = input,
                        output = tmpOut,
                        filter = FilterOptions(inhabitedThresholdSeconds = 0),
                        outputOptions = OutputOptions(force = true),
                        progress =
                            ProgressOptions(
                                interval = 10,
                                sink =
                                    CallbackProgressSink { e ->
                                        if (e.stage == ProgressStage.Discover) {
                                            System.err.println(
                                                "DISCOVER: cur=${e.current} tot=${e.total} msg=${e.message}",
                                            )
                                        }
                                    },
                            ),
                    ),
                )
            } catch (e: FileSystemException) {
                fsFail(e)
            }
        assertTrue(report.processedChunks > 0)
        assertTrue(report.errors.isEmpty())
        Cleaner.deleteTreeWithRetry(tmpOut, 5, 10)
    }

    @Test
    fun `strict mode collects errors for damaged mca`() {
        val fixture = TestPaths.world()
        logInput(fixture)
        val region = fixture.resolve("region")
        val mcaCount =
            try {
                Files.list(region).use { s -> s.filter { it.fileName.toString().endsWith(".mca") }.count() }
            } catch (e: FileSystemException) {
                fsFail(e)
            }
        assertTrue(mcaCount > 0)
        val tmpWorld = TestTmp.createTempDirectory("optimizer-world-bad-")
        copyDir(fixture, tmpWorld)
        val bad = tmpWorld.resolve("region").resolve("r.bad.mca")
        Files.write(bad, "x".toByteArray(Charsets.UTF_8))
        val tmpOut = TestTmp.createTempDirectory("optimizer-out-bad-")
        val report =
            try {
                Optimizer.run(
                    OptimizerRequest(
                        input = tmpWorld,
                        output = tmpOut,
                        filter = FilterOptions(inhabitedThresholdSeconds = 0, strict = true),
                        progress =
                            ProgressOptions(
                                interval = 10,
                                sink =
                                    CallbackProgressSink { e ->
                                        if (e.stage == ProgressStage.Discover) {
                                            System.err.println(
                                                "DISCOVER: cur=${e.current} tot=${e.total} msg=${e.message}",
                                            )
                                        }
                                    },
                            ),
                    ),
                )
            } catch (e: FileSystemException) {
                fsFail(e)
            }
        assertTrue(report.errors.any { it.kind == "MCA" })
        Cleaner.deleteTreeWithRetry(tmpWorld, 5, 10)
        Cleaner.deleteTreeWithRetry(tmpOut, 5, 10)
    }

    @Test
    fun `non-empty output without force returns report with output error`() {
        val input = TestPaths.world()
        logInput(input)
        val region = input.resolve("region")
        val mcaCount =
            try {
                Files.list(region).use { s -> s.filter { it.fileName.toString().endsWith(".mca") }.count() }
            } catch (e: FileSystemException) {
                fsFail(e)
            }
        assertTrue(mcaCount > 0)
        val tmpOut = TestTmp.createTempDirectory("optimizer-out-nonempty-")
        Files.write(tmpOut.resolve("dummy.txt"), "a".toByteArray(Charsets.UTF_8))
        val report =
            try {
                Optimizer.run(
                    OptimizerRequest(
                        input = input,
                        output = tmpOut,
                        filter = FilterOptions(inhabitedThresholdSeconds = 0),
                    ),
                )
            } catch (e: FileSystemException) {
                fsFail(e)
            }
        assertEquals(0, report.processedChunks)
        assertTrue(report.errors.any { it.kind == "Output" })
        Cleaner.deleteTreeWithRetry(tmpOut, 5, 10)
    }

    @Test
    fun `progress callback by chunks is emitted`() {
        val input = TestPaths.world()
        logInput(input)
        val region = input.resolve("region")
        val mcaCount =
            try {
                Files.list(region).use { s -> s.filter { it.fileName.toString().endsWith(".mca") }.count() }
            } catch (e: FileSystemException) {
                fsFail(e)
            }
        assertTrue(mcaCount > 0)
        val events = mutableListOf<ProgressEvent>()
        val tmpOut = TestTmp.createTempDirectory("optimizer-out-progress-")
        val report =
            try {
                Optimizer.run(
                    OptimizerRequest(
                        input = input,
                        output = tmpOut,
                        filter = FilterOptions(inhabitedThresholdSeconds = 0),
                        progress =
                            ProgressOptions(
                                interval = 100,
                                sink =
                                    CallbackProgressSink { e ->
                                        events.add(e)
                                        if (e.stage == ProgressStage.Discover) {
                                            System.err.println(
                                                "DISCOVER: cur=${e.current} tot=${e.total} msg=${e.message}",
                                            )
                                        }
                                    },
                            ),
                    ),
                )
            } catch (e: FileSystemException) {
                fsFail(e)
            }
        assertTrue(events.any { it.stage == ProgressStage.Done })
        assertTrue(events.any { it.stage == ProgressStage.ChunkProgress })
        assertTrue(report.processedChunks > 0)
        assertTrue(report.errors.isEmpty())
        Cleaner.deleteTreeWithRetry(tmpOut, 5, 10)
    }

    @Test
    fun `progress callback by time is emitted`() {
        val input = TestPaths.world()
        logInput(input)
        val region = input.resolve("region")
        val mcaCount =
            try {
                Files.list(region).use { s -> s.filter { it.fileName.toString().endsWith(".mca") }.count() }
            } catch (e: FileSystemException) {
                fsFail(e)
            }
        assertTrue(mcaCount > 0)
        val events = mutableListOf<ProgressEvent>()
        val tmpOut = TestTmp.createTempDirectory("optimizer-out-progress-ms-")
        val report =
            try {
                Optimizer.run(
                    OptimizerRequest(
                        input = input,
                        output = tmpOut,
                        filter = FilterOptions(inhabitedThresholdSeconds = 0),
                        progress =
                            ProgressOptions(
                                interval = 100000,
                                intervalMs = 5,
                                sink =
                                    CallbackProgressSink { e: ProgressEvent ->
                                        events.add(e)
                                        if (e.stage == ProgressStage.Discover) {
                                            System.err.println(
                                                "DISCOVER: cur=${e.current} tot=${e.total} msg=${e.message}",
                                            )
                                        }
                                    },
                            ),
                    ),
                )
            } catch (e: FileSystemException) {
                fsFail(e)
            }
        assertTrue(events.any { it.stage == ProgressStage.Done })
        assertTrue(events.any { it.stage == ProgressStage.ChunkProgress })
        assertTrue(report.processedChunks > 0)
        assertTrue(report.errors.isEmpty())
        Cleaner.deleteTreeWithRetry(tmpOut, 5, 10)
    }
}
